package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * What comes back next time: the folder the navigator showed, the files that were open, which was in front, and
 * the roots the navigator's Open offers as recent.
 *
 * <p>Written whenever that set changes — not on every keystroke, since dirtiness and the status line are not part
 * of it — and read once, at start, after the window exists. Files named on the command line open after the
 * remembered ones, in front.
 *
 * <p>A remembered file that has gone is skipped, and a folder that has gone is forgotten; neither is an error,
 * because the user moved them and the editor should not complain about it.
 */
final class Session {

    static final String FOLDER = "session.folder";
    static final String FILES = "session.files";
    static final String FRONT = "session.front";
    static final String RECENT = "session.recent";

    /** What is remembered, as a value, so "did it change" is an equals. */
    record Saved(String folder, List<String> files, String front, List<String> recent) {

        static Saved of(Doc doc) {
            Doc.Entry f = doc.front();
            return new Saved(doc.folder() == null ? "" : doc.folder().toString(),
                    doc.files().stream().map(Path::toString).toList(),
                    f == null || f.path() == null ? "" : f.path().toString(),
                    doc.recent().stream().map(Path::toString).toList());
        }
    }

    private final Settings settings;
    private final Model model;
    private volatile Saved last;
    /** Off until {@link #restore} has read what was saved: before that, writing would overwrite it with the empty tab the tree starts with. */
    private volatile boolean armed;

    Session(Settings settings, Model model) {
        this.settings = settings;
        this.model = model;
    }

    /**
     * Remember the session if it differs from what was last written. Runs on the model's listener, and once from
     * {@link #restore}.
     *
     * <p>It reads the newest session itself rather than taking the listener's snapshot, and that is the fix for a
     * session that lost a file: {@code onCommitLatest} orders its own deliveries, but not against the direct call
     * at the end of a restore, so a delivery of an older snapshot could land after it and be the last thing
     * written. Reading {@link Model#doc()} under this lock means the last write is never older than the last
     * commit before it.
     */
    synchronized void remember() {
        if (!armed) {
            return;
        }
        Saved now = Saved.of(model.doc());
        if (Objects.equals(now, last)) {
            return;
        }
        last = now;
        settings.putString(FOLDER, now.folder())
                .putList(FILES, now.files())
                .putString(FRONT, now.front())
                .putList(RECENT, now.recent())
                .save();
    }

    /**
     * Reopen what was open, then {@code extra} (the command line's files), in order, on {@code io}. The tabs come
     * back in their old order because the loads run one after another on one task rather than racing.
     *
     * <p><b>Only a launch with no paths has a session.</b> One started with paths is somebody's "open this" (the
     * user's, or Vexplore's, one new window per spawn): it opens just those, never arms, and so never writes. Two
     * windows would otherwise overwrite each other's session, and a one-file window would drag the last project's
     * tabs in.
     */
    void restore(Actions actions, List<Path> extra, Executor io) {
        boolean ephemeral = !extra.isEmpty();
        String folder = ephemeral ? "" : settings.getString(FOLDER, "");
        List<String> files = ephemeral ? List.of() : settings.getList(FILES);
        String front = ephemeral ? "" : settings.getString(FRONT, "");
        // Recent roots are history rather than this window's tabs, so a launch with paths offers them too.
        List<String> recent = settings.getList(RECENT);
        io.execute(() -> {
            List<Path> roots = new ArrayList<>();
            for (String r : recent) {
                if (Files.isDirectory(Path.of(r))) {
                    roots.add(Path.of(r));
                }
            }
            model.recent(roots);
            if (!folder.isEmpty() && Files.isDirectory(Path.of(folder))) {
                actions.showFolder(Path.of(folder));
            }
            List<Path> open = new ArrayList<>();
            for (String f : files) {
                Path p = Path.of(f);
                if (Files.isRegularFile(p)) {
                    open.add(p);
                }
            }
            for (Path p : open) {
                actions.load(p);
            }
            boolean somewhere = !folder.isEmpty() && Files.isDirectory(Path.of(folder)) || !open.isEmpty();
            for (Path p : extra) {
                if (Files.isDirectory(p)) {
                    actions.showFolder(p);
                } else {
                    actions.load(p);
                }
                somewhere = true;
            }
            // A window with nothing to show has one thing to do first, and Open is where it is done.
            if (!somewhere) {
                actions.chooseRoot();
            }
            if (ephemeral) {
                return;
            }
            // Armed only now: a commit before this point would write a session with half the tabs still to come.
            armed = true;
            remember();
            if (!front.isEmpty()) {
                actions.open(Path.of(front));
            }
        });
    }
}
