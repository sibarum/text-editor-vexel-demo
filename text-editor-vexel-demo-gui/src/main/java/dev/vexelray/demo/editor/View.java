package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.Intent.Command;
import dev.vexelray.demo.editor.core.Make;
import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.Add;
import dev.vexelray.demo.editor.core.Messages.AddTab;
import dev.vexelray.demo.editor.core.Messages.Answer;
import dev.vexelray.demo.editor.core.Messages.Ask;
import dev.vexelray.demo.editor.core.Messages.AskWhere;
import dev.vexelray.demo.editor.core.Messages.Caption;
import dev.vexelray.demo.editor.core.Messages.Choice;
import dev.vexelray.demo.editor.core.Messages.ChooseRoot;
import dev.vexelray.demo.editor.core.Messages.CloseTab;
import dev.vexelray.demo.editor.core.Messages.Closing;
import dev.vexelray.demo.editor.core.Messages.Dropped;
import dev.vexelray.demo.editor.core.Messages.Edited;
import dev.vexelray.demo.editor.core.Messages.Flush;
import dev.vexelray.demo.editor.core.Messages.FrontTab;
import dev.vexelray.demo.editor.core.Messages.Fronted;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.Moved;
import dev.vexelray.demo.editor.core.Messages.NewFile;
import dev.vexelray.demo.editor.core.Messages.NewScratch;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.OpenText;
import dev.vexelray.demo.editor.core.Messages.Placed;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Purpose;
import dev.vexelray.demo.editor.core.Messages.Recent;
import dev.vexelray.demo.editor.core.Messages.Relist;
import dev.vexelray.demo.editor.core.Messages.Relocated;
import dev.vexelray.demo.editor.core.Messages.Retitle;
import dev.vexelray.demo.editor.core.Messages.Revert;
import dev.vexelray.demo.editor.core.Messages.Save;
import dev.vexelray.demo.editor.core.Messages.SaveAs;
import dev.vexelray.demo.editor.core.Messages.SaveFailed;
import dev.vexelray.demo.editor.core.Messages.Saved;
import dev.vexelray.demo.editor.core.Messages.ScratchShown;
import dev.vexelray.demo.editor.core.Messages.Settled;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.demo.editor.core.Messages.Template;
import dev.vexelray.demo.editor.core.Messages.Tell;
import dev.vexelray.demo.editor.core.Preferences;
import dev.vexelray.demo.editor.core.ProjectIndex;
import dev.vexelray.demo.editor.core.TextFile;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;
import sibarum.atchung.Atchung;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.Source;
import sibarum.concordance.index.SourceRef;
import sibarum.concordance.index.Symbol;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The view: what the editor decided, shown, and what the person did, told. On a lane of its own.
 *
 * <h2>It decides nothing that is the editor's</h2>
 *
 * <p>Every decision about the session is the {@code Editor}'s, in {@code -core}: whether a file is already open,
 * whether closing needs a question, what a root keeps, where an Untitled document is saved. This turns the editor's
 * messages into widgets ({@code AddTab} is a tab, {@code Ask} is a dialog) and the person's gestures into messages
 * ({@code Open}, {@code Closing}, {@code Save}). What is left here is what only the window knows: the preferences menu,
 * the Run menu, the formatted view, and going to a declaration, which reads the text in front and the index.
 *
 * <h2>Threads</h2>
 *
 * <p>Every {@code @Subscribe} here runs on the {@code view} lane, one at a time. Input arrives elsewhere: a chord or a
 * menu item on the handler lane, a dialog's answer on whichever thread presses it. Those only send an {@link Intent}
 * to this lane, or a message straight to the editor when nothing of the view's is needed to say it. Writing a widget
 * from here is the framework's idiom: a prop written off the GUI thread is queued and applied by the next drain.
 *
 * <p>The disk is reached here only where the editor has no part in it: a preference written to a root's {@code .vex},
 * a library's source read for a declaration, the Run menu worked out. Those go to the framework's offload lane as
 * they did before the editor was split; making them jobs too is in docs/TODO.md.
 */
@Component(lane = "view")
@Publishes({Messages.OPEN, Messages.NEW_FILE, Messages.OPEN_TEXT, Messages.FRONTED, Messages.EDITED,
        Messages.DROPPED, Messages.CLOSING, Messages.SAVE, Messages.SAVE_AS, Messages.ANSWER, Messages.PLACED,
        Messages.RELOCATED, Messages.NEW_SCRATCH, Messages.ADD, Messages.STATUS, Intent.TOPIC})
final class View {

    private static final Log LOG = Log.of("editor.files");

    /** As the editor's: a whole set of tabs is a message each, and a full mailbox ends the application. */
    static final int SET = 4096;

    /** What Save As offers: every language the highlighter knows, by extension. */
    private static final List<FileDialog.Filter> TEXT = List.of(FileDialog.Filter.of("Text files",
            Highlighter.knownExtensions().stream().sorted().toArray(String[]::new)));

    private final Gui gui;
    private final Ui ui;
    private final Workspace ws;
    private final Navigator nav;
    private final Atchung bus;
    private final Dialogs dialogs;
    private final Gates gates;
    private final Console console;
    private final ProjectIndex concordance;
    private final Preferences prefs;
    private final Executor io;

    /** Sets of tabs being closed, numbered. Only this lane counts them. */
    private long batches;
    /**
     * Folders picked as the new root, and runs chosen from the Run menu, each held under a ticket while the editor
     * decides about the unsaved work. Put from a worker, taken on this lane, so concurrent.
     */
    private final AtomicLong tickets = new AtomicLong();
    private final Map<Long, Path> roots = new ConcurrentHashMap<>();
    private final Map<Long, Runs.Run> runs = new ConcurrentHashMap<>();
    /** Library sources read for a declaration, waiting for their tab, by their {@code OpenText}'s ticket. */
    private final Map<Long, Library> libraries = new ConcurrentHashMap<>();
    /** What to do once a file being opened for a declaration has its tab: select the declaration. This lane only. */
    private final Map<Path, Consumer<Buffer>> selections = new HashMap<>();

    /** A library's source on its way to a tab, and what to do in that tab once it is there. */
    private record Library(Source source, Consumer<Buffer> then) {
    }

    View(Gui gui, Ui ui, Atchung bus, Dialogs dialogs, Gates gates, Console console, ProjectIndex concordance,
         Preferences prefs) {
        this.gui = gui;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.bus = bus;
        this.dialogs = dialogs;
        this.gates = gates;
        this.console = console;
        this.concordance = concordance;
        this.prefs = prefs;
        this.io = gui.offload();
        ws.wordWrap(prefs.wrap());
        ui.onPreferences(e -> gui.menus().present(e, preferencesMenu()));
        ws.onEdited((id, dirty) -> bus.publish(Messages.EDITED_TOPIC, new Edited(id, dirty)));
        ws.onFronted(b -> {
            bus.publish(Messages.FRONTED_TOPIC, new Fronted(b.id));
            ui.language(b.language());
        });
        // From the navigator the keyboard stays in the navigator, so walking it with the arrows keeps walking.
        nav.onOpenFile(file -> bus.publish(Messages.OPEN_TOPIC, new Open(file, false)));
        nav.onOpenFolder(this::pickRoot);
        nav.onNewScratch(folder -> bus.publish(Messages.NEW_SCRATCH_TOPIC, new NewScratch(folder)));
        nav.onRelocated((from, to) -> bus.publish(Messages.RELOCATED_TOPIC, new Relocated(from, to)));
        nav.onOpening(gates::settle);
        nav.onAdd(this::add);
        // A build or a script writes files the tree has not listed; its end is when they are all there.
        console.onEnded(nav::refresh);
        ws.runButton().onPress(this::runMenu);
        ws.formattedButton().onPress(this::toggleFormatted);
        ws.tabs().onContextMenu((index, menu) -> {
            List<Buffer> all = ws.all();
            if (index < 0 || index >= all.size()) {
                return;
            }
            Buffer b = all.get(index);
            boolean vexplore = Suite.hasVexplore();
            menu.item("Close", () -> tell(Command.CLOSE, b.id))
                    .item("Close others", all.size() > 1, () -> tell(Command.CLOSE_OTHERS, b.id))
                    .item("Close all", () -> tell(Command.CLOSE_ALL, Messages.NO_TAB))
                    .separator()
                    .item("Reveal in navigator", b.path() != null, () -> reveal(b))
                    .item(Suite.vexploreLabel(vexplore), vexplore && b.path() != null,
                            () -> Suite.showInVexplore(b.path(), io))
                    // A library's source has no path of its own, and its archive!/entry is the next best thing.
                    .item("Copy path", b.path() != null || b.library() != null, () -> gui.clipboard().set(
                            String.valueOf(b.path() != null ? b.path() : b.library())));
        });
        shortcuts();
        // The tree always has a document in it: an empty Untitled one, until the session or a dialog brings another.
        bus.publish(Messages.NEW_FILE_TOPIC, new NewFile());
    }

    /**
     * Every chord, as a {@code GLOBAL} claim: a focused field still owns the keys it claims for itself. What needs the
     * tabs goes to this lane as an {@link Intent}; what needs nothing of the view's acts at once, or goes straight to
     * the editor.
     */
    private void shortcuts() {
        gui.shortcut(Key.N, () -> bus.publish(Messages.NEW_FILE_TOPIC, new NewFile()), Modifier.CONTROL);
        gui.shortcut(Key.N, () -> bus.publish(Messages.NEW_SCRATCH_TOPIC, new NewScratch(null)),
                Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.N, this::toggleScratches, Modifier.ALT);
        gui.shortcut(Key.O, this::openDialog, Modifier.CONTROL);
        gui.shortcut(Key.O, this::openFolderDialog, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.S, () -> tell(Command.SAVE_FRONT, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.S, () -> tell(Command.SAVE_AS_FRONT, Messages.NO_TAB), Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.W, () -> tell(Command.CLOSE_FRONT, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.TAB, () -> tell(Command.NEXT_TAB, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.TAB, () -> tell(Command.PREVIOUS_TAB, Messages.NO_TAB), Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.PAGE_DOWN, () -> tell(Command.NEXT_TAB, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.PAGE_UP, () -> tell(Command.PREVIOUS_TAB, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.B, ui::toggleNavigator, Modifier.CONTROL);
        gui.shortcut(Key.E, nav::focus, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.Z, this::toggleWrap, Modifier.ALT);
        gui.shortcut(Key.V, this::toggleFormatted, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.F5, this::runMenu);
        // The field's own Ctrl+Enter follows a hyperlink under the caret, and the editor gives its fields none;
        // without a link it fell through to inserting a newline, which this claim now outranks.
        gui.shortcut(Key.ENTER, () -> tell(Command.DECLARATION, Messages.NO_TAB), Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ENTER, () -> tell(Command.DECLARATION, Messages.NO_TAB), Modifier.CONTROL);
    }

    private void tell(Command command, long id) {
        bus.publish(Intent.INTENTS, new Intent(command, id));
    }

    @Subscribe(topic = Intent.TOPIC)
    public void intent(Intent intent) {
        intent.command().on(this, intent.id());
    }

    // ------------------------------------------------------------------ what the editor says

    /** A new root: the navigator shows it, its own preferences are read, and it is indexed if it is a Maven project. */
    @Subscribe(topic = Messages.SHOW_FOLDER)
    public void showFolder(ShowFolder m) {
        Path folder = m.path();
        nav.show(folder, null);
        ui.showNavigator(true);
        // Reading a root's .vex is the disk, so it is off this lane.
        io.execute(() -> {
            prefs.root(folder);
            ws.wordWrap(prefs.wrap());
            concordance.folder(prefs.index() ? folder : null);
        });
    }

    @Subscribe(topic = Messages.RECENT)
    public void recent(Recent m) {
        List<Path> recent = new ArrayList<>(3);
        for (Path p : new Path[] {m.first(), m.second(), m.third()}) {
            if (p != null) {
                recent.add(p);
            }
        }
        nav.recent(recent, null);
    }

    /**
     * A tab, for a file the editor read, a library's source, or an Untitled document. An empty Untitled tab the editor
     * named as replaced goes, if it is still empty now, and the editor is told it went.
     */
    @Subscribe(topic = Messages.ADD_TAB, capacity = SET)
    public void addTab(AddTab m) {
        Library library = m.ticket() == 0 ? null : libraries.remove(m.ticket());
        Buffer b = ws.add(m.id(), m.path(), new TextFile.Loaded(m.text(), m.crlf(), List.of()), m.focus(),
                library == null ? null : library.source());
        if (m.replace() != Messages.NO_TAB) {
            Buffer replaced = ws.byId(m.replace());
            if (replaced != null && replaced.pristine()) {
                ws.close(replaced.id);
                bus.publish(Messages.DROPPED_TOPIC, new Dropped(replaced.id));
            }
        }
        ui.language(b.language());
        if (library != null) {
            library.then().accept(b);
        }
        Consumer<Buffer> selection = m.path() == null ? null : selections.remove(m.path());
        if (selection != null) {
            selection.accept(b);
        }
    }

    @Subscribe(topic = Messages.FRONT_TAB)
    public void frontTab(FrontTab m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            ws.show(b, m.focus());
            ui.language(b.language());
        }
    }

    @Subscribe(topic = Messages.CLOSE_TAB, capacity = SET)
    public void closeTab(CloseTab m) {
        ws.close(m.id());
    }

    @Subscribe(topic = Messages.RETITLE, capacity = SET)
    public void retitle(Retitle m) {
        ws.retitle(m.id(), m.title());
    }

    @Subscribe(topic = Messages.CAPTION)
    public void caption(Caption m) {
        ui.caption(m.text());
    }

    @Subscribe(topic = Messages.STATUS)
    public void status(Status m) {
        ui.status(m.message(), m.alert());
    }

    /** Where the root stands in git, from the {@code Repository}. */
    @Subscribe(topic = Messages.GIT_HEAD)
    public void gitHead(GitHead m) {
        ui.git(m.label());
    }

    /** A question, as the framework's dialog: its answer goes back to the editor, from whichever thread presses it. */
    @Subscribe(topic = Messages.ASK)
    public void ask(Ask m) {
        Modal modal = Modal.of(m.title(), m.message()).defaultButton(m.confirm(), () -> answer(m, Choice.CONFIRM));
        if (!m.extra().isEmpty()) {
            modal.button(m.extra(), () -> answer(m, Choice.EXTRA));
        }
        if (!m.other().isEmpty()) {
            modal.button(m.other(), () -> answer(m, Choice.OTHER));
        }
        dialogs.ask(modal.cancelButton("Cancel", () -> answer(m, Choice.CANCEL)));
    }

    private void answer(Ask m, Choice choice) {
        bus.publish(Messages.ANSWER_TOPIC, new Answer(m.question(), choice));
    }

    @Subscribe(topic = Messages.TELL)
    public void tell(Tell m) {
        dialogs.ask(Modal.of(m.title(), m.message()).defaultButton("OK", () -> { }));
    }

    /** Where to save: the platform's own dialog, its answer, or that there was none, back to the editor. */
    @Subscribe(topic = Messages.ASK_WHERE)
    public void askWhere(AskWhere m) {
        dialogs.saveFile(m.dir(), m.name(), TEXT,
                place -> bus.publish(Messages.PLACED_TOPIC, new Placed(m.question(), place)),
                () -> bus.publish(Messages.PLACED_TOPIC, new Placed(m.question(), null)));
    }

    @Subscribe(topic = Messages.FLUSH, capacity = SET)
    public void flush(Flush m) {
        Buffer b = ws.byId(m.id());
        if (b == null) {
            bus.publish(Messages.SAVE_TOPIC, new Save(m.id(), "", false));
        } else {
            save(b);
        }
    }

    @Subscribe(topic = Messages.SAVE_FAILED)
    public void saveFailed(SaveFailed m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            b.saveFailed();
        }
    }

    /** A tab was written: to a new place, for a Save As or a first save, which its highlighting follows. */
    @Subscribe(topic = Messages.SAVED)
    public void saved(Saved m) {
        moved(new Moved(m.id(), m.path()));
    }

    @Subscribe(topic = Messages.MOVED)
    public void moved(Moved m) {
        Buffer b = ws.byId(m.id());
        if (b != null && !Objects.equals(b.path(), m.path())) {
            b.savedAs(m.path());
            ws.syncFormatted();
            if (b == ws.front()) {
                ui.language(b.language());
            }
        }
    }

    @Subscribe(topic = Messages.REVERT)
    public void revert(Revert m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            b.revert(new TextFile.Loaded(m.text(), m.crlf(), List.of()));
        }
    }

    /** What the editor decided about a set this lane is waiting on: a gate's, or a run's. */
    @Subscribe(topic = Messages.SETTLED)
    public void settled(Settled m) {
        if (m.purpose() == Purpose.RUN) {
            Runs.Run run = runs.remove(m.batch());
            if (run != null && m.go()) {
                console.run(run);
            }
        } else {
            gates.answer(m.batch(), m.go());
        }
    }

    @Subscribe(topic = Messages.SCRATCH_SHOWN)
    public void scratchShown(ScratchShown m) {
        io.execute(() -> nav.scratchMade(m.path()));
    }

    /** Something was written or made the navigator has not listed: list it, and show what was made. */
    @Subscribe(topic = Messages.RELIST)
    public void relist(Relist m) {
        nav.refresh();
        if (m.reveal() != null) {
            nav.reveal(m.reveal());
        }
    }

    @Subscribe(topic = Messages.PLAIN)
    public void plain(Plain m) {
        ui.plain();
    }

    /** A window with nothing to show has one thing to do first, and Open is where it is done. */
    @Subscribe(topic = Messages.CHOOSE_ROOT)
    public void chooseRoot(ChooseRoot m) {
        nav.mode(Navigator.Mode.OPEN);
        ui.showNavigator(true);
    }

    // ------------------------------------------------------------------ what the person did, on this lane

    void saveFront() {
        Buffer b = ws.front();
        if (b != null) {
            save(b);
        }
    }

    void saveAsFront() {
        Buffer b = ws.front();
        if (b != null) {
            bus.publish(Messages.SAVE_AS_TOPIC, new SaveAs(b.id));
        }
    }

    /** The text, taken now and marked saved now: what is written is what the document said when Save was asked for. */
    private void save(Buffer b) {
        bus.publish(Messages.SAVE_TOPIC, new Save(b.id, b.takeForSave(), true));
    }

    void closeFront() {
        Buffer b = ws.front();
        if (b != null) {
            rollCall(List.of(b), ++batches, Purpose.TABS, null);
        }
    }

    void close(long id) {
        Buffer b = ws.byId(id);
        if (b != null) {
            rollCall(List.of(b), ++batches, Purpose.TABS, null);
        }
    }

    void closeOthers(long keep) {
        List<Buffer> rest = new ArrayList<>(ws.all());
        rest.removeIf(b -> b.id == keep);
        rollCall(rest, ++batches, Purpose.TABS, null);
    }

    void closeAll() {
        rollCall(ws.all(), ++batches, Purpose.TABS, null);
    }

    void cycle(int delta) {
        ws.cycle(delta);
    }

    /** The window was asked to close: tell the editor every tab, and whether each is unsaved now. */
    void quit(long ticket) {
        rollCall(ws.all(), ticket, Purpose.QUIT, null);
    }

    /** About to go where a root is picked: the editor settles the unsaved work first, or says to stay. */
    void settle(long ticket) {
        rollCall(ws.all(), ticket, Purpose.SETTLE, null);
    }

    /** A new root was picked: every tab goes to the editor with it, to be put away as the old root's. */
    void root(long ticket) {
        Path folder = roots.remove(ticket);
        if (folder != null) {
            rollCall(ws.all(), ticket, Purpose.ROOT, folder);
        }
    }

    /** Something to run was chosen: every tab goes to the editor, which saves what has a file before it runs. */
    void run(long ticket) {
        if (runs.containsKey(ticket)) {
            rollCall(ws.all(), ticket, Purpose.RUN, null);
        }
    }

    /**
     * One {@link Closing} per tab, each carrying how many make the set and whether its tab is unsaved now; one with no
     * tab for an empty set, so the set still arrives.
     */
    private void rollCall(List<Buffer> buffers, long batch, Purpose purpose, Path folder) {
        if (buffers.isEmpty()) {
            bus.publish(Messages.CLOSING_TOPIC, new Closing(batch, 1, Messages.NO_TAB, false, purpose, folder));
            return;
        }
        for (Buffer b : buffers) {
            bus.publish(Messages.CLOSING_TOPIC, new Closing(batch, buffers.size(), b.id, b.dirty(), purpose, folder));
        }
    }

    // ------------------------------------------------------------------ what the person did, on a worker

    private void openDialog() {
        dialogs.openFile(startDir(), List.of(), p -> bus.publish(Messages.OPEN_TOPIC, new Open(p, true)), () -> { });
    }

    /** Ctrl+Shift+O: the folder dialog, as the way to a new root, so unsaved work is settled before it as Open settles it. */
    private void openFolderDialog() {
        gates.settle(() -> dialogs.openFolder(startDir(), this::pickRoot, () -> { }), () -> { });
    }

    /** {@code folder} was picked as the root: held under a ticket while the tabs go to the editor with it. */
    private void pickRoot(Path folder) {
        long ticket = tickets.incrementAndGet();
        roots.put(ticket, folder);
        tell(Command.ROOT, ticket);
    }

    /** Alt+N: the scratches over the navigator's files, or the files again. The navigator is shown for it. */
    private void toggleScratches() {
        nav.toggleScratches();
        if (nav.mode() == Navigator.Mode.SCRATCHES) {
            ui.showNavigator(true);
        }
    }

    private void reveal(Buffer b) {
        if (!nav.reveal(b.path())) {
            say(b.name() + " is not inside the open folder");
        } else {
            ui.showNavigator(true);
        }
    }

    /**
     * The navigator's Add: ask for a name above the tree, starting from one Add suggests, and send what was named to
     * the editor. A link is asked what it points at first, with the platform's own dialog.
     */
    private void add(Path folder, Made what, Template template) {
        if (what == Made.SYMLINK) {
            dialogs.openFile(folder, List.of(), target -> nav.askName(folder, String.valueOf(target.getFileName()),
                    name -> bus.publish(Messages.ADD_TOPIC, new Add(folder, what, null, name, target))), () -> { });
            return;
        }
        nav.askName(folder, Make.suggested(what, template, folder),
                name -> bus.publish(Messages.ADD_TOPIC, new Add(folder, what, template, name, null)));
    }

    private void say(String message) {
        bus.publish(Messages.STATUS_TOPIC, new Status(message, false));
    }

    private void refuse(String message) {
        bus.publish(Messages.STATUS_TOPIC, new Status(message, true));
    }

    /** Where a dialog starts: beside the document in front, else the open folder, else home. */
    private Path startDir() {
        Buffer b = ws.front();
        if (b != null && b.path() != null && b.path().getParent() != null) {
            return b.path().getParent();
        }
        Path folder = nav.folder();
        return folder != null ? folder : Path.of(System.getProperty("user.home"));
    }

    // ------------------------------------------------------------------ running

    /**
     * The Run button, and F5: what can be run for the folder and the document in front ({@link Runs}), as a menu under
     * the button. Worked out on the offload lane, since it reads the disk.
     */
    void runMenu() {
        Buffer b = ws.front();
        Path project = nav.folder();
        Path file = b == null ? null : b.path();
        String text = b == null ? null : b.field.text();
        io.execute(() -> {
            List<MenuItem> items = new ArrayList<>();
            for (Runs.Run r : Runs.offered(project, file, text)) {
                items.add(r == null ? MenuItem.SEPARATOR : MenuItem.of(r.label(), () -> run(r)));
            }
            if (items.isEmpty()) {
                items.add(MenuItem.disabled("Nothing to run here"));
            }
            Node button = ws.runButton().node();
            NodeLayout at = button.layout();
            float x = at.present() ? at.rect().x() : 0f;
            float y = at.present() ? at.rect().y() + at.rect().h() : 0f;
            gui.menus().present(new ClickEvent(button.id(), MouseButton.LEFT, x, y), items);
        });
    }

    /**
     * Run {@code run} in the console, once every document that has a file and unsaved work has been saved: what runs
     * is what is on disk, so it has to be what is on screen. The editor saves them, and a save that fails runs nothing.
     */
    private void run(Runs.Run run) {
        long ticket = tickets.incrementAndGet();
        runs.put(ticket, run);
        tell(Command.RUN, ticket);
    }

    // ------------------------------------------------------------------ code

    /**
     * One declaration go to declaration may land on.
     *
     * @param file    where it is, or null when it is in a library or in {@code in}, an Untitled document
     * @param library the library source it is in, or null
     * @param in      the open document showing it, if any: for one read from the text in front, that text, which
     *                is fresher than the index
     * @param context what it was declared beside, for its label; null to ask the index, which is asked only for the
     *                one target gone to, since a common name has hundreds of declarations in a project's libraries
     */
    private record Target(Symbol symbol, Path file, Source library, Buffer in, List<Symbol> context) {

        /** Where it is, for the status line: the file, or the library's jar. Empty in a document never saved. */
        String where() {
            if (file != null) {
                return " in " + file.getFileName();
            }
            return switch (library) {
                case Source.InArchive a -> " in " + a.archive().getFileName();
                case Source.Decompiled d -> " in " + d.archive().getFileName() + ", decompiled";
                case null, default -> "";
            };
        }
    }

    /**
     * Where each library declaration gone to turned out to be. A declaration read from a class file has no line
     * until its class is decompiled and searched, which is too slow to redo on every Ctrl+Enter; this is how
     * pressing again on one just reached knows it is there.
     */
    private final Map<Symbol, SourceRef> located = new ConcurrentHashMap<>();

    /** Where {@code t}'s declaration is in the text it is shown in, as far as is known without reading it. */
    private SourceRef position(Target t) {
        SourceRef known = located.get(t.symbol());
        return known != null ? known : t.symbol().at();
    }

    private String label(Target t, Index index) {
        List<Symbol> context = t.context() != null ? t.context()
                : index != null ? index.declaredIn(t.symbol().source()) : List.of(t.symbol());
        return Outline.label(t.symbol(), context);
    }

    /**
     * Ctrl+Enter: go to where the name under the caret is declared.
     *
     * <p>The document in front is read as it is now, and the rest of the project from its index. Names are
     * matched as Concordance matches them — by name, not by resolving them — so a common name can have several
     * declarations. They are visited in a fixed order, and pressing again on the one just reached goes on to the
     * next: a list nobody has to open, at the cost of not seeing them all at once. The project's own come before
     * its libraries', and within each which comes first depends on what the name is doing: before a {@code (} it
     * is a method or constructor, otherwise a type or a field.
     *
     * <p>A library's declaration opens its source read-only in a tab of its own: the text from its
     * {@code -sources.jar}, or its class decompiled when the library came without sources.
     */
    void goToDeclaration() {
        Buffer b = ws.front();
        if (b == null) {
            return;
        }
        Buffer.Word word = b.wordAtCaret();
        if (word == null) {
            say("Go to declaration: the caret is not on a name");
            return;
        }
        List<Target> targets = declarationsOf(b, word);
        if (targets.isEmpty()) {
            say(concordance.indexing()
                    ? "No declaration of " + word.text() + " in this file; the project is still being indexed"
                    : concordance.current() == null && !prefs.index()
                    ? "No declaration of " + word.text() + " in this file; indexing is off in Preferences"
                    : concordance.current() == null
                    ? "No declaration of " + word.text() + " in this file"
                    : "No declaration of " + word.text() + " in this project");
            return;
        }
        int here = -1;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            SourceRef at = position(t);
            if (t.in() == b && b.declaredNameAt(t.symbol().name(), at.line(), at.column()) == word.start()) {
                here = i;
                break;
            }
        }
        int next = (here + 1) % targets.size();
        Target to = targets.get(next);
        String label = label(to, concordance.current());
        goTo(to, targets.size() == 1
                ? (here == 0 ? label + " is the only declaration of " + word.text() : label + to.where())
                : label + to.where() + " (" + (next + 1) + " of " + targets.size() + ", Ctrl+Enter for the next)");
    }

    /**
     * Every declaration named {@code word}, in the order {@link #goToDeclaration} visits them. The order does not
     * depend on which document is in front, or pressing again from the one just reached would start a different
     * walk and never arrive at the rest.
     */
    private List<Target> declarationsOf(Buffer front, Buffer.Word word) {
        String name = word.text();
        List<Target> out = new ArrayList<>();
        Path frontFile = front.path() == null ? null : front.path().toAbsolutePath().normalize();
        Source frontLibrary = front.library();
        List<Symbol> local = front.declarations();
        for (Symbol s : local) {
            if (s.name().equals(name)) {
                out.add(new Target(s, frontFile, frontLibrary, front, local));
            }
        }
        Index index = concordance.current();
        if (index != null) {
            for (Symbol s : index.namesContaining(name)) {
                if (!s.name().equals(name)) {
                    continue;
                }
                if (s.source() instanceof Source.OnDisk disk) {
                    Path file = disk.file().toAbsolutePath().normalize();
                    if (file.equals(frontFile) && !local.isEmpty()) {
                        continue;   // the front document's own declarations were read from its text, which is newer
                    }
                    out.add(new Target(s, file, null, ws.find(file), null));
                } else {
                    if (s.source().equals(frontLibrary) && !local.isEmpty()) {
                        continue;   // read from the text in front, which is placed where a class file's are not
                    }
                    out.add(new Target(s, null, s.source(), ws.findLibrary(s.source()), null));
                }
            }
        }
        out.sort(Comparator
                .comparingInt((Target t) -> t.library() == null ? 0 : 1)
                .thenComparingInt(t -> preference(t.symbol().kind(), word.call()))
                .thenComparing(t -> t.file() != null ? t.file().toString()
                        : t.library() != null ? t.library().toString() : "")
                .thenComparingInt(t -> t.symbol().line())
                .thenComparingInt(t -> t.symbol().at().column()));
        return out;
    }

    /** Which kinds of declaration a name most likely means, smallest first: a name before a {@code (} is called. */
    private static int preference(Symbol.Kind kind, boolean call) {
        return switch (kind) {
            case METHOD, CONSTRUCTOR -> call ? 0 : 2;
            case FIELD -> call ? 2 : 1;
            default -> call ? 1 : 0;   // a type: `new Circle(` reaches the class when it declares no constructor
        };
    }

    /**
     * Bring {@code to}'s document forward, opening it if it is not open, select the declared name, and then say
     * {@code message} — after, because opening a file says so on the status line too, and the jump is the news. A file
     * not yet open is opened by the editor, and selected here when its tab arrives.
     */
    private void goTo(Target to, String message) {
        Symbol s = to.symbol();
        if (to.library() != null && s.source().equals(to.library())) {
            // From the index, not from the text in front: read the source, and place the declaration in it.
            io.execute(() -> goToLibrary(to, message));
            return;
        }
        Buffer open = to.in() != null ? to.in() : ws.find(to.file());
        if (open != null) {
            if (open != ws.front()) {
                ws.show(open);
            }
            open.selectDeclaration(s.name(), s.line(), s.at().column());
            say(message);
            return;
        }
        selections.put(to.file(), loaded -> {
            loaded.selectDeclaration(s.name(), s.line(), s.at().column());
            say(message);
        });
        bus.publish(Messages.OPEN_TOPIC, new Open(to.file(), true));
    }

    /**
     * {@link #goTo} for a library's declaration, on the offload lane: read its source if it is not open, which for a
     * class without sources is decompiling it, a fraction of a second or, for a huge class, up to Concordance's time
     * limit, and place the declaration in that text. The tab itself is the editor's, asked for with the text read.
     */
    private void goToLibrary(Target to, String message) {
        Symbol s = to.symbol();
        Source source = to.library();
        String name = Buffer.libraryName(source);
        Buffer open = ws.findLibrary(source);
        try {
            if (open == null) {
                if (source instanceof Source.Decompiled) {
                    say("Decompiling " + name);
                }
                TextFile.Loaded loaded = TextFile.decode(source.read());
                SourceRef at = source.locate(s);
                located.put(s, at);
                long ticket = tickets.incrementAndGet();
                libraries.put(ticket, new Library(source, shown -> {
                    shown.selectDeclaration(s.name(), at.line(), at.column());
                    say(message);
                }));
                bus.publish(Messages.OPEN_TEXT_TOPIC, new OpenText(ticket, name, loaded.text(), loaded.crlf()));
                return;
            }
            if (open != ws.front()) {
                ws.show(open);
            }
            SourceRef at = source.locate(s);
            located.put(s, at);
            open.selectDeclaration(s.name(), at.line(), at.column());
            say(message);
        } catch (TextFile.Unsupported | IOException | RuntimeException e) {
            LOG.warn("could not show {}", source, e);
            say("Could not show " + name + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ view

    /** Alt+Z, and the preferences menu: word wrap, remembered where the root's preferences are kept. */
    void toggleWrap() {
        boolean on = !ws.wrap();
        ws.wordWrap(on);
        io.execute(() -> prefs.wrap(on));
        say((on ? "Word wrap on" : "Word wrap off") + scope());
    }

    /**
     * The preferences button's menu. The theme is the editor's and takes effect at the next start; word wrap and
     * indexing apply now, to the root's own {@code .vex} preferences when it keeps them and to the editor's otherwise.
     */
    List<MenuItem> preferencesMenu() {
        Preferences.Shade next = prefs.theme();
        Preferences.Shade now = Look.light(gui.theme().palette()) ? Preferences.Shade.LIGHT : Preferences.Shade.DARK;
        Path at = prefs.root();
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.disabled(next == now ? "Theme" : "Theme (from the next start)"));
        items.add(check(next == Preferences.Shade.DARK, "Dark", () -> theme(Preferences.Shade.DARK)));
        items.add(check(next == Preferences.Shade.LIGHT, "Light", () -> theme(Preferences.Shade.LIGHT)));
        items.add(MenuItem.SEPARATOR);
        items.add(MenuItem.disabled(at != null && prefs.projectScoped() ? "Editor, for this project" : "Editor"));
        items.add(check(ws.wrap(), "Word wrap", this::toggleWrap));
        items.add(check(prefs.index(), "Index Maven projects", this::toggleIndex));
        items.add(MenuItem.SEPARATOR);
        items.add(check(prefs.projectScoped(), at == null ? "Keep for this project (no folder open)"
                : "Keep for this project, in " + Preferences.DIR, at != null, this::toggleProjectScoped));
        items.add(check(prefs.gitignore(), "Add " + Preferences.DIR + " to .gitignore", true, this::toggleGitignore));
        return items;
    }

    private static MenuItem check(boolean on, String label, Runnable action) {
        return check(on, label, true, action);
    }

    private static MenuItem check(boolean on, String label, boolean enabled, Runnable action) {
        return new MenuItem(null, label, action, enabled, on ? Icon.CHECK : null);
    }

    private void theme(Preferences.Shade shade) {
        io.execute(() -> prefs.theme(shade));
        boolean light = Look.light(gui.theme().palette());
        boolean same = light == (shade == Preferences.Shade.LIGHT);
        String name = shade == Preferences.Shade.LIGHT ? "Light" : "Dark";
        say(same ? name + " theme" : name + " theme from the next start of " + TextEditor.TITLE);
    }

    /** Indexing on or off: on indexes the root now if it is a Maven project, off drops the index there is. */
    void toggleIndex() {
        boolean on = !prefs.index();
        io.execute(() -> {
            prefs.index(on);
            concordance.folder(on ? nav.folder() : null);
        });
        say((on ? "Indexing Maven projects" : "Not indexing Maven projects") + scope());
    }

    private void toggleProjectScoped() {
        boolean keep = !prefs.projectScoped();
        io.execute(() -> {
            if (!prefs.keepForProject(keep)) {
                refuse("Could not " + (keep ? "write" : "take away") + " " + Preferences.DIR + "/"
                        + Preferences.FILE + "; the log says why");
                return;
            }
            // Leaving the project's: the editor's own values take over, and may differ.
            ws.wordWrap(prefs.wrap());
            concordance.folder(prefs.index() ? nav.folder() : null);
            say(keep ? "Preferences kept for this project, in " + Preferences.DIR + "/" + Preferences.FILE
                    : "This project uses the editor's preferences again");
        });
    }

    private void toggleGitignore() {
        boolean on = !prefs.gitignore();
        io.execute(() -> prefs.gitignore(on));
        say(on ? "A project's " + Preferences.DIR + " folder goes in its .gitignore"
                : "A project's " + Preferences.DIR + " folder is left out of its .gitignore");
    }

    /** Where a preference just changed is kept, for the status line. */
    private String scope() {
        return prefs.projectScoped() ? ", for this project" : "";
    }

    /** Ctrl+Shift+V, and the Formatted button: a Markdown document formatted, or back to its source. */
    void toggleFormatted() {
        String said = ws.toggleFormatted();
        if (said != null) {
            say(said);
        }
    }
}
