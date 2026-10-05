package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * What comes back next time: the folder the navigator showed, the files that were open, and which was in front.
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

    /** What is remembered, as a value, so "did it change" is an equals. */
    record Saved(String folder, List<String> files, String front) {

        static Saved of(Doc doc) {
            Doc.Entry f = doc.front();
            return new Saved(doc.folder() == null ? "" : doc.folder().toString(),
                    doc.files().stream().map(Path::toString).toList(),
                    f == null || f.path() == null ? "" : f.path().toString());
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
                .save();
    }

    /**
     * Reopen what was open, then {@code extra} (the command line's files), in order, on {@code io}. The tabs come
     * back in their old order because the loads run one after another on one task rather than racing.
     */
    void restore(Actions actions, List<Path> extra, Executor io) {
        String folder = settings.getString(FOLDER, "");
        List<String> files = settings.getList(FILES);
        String front = settings.getString(FRONT, "");
        io.execute(() -> {
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
            for (Path p : extra) {
                if (Files.isDirectory(p)) {
                    actions.showFolder(p);
                } else {
                    actions.load(p);
                }
            }
            // Armed only now: a commit before this point would write a session with half the tabs still to come.
            armed = true;
            remember();
            if (extra.isEmpty() && !front.isEmpty()) {
                actions.open(Path.of(front));
            }
        });
    }
}
