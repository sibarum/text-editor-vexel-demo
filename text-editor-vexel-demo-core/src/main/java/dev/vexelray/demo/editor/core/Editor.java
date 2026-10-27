package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Add;
import dev.vexelray.demo.editor.core.Messages.AddTab;
import dev.vexelray.demo.editor.core.Messages.Answer;
import dev.vexelray.demo.editor.core.Messages.Ask;
import dev.vexelray.demo.editor.core.Messages.AskWhere;
import dev.vexelray.demo.editor.core.Messages.Begin;
import dev.vexelray.demo.editor.core.Messages.Caption;
import dev.vexelray.demo.editor.core.Messages.ChooseRoot;
import dev.vexelray.demo.editor.core.Messages.CloseTab;
import dev.vexelray.demo.editor.core.Messages.Closing;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.demo.editor.core.Messages.Dropped;
import dev.vexelray.demo.editor.core.Messages.Dropping;
import dev.vexelray.demo.editor.core.Messages.Edited;
import dev.vexelray.demo.editor.core.Messages.FileRead;
import dev.vexelray.demo.editor.core.Messages.Find;
import dev.vexelray.demo.editor.core.Messages.Flush;
import dev.vexelray.demo.editor.core.Messages.Found;
import dev.vexelray.demo.editor.core.Messages.FrontTab;
import dev.vexelray.demo.editor.core.Messages.Fronted;
import dev.vexelray.demo.editor.core.Messages.Kind;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.Moved;
import dev.vexelray.demo.editor.core.Messages.NewFile;
import dev.vexelray.demo.editor.core.Messages.NewScratch;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.OpenText;
import dev.vexelray.demo.editor.core.Messages.Placed;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Purpose;
import dev.vexelray.demo.editor.core.Messages.Read;
import dev.vexelray.demo.editor.core.Messages.Recent;
import dev.vexelray.demo.editor.core.Messages.Relist;
import dev.vexelray.demo.editor.core.Messages.Relocated;
import dev.vexelray.demo.editor.core.Messages.Retitle;
import dev.vexelray.demo.editor.core.Messages.Revert;
import dev.vexelray.demo.editor.core.Messages.Save;
import dev.vexelray.demo.editor.core.Messages.SaveAs;
import dev.vexelray.demo.editor.core.Messages.SaveFailed;
import dev.vexelray.demo.editor.core.Messages.Saved;
import dev.vexelray.demo.editor.core.Messages.ScratchFor;
import dev.vexelray.demo.editor.core.Messages.ScratchMade;
import dev.vexelray.demo.editor.core.Messages.ScratchNamed;
import dev.vexelray.demo.editor.core.Messages.ScratchShown;
import dev.vexelray.demo.editor.core.Messages.Settled;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.demo.editor.core.Messages.Tell;
import dev.vexelray.demo.editor.core.Messages.Why;
import dev.vexelray.demo.editor.core.Messages.Write;
import dev.vexelray.demo.editor.core.Messages.Written;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.gui.core.app.Settings;
import sibarum.atchung.Atchung;
import sibarum.probe.Log;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The controller: every decision the editor makes, on a lane of its own, reached only by messages.
 *
 * <h2>One thread, so no locks</h2>
 *
 * <p>Everything here runs on the {@code editor} lane, one message at a time, in the order each mailbox received them.
 * The session ({@link Doc}) is a field nothing else can reach, the questions waiting for an answer and the saves
 * waiting to land are maps nothing else can reach, and so none of them needs a lock. Reaching across lanes is a
 * compile error (T2.3); a message is the only way in, and what goes out is a message too ({@link Messages}).
 *
 * <h2>What it decides</h2>
 *
 * <ul>
 *   <li>Whether a file is already open, which id a new tab gets, and when the empty Untitled tab a window starts with
 *       gives way to a file.</li>
 *   <li>Nothing is lost without being asked: closing a tab with unsaved work, closing several, going to pick a root,
 *       switching roots and quitting all go through one question, and a save that fails cancels whatever was waiting
 *       on it, quitting included. Where an Untitled document is saved is asked as well.</li>
 *   <li>Each root keeps its own tabs: a root left behind takes them with it, and gets them back when it is the root
 *       again. The newest {@link #ROOTS_KEPT} roots are kept.</li>
 *   <li>What the status line says, and what the title bar says the window is open on.</li>
 *   <li>What comes back next time (the session), kept in the framework's one {@link Settings}. Only a launch with no
 *       paths has a session: one started with paths is somebody's "open this", opens just those, and never writes,
 *       so two windows do not overwrite each other's session.</li>
 * </ul>
 *
 * <p>The disk is the jobs' ({@link ReadFile}, {@link WriteFile}, {@link FindPath}, {@link MakeScratch},
 * {@link NameScratch}, {@link DropScratch}, {@link Make}): this sends a request and acts on the reply, which comes back
 * here like any other message. A slow disk is a slow answer, never a stalled lane.
 *
 * <h2>Order</h2>
 *
 * <p>Each mailbox is in order, and two mailboxes are not in order with each other. So nothing here depends on one
 * kind of message arriving before another: a set of {@link Closing}s says how many make it and is decided when the
 * last arrives, and each carries the view's own word on whether its tab is unsaved. Jobs are not in order at all, so
 * the restored session, and the tabs a root gets back, ask for one file at a time and ask for the next when the last
 * is answered ({@link #steps}).
 */
@Component(lane = "editor")
@Publishes({Messages.SHOW_FOLDER, Messages.RECENT, Messages.ADD_TAB, Messages.FRONT_TAB, Messages.CLOSE_TAB,
        Messages.RETITLE, Messages.CAPTION, Messages.STATUS, Messages.ASK, Messages.TELL, Messages.ASK_WHERE,
        Messages.FLUSH, Messages.SAVE_FAILED, Messages.SAVED, Messages.MOVED, Messages.REVERT, Messages.SETTLED,
        Messages.SCRATCH_SHOWN, Messages.RELIST, Messages.PLAIN, Messages.CHOOSE_ROOT, Messages.READ, Messages.WRITE,
        Messages.FIND, Messages.MAKE_SCRATCH, Messages.NAME_SCRATCH, Messages.DROP_SCRATCH, Messages.MAKE})
public final class Editor {

    private static final Log LOG = Log.of("editor.files");

    static final String FOLDER = "session.folder";
    static final String FILES = "session.files";
    static final String FRONT = "session.front";
    static final String RECENT = "session.recent";
    /** The roots with tabs put away, newest first. Each one's tabs are under the two prefixes below and its path. */
    static final String ROOTS = "session.roots";
    static final String ROOT_FILES = "session.root.files.";
    static final String ROOT_FRONT = "session.root.front.";
    static final int ROOTS_KEPT = 32;

    /** How many recent roots the navigator's Open lists: one fewer than {@link Doc#RECENT}, which may hold the root. */
    static final int RECENT_SHOWN = Doc.RECENT - 1;

    /**
     * Per-item mailboxes take a whole set at once: closing every tab is a message per tab, and a full mailbox ends the
     * application (components.md, ruling 5). Far more than a person keeps open, so a burst never meets it.
     */
    static final int SET = 4096;

    private static final boolean SYSTEM_CRLF = System.lineSeparator().equals("\r\n");

    private final Atchung bus;
    private final Settings settings;
    private final Scratches scratches;
    private final List<String> arguments;

    private Doc doc = Doc.initial();
    private long ids;
    private long questions;
    /** Files asked for and not yet answered, so a second click does not open a second tab. */
    private final Set<Path> reading = new HashSet<>();
    /** The questions on screen, by number: what each answer does. */
    private final Map<Long, Choices> asked = new HashMap<>();
    /** The save dialogs on screen, by number: what a place picked, or none, does. */
    private final Map<Long, Consumer<Path>> where = new HashMap<>();
    /** Where a tab's next {@link Save} is written instead of its own file: Save As, or Keep as scratch. */
    private final Map<Long, Path> retarget = new HashMap<>();
    /** Each tab whose text has been asked for to save, and what is waiting for it to land. */
    private final Map<Long, Saving> saving = new HashMap<>();
    /** The sets of {@link Closing}s still arriving. */
    private final Map<Batch, List<Closing>> closing = new HashMap<>();
    /** Keep as scratch, waiting on a free name for the tab. */
    private final Map<Long, Runnable> keeping = new HashMap<>();
    /** Scratches made empty and not written since: closed like that, they go again. */
    private final Set<Path> fresh = new HashSet<>();
    /** What the status line says once a file Add made has its tab, by the file. */
    private final Map<Path, String> making = new HashMap<>();

    /** The restore's steps, and a returning root's, still to take: one at a time, each asking for the next. */
    private final Queue<Runnable> steps = new ArrayDeque<>();
    private boolean stepping;
    /** What the command line's paths turned out to be, by place: a window on files alone is a plain one. */
    private Kind[] argumentKinds = new Kind[0];
    /** Whether the restore found anything to show: a window with nothing goes straight to Open. */
    private boolean somewhere;
    /** How many tabs a returning root has had back so far, for the one line that says so. */
    private int reopened;

    /** The roots the last session offered as recent, as each is found still there; null where it is not. */
    private Path[] recentFound = new Path[0];
    private int recentWaiting;

    /** Off until the restore has finished: before that, a write would overwrite what is being restored. */
    private boolean armed;
    /** What a window that never writes has put away: the tabs of the roots it left. */
    private final Map<Path, Tabs> unwritten = new HashMap<>();
    private Remembered last;

    public Editor(Atchung bus, Settings settings, Launch launch, Scratches scratches) {
        this.bus = bus;
        this.settings = settings;
        this.scratches = scratches;
        this.arguments = launch.rest().stream().filter(s -> !s.isBlank()).toList();
    }

    // ------------------------------------------------------------------ the session, brought back

    /**
     * The window exists: bring back the folder, then the files that were open, then what the command line named, in
     * that order and one at a time, so the tabs come back as they were. A remembered file that has gone is skipped and
     * a folder that has gone is forgotten; the user moved them, and an editor should not complain about it.
     */
    @Subscribe(topic = Messages.BEGIN)
    public void begin(Begin begin) {
        // Recent roots are history rather than this window's tabs, so a launch with paths offers them too.
        List<String> recent = settings.getList(RECENT);
        recentFound = new Path[recent.size()];
        recentWaiting = recent.size();
        for (int i = 0; i < recent.size(); i++) {
            Path p = path(recent.get(i));
            if (p == null) {
                recentWaiting--;
            } else {
                bus.publish(Messages.FIND_TOPIC, new Find(p, Why.RECENT, i));
            }
        }
        boolean ephemeral = !arguments.isEmpty();
        if (!ephemeral) {
            Path folder = path(settings.getString(FOLDER, ""));
            if (folder != null) {
                steps.add(() -> bus.publish(Messages.FIND_TOPIC, new Find(folder, Why.RESTORE_FOLDER, 0)));
            }
            for (String f : settings.getList(FILES)) {
                Path p = path(f);
                if (p != null) {
                    steps.add(() -> read(p, Why.RESTORE_FILE, true, Messages.NO_TAB));
                }
            }
        }
        // What the command line named is looked at first, all of it, so a window on files alone is plain before its
        // first file is read and the navigator is never seen to go.
        argumentKinds = new Kind[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            Path p = path(arguments.get(i));
            int at = i;
            if (p != null) {
                steps.add(() -> bus.publish(Messages.FIND_TOPIC, new Find(p, Why.ARGUMENT, at)));
            }
        }
        steps.add(() -> {
            boolean anyFolder = false;
            for (Kind k : argumentKinds) {
                anyFolder |= k == Kind.FOLDER;
            }
            if (ephemeral && !anyFolder) {
                bus.publish(Messages.PLAIN_TOPIC, new Plain());
            }
            for (int i = 0; i < arguments.size(); i++) {
                Path p = path(arguments.get(i));
                Kind k = argumentKinds[i];
                if (k == Kind.FOLDER) {
                    steps.add(() -> {
                        showFolder(p);
                        next();
                    });
                } else if (k == Kind.FILE) {
                    steps.add(() -> read(p, Why.RESTORE_FILE, true, Messages.NO_TAB));
                }
            }
            steps.add(() -> {
                if (!somewhere) {
                    bus.publish(Messages.CHOOSE_ROOT_TOPIC, new ChooseRoot());
                }
                if (!ephemeral) {
                    armed = true;
                    remember();
                    Path front = path(settings.getString(FRONT, ""));
                    Doc.Entry e = front == null ? null : doc.entry(front);
                    if (e != null) {
                        frontTab(e.id(), true);
                    }
                }
                next();
            });
            next();
        });
        start();
    }

    /** Take the next step if none is being taken. */
    private void start() {
        if (!stepping) {
            stepping = true;
            next();
        }
    }

    /** The last step is done: take the next. */
    private void next() {
        Runnable step = steps.poll();
        if (step == null) {
            stepping = false;
        } else {
            step.run();
        }
    }

    /** A path from a remembered or typed string, absolute and normalised; null for none or one that is not a path. */
    private static Path path(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** What {@link FindPath} found at a path, acted on for whoever asked. */
    @Subscribe(topic = Messages.FOUND)
    public void found(Found f) {
        switch (f.why()) {
            case RECENT -> {
                if (f.index() < recentFound.length) {
                    recentFound[f.index()] = f.kind() == Kind.FOLDER ? f.path() : null;
                    if (--recentWaiting == 0) {
                        List<Path> roots = new ArrayList<>();
                        for (Path p : recentFound) {
                            if (p != null) {
                                roots.add(p);
                            }
                        }
                        doc = doc.withRecent(roots);
                        recent();
                        remember();
                    }
                }
            }
            case RESTORE_FOLDER -> {
                if (f.kind() == Kind.FOLDER) {
                    showFolder(f.path());
                }
                next();
            }
            case ARGUMENT -> {
                if (f.index() < argumentKinds.length) {
                    argumentKinds[f.index()] = f.kind();
                }
                next();
            }
            default -> LOG.debug("found {} for {}, which asked nothing", f.path(), f.why());
        }
    }

    /**
     * Write the session if it differs from what was last written. Unsaved work is not part of it, so this runs when
     * the set of files, the one in front, the folder or the recent roots change, not on every keystroke.
     */
    private void remember() {
        if (!armed) {
            return;
        }
        Doc.Entry f = doc.front();
        Remembered now = new Remembered(doc.folder() == null ? "" : doc.folder().toString(),
                doc.files().stream().map(Path::toString).toList(),
                f == null || f.path() == null ? "" : f.path().toString(),
                doc.recent().stream().map(Path::toString).toList());
        if (now.equals(last)) {
            return;
        }
        last = now;
        settings.putString(FOLDER, now.folder()).putList(FILES, now.files()).putString(FRONT, now.front())
                .putList(RECENT, now.recent()).save();
    }

    /** What is remembered, as a value, so "did it change" is an equals. */
    private record Remembered(String folder, List<String> files, String front, List<String> recent) {
    }

    // ------------------------------------------------------------------ each root's own tabs

    /** The tabs a root had when it was left: its files in tab order, and the one in front, or null. */
    record Tabs(List<Path> files, Path front) {

        Tabs {
            files = List.copyOf(files);
        }

        static Tabs of(Doc doc) {
            Doc.Entry f = doc.front();
            return new Tabs(doc.files(), f == null ? null : f.path());
        }
    }

    /**
     * Put away {@code tabs} as {@code root}'s, to come back when it is the root again. Told the root rather than
     * reading it from a session already on its way to the next.
     */
    private void leave(Path root, Tabs tabs) {
        Path key = root.toAbsolutePath().normalize();
        if (!armed) {
            unwritten.put(key, tabs);
            return;
        }
        String name = key.toString();
        List<String> roots = new ArrayList<>(settings.getList(ROOTS));
        roots.remove(name);
        roots.addFirst(name);
        while (roots.size() > ROOTS_KEPT) {
            String gone = roots.removeLast();
            settings.remove(ROOT_FILES + gone).remove(ROOT_FRONT + gone);
        }
        settings.putList(ROOTS, roots)
                .putList(ROOT_FILES + name, tabs.files().stream().map(Path::toString).toList())
                .putString(ROOT_FRONT + name, tabs.front() == null ? "" : tabs.front().toString())
                .save();
    }

    /** The tabs {@code root} had when it was last left; none for a root never left, or left with nothing open. */
    private Tabs tabsAt(Path root) {
        Path key = root.toAbsolutePath().normalize();
        Tabs held = unwritten.get(key);
        if (held != null) {
            return held;
        }
        String name = key.toString();
        String front = settings.getString(ROOT_FRONT + name, "");
        return new Tabs(settings.getList(ROOT_FILES + name).stream().map(Path::of).toList(),
                front.isEmpty() ? null : Path.of(front));
    }

    /**
     * Make {@code to} the root, and change the tabs with it: the ones open now are put away as {@code from}'s and
     * closed, and the ones {@code to} had when it was last left come back, in their order, with the same one in front.
     * Unsaved work was settled on the way here.
     */
    private void swap(Path from, Path to, List<Long> open) {
        if (from != null) {
            leave(from, Tabs.of(doc));
        }
        showFolder(to);
        open.forEach(this::closeTab);
        Tabs back = tabsAt(to);
        reopened = 0;
        for (Path p : back.files()) {
            steps.add(() -> read(p, Why.REOPEN, false, Messages.NO_TAB));
        }
        steps.add(() -> {
            if (reopened > 0) {
                // Each tab came back without a word; what happened is one thing.
                say("Reopened " + reopened + (reopened == 1 ? " tab" : " tabs") + " from last time in "
                        + (to.getFileName() == null ? to : to.getFileName()));
            }
            // The keyboard stays where the root was picked, the navigator or a dialog; the tab in front is just shown.
            Doc.Entry front = back.front() == null ? null : doc.entry(back.front().toAbsolutePath().normalize());
            if (front != null) {
                frontTab(front.id(), false);
            }
            next();
        });
        start();
    }

    // ------------------------------------------------------------------ opening

    /** The tree always has a document in it: an empty Untitled one, until something else arrives. */
    @Subscribe(topic = Messages.NEW_FILE)
    public void newFile(NewFile n) {
        addTab(null, "", SYSTEM_CRLF, true, null, 0, false);
    }

    @Subscribe(topic = Messages.OPEN)
    public void open(Open open) {
        Path file = open.path().toAbsolutePath().normalize();
        Doc.Entry already = doc.entry(file);
        if (already != null) {
            frontTab(already.id(), open.focus());
        } else {
            read(file, Why.OPEN, open.focus(), Messages.NO_TAB);
        }
    }

    /** Whether the reply to a read of this kind takes the next step: the restore's, or a returning root's. */
    private static boolean stepped(Why why) {
        return why == Why.RESTORE_FILE || why == Why.REOPEN;
    }

    private void read(Path file, Why why, boolean focus, long id) {
        if (why != Why.REVERT && (doc.entry(file) != null || !reading.add(file))) {
            if (stepped(why)) {
                next();
            }
            return;
        }
        bus.publish(Messages.READ_TOPIC, new Read(file, why, focus, id));
    }

    /** What {@link ReadFile} made of a file: a new tab, a tab put back as it was, or a reason on the status line. */
    @Subscribe(topic = Messages.FILE_READ)
    public void read(FileRead r) {
        if (r.why() == Why.REVERT) {
            reverted(r);
            return;
        }
        reading.remove(r.path());
        String made = r.why() == Why.MADE ? making.remove(r.path()) : null;
        String name = String.valueOf(r.path().getFileName());
        if (!r.problem().isEmpty()) {
            if (stepped(r.why())) {
                LOG.info("not reopening {}: {}", r.path(), r.problem());
            } else {
                refuse("Could not open " + name);
                tell("Could not open " + name, r.problem());
            }
        } else if (doc.entry(r.path()) == null) {
            addTab(r.path(), r.text(), r.crlf(), r.focus(), null, 0, true);
            somewhere = true;
            if (r.why() == Why.REOPEN) {
                reopened++;
            } else {
                say(made != null ? made
                        : r.notes().isEmpty() ? "Opened " + name : "Opened " + name + " (" + r.notes() + ")");
            }
        }
        if (stepped(r.why())) {
            next();
        }
    }

    /** A library's source, read by the view: a read-only tab of its own, unless one is open on it already. */
    @Subscribe(topic = Messages.OPEN_TEXT)
    public void openText(OpenText t) {
        Doc.Entry already = doc.library(t.name());
        if (already != null) {
            frontTab(already.id(), true);
        } else {
            addTab(null, t.text(), t.crlf(), true, t.name(), t.ticket(), true);
        }
    }

    /**
     * Open a tab, in front. A file arriving in a window whose only tab is an empty Untitled one takes its place: the
     * view puts that one away if it is still empty when this arrives, and says so ({@link Dropped}).
     */
    private void addTab(Path path, String text, boolean crlf, boolean focus, String library, long ticket,
                        boolean replacing) {
        long replace = replacing ? pristine() : Messages.NO_TAB;
        long id = ++ids;
        doc = doc.withTab(new Doc.Entry(id, path, false, crlf, library)).withActive(id);
        bus.publish(Messages.ADD_TAB_TOPIC,
                new AddTab(id, path, text, crlf, focus, replace, library == null ? "" : library, ticket));
        caption();
        remember();
    }

    /** The empty Untitled tab a new file would replace, if that is the only tab; else {@link Messages#NO_TAB}. */
    private long pristine() {
        if (doc.tabs().size() != 1) {
            return Messages.NO_TAB;
        }
        Doc.Entry only = doc.tabs().getFirst();
        return only.untitled() && !only.dirty() ? only.id() : Messages.NO_TAB;
    }

    @Subscribe(topic = Messages.DROPPED)
    public void dropped(Dropped d) {
        if (doc.entry(d.id()) != null) {
            doc = doc.without(d.id());
            caption();
            remember();
        }
    }

    private void frontTab(long id, boolean focus) {
        doc = doc.withActive(id);
        bus.publish(Messages.FRONT_TAB_TOPIC, new FrontTab(id, focus));
        caption();
        remember();
    }

    /** Make {@code folder} the root: the navigator shows it, and what is remembered and offered follows. */
    private void showFolder(Path folder) {
        doc = doc.withFolder(folder);
        somewhere = true;
        bus.publish(Messages.SHOW_FOLDER_TOPIC, new ShowFolder(folder));
        recent();
        caption();
        remember();
    }

    /** What Open offers as recent: the newest roots but the root itself and the scratches, which have a button of their own. */
    private void recent() {
        List<Path> shown = doc.recent().stream()
                .filter(p -> !Objects.equals(p, doc.folder()) && !scratches.is(p))
                .limit(RECENT_SHOWN).toList();
        bus.publish(Messages.RECENT_TOPIC, new Recent(shown.size() > 0 ? shown.get(0) : null,
                shown.size() > 1 ? shown.get(1) : null, shown.size() > 2 ? shown.get(2) : null));
    }

    // ------------------------------------------------------------------ what the view reports

    @Subscribe(topic = Messages.FRONTED)
    public void fronted(Fronted f) {
        if (doc.entry(f.id()) != null && doc.active() != f.id()) {
            doc = doc.withActive(f.id());
            caption();
            remember();
        }
    }

    @Subscribe(topic = Messages.EDITED)
    public void edited(Edited e) {
        Doc.Entry entry = doc.entry(e.id());
        if (entry == null || entry.dirty() == e.dirty()) {
            return;
        }
        doc = doc.withEntry(e.id(), x -> x.withDirty(e.dirty()));
        bus.publish(Messages.RETITLE_TOPIC, new Retitle(e.id(), doc.entry(e.id()).title()));
        if (doc.active() == e.id()) {
            caption();
        }
    }

    /**
     * A scratch, or a folder of them, moved or was renamed in the navigator: every tab open on it, or on anything under
     * it, follows it there. Unsaved work stays unsaved, and is saved to the new place.
     */
    @Subscribe(topic = Messages.RELOCATED)
    public void relocated(Relocated r) {
        Path a = r.from().toAbsolutePath().normalize();
        Path b = r.to().toAbsolutePath().normalize();
        for (Doc.Entry e : doc.tabs()) {
            if (e.path() != null && e.path().startsWith(a)) {
                Path now = b.resolve(a.relativize(e.path()));
                doc = doc.withEntry(e.id(), x -> x.withPath(now));
                bus.publish(Messages.MOVED_TOPIC, new Moved(e.id(), now));
                bus.publish(Messages.RETITLE_TOPIC, new Retitle(e.id(), doc.entry(e.id()).title()));
                // A scratch filed away or given a name is one somebody means to keep: closed empty, it stays.
                fresh.remove(e.path());
            }
        }
        caption();
        remember();
        say((Objects.equals(a.getParent(), b.getParent()) ? "Renamed " : "Moved ") + b.getFileName());
    }

    // ------------------------------------------------------------------ saving

    /**
     * Write a tab's text, whether the view sent it of its own accord (Ctrl+S) or because a close asked for it. One
     * that has never been saved asks where first; a library's source is not the user's to save.
     */
    @Subscribe(topic = Messages.SAVE, capacity = SET)
    public void save(Save s) {
        Doc.Entry entry = doc.entry(s.id());
        if (!s.open() || entry == null) {
            // The tab went while its text was asked for: there is nothing left to write, and nothing lost by not.
            landed(s.id());
            return;
        }
        if (entry.library() != null) {
            say(entry.name() + " is a library's source, and read-only");
            failed(s.id());
            return;
        }
        Path target = retarget.remove(s.id());
        if (target == null) {
            target = entry.path();
        }
        if (target != null) {
            bus.publish(Messages.WRITE_TOPIC, new Write(s.id(), target, s.text(), entry.crlf()));
            return;
        }
        // Never saved: ask where, holding the text taken when Save was asked for. Cancelled is a save that did not
        // happen, so the tab is unsaved again and whatever waited on it is not done: a quit stays open.
        askWhere(startDir(entry), "untitled.txt", place -> {
            if (place == null) {
                bus.publish(Messages.SAVE_FAILED_TOPIC, new SaveFailed(s.id()));
                failed(s.id());
            } else if (doc.entry(s.id()) == null) {
                landed(s.id());
            } else {
                bus.publish(Messages.WRITE_TOPIC, new Write(s.id(), place, s.text(), doc.entry(s.id()).crlf()));
            }
        });
    }

    /** Ctrl+Shift+S: ask where, then save there. Cancelled, nothing happens. */
    @Subscribe(topic = Messages.SAVE_AS)
    public void saveAs(SaveAs s) {
        Doc.Entry entry = doc.entry(s.id());
        if (entry == null) {
            return;
        }
        if (entry.library() != null) {
            say(entry.name() + " is a library's source, and read-only");
            return;
        }
        String name = entry.path() != null ? String.valueOf(entry.path().getFileName()) : "untitled.txt";
        askWhere(startDir(entry), name, place -> {
            if (place != null && doc.entry(s.id()) != null) {
                retarget.put(s.id(), place);
                bus.publish(Messages.FLUSH_TOPIC, new Flush(s.id()));
            }
        });
    }

    /** Where a save dialog starts: beside the tab's file, else the root, else home. */
    private Path startDir(Doc.Entry entry) {
        if (entry.path() != null && entry.path().getParent() != null) {
            return entry.path().getParent();
        }
        return doc.folder() != null ? doc.folder() : Path.of(System.getProperty("user.home"));
    }

    @Subscribe(topic = Messages.WRITTEN)
    public void written(Written w) {
        String name = String.valueOf(w.path().getFileName());
        if (!w.problem().isEmpty()) {
            bus.publish(Messages.SAVE_FAILED_TOPIC, new SaveFailed(w.id()));
            refuse("Could not save " + name);
            tell("Could not save " + name, w.problem());
            failed(w.id());
            return;
        }
        Path saved = w.path().toAbsolutePath().normalize();
        fresh.remove(saved);
        Doc.Entry entry = doc.entry(w.id());
        if (entry != null && !saved.equals(entry.path())) {
            doc = doc.withEntry(w.id(), e -> e.withPath(saved));
            bus.publish(Messages.RETITLE_TOPIC, new Retitle(w.id(), doc.entry(w.id()).title()));
            caption();
            remember();
        }
        bus.publish(Messages.SAVED_TOPIC, new Saved(w.id(), saved));
        // A Save As, or a save into a folder made since, is a file the navigator has not listed.
        bus.publish(Messages.RELIST_TOPIC, new Relist(null));
        say("Saved " + name);
        landed(w.id());
    }

    private void landed(long id) {
        Saving run = saving.remove(id);
        if (run != null) {
            run.landed(id);
        }
    }

    private void failed(long id) {
        Saving run = saving.remove(id);
        if (run != null) {
            run.failed();
        }
    }

    /** Save each of {@code ids}: {@code done} once all have landed, {@code failed} on the first that does not. */
    private void saveAll(List<Long> ids, Runnable done, Runnable failed) {
        if (ids.isEmpty()) {
            done.run();
            return;
        }
        Saving run = new Saving(new LinkedHashSet<>(ids), done, failed);
        for (long id : ids) {
            saving.put(id, run);
            bus.publish(Messages.FLUSH_TOPIC, new Flush(id));
        }
    }

    /** One "save these, then" in flight: which have still to land, and exactly one of its two endings, once. */
    private final class Saving {
        private final Set<Long> waiting;
        private final Runnable done;
        private final Runnable failed;
        private boolean over;

        Saving(Set<Long> waiting, Runnable done, Runnable failed) {
            this.waiting = waiting;
            this.done = done;
            this.failed = failed;
        }

        void landed(long id) {
            waiting.remove(id);
            if (waiting.isEmpty() && !over) {
                over = true;
                done.run();
            }
        }

        void failed() {
            if (over) {
                return;
            }
            over = true;
            for (long id : waiting) {
                saving.remove(id, this);
            }
            failed.run();
        }
    }

    // ------------------------------------------------------------------ closing, quitting, settling

    private record Batch(long batch, Purpose purpose) {
    }

    /**
     * One tab of a set. Nothing is decided until the whole set is here, because they come one message each and in no
     * promised order; then the set is decided at once, with one question for all its unsaved tabs.
     */
    @Subscribe(topic = Messages.CLOSING, capacity = SET)
    public void closing(Closing c) {
        Batch key = new Batch(c.batch(), c.purpose());
        List<Closing> set = closing.computeIfAbsent(key, k -> new ArrayList<>());
        set.add(c);
        if (set.size() < c.of()) {
            return;
        }
        closing.remove(key);
        List<Long> all = set.stream().map(Closing::id).filter(id -> id != Messages.NO_TAB).toList();
        List<Long> unsaved = set.stream().filter(x -> x.dirty() && x.id() != Messages.NO_TAB)
                .map(Closing::id).toList();
        switch (c.purpose()) {
            case TABS -> closeTabs(all, unsaved);
            case QUIT -> quit(c.batch(), unsaved);
            case SETTLE -> settle(unsaved, () -> settled(c.batch(), Purpose.SETTLE, true),
                    () -> settled(c.batch(), Purpose.SETTLE, false));
            case ROOT -> switchRoot(c.folder(), all, unsaved);
            case RUN -> {
                // What runs is what is on disk, so it has to be what is on screen; a save that fails runs nothing.
                List<Long> files = unsaved.stream().filter(id -> {
                    Doc.Entry e = doc.entry(id);
                    return e != null && e.path() != null && e.library() == null;
                }).toList();
                saveAll(files, () -> settled(c.batch(), Purpose.RUN, true),
                        () -> settled(c.batch(), Purpose.RUN, false));
            }
        }
    }

    private void settled(long batch, Purpose purpose, boolean go) {
        bus.publish(Messages.SETTLED_TOPIC, new Settled(batch, purpose, go));
    }

    private void closeTabs(List<Long> all, List<Long> unsaved) {
        Runnable closeThem = () -> all.forEach(this::closeTab);
        if (unsaved.isEmpty()) {
            closeThem.run();
            return;
        }
        if (all.size() == 1) {
            long id = all.getFirst();
            Doc.Entry e = doc.entry(id);
            // A document that was never saved anywhere may belong nowhere: it can be kept without deciding where.
            boolean keepable = e != null && e.untitled();
            ask("Unsaved changes", "Save the changes to " + names(unsaved) + " before closing it?",
                    "Save", "Don't save", keepable ? "Keep as scratch" : "",
                    () -> saveAll(unsaved, closeThem, () -> { }), closeThem,
                    () -> keepAsScratch(id, closeThem), () -> { });
        } else {
            ask("Unsaved changes", unsavedMessage(unsaved), "Save all", "Discard", "",
                    () -> saveAll(unsaved, closeThem, () -> { }), closeThem, () -> { }, () -> { });
        }
    }

    /** Save {@code id}, an Untitled document, as a new scratch, then {@code then}; nothing happens if it cannot be. */
    private void keepAsScratch(long id, Runnable then) {
        keeping.put(id, then);
        bus.publish(Messages.NAME_SCRATCH_TOPIC, new ScratchFor(id));
    }

    @Subscribe(topic = Messages.SCRATCH_NAMED)
    public void scratchNamed(ScratchNamed n) {
        Runnable then = keeping.remove(n.id());
        if (then == null || doc.entry(n.id()) == null) {
            return;
        }
        if (!n.problem().isEmpty()) {
            refuse("Could not keep it as a scratch");
            return;
        }
        retarget.put(n.id(), n.path());
        saveAll(List.of(n.id()), () -> {
            say("Kept as scratch " + n.path().getFileName());
            then.run();
        }, () -> { });
    }

    /**
     * The close gate's question. The window is still open and drawing while it is unanswered, and every path through
     * it answers the gate exactly once.
     */
    private void quit(long gate, List<Long> unsaved) {
        Runnable proceed = () -> settled(gate, Purpose.QUIT, true);
        Runnable cancel = () -> settled(gate, Purpose.QUIT, false);
        if (unsaved.isEmpty()) {
            proceed.run();
            return;
        }
        ask("Quit with unsaved changes?", unsavedMessage(unsaved), "Save all", "Discard", "",
                () -> saveAll(unsaved, proceed, cancel), proceed, () -> { }, cancel);
    }

    /**
     * Save or throw away the unsaved work in {@code unsaved}, asking which, then {@code proceed}; or {@code cancel},
     * and nothing changes. Throwing away puts a file back as it is on disk, and closes an Untitled document, so the tabs
     * are all still there for a root that stays the root.
     */
    private void settle(List<Long> unsaved, Runnable proceed, Runnable cancel) {
        if (unsaved.isEmpty()) {
            proceed.run();
            return;
        }
        ask("Unsaved changes", unsavedMessage(unsaved), "Save all", "Discard", "",
                () -> saveAll(unsaved, proceed, cancel),
                () -> {
                    unsaved.forEach(this::discard);
                    proceed.run();
                },
                () -> { }, cancel);
    }

    /** Throw away a tab's unsaved work: read its file again, or close it if it has none to go back to. */
    private void discard(long id) {
        Doc.Entry e = doc.entry(id);
        if (e == null) {
            return;
        }
        if (e.path() != null) {
            read(e.path(), Why.REVERT, false, id);
        } else {
            closeTab(id);
        }
    }

    private void reverted(FileRead r) {
        Doc.Entry e = doc.entry(r.id());
        if (e == null) {
            return;
        }
        if (!r.problem().isEmpty()) {
            LOG.warn("could not reread {}; closing it instead: {}", r.path(), r.problem());
            closeTab(r.id());
            return;
        }
        doc = doc.withEntry(r.id(), x -> new Doc.Entry(x.id(), x.path(), false, r.crlf(), x.library()));
        bus.publish(Messages.REVERT_TOPIC, new Revert(r.id(), r.text(), r.crlf()));
        bus.publish(Messages.RETITLE_TOPIC, new Retitle(r.id(), doc.entry(r.id()).title()));
        if (doc.active() == r.id()) {
            caption();
        }
    }

    /**
     * Make {@code folder} the root, and change the tabs with it, once unsaved work is settled. Tabs open with no root
     * at all belong to no project, so the first root of a window joins them rather than replacing them.
     */
    private void switchRoot(Path folder, List<Long> all, List<Long> unsaved) {
        if (folder == null) {
            return;
        }
        Path to = folder.toAbsolutePath().normalize();
        Path from = doc.folder();
        if (to.equals(from)) {
            return;
        }
        if (from == null) {
            swap(null, to, List.of());
            return;
        }
        settle(unsaved, () -> swap(from, to, all), () -> { });
    }

    private void closeTab(long id) {
        Doc.Entry e = doc.entry(id);
        if (e == null) {
            return;
        }
        doc = doc.without(id);
        retarget.remove(id);
        bus.publish(Messages.CLOSE_TAB_TOPIC, new CloseTab(id));
        if (e.path() != null && fresh.remove(e.path())) {
            bus.publish(Messages.DROP_SCRATCH_TOPIC, new Dropping(e.path()));
        }
        if (doc.tabs().isEmpty()) {
            addTab(null, "", SYSTEM_CRLF, true, null, 0, false);
        } else {
            caption();
            remember();
        }
    }

    private String names(List<Long> ids) {
        return ids.stream().map(doc::entry).filter(Objects::nonNull).map(Doc.Entry::name)
                .collect(Collectors.joining(", "));
    }

    private String unsavedMessage(List<Long> unsaved) {
        return unsaved.size() == 1
                ? names(unsaved) + " has unsaved changes."
                : unsaved.size() + " documents have unsaved changes: " + names(unsaved) + ".";
    }

    // ------------------------------------------------------------------ scratches

    @Subscribe(topic = Messages.NEW_SCRATCH)
    public void newScratch(NewScratch s) {
        bus.publish(Messages.MAKE_SCRATCH_TOPIC, s);
    }

    /** A new scratch is made: open it, empty, in front, and show it where the scratches are listed. */
    @Subscribe(topic = Messages.SCRATCH_MADE)
    public void scratchMade(ScratchMade m) {
        if (!m.problem().isEmpty()) {
            refuse("Could not make a scratch");
            tell("Could not make a scratch", m.problem());
            return;
        }
        Path made = m.path().toAbsolutePath().normalize();
        fresh.add(made);
        addTab(made, "", SYSTEM_CRLF, true, null, 0, true);
        bus.publish(Messages.SCRATCH_SHOWN_TOPIC, new ScratchShown(made));
        say("New scratch " + made.getFileName());
    }

    // ------------------------------------------------------------------ the navigator's Add

    /**
     * Make what the navigator's Add named, once the name is one a file can have: a folder, a file, a link, a project, a
     * module or an archive.
     */
    @Subscribe(topic = Messages.ADD)
    public void add(Add a) {
        String name = a.name() == null ? "" : a.name().strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            refuse("Not a name: " + name);
            return;
        }
        try {
            a.folder().resolve(name);
        } catch (InvalidPathException e) {
            refuse("Not a name: " + name);
            return;
        }
        if (a.what() == Made.SYMLINK && a.target() == null) {
            return;
        }
        bus.publish(Messages.MAKE_TOPIC, new Add(a.folder(), a.what(), a.template(), name, a.target()));
    }

    /**
     * What {@link Make} made: listed and shown in the navigator, and a file opened, or the pom of a project or module,
     * since that is the next thing anyone does with one.
     */
    @Subscribe(topic = Messages.MADE)
    public void made(Created c) {
        String name = String.valueOf(c.path().getFileName());
        if (!c.problem().isEmpty()) {
            refuse("Could not make " + name + ": " + c.problem());
            return;
        }
        bus.publish(Messages.RELIST_TOPIC, new Relist(c.path()));
        String said = "Made " + name + (c.note().isEmpty() ? "" : " (" + c.note() + ")");
        Path open = switch (c.what()) {
            case FILE -> c.path();
            case PROJECT, SUBMODULE -> c.path().resolve("pom.xml");
            default -> null;
        };
        if (open == null) {
            say(said);
        } else {
            // Said when its tab arrives, or opening it would say "Opened" over it.
            making.put(open.toAbsolutePath().normalize(), said);
            read(open.toAbsolutePath().normalize(), Why.MADE, true, Messages.NO_TAB);
        }
    }

    // ------------------------------------------------------------------ questions

    /** What each of a question's answers does. */
    private record Choices(Runnable confirm, Runnable other, Runnable extra, Runnable cancel) {
    }

    private void ask(String title, String message, String confirm, String other, String extra,
                     Runnable onConfirm, Runnable onOther, Runnable onExtra, Runnable onCancel) {
        long q = ++questions;
        asked.put(q, new Choices(onConfirm, onOther, onExtra, onCancel));
        bus.publish(Messages.ASK_TOPIC, new Ask(q, title, message, confirm, other, extra));
    }

    @Subscribe(topic = Messages.ANSWER)
    public void answer(Answer a) {
        Choices c = asked.remove(a.question());
        if (c == null) {
            return;
        }
        switch (a.choice()) {
            case CONFIRM -> c.confirm().run();
            case OTHER -> c.other().run();
            case EXTRA -> c.extra().run();
            case CANCEL -> c.cancel().run();
        }
    }

    private void askWhere(Path dir, String name, Consumer<Path> then) {
        long q = ++questions;
        where.put(q, then);
        bus.publish(Messages.ASK_WHERE_TOPIC, new AskWhere(q, dir, name));
    }

    @Subscribe(topic = Messages.PLACED)
    public void placed(Placed p) {
        Consumer<Path> then = where.remove(p.question());
        if (then != null) {
            then.accept(p.path() == null ? null : p.path().toAbsolutePath().normalize());
        }
    }

    // ------------------------------------------------------------------ the status line and the caption

    private void say(String message) {
        bus.publish(Messages.STATUS_TOPIC, new Status(message, false));
    }

    /** Say why on the status line, and draw the eye to it. */
    private void refuse(String why) {
        bus.publish(Messages.STATUS_TOPIC, new Status(why, true));
    }

    /** Tell the person what went wrong where it cannot be missed: a refusal is a status line, a failure is this. */
    private void tell(String title, String message) {
        bus.publish(Messages.TELL_TOPIC, new Tell(title, message));
    }

    /** What the title bar says the window is open on: the root's name, else the tab in front's title. */
    private void caption() {
        Path folder = doc.folder();
        String text;
        if (folder != null) {
            Path name = folder.getFileName();
            text = scratches.is(folder) ? Scratches.NAME : String.valueOf(name == null ? folder : name);
        } else {
            Doc.Entry f = doc.front();
            text = f == null ? "" : f.title();
        }
        bus.publish(Messages.CAPTION_TOPIC, new Caption(text));
    }
}
