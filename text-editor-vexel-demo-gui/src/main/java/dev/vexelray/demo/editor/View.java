package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.Intent.Command;
import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.Add;
import dev.vexelray.demo.editor.core.Messages.AddTab;
import dev.vexelray.demo.editor.core.Messages.IndexWanted;
import dev.vexelray.demo.editor.core.Messages.Indexed;
import dev.vexelray.demo.editor.core.Messages.Caption;
import dev.vexelray.demo.editor.core.Messages.Close;
import dev.vexelray.demo.editor.core.Messages.CloseTabs;
import dev.vexelray.demo.editor.core.Messages.Edited;
import dev.vexelray.demo.editor.core.Messages.Flush;
import dev.vexelray.demo.editor.core.Messages.FrontTab;
import dev.vexelray.demo.editor.core.Messages.Fronted;
import dev.vexelray.demo.editor.core.Messages.Format;
import dev.vexelray.demo.editor.core.Messages.Formatted;
import dev.vexelray.demo.editor.core.Messages.GitStatusRead;
import dev.vexelray.demo.editor.core.Messages.GitStatusWanted;
import dev.vexelray.demo.editor.core.Messages.Go;
import dev.vexelray.demo.editor.core.Messages.HandTo;
import dev.vexelray.demo.editor.core.Messages.HandedTo;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Listing;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.Check;
import dev.vexelray.demo.editor.core.Messages.Checked;
import dev.vexelray.demo.editor.core.Messages.Mark;
import dev.vexelray.demo.editor.core.Messages.Marked;
import dev.vexelray.demo.editor.core.Messages.Moved;
import dev.vexelray.demo.editor.core.Messages.Navigate;
import dev.vexelray.demo.editor.core.Messages.Navigated;
import dev.vexelray.demo.editor.core.Messages.NewScratch;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.Picture;
import dev.vexelray.demo.editor.core.Messages.Pictured;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Recent;
import dev.vexelray.demo.editor.core.Messages.Relocate;
import dev.vexelray.demo.editor.core.Messages.Relocated;
import dev.vexelray.demo.editor.core.Messages.Retitle;
import dev.vexelray.demo.editor.core.Messages.RevealAsked;
import dev.vexelray.demo.editor.core.Messages.RevealRow;
import dev.vexelray.demo.editor.core.Messages.Offer;
import dev.vexelray.demo.editor.core.Messages.RunThis;
import dev.vexelray.demo.editor.core.Messages.RunsOffered;
import dev.vexelray.demo.editor.core.Messages.RunsWanted;
import dev.vexelray.demo.editor.core.Messages.Save;
import dev.vexelray.demo.editor.core.Messages.SaveFailed;
import dev.vexelray.demo.editor.core.Messages.ScratchFolderMade;
import dev.vexelray.demo.editor.core.Messages.ScratchFolderWanted;
import dev.vexelray.demo.editor.core.Messages.ScratchMade;
import dev.vexelray.demo.editor.core.Messages.ScratchesFound;
import dev.vexelray.demo.editor.core.Messages.ScratchesWanted;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.demo.editor.core.Messages.Declaration;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.demo.editor.core.Messages.Written;
import dev.vexelray.demo.editor.core.Messages.SymbolsFound;
import dev.vexelray.demo.editor.core.Messages.SymbolsWanted;
import dev.vexelray.demo.editor.core.FindSymbols;
import dev.vexelray.demo.editor.core.NavigateText;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Breadcrumb;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The view: what the editor decided, shown, and what the person did, told. A {@code @View}, so it runs on the GUI
 * thread, in each frame's widget tick (threading.md, T7.4).
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Every decision is the {@code Editor}'s, in {@code -core}: whether a file is already open, what a typed path names,
 * whether closing needs a question. This turns the editor's messages into widgets ({@code AddTab} is a tab) and the
 * person's gestures into messages ({@code Open}, {@code Close}, {@code Save}). A question is not the view's: the editor
 * sends the framework an {@code Ask}, and the framework shows it.
 *
 * <h2>Everything it hears is a message, in the frame</h2>
 *
 * <p>Every {@code @Subscribe} here is drained on the GUI thread, after input is dispatched and before layout: what the
 * editor and the jobs send, and what the widgets say they did (a tab selected, a tree's row clicked or its menu chosen,
 * a field's text changed). So a click is heard, and what the view writes about it drawn, in the frame the click
 * arrived, and the widgets, the {@link Workspace} and the {@link Navigator} are only ever touched here, with no lock.
 * A widget's messages carry its address, and every widget of a kind shares a topic, so each handler first asks which
 * of the view's widgets a message is about.
 *
 * <h2>Gestures are declared, not handled</h2>
 *
 * <p>A chord, a menu item and a dialog's button each name the message they send ({@link Intent}, a
 * {@code Messages} record) through a {@link Sender}; the dispatcher sends it, and nothing of the application's runs at
 * the moment of the click (T7.5). What needs the view comes back to it as one of these handlers.
 *
 * <h2>What it sends is in its constructor</h2>
 *
 * <p>One {@link Sender} for each message it sends, and nothing else to send with: the wiring finds each one's topic by
 * its payload, beside the record in {@code Messages}, so the processor sees every message the view can send without
 * reading a line of its body (ROADMAP fork 5).
 *
 * <h2>Slow work is a job</h2>
 *
 * <p>Reading a folder for the tree and colouring a document are the jobs' ({@code ListFolder},
 * {@code MarkText}): the view asks and acts on the answer, because its time is the frame's.
 */
@dev.vexelray.framework.api.View
final class View {

    private final Gui gui;
    private final Ui ui;
    private final Workspace ws;
    private final Navigator nav;

    private final Sender<Open> open;
    private final Sender<Go> go;
    private final Sender<Fronted> fronted;
    private final Sender<Edited> edited;
    private final Sender<Close> close;
    private final Sender<Save> save;
    private final Sender<Listing> listing;
    private final Sender<Mark> mark;
    private final Sender<Check> check;
    private final Sender<Navigate> navigate;
    private final Sender<Format> format;
    private final Sender<TreeView.Children> children;
    private final Sender<Intent> intents;
    private final Sender<Picture> picture;
    private final Sender<Add> add;
    private final Sender<NewScratch> newScratch;
    private final Sender<ScratchesWanted> scratchesWanted;
    private final Sender<Relocate> relocate;
    private final Sender<ScratchFolderWanted> scratchFolderWanted;
    private final Sender<RunsWanted> runsWanted;
    private final Sender<RunThis> runThis;
    private final Sender<WindowCommand> windows;
    private final Sender<IndexWanted> indexWanted;
    private final Sender<HandTo> handTo;
    private final Sender<GitStatusWanted> gitStatusWanted;
    private final Sender<TreeView.Inks> inks;
    private final Sender<SymbolsWanted> symbolsWanted;
    private final Sender<RevealAsked> revealAsked;
    /** How many times git's status has been asked for: an answer to any but the last is stale, and left. */
    private long gitAsked;
    /** How many times Symbols has asked: an answer to any but the last is stale, and left. */
    private long symbolsAsked;
    /**
     * What is being indexed and has not been answered yet: a project, or a folder in none known yet; null when nothing
     * is. One at a time, since an index holds an offload thread for seconds.
     */
    private Path indexing;
    /** What to index once that is answered, in order; one that changed while it was being indexed is here again. */
    private final Set<Path> indexQueue = new LinkedHashSet<>();
    /** What the person asked to index: its answer is said on the status line. One indexed unasked is quiet. */
    private final Set<Path> indexAsked = new HashSet<>();
    /** The projects indexed in this run, so a folder or a file in one is indexed as that project. */
    private final Set<Path> projects = new HashSet<>();
    /** What the Run menu was last asked about, so an answer about anything else is left. */
    private RunsWanted runsAsked = new RunsWanted(Optional.empty(), Optional.empty());
    /** The file a row's Run asked about, to run what is found in it; null when none is waiting. */
    private Path rowRun;
    /** A declaration to select once its file is in front (go to definition); null when none is wanted. */
    private Reveal reveal;
    /** The last Ctrl+Enter asked, whose text an import is worked out from; null before the first. */
    private Navigate asked;

    /**
     * Where a declaration is: in {@code file}, {@code [start, end)} as the file on disk has it, named {@code name}; or,
     * from Symbols, {@code at}, by line and column, whose range is found in the text once the file is open.
     */
    private record Reveal(Path file, int start, int end, String name, Declaration at) {

        Reveal(Path file, int start, int end, String name) {
            this(file, start, end, name, null);
        }
    }

    /**
     * Whether documents wrap their long lines: every one alike, on until the person says otherwise. Not kept past this
     * run yet; that comes with the preferences.
     */
    private boolean wrap = true;

    /** The tab in front, as the editor was last told. */
    private long front = Messages.NO_TAB;
    /** The recent folder Open's drop-down has reached, before it is picked; null when it has reached none. */
    private Path recentCursor;
    /** The row of Open's tree under the pointer, or null. */
    private Path treeHover;

    View(Gui gui, Keymap keys, Ui ui, Sender<Open> open, Sender<Go> go, Sender<Fronted> fronted,
         Sender<Edited> edited, Sender<Close> close, Sender<Save> save,
         Sender<Listing> listing, Sender<Mark> mark, Sender<Check> check, Sender<Navigate> navigate,
         Sender<Format> format, Sender<TreeView.Children> children,
         Sender<Intent> intents, Sender<Picture> picture, Sender<Add> add, Sender<NewScratch> newScratch,
         Sender<ScratchesWanted> scratchesWanted, Sender<Relocate> relocate,
         Sender<ScratchFolderWanted> scratchFolderWanted, Sender<RunsWanted> runsWanted, Sender<RunThis> runThis,
         Sender<WindowCommand> windows, Sender<IndexWanted> indexWanted, Sender<HandTo> handTo,
         Sender<GitStatusWanted> gitStatusWanted, Sender<TreeView.Inks> inks, Sender<SymbolsWanted> symbolsWanted,
         Sender<RevealAsked> revealAsked) {
        this.symbolsWanted = symbolsWanted;
        this.revealAsked = revealAsked;
        this.handTo = handTo;
        this.gitStatusWanted = gitStatusWanted;
        this.inks = inks;
        this.windows = windows;
        this.indexWanted = indexWanted;
        this.runsWanted = runsWanted;
        this.runThis = runThis;
        this.gui = gui;
        this.newScratch = newScratch;
        this.scratchesWanted = scratchesWanted;
        this.relocate = relocate;
        this.scratchFolderWanted = scratchFolderWanted;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.picture = picture;
        this.add = add;
        this.open = open;
        this.go = go;
        this.fronted = fronted;
        this.edited = edited;
        this.close = close;
        this.save = save;
        this.listing = listing;
        this.mark = mark;
        this.check = check;
        this.navigate = navigate;
        this.format = format;
        this.children = children;
        this.intents = intents;
        commands(keys);
        ui.newMenu(false, false, intents);   // off until a folder is shown, and what it offers comes with its listing
        ui.viewMenu(wrap, intents);
        ui.runMenu(List.of(), runThis, windows);   // Run… at once; what the folder offers comes with its answer
        gui.claim(nav.renameNode(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, intents,
                new Intent(Command.LEAVE_RENAME));
        // Symbols: Down from the field into the list, and Escape from either back to the files.
        gui.claim(nav.symbolFieldNode(), Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, intents,
                new Intent(Command.SYMBOLS_DOWN));
        gui.claim(nav.symbolFieldNode(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, intents,
                new Intent(Command.LEAVE_SYMBOLS));
        gui.claim(nav.symbolListNode(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, intents,
                new Intent(Command.LEAVE_SYMBOLS));
    }

    /**
     * Every command the keyboard reaches across the window, as a declaration: a stable id, what the person calls it,
     * the chords it starts with, and the {@link Intent} it sends. The chords are the person's to change from here: the
     * framework's key bindings list these, and a rebind moves the claim without this view hearing of it, and moves
     * what a menu line naming the id says beside it ({@link dev.vexelray.gui.core.input.Commands}). So a chord is written nowhere else, and the id
     * is the part to keep stable, since it is what the person's changes are stored under. Chords a text field leaves
     * alone, so a focused field never needs to outrank them. Zoom is here too: which chord zooms, or whether zooming
     * exists at all, is the application's to say; how far it goes is {@code Appearance.ZoomRange}, the framework's.
     */
    private void commands(Keymap keys) {
        // Unbound until somebody binds them: the New menu is how they are reached.
        command(keys, CommandIds.NEW_FILE, "New file", Command.NEW_FILE);
        command(keys, CommandIds.NEW_PROJECT, "New project", Command.NEW_PROJECT);
        command(keys, CommandIds.NEW_MODULE, "New submodule", Command.NEW_MODULE);
        command(keys, CommandIds.NEW_INDEX, "Index this project", Command.NEW_INDEX);
        command(keys, CommandIds.SAVE, "Save", Command.SAVE_FRONT, Shortcut.of(Key.S, Modifier.CONTROL));
        command(keys, CommandIds.CLOSE, "Close tab", Command.CLOSE_FRONT, Shortcut.of(Key.W, Modifier.CONTROL));
        command(keys, "tab.next", "Next tab", Command.NEXT_TAB,
                Shortcut.of(Key.TAB, Modifier.CONTROL), Shortcut.of(Key.PAGE_DOWN, Modifier.CONTROL));
        command(keys, "tab.previous", "Previous tab", Command.PREVIOUS_TAB,
                Shortcut.of(Key.TAB, Modifier.CONTROL, Modifier.SHIFT), Shortcut.of(Key.PAGE_UP, Modifier.CONTROL));
        command(keys, "go.path", "Go to path", Command.FOCUS_PATH, Shortcut.of(Key.L, Modifier.CONTROL));
        command(keys, "go.files", "Go to files", Command.FOCUS_TREE,
                Shortcut.of(Key.E, Modifier.CONTROL, Modifier.SHIFT));
        command(keys, CommandIds.FORMATTED, "Formatted or source", Command.TOGGLE_FORMATTED,
                Shortcut.of(Key.V, Modifier.CONTROL, Modifier.SHIFT));
        command(keys, CommandIds.WRAP, "Word wrap", Command.TOGGLE_WRAP, Shortcut.of(Key.Z, Modifier.ALT));
        command(keys, CommandIds.NAVIGATOR, "Show or hide the navigator", Command.TOGGLE_NAVIGATOR,
                Shortcut.of(Key.B, Modifier.CONTROL));
        command(keys, "folder.open", "Open a folder", Command.TOGGLE_OPEN, Shortcut.of(Key.O, Modifier.CONTROL));
        command(keys, CommandIds.SYMBOLS, "Go to symbol", Command.TOGGLE_SYMBOLS, Shortcut.of(Key.T, Modifier.CONTROL));
        command(keys, "scratch.new", "New scratch", Command.NEW_SCRATCH,
                Shortcut.of(Key.N, Modifier.CONTROL, Modifier.SHIFT));
        command(keys, "scratch.list", "Show or hide the scratches", Command.TOGGLE_SCRATCHES,
                Shortcut.of(Key.N, Modifier.ALT));
        command(keys, "run.menu", "Run menu", Command.RUN_MENU, Shortcut.of(Key.F5));
        command(keys, CommandIds.ZOOM_IN, "Zoom in", Command.ZOOM_IN,
                Shortcut.of(Key.EQUAL, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL));
        command(keys, CommandIds.ZOOM_OUT, "Zoom out", Command.ZOOM_OUT,
                Shortcut.of(Key.MINUS, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_SUBTRACT, Modifier.CONTROL));
        command(keys, CommandIds.ZOOM_RESET, "Reset zoom", Command.ZOOM_RESET,
                Shortcut.of(Key.DIGIT_0, Modifier.CONTROL), Shortcut.of(Key.NUMPAD_0, Modifier.CONTROL));
    }

    private void command(Keymap keys, String id, String label, Command command, Shortcut... chords) {
        keys.action(id, label, chords).sends(intents, new Intent(command));
    }

    @Subscribe
    public void intent(Intent intent) {
        intent.command().on(this);
    }

    // ------------------------------------------------------------------ what the editor says

    /** A folder to show: the navigator shows it, and comes back if it was hidden, which is how a plain window ends. */
    @Subscribe
    public void showFolder(ShowFolder m) {
        nav.show(m.path());
        ui.showNavigator(true);
        picture();
        wantRuns();
    }

    /** Reveal in navigator, after the project it is in is shown: unfold the tree to the file and ring its row. */
    @Subscribe
    public void revealRow(RevealRow m) {
        nav.reveal(m.file());
        ui.showNavigator(true);
    }

    /** A window on files alone: the navigator out of the way until a folder is shown or Ctrl+B asks for it. */
    @Subscribe
    public void plain(Plain m) {
        ui.showNavigator(false);
    }

    /** The folders shown before, for Open's Recent. */
    @Subscribe
    public void recent(Recent m) {
        nav.picker().recent(m.folders());
    }

    @Subscribe
    public void addTab(AddTab m) {
        Buffer b = ws.add(m.id(), m.path(), m.text(), m.focus());
        b.wordWrap(wrap);
        // Ctrl+Enter is go to definition in a document, before the field takes it as a newline.
        gui.claim(b.field.node(), Shortcut.of(Key.ENTER, Modifier.CONTROL), ClaimScope.FOCUSED, intents,
                new Intent(Command.GO_TO_DEFINITION));
        revealIn(b);
    }

    @Subscribe
    public void frontTab(FrontTab m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            ws.show(b, m.focus());
            revealIn(b);
        }
    }

    @Subscribe
    public void closeTabs(CloseTabs m) {
        for (long id : m.tabs()) {
            ws.close(id);
        }
    }

    /** The last title per tab in the frame: a state topic, so a burst of them is never a backlog. */
    @Subscribe
    public void retitle(Retitle m) {
        ws.retitle(m.id(), m.title());
    }

    @Subscribe
    public void caption(Caption m) {
        ui.caption(m.file());
        Buffer b = ws.front();
        ui.problem(b == null ? "" : b.problem());
        wantRuns();
        wantGit();
    }

    // ------------------------------------------------------------------ the Run menu

    /**
     * Ask what the Run menu should offer for the folder shown and the file in front. Asked when either changes: a
     * folder shown, and every caption, which the editor sends when the front file changes, is saved or goes.
     */
    private void wantRuns() {
        Buffer front = ws.front();
        runsAsked = new RunsWanted(Optional.ofNullable(nav.folder()),
                Optional.ofNullable(front == null ? null : front.path));
        runsWanted.send(runsAsked);
    }

    /**
     * What can be run: the title bar's Run menu, declared again. Run… first, then each group of offers under a rule.
     * An answer about another folder or file than the last asked is stale, and left.
     */
    @Subscribe
    public void runsOffered(RunsOffered m) {
        if (rowRun != null && m.folder().isEmpty() && m.file().equals(Optional.of(rowRun))) {
            rowRun(m);
        }
        if (!m.folder().equals(runsAsked.folder()) || !m.file().equals(runsAsked.file())) {
            return;
        }
        ui.runMenu(m.offers(), runThis, windows);
    }

    /**
     * What a row's Run found in its file: the first thing the file itself offers (its {@code main}, itself as a
     * program, or a Pontif run), run as the Run menu's line would be, saving first; or why nothing was.
     */
    private void rowRun(RunsOffered m) {
        Path file = rowRun;
        rowRun = null;
        for (Offer offer : m.offers()) {
            if (offer.group() == 0) {
                runThis.send(new RunThis(offer));
                return;
            }
        }
        ui.status("Nothing to run in " + Picker.label(file), true);
    }

    /** F5: the Run menu, dropped from the title bar as a click on it would. */
    void runMenu() {
        ui.openRunMenu();
    }

    @Subscribe
    public void status(Status m) {
        ui.status(m.message(), m.alert());
    }

    @Subscribe
    public void flush(Flush m) {
        for (long id : m.tabs()) {
            Buffer b = ws.byId(id);
            if (b == null) {
                save.send(new Save(id, "", false));
            } else {
                save(b);
            }
        }
    }

    @Subscribe
    public void saveFailed(SaveFailed m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            b.saveFailed();
            reportDirty(b);
        }
    }

    // ------------------------------------------------------------------ what the jobs answer

    @Subscribe
    public void listed(Listed m) {
        if (m.tree().equals(nav.treeAddress())) {
            children.send(nav.children(m));
            inks.send(nav.inks(m));
            nav.listed();
            if (m.roots() && m.dir().equals(nav.folder())) {
                ui.newMenu(true, m.inProject(), intents);
                wantGit();   // the folder shown, listed again: what git says of it may have changed too
                if (m.inProject()) {
                    index(m.dir(), false);   // and its project's index, which Symbols reads, may have too
                }
            }
        } else if (m.tree().equals(nav.picker().treeAddress())) {
            children.send(nav.picker().children(m));
        } else if (m.tree().equals(nav.scratchAddress())) {
            children.send(nav.scratchChildren(m));
            nav.scratchListed();
        }
    }

    /**
     * An index built, or why not: said on the status line if the person asked for it, and Symbols, if it is up, asks
     * again of it. Then the next one waiting is begun.
     */
    @Subscribe
    public void indexed(Indexed m) {
        boolean asked = indexing != null && indexAsked.remove(indexing);
        indexing = null;
        if (m.problem().isEmpty()) {
            projects.add(m.project());
        }
        if (nav.symbolsUp()) {
            askSymbols(nav.symbolText());   // what was typed, asked again of the index just built
        }
        String name = Picker.label(m.project());
        if (!asked) {
            // Indexed unasked, on showing a folder or saving a source: quiet, as a save's colours are. The log has it.
        } else if (!m.problem().isEmpty()) {
            ui.status("Not indexed: " + m.problem(), true);
        } else {
            ui.status("Indexed " + name + ": " + m.summary()
                    + (m.unreadable() == 0 ? "" : ", " + m.unreadable() + " not readable"), false);
        }
        Iterator<Path> next = indexQueue.iterator();
        if (next.hasNext()) {
            Path folder = next.next();
            next.remove();
            beginIndex(folder);
        }
    }

    /**
     * Index the project {@code folder} is in, now or after the one under way; {@code asked}: the person asked, so the
     * answer is said. A folder in a project already indexed is asked as that project, so a burst of saves across its
     * packages is one index after the one under way, not one each. One asked for while it is being indexed is asked
     * again after, since what changed may have been read before it did.
     */
    private void index(Path folder, boolean asked) {
        Path key = folder.toAbsolutePath().normalize();
        for (Path project : projects) {
            if (key.startsWith(project)) {
                key = project;
                break;
            }
        }
        if (asked) {
            indexAsked.add(key);
        }
        if (indexing == null) {
            beginIndex(key);
        } else {
            indexQueue.add(key);
        }
    }

    private void beginIndex(Path key) {
        indexing = key;
        indexWanted.send(new IndexWanted(key));
    }

    /**
     * A file written: a Java source or a pom changes what the project declares, so its project is indexed again. Heard
     * beside the editor, which says the save on the status line.
     */
    @Subscribe
    public void written(Written m) {
        String name = String.valueOf(m.path().getFileName());
        if (m.problem().isEmpty() && m.path().getParent() != null
                && (name.endsWith(".java") || name.equals("pom.xml"))) {
            index(m.path().getParent(), false);
        }
    }

    /** A scratch made: the editor opens it, and the scratches' tree, if it is up, lists it and selects it. */
    @Subscribe
    public void scratchMade(ScratchMade m) {
        if (m.problem().isEmpty()) {
            nav.scratchMade(m.path());
        }
    }

    /**
     * A scratch, or a folder of them, moved: the name field goes if it was for that, the scratches' tree lists it where
     * it went and selects it, and a move the person made goes in the window's history. One that did not land, which the
     * editor says why, leaves a name field up to be changed.
     */
    @Subscribe
    public void relocated(Relocated m) {
        boolean renaming = m.from().equals(nav.renaming());
        if (!m.problem().isEmpty()) {
            if (renaming) {
                nav.refuseRename();
            }
            return;
        }
        if (renaming) {
            nav.endRename();
        }
        if (m.undoable()) {
            History history = gui.history();
            if (history != null) {
                history.record(new Moving(m.to(), m.from(), relocate));
            }
        }
        nav.scratchMade(m.to());
    }

    /** A new folder among the scratches: listed, selected, and named at once. */
    @Subscribe
    public void scratchFolderMade(ScratchFolderMade m) {
        if (!m.problem().isEmpty()) {
            ui.status("Could not make a folder: " + m.problem(), true);
            return;
        }
        nav.scratchMade(m.path());
        nav.beginRename(m.path());
    }

    /** A tab's file moved: the document follows it, and takes the colours its new name says. */
    @Subscribe
    public void moved(Moved m) {
        Buffer b = ws.byId(m.id());
        if (b == null) {
            return;
        }
        Mark wanted = b.moved(m.path());
        if (wanted != null) {
            mark.send(wanted);
        }
        check(b.check());
        if (b.id == front) {
            ui.language(b.language());
        }
    }

    /** Whether there is a scratch: Open offers the scratches' folder once there is. */
    @Subscribe
    public void scratchesFound(ScratchesFound m) {
        nav.picker().scratchesThere(m.any());
    }

    /** A folder's picture: drawn if it is still the one wanted, and the next asked for if the pointer has moved on. */
    @Subscribe
    public void pictured(Pictured m) {
        ask(ui.preview().landed(m));
    }

    @Subscribe
    public void marked(Marked m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            b.marked(m);
        }
    }

    /** A document's problems: shown, the status line told if it is in front, and the next check sent if one is due. */
    @Subscribe
    public void checked(Checked m) {
        Buffer b = ws.byField(m.field());
        if (b == null) {
            return;
        }
        check(b.checked(m));
        if (b.id == front) {
            ui.problem(b.problem());
        }
    }

    /** Send {@code wanted}, the check a buffer asked for, if it asked for one. */
    private void check(Check wanted) {
        if (wanted != null) {
            check.send(wanted);
        }
    }

    // ------------------------------------------------------------------ go to definition

    /** Ctrl+Enter in a document: ask {@code NavigateText} about the name at the caret. */
    void goToDefinition() {
        Buffer b = ws.front();
        if (b != null) {
            asked = new Navigate(b.address(), b.field.text(), b.version(), b.field.caret(), b.path);
            navigate.send(asked);
        }
    }

    /** What Ctrl+Enter found: said on the status line, and acted on by its kind. */
    @Subscribe
    public void navigated(Navigated m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            m.to(new Navigation(b));
        }
    }

    /** Acting on one answer to Ctrl+Enter in {@code from}. */
    private final class Navigation implements Navigated.Sink {

        private final Buffer from;

        Navigation(Buffer from) {
            this.from = from;
        }

        @Override
        public void here(Navigated m) {
            from.reveal(m.start(), m.end(), "");
            say(m);
        }

        /** The declaration's file: selected now if it is open, else once the editor has opened it. */
        @Override
        public void there(Navigated m) {
            Path file = m.file().orElse(null);
            if (file == null) {
                say(m);
                return;
            }
            int[] word = NavigateText.wordAt(from.field.text(), from.field.caret());
            String name = word == null ? "" : from.field.text().substring(word[0], word[1]);
            reveal = new Reveal(file.toAbsolutePath().normalize(), m.start(), m.end(), name);
            Buffer open = ws.find(file);
            if (open != null && open.id == front) {
                revealIn(open);
            } else {
                View.this.open.send(new Open(file, true));
            }
            say(m);
        }

        @Override
        public void imported(Navigated m) {
            if (from.readOnly()) {
                ui.status(from.name() + " is a built-in module's source, to be read; it is not changed", true);
            } else if (asked != null && asked.field().equals(m.field()) && asked.version() == m.version()
                    && from.edit(asked.text(), m.start(), m.end(), m.insert())) {
                say(m);
            } else {
                ui.status("The text changed before the import was added: Ctrl+Enter again", true);
            }
        }

        @Override
        public void say(Navigated m) {
            if (!m.message().isEmpty()) {
                ui.status(m.message(), m.kind() == Navigated.Kind.SAY);
            }
        }
    }

    /** The declaration wanted, selected in {@code b} if {@code b} is its file. */
    private void revealIn(Buffer b) {
        if (reveal == null || !b.path.toAbsolutePath().normalize().equals(reveal.file())) {
            return;
        }
        if (reveal.at() != null) {
            int[] name = FindSymbols.nameAt(b.field.text(), reveal.at());
            b.reveal(name[0], name[1], reveal.name());
        } else {
            b.reveal(reveal.start(), reveal.end(), reveal.name());
        }
        reveal = null;
    }

    // ------------------------------------------------------------------ Symbols

    /** Ctrl+T: Symbols over the navigator's files, asking at once for what was typed last; or the files again. */
    void toggleSymbols() {
        if (nav.symbolsUp() && ui.navigatorShown()) {
            leaveSymbols();
            return;
        }
        open(false);
        ui.showNavigator(true);
        nav.symbols(true);
        picture();
        askSymbols(nav.symbolText());
    }

    /** Escape in Symbols: the files back, and the keyboard in the document in front, or in the tree. */
    void leaveSymbols() {
        nav.symbols(false);
        Buffer b = ws.front();
        if (b != null) {
            gui.focus(b.keyboard());
        } else {
            nav.focusTree();
        }
    }

    /** Down in Symbols' field: into the list. */
    void symbolsDown() {
        nav.focusSymbolList();
    }

    /** Ask {@code FindSymbols} what {@code typed} names in the project the folder shown is in. */
    private void askSymbols(String typed) {
        Path in = nav.folder();
        if (in != null) {
            symbolsWanted.send(new SymbolsWanted(in, typed, ++symbolsAsked));
        } else {
            ui.status("Open a folder first", true);
        }
    }

    /**
     * What {@code FindSymbols} found, if it answers the last question: listed. No index yet while one is being built
     * says that, rather than offering to build another; Symbols asks again when it lands.
     */
    @Subscribe
    public void symbolsFound(SymbolsFound m) {
        if (m.asked() != symbolsAsked) {
            return;
        }
        nav.symbolsFound(m);
        if (!m.indexed() && m.problem().isEmpty() && indexing != null) {
            nav.indexing(m.project());
        }
    }

    /** Enter on a row of Symbols, or a double click: go to it. */
    @Subscribe
    public void symbolActivated(ListView.Activated m) {
        if (m.list().equals(nav.symbolListAddress()) && m.item() instanceof Declaration d) {
            goToSymbol(d);
        }
    }

    /** A click on a row of Symbols: go to it. */
    @Subscribe
    public void symbolClicked(ListView.Clicked m) {
        if (m.list().equals(nav.symbolListAddress()) && m.item() instanceof Declaration d) {
            goToSymbol(d);
        }
    }

    /**
     * Go to {@code d}: its file opened, or fronted, with its name selected once its text is there; and the files back,
     * as picking ends Open. Ctrl+T comes back to what was typed.
     */
    private void goToSymbol(Declaration d) {
        if (d == null) {
            return;
        }
        Path file = d.file().toAbsolutePath().normalize();
        reveal = new Reveal(file, 0, 0, d.name(), d);
        nav.symbols(false);
        Buffer open = ws.find(file);
        if (open != null && open.id == front) {
            revealIn(open);
            gui.focus(open.keyboard());
        } else {
            this.open.send(new Open(file, true));
        }
        ui.status(d.name() + ", " + Navigator.detail(d), false);
    }

    @Subscribe
    public void formatted(Formatted m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            b.formatted(m);
        }
    }

    // ------------------------------------------------------------------ what the widgets say

    /**
     * The bar's selection moved: the editor hears which file is in front, and the status line its format and where
     * its caret is.
     */
    @Subscribe
    public void tabSelected(Tabs.Selected m) {
        Buffer b = m.tabs().equals(ws.address()) ? ws.at(m.index()) : null;
        if (b != null && b.id != front) {
            front = b.id;
            ui.language(b.language());
            ui.problem(b.problem());
            ui.position(b.position());
            fronted.send(new Fronted(b.id));
        }
    }

    /** One of the tab menu's actions. */
    @Subscribe
    public void tabChosen(Tabs.Chosen m) {
        Buffer b = m.at().equals(ws.address()) && m.item() instanceof Integer i ? ws.at(i) : null;
        if (b == null) {
            return;
        }
        if (m.action().equals(Workspace.SAVE)) {
            save(b);
        } else if (m.action().equals(Workspace.CLOSE)) {
            close(List.of(b));
        } else if (m.action().equals(Workspace.CLOSE_OTHERS)) {
            List<Buffer> rest = new ArrayList<>(ws.all());
            rest.remove(b);
            close(rest);
        } else if (m.action().equals(Workspace.CLOSE_ALL)) {
            close(ws.all());
        } else if (m.action().equals(Workspace.REVEAL)) {
            revealAsked.send(new RevealAsked(b.path));   // the editor shows its project, then says to reveal it
        } else if (m.action().equals(Workspace.FORMAT)) {
            toggleFormatted(b);
        }
    }

    /** A document's text changed: whether it is now unsaved, and its colours. */
    @Subscribe
    public void fieldChanged(TextField.Changed m) {
        if (m.field().equals(nav.symbolFieldAddress())) {
            askSymbols(m.text());
            return;
        }
        Buffer b = ws.byField(m.field());
        if (b == null) {
            return;
        }
        reportDirty(b);
        Mark wanted = b.changed(m.text(), m.version());
        if (wanted != null) {
            mark.send(wanted);
        }
        check(b.check());
        Format again = b.reformat(m.text());
        if (again != null) {
            format.send(again);
        }
    }

    @Subscribe
    public void fieldCaret(TextField.Caret m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            b.caret(m.line(), m.column());
            if (b.id == front) {
                ui.position(b.position());
            }
        }
    }

    /**
     * The path bar was submitted: what the text names is the editor's call, unless a row's menu put the bar in mind of
     * something its extension cannot say, which goes with it.
     */
    @Subscribe
    public void fieldSubmitted(TextField.Submitted m) {
        if (m.field().equals(nav.symbolFieldAddress())) {
            goToSymbol(nav.symbolChosen());
            return;
        }
        if (m.field().equals(nav.renameAddress())) {
            Path from = nav.renaming();
            Path to = nav.renamedTo(m.text());
            if (from == null) {
                return;
            }
            if (to == null) {
                nav.refuseRename();
            } else {
                relocate.send(new Relocate(from, to, true));
            }
            return;
        }
        if (m.field().equals(nav.pathAddress())) {
            Made what = nav.naming();
            Path from = nav.namingFrom();
            String text = nav.submitted(m.text());
            if (what == Made.FILE || what == Made.FOLDER) {
                go.send(new Go(text));
            } else {
                add.send(new Add(text, what, Optional.ofNullable(from)));
            }
        }
    }

    /** The tree wants a level: ask {@code ListFolder}, or answer an empty top when no folder is shown yet. */
    @Subscribe
    public void treeWanted(TreeView.ChildrenWanted m) {
        if (m.tree().equals(nav.picker().treeAddress())) {
            // Open's top is the one folder it starts at, known already; the levels under it are a listing.
            Listing wanted = nav.picker().wanted(m.parent());
            if (wanted != null) {
                listing.send(wanted);
            } else {
                children.send(nav.picker().top());
            }
            return;
        }
        if (m.tree().equals(nav.scratchAddress())) {
            listing.send(nav.scratchesWanted(m.parent()));
            return;
        }
        if (!m.tree().equals(nav.treeAddress())) {
            return;
        }
        Listing wanted = nav.wanted(m.parent());
        if (wanted != null) {
            listing.send(wanted);
        } else {
            children.send(nav.nothing());
        }
    }

    /** A file selected in the tree opens, and the keyboard stays in the tree, so walking it keeps walking. */
    @Subscribe
    public void treeSelected(TreeView.Selected m) {
        boolean files = m.tree().equals(nav.treeAddress()) || m.tree().equals(nav.scratchAddress());
        if (files && m.item() instanceof Path p && !nav.isFolder(p)) {
            open.send(new Open(p, false));
        } else if (m.tree().equals(nav.picker().treeAddress())) {
            picture();
        }
    }

    /** The row under the pointer in Open's tree, or none: pictured. */
    @Subscribe
    public void treeHovered(TreeView.Hovered m) {
        if (m.tree().equals(nav.picker().treeAddress())) {
            treeHover = m.item() instanceof Path p ? p : null;
            picture();
        }
    }

    /** Enter on a folder opens it in place; in Open, it picks it. */
    @Subscribe
    public void treeActivated(TreeView.Activated m) {
        if (m.tree().equals(nav.treeAddress()) && m.item() instanceof Path p && nav.isFolder(p)) {
            nav.expand(p);
        } else if (m.tree().equals(nav.scratchAddress()) && m.item() instanceof Path p && nav.isFolder(p)) {
            nav.expandScratch(p);
        } else if (m.tree().equals(nav.picker().treeAddress()) && m.item() instanceof Path p) {
            pick(p);
        }
    }

    /** A click on a folder in Open picks it. In the files a click is a selection, heard as one. */
    @Subscribe
    public void treeClicked(TreeView.Clicked m) {
        if (m.tree().equals(nav.picker().treeAddress()) && m.item() instanceof Path p) {
            pick(p);
        }
    }

    /** The chip pressed or released: Open, or the files, from Open or from the scratches. */
    @Subscribe
    public void chipToggled(Button.Toggled m) {
        if (!m.button().equals(nav.chipAddress())) {
            return;
        }
        if (!m.pressed() && nav.scratching()) {
            toggleScratches();
        } else {
            open(m.pressed());
        }
    }

    /** The scratches pressed in Open: picked. New scratch over the scratches: one made. */
    @Subscribe
    public void buttonPressed(Button.Pressed m) {
        if (m.button().equals(nav.newScratchAddress())) {
            newScratch();
        } else if (m.button().equals(nav.indexButtonAddress())) {
            indexHere();
        } else if (m.button().equals(nav.picker().scratchesAddress())) {
            pick(nav.picker().scratchesFolder());
        }
    }

    /** The recent folder Open's drop-down has reached, or none: pictured before it is picked. */
    @Subscribe
    public void recentReached(Select.Changed m) {
        if (!m.select().equals(nav.picker().recentAddress())) {
            return;
        }
        recentCursor = null;
        for (Object o : m.chosen()) {
            if (o instanceof Path p) {
                recentCursor = p;
            }
        }
        picture();
    }

    /** A recent folder picked in Open's drop-down; shut on nothing, it picks nothing. */
    @Subscribe
    public void recentPicked(Select.Committed m) {
        if (!m.select().equals(nav.picker().recentAddress())) {
            return;
        }
        for (Object o : m.chosen()) {
            if (o instanceof Path p) {
                nav.picker().recentPicked();
                pick(p);
                return;
            }
        }
    }

    /** One of Open's way up: its tree starts there instead. */
    @Subscribe
    public void ancestry(Breadcrumb.Navigated m) {
        if (m.breadcrumb().equals(nav.picker().ancestryAddress()) && m.item() instanceof Path p) {
            nav.widen(p);
            treeHover = null;   // a row of the tree just replaced
            claimEscape();
            picture();
        }
    }

    /** One of a row's menu actions. */
    @Subscribe
    public void treeChosen(TreeView.Chosen m) {
        if (m.at().equals(nav.picker().treeAddress()) && m.item() instanceof Path p) {
            if (m.action().equals(Navigator.SHOW)) {
                pick(p);
            } else if (m.action().equals(Navigator.COPY_PATH)) {
                gui.clipboard().set(p.toString());
            }
            return;
        }
        if (m.at().equals(nav.scratchAddress()) && m.item() instanceof Path p) {
            // A file's row makes things beside it, in the folder it is in.
            Path in = nav.isFolder(p) ? p : p.getParent();
            if (m.action().equals(Navigator.NEW_SCRATCH)) {
                newScratch.send(new NewScratch(Optional.of(in)));
            } else if (m.action().equals(Navigator.NEW_FOLDER)) {
                scratchFolderWanted.send(new ScratchFolderWanted(Optional.of(in)));
            } else if (m.action().equals(Navigator.RENAME)) {
                nav.beginRename(p);
            } else if (m.action().equals(Navigator.COPY_PATH)) {
                gui.clipboard().set(p.toString());
            }
            return;
        }
        if (!m.at().equals(nav.treeAddress()) || !(m.item() instanceof Path p)) {
            return;
        }
        if (m.action().equals(Navigator.OPEN)) {
            open.send(new Open(p, true));
        } else if (m.action().equals(Navigator.RUN)) {
            // Asked about the file alone, so what comes back is what the file itself offers (rowRun).
            rowRun = p;
            runsWanted.send(new RunsWanted(Optional.empty(), Optional.of(p)));
        } else if (Navigator.OPEN_IN.containsKey(m.action())) {
            handTo.send(new HandTo(p, nav.isFolder(p), Navigator.OPEN_IN.get(m.action())));
        } else if (m.action().equals(Navigator.SHOW)) {
            go.send(new Go(p.toString()));
        } else if (m.action().equals(Navigator.COPY_PATH)) {
            gui.clipboard().set(p.toString());
        } else if (m.action().equals(Navigator.NEW_FILE) || m.action().equals(Navigator.NEW_FOLDER)) {
            // A file's row names something beside it, in the folder it is in.
            Path in = nav.isFolder(p) ? p : p.getParent();
            boolean folder = m.action().equals(Navigator.NEW_FOLDER);
            nav.startNew(in, folder);
            ui.status(folder ? "Name the new folder, then Enter" : "Name the new file, then Enter", false);
        } else if (m.action().equals(Navigator.NEW_PROJECT)) {
            nav.startNew(p, Made.PROJECT, "new-project", null);
            ui.status("Name the new project, then Enter", false);
        } else if (m.action().equals(Navigator.NEW_MODULE)) {
            nav.startNew(p, Made.MODULE, "new-module", null);
            ui.status("Name the new module, then Enter", false);
        } else if (m.action().equals(Navigator.ARCHIVE)) {
            // Beside the folder, where an archive of it is looked for, and where it cannot end up holding itself.
            Path beside = p.getParent() != null ? p.getParent() : p;
            nav.startNew(beside, Made.ARCHIVE, Picker.label(p) + ".zip", p);
            ui.status("Name the archive, then Enter", false);
        } else if (m.action().equals(Navigator.LINK)) {
            Path beside = p.getParent() != null ? p.getParent() : p;
            nav.startNew(beside, Made.LINK, Picker.label(p) + "-link", p);
            ui.status("Name the link to " + Picker.label(p) + ", then Enter", false);
        }
    }

    // ------------------------------------------------------------------ git's colours

    /**
     * Ask git what it says of the folder shown ({@code ReadGitStatus}): when its top level is listed, which is when it
     * is shown and whenever the editor says its files changed, and after every caption, which follows a save.
     */
    private void wantGit() {
        if (nav.folder() != null) {
            gitStatusWanted.send(new GitStatusWanted(nav.folder(), ++gitAsked));
        }
    }

    /** What git says: every row listed so far takes its ink from it. An answer to an older question is left. */
    @Subscribe
    public void gitStatusRead(GitStatusRead m) {
        if (m.asked() != gitAsked) {
            return;
        }
        nav.git(m);
        inks.send(nav.inks());
    }

    /** What a row's Open in came to ({@code HandOff}): said in the status line either way. */
    @Subscribe
    public void handedTo(HandedTo m) {
        String name = Picker.label(m.path());
        ui.status(m.problem() == null ? "Opened " + name + " in " + m.app().title() : m.problem(), m.problem() != null);
    }

    /**
     * A row dropped or pasted in the scratches' tree: moved there, if that is a move. The tree has drawn nothing for it;
     * the listing after the move does.
     */
    @Subscribe
    public void treePlaced(TreeView.Placed m) {
        if (m.at().equals(nav.scratchAddress()) && m.item() instanceof Path p && m.effect() == DropEffect.MOVE) {
            Path to = nav.placedTo(p, m.where());
            if (to != null) {
                relocate.send(new Relocate(p, to, true));
            }
        }
    }

    @Subscribe
    public void treeRevealed(TreeView.Revealed m) {
        if (m.tree().equals(nav.treeAddress())) {
            nav.revealed(m.item(), m.reached());
        } else if (m.tree().equals(nav.scratchAddress())) {
            nav.scratchRevealed(m.item(), m.reached());
        }
    }

    // ------------------------------------------------------------------ what the person did, by chord

    void saveFront() {
        Buffer b = ws.front();
        if (b != null) {
            save(b);
        }
    }

    /** The text, taken now and marked saved now: what is written is what the document said when Save was asked for. */
    private void save(Buffer b) {
        save.send(new Save(b.id, b.takeForSave(), true));
        reportDirty(b);
    }

    void closeFront() {
        Buffer b = ws.front();
        if (b != null) {
            close(List.of(b));
        }
    }

    /** Ask the editor to close these tabs, in one message: whether any is unsaved is the editor's to know. */
    private void close(List<Buffer> buffers) {
        List<Long> ids = new ArrayList<>(buffers.size());
        for (Buffer b : buffers) {
            ids.add(b.id);
        }
        close.send(new Close(ids));
    }

    void toggleFormatted() {
        Buffer b = ws.front();
        if (b != null) {
            toggleFormatted(b);
        }
    }

    /** Show {@code b} formatted, or as its source, and say which on the status line. */
    private void toggleFormatted(Buffer b) {
        if (!b.formattable()) {
            ui.status("Only Markdown has a formatted view", false);
            return;
        }
        Format wanted = b.showFormatted(!b.showingFormatted());
        if (wanted != null) {
            format.send(wanted);
        }
        if (b == ws.front()) {
            gui.focus(b.keyboard());
        }
        ui.status(b.showingFormatted() ? "Formatted view" : "Source view", false);
    }

    /** Ctrl+Shift+N: a new scratch, which the editor opens in front once it is made. */
    void newScratch() {
        newScratch.send(new NewScratch(Optional.empty()));
    }

    /**
     * Alt+N: the scratches over the navigator's files, bringing the navigator back if it was hidden, or the files
     * again with the keyboard in them.
     */
    void toggleScratches() {
        boolean show = !nav.scratching() || !ui.navigatorShown();
        if (show) {
            ui.showNavigator(true);
            nav.scratches(true);
        } else {
            nav.scratches(false);
            nav.focusTree();
        }
        picture();   // Open may have been what the scratches replaced
    }

    /** Escape in the name field: no rename, and the keyboard back in the scratches' tree. */
    void leaveRename() {
        nav.endRename();
    }

    void cycle(int delta) {
        ws.cycle(delta);
    }

    /** Ctrl+B: the navigator, or the whole window for the files; the keyboard goes where the eye is. */
    void toggleNavigator() {
        boolean show = !ui.navigatorShown();
        ui.showNavigator(show);
        if (show && nav.folder() == null) {
            open(true);   // no folder to list: what there is to do here is pick one
        } else if (show) {
            nav.focusTree();
        } else if (ws.front() != null) {
            gui.focus(ws.front().keyboard());
        }
        picture();
    }

    /** Ctrl+O: Open, or back to the files. */
    void toggleOpen() {
        open(!nav.opened() || !ui.navigatorShown());
    }

    /**
     * Put Open up, bringing the navigator back if it was hidden, or take it away and give the keyboard to the files.
     * Showing a folder takes it away too, without this: the navigator ends Open itself when it is shown one.
     */
    void open(boolean value) {
        if (value) {
            if (!nav.opened()) {
                ui.preview().forget();   // each visit looks at the disk again
                recentCursor = null;
                treeHover = null;
                scratchesWanted.send(new ScratchesWanted());
            }
            ui.showNavigator(true);
            nav.open(true);
            claimEscape();
        } else if (nav.opened()) {
            nav.open(false);
            nav.focusTree();
        }
        picture();
    }

    /**
     * While Open is up, its picture stands where the editor does: of the recent folder the drop-down has reached, then
     * of the row under the pointer, and with neither, of the selected row. Before the tree's walk has selected the
     * folder shown, that folder, which the walk will select; with none shown, the folder the tree starts at. Worked
     * out from all of them each time rather than from whichever message came last, since leaving one control and
     * entering the next arrive as two messages in no promised order.
     */
    private void picture() {
        boolean on = nav.opened() && ui.navigatorShown();
        ui.picturing(on);
        if (!on) {
            return;
        }
        Path selected = nav.picker().selected();
        Path folder = recentCursor != null ? recentCursor
                : treeHover != null ? treeHover
                : selected != null ? selected
                : nav.folder() != null ? nav.folder()
                : nav.picker().topFolder();
        ask(ui.preview().show(folder));
    }

    private void ask(Picture wanted) {
        if (wanted != null) {
            picture.send(wanted);
        }
    }

    /** Esc in Open's tree goes back to the files. Claimed on each tree, since Open builds one each time it starts. */
    private void claimEscape() {
        Node picker = nav.picker().treeNode();
        if (picker != null) {
            gui.claim(picker, Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, intents, new Intent(Command.LEAVE_OPEN));
        }
    }

    /** A folder picked in Open: the editor shows it, as if it were typed in the path bar, and that ends Open. */
    private void pick(Path folder) {
        go.send(new Go(folder.toString()));
    }

    /** The path bar and the tree are the navigator's, so asking for either brings it back if it was hidden. */
    void focusPath() {
        ui.showNavigator(true);
        nav.focusPath();
    }

    void focusTree() {
        ui.showNavigator(true);
        nav.focusTree();
    }

    /**
     * The New menu: name {@code what} in the folder shown, in the path bar, as a row's menu does for its row. The files
     * come back from Open or the scratches first, since the bar is theirs.
     */
    void newHere(Made what) {
        Path in = nav.folder();
        if (in == null) {
            ui.status("Open a folder first", true);
            return;
        }
        open(false);
        nav.scratches(false);
        ui.showNavigator(true);
        picture();
        if (what == Made.PROJECT) {
            nav.startNew(in, Made.PROJECT, "new-project", null);
            ui.status("Name the new project, then Enter", false);
        } else if (what == Made.MODULE) {
            nav.startNew(in, Made.MODULE, "new-module", null);
            ui.status("Name the new module, then Enter", false);
        } else {
            nav.startNew(in, false);
            ui.status("Name the new file, then Enter", false);
        }
    }

    /**
     * New → Index this project: index the project the folder shown is in, after the one under way if one is, and say
     * so when it lands.
     */
    void indexHere() {
        Path in = nav.folder();
        if (in == null) {
            ui.status("Open a folder first", true);
            return;
        }
        index(in, true);
        nav.indexing(in);
        ui.status("Indexing " + Picker.label(in) + "…", false);
    }

    /** Whether documents wrap their long lines now: Alt+Z flips this. */
    boolean wrapping() {
        return wrap;
    }

    /**
     * Alt+Z, or the View menu's ticked line: word wrap {@code on} or off in every open document, and in every one
     * opened after. The menu line says to set it, not to flip it, so choosing it sets what it said when it was drawn.
     */
    void wrap(boolean on) {
        wrap = on;
        for (Buffer b : ws.all()) {
            b.wordWrap(wrap);
        }
        ui.viewMenu(wrap, intents);
        ui.status(wrap ? "Word wrap on" : "Word wrap off", false);
    }

    /** In ({@code 1}), out ({@code -1}), or back to where it started ({@code 0}). */
    void zoom(int direction) {
        if (direction > 0) {
            gui.zoomIn();
        } else if (direction < 0) {
            gui.zoomOut();
        } else {
            gui.resetZoom();
        }
    }

    /** Tell the editor when a document went unsaved, or came back to what is on disk. */
    private void reportDirty(Buffer b) {
        if (b.dirtyChanged()) {
            edited.send(new Edited(b.id, b.dirty()));
        }
    }
}
