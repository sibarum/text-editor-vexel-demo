package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.CreateFile;
import dev.vexelray.demo.editor.core.ListFolder;
import dev.sibarum.suite.git.Status;
import dev.vexelray.demo.editor.core.Messages.App;
import dev.vexelray.demo.editor.core.Messages.Declaration;
import dev.vexelray.demo.editor.core.Messages.GitStatusRead;
import dev.vexelray.demo.editor.core.Messages.Item;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Listing;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.SymbolsFound;
import dev.vexelray.demo.editor.core.Scratches;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.FocusRing;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.text.Ellipsis;
import dev.vexelray.gui.fileicons.FileFamily;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.widget.Framing;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.gui.widget.Unclip;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The file navigator: a path bar over a tree of what is in one folder. The {@link View}'s, on the GUI thread.
 *
 * <h2>The path bar is the whole of "open"</h2>
 *
 * <p>There is no file dialog, by choice, though the framework offers native ones ({@code Dialogs}). The bar
 * stands in for all three of them: type a folder and the tree shows it, type a file and it opens, type a name that
 * is not there yet and it is created. Ctrl+L puts the keyboard in it; Enter goes. It is an ordinary single-line
 * {@link TextField}, named {@code path}, and its {@code SUBMITTED} message is the whole of its wiring.
 *
 * <p>So it is where new things are named, too. A row's New ▸ File… puts the folder into the bar with the caret
 * after it, and Enter makes the name typed there, from the template its extension asks for. A name ending in a
 * separator is a folder, and New ▸ Folder… adds that separator itself, so the person types only the name.
 *
 * <p>What an extension cannot say is said by the row's menu, and the bar keeps it in mind until Enter: a Maven project,
 * or where a project is around the row a module of it instead; an archive of a folder, named beside it; and a link to
 * the row, where the platform makes links without privilege. Each puts a name in the bar, selected, so Enter takes it
 * and typing replaces it. Which of these a row offers its listing said, so the menu asks nothing of the disk.
 *
 * <h2>The tree asks, and is answered</h2>
 *
 * <p>The tree asks for a level the first time it is opened ({@code TreeView.CHILDREN_WANTED}); reading a folder is
 * I/O, so the view asks {@code ListFolder} on the offload lane, and when the answer comes this turns it into the tree's
 * entries ({@link #children}). What a row is drawn from comes with the answer, so drawing calls nothing, and whether a
 * row is a folder is known from the listing that drew it, never by looking at the disk again on the GUI thread.
 *
 * <p>Selecting a file opens it: a click, or walking the tree with the arrow keys, which keeps the keyboard in the tree
 * so the walk carries on. Enter opens a folder in place. Rows move rather than jump when a folder opens, because the
 * tree is handed {@link Motion#arrival}. There is one tree for the life of the window, and showing another folder
 * refreshes it, which asks again for every level it has listed.
 *
 * <h2>Open is the other place it can be</h2>
 *
 * <p>Above the bar, a chip says which folder is shown; pressing it, or Ctrl+O, puts the {@link Picker} where the files
 * were, to pick another folder from. The chip stays pressed while Open is up, so the way back is the same name pressed
 * again, and the card steeps in the accent and wears it as its edge, with a sweep down it as it changes, so the switch
 * is seen even by an eye that was on the document. Showing a folder ends Open.
 *
 * <p>The scratches' folder ({@link Scratches}) is a folder like any other once it is shown, but for its name: the chip
 * says Scratches rather than the folder's own, which is only where the editor keeps them.
 *
 * <h2>The scratches can be laid over the files</h2>
 *
 * <p>Alt+N puts a tree of the scratches where the files were, without showing their folder: the folder shown stays
 * the one shown, and Alt+N again, or the chip, puts its files back. It is the third place the card can be, beside the
 * files and Open, and it is edged in the accent as Open is, but not steeped in it, since the editor is still beside
 * it and still the thing being worked in. Above the tree is New scratch, and each row's menu has New ▸ Scratch.
 * A scratch made while it is up is listed and selected once its listing lands ({@link #scratchMade}).
 *
 * <p>The scratches are organised in their tree: a row dragged onto a folder, or between the rows of one, or cut and
 * pasted there, is moved there ({@link #placedTo}), and Rename… puts a name field above the tree, Enter to rename and
 * Escape to leave it. New folder here makes one and opens the field on it. The view asks {@code MoveScratch} for each,
 * and the window's history takes in each that lands, so Ctrl+Z puts it back.
 *
 * <h2>Symbols: the project's declarations, by name</h2>
 *
 * <p>Ctrl+T puts a name field and a list where the files were: the declarations in the project's index
 * ({@code FindSymbols}) whose names answer what is typed, best first, each row its name over what it is and where.
 * The keyboard stays in the field while typing; Down goes into the list, Enter or a click on a row goes to the
 * declaration, and Escape puts the files back. Under the list a line says how many were found, or that the project has
 * no index yet, with the button that builds one. It is edged in the accent as the scratches are: the editor is still
 * beside it.
 */
final class Navigator {

    /** Where the card is: the folder's files, Open in their place, or the scratches over them. */
    enum Place { FILES, OPEN, SCRATCHES, SYMBOLS }

    /** The rows' menu actions, by the id {@code TreeView.CHOSEN} carries back. */
    static final String OPEN = "open";
    static final String SHOW = "show-folder";
    static final String COPY_PATH = "copy-path";
    static final String NEW_FILE = "new-file";
    static final String NEW_FOLDER = "new-folder";
    static final String NEW_PROJECT = "new-project";
    static final String NEW_MODULE = "new-module";
    static final String ARCHIVE = "archive";
    static final String LINK = "link";
    static final String NEW_SCRATCH = "new-scratch";
    static final String RENAME = "rename";
    static final String RUN = "run";

    /**
     * The extensions whose files a row offers to Run, as the Run menu would run them ({@code Runs}): a Java file with a
     * {@code main}, a Pontif file, a jar, and what the platform runs as a program. Said by the name alone, so the
     * listing reads nothing more; a Java file with no {@code main} is said when Run finds nothing in it.
     */
    static final Set<String> RUNNABLE = File.separatorChar == '\\'
            ? Set.of("java", "ptf", "jar", "exe", "bat", "cmd", "ps1")
            : Set.of("java", "ptf", "jar", "sh");

    /** The submenu the rows' New lines are in: File…, Folder…, Project…, Module…; Scratch and Folder over scratches. */
    static final String NEW_MENU = "New";

    /** The submenu that hands a row to another program ({@code HandOff}), each line's id with the program it names. */
    static final String OPEN_IN_MENU = "Open in";
    static final Map<String, App> OPEN_IN = Map.of("open-in-vexplore", App.VEXPLORE,
            "open-in-mainframe", App.MAINFRAME, "open-in-rasterware", App.RASTERWARE, "open-in-host", App.HOST);

    /** How many times a row being revealed, or a new scratch's, is looked for before it is left unselected. */
    private static final int REVEAL_TRIES = 10;

    private final Gui gui;
    private final Motion motion;
    private final Scratches scratches;
    private final TextField path;
    private final TreeView<Path> tree;
    private final Button chip;
    private final Picker picker;
    private final TreeView<Path> scratchTree;
    private final Button newScratch;
    /** Rename's field, above the scratches' tree while a row is being renamed. */
    private final TextField renameField;
    private final Node scratchPane;
    /** Symbols: the name typed, the declarations it answers, and the line saying how many or why none. */
    private final TextField symbolField;
    private final ListView<Declaration> symbolList;
    private final Node symbolNote;
    private final Button indexButton;
    private final Node symbolPane;
    /**
     * What stands in the chip's place while Symbols is up: its name and icon in violet, and how to leave. Not the chip,
     * whose pressed look is the theme's teal, which is Open's: a mode is told by its colour, so Symbols wears its own.
     */
    private final Node symbolHeader;
    /** The declarations listed now. */
    private List<Declaration> symbols = List.of();
    /** The scratch or folder the name field is for, or null while it is put away. */
    private Path renaming;
    private final Node root;
    /** Where the card is. */
    private Place place = Place.FILES;
    /** A scratch made while the scratches are up, to select once its row is there; null for none. */
    private Path scratchWanted;
    /** Whether a walk to it is under way, and how many more may be tried. */
    private boolean scratchWalking;
    private int scratchTries;
    /** What the chip says, which a node does not give back. */
    private String chipText = NO_FOLDER;
    /** Every folder a listing has shown, so a row's kind is known without the disk. */
    private final Set<Path> folders = new HashSet<>();
    /** Every path the files tree has been told of since the folder was shown, so new colours reach every row. */
    private final Set<Path> listed = new HashSet<>();
    /** Each folder a compacted row stands for, and the row's path: what a reveal walks through as that row. */
    private final Map<Path, Path> folded = new HashMap<>();
    /** What git last said of the folder shown, or null: outside a repository, or before it answers. */
    private Status git;
    /** The folder shown, or null for none yet. */
    private Path folder;
    /** The file a reveal is walking to, to ring when the walk lands. */
    private Path revealing;
    /** Whether a walk to it is under way, and how many more may be tried. */
    private boolean revealWalking;
    private int revealTries;
    /**
     * What a row's menu put the bar in mind of, until the bar is next submitted or taken for something else: a file,
     * which is also the bar's own mind, where the text alone says what it names.
     */
    private Made naming = Made.FILE;
    /** What an archive being named holds, or what a link being named points at; null for the rest. */
    private Path namingFrom;
    /** Whether rows offer "Link to this…": where the platform makes links without privilege, or a test says so. */
    private boolean links = CreateFile.links();

    Navigator(Gui gui, Motion motion, Scratches scratches) {
        this.gui = gui;
        this.motion = motion;
        this.scratches = scratches;

        path = new TextField(gui, "").name("path");
        path.node().width(Length.FILL).font(Type.UI).textSize(Type.SMALL);
        gui.landmark(Landmarks.PATH, path.node());

        tree = new TreeView<Path>(gui, "files").motion(motion.arrival)
                .action(MenuAction.of(OPEN, "›", "Open"))
                .action(MenuAction.of(RUN, "▶", "Run"))
                .action(MenuAction.of(SHOW, "»", "Set project root"))
                .action(MenuAction.of("open-in-vexplore", "Vexplore").under(OPEN_IN_MENU))
                .action(MenuAction.of("open-in-mainframe", "MainFrame").under(OPEN_IN_MENU))
                .action(MenuAction.of("open-in-rasterware", "Rasterware").under(OPEN_IN_MENU))
                .action(MenuAction.of("open-in-host", "Host OS").under(OPEN_IN_MENU))
                .action(MenuAction.of(COPY_PATH, "Copy path"))
                .action(MenuAction.of(NEW_FILE, "+", "File…").under(NEW_MENU))
                .action(MenuAction.of(NEW_FOLDER, "Folder…").under(NEW_MENU))
                .action(MenuAction.of(NEW_PROJECT, "Project…").under(NEW_MENU))
                .action(MenuAction.of(NEW_MODULE, "Module…").under(NEW_MENU))
                .action(MenuAction.of(ARCHIVE, "Archive this folder…"))
                .action(MenuAction.of(LINK, "Link to this…"));
        tree.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.TREE, tree.node());

        // A chip, outlined and so plainly pressable, saying the thing that Open changes: the thing to change is the
        // thing to press. A button rather than a clickable text, so Tab, Enter and Space reach it too.
        // A long folder name is cut off at the chip's edge rather than painted over the card's, and shown whole while
        // the pointer is on it.
        chip = new Button(gui, NO_FOLDER).name("folder").kind(Button.Kind.SECONDARY).toggle(true)
                .unclip(Unclip.of(gui));
        chip.node().width(Length.FILL);
        gui.landmark(Landmarks.FOLDER, chip.node());

        picker = new Picker(gui, scratches);
        picker.node().visible(false);

        // The one tree here that changes the disk: a row dragged onto a folder or between the rows of one, or cut and
        // pasted there, is moved there. The tree refuses in the frame what it can see (onto a file, into itself); what
        // only the disk knows, a name taken there, the job refuses when the move is asked for.
        scratchTree = new TreeView<Path>(gui, "scratches").motion(motion.arrival)
                .action(MenuAction.of(NEW_SCRATCH, "+", "Scratch").under(NEW_MENU))
                .action(MenuAction.of(NEW_FOLDER, "Folder").under(NEW_MENU))
                .action(MenuAction.of(RENAME, "Rename…"))
                .action(MenuAction.of(COPY_PATH, "Copy path"))
                .reorderable(DropEffect.MOVE);
        scratchTree.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.SCRATCHES, scratchTree.node());
        newScratch = new Button(gui, "New scratch").kind(Button.Kind.GHOST).name("new-scratch");
        newScratch.node().width(Length.FILL);
        gui.landmark(Landmarks.NEW_SCRATCH, newScratch.node());
        renameField = new TextField(gui, "").name("rename");
        renameField.node().width(Length.FILL).font(Type.UI).textSize(Type.SMALL).visible(false);
        gui.landmark(Landmarks.RENAME_SCRATCH, renameField.node());
        scratchPane = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .children(newScratch.node(), renameField.node(), scratchTree.node());
        scratchPane.visible(false);

        symbolField = new TextField(gui, "").name("symbols");
        symbolField.node().width(Length.FILL).font(Type.UI).textSize(Type.SMALL);
        gui.landmark(Landmarks.SYMBOLS, symbolField.node());
        // Two lines a row: the name, then what it is and where, fainter. Each kept to its line, cut with an ellipsis,
        // since the navigator is narrow and a row's height is fixed.
        symbolList = new ListView<Declaration>(gui, 2.5f, (nodes, d) -> nodes.column().width(Length.FILL).children(
                nodes.text(d.name()).font(Type.UI).textSize(Type.SMALL)
                        .wordWrap(false).width(Length.FILL).ellipsis(Ellipsis.END),
                nodes.text(detail(d)).font(Type.UI).textSize(Type.SMALL)
                        .textColor(gui.theme().color(Role.DIM))
                        .wordWrap(false).width(Length.FILL).ellipsis(Ellipsis.END)))
                .name("symbol-list")
                .looks(Look.SEARCH_SELECTED, null);
        symbolList.node().width(Length.FILL).height(Length.grow(1f));
        // Focus in Symbols is ringed in its violet, not the theme's teal, which is Open's colour.
        FocusRing searching = gui.theme().focusRing().color(Look.SEARCH_FOCUS);
        symbolField.node().focusRing(searching);
        symbolList.node().focusRing(searching);
        Color violet = gui.theme().color(Look.SEARCHING_RIM);
        symbolHeader = gui.row()
                .width(Length.FILL)
                .gap(Type.TIGHT)
                .alignItems(AlignItems.CENTER)
                .children(IconView.of(gui, Icon.SEARCH).size(Length.rem(1.125f)).tint(violet).node(),
                        gui.text(SYMBOLS).font(Type.UI).textColor(violet).width(Length.grow(1f))
                                .wordWrap(false).ellipsis(Ellipsis.END),
                        gui.text("Esc").font(Type.UI).textSize(Type.SMALL).wordWrap(false)
                                .textColor(gui.theme().color(Role.DIM)));
        symbolHeader.visible(false);
        gui.landmark(Landmarks.SYMBOL_MODE, symbolHeader);
        gui.landmark(Landmarks.SYMBOL_LIST, symbolList.node());
        symbolNote = gui.text(TYPE_A_NAME).font(Type.UI).textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.DIM));
        gui.landmark(Landmarks.SYMBOL_NOTE, symbolNote);
        indexButton = new Button(gui, "Index this project").kind(Button.Kind.SECONDARY).name("index-project");
        indexButton.node().width(Length.FILL).visible(false);
        gui.landmark(Landmarks.INDEX_PROJECT, indexButton.node());
        symbolPane = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .children(symbolField.node(), symbolList.node(), symbolNote, indexButton.node());
        symbolPane.visible(false);

        // The edge is there in every place, in the panel's own colour while the files are shown, so it costs no
        // layout when it turns to the accent.
        // The trees sit in the card rather than in wells of their own.
        tree.frame(Framing.FLUSH);
        scratchTree.frame(Framing.FLUSH);
        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .gap(Type.TIGHT)
                .padding(Type.GAP, Type.GAP)
                .corner(Type.CORNER)
                .alignItems(AlignItems.STRETCH)
                .children(chip.node(), symbolHeader, path.node(), tree.node(), picker.node(), scratchPane, symbolPane);
        paint();
    }

    /** What the chip says while no folder is shown. */
    static final String NO_FOLDER = "No folder";

    /**
     * The card's colours for where it is: the panel for the files, steeped in the accent for Open, edged in it for the
     * scratches, and steeped and edged in violet for Symbols, the one mode in a hue of its own.
     */
    private void paint() {
        Role fill = place == Place.OPEN ? Look.PICKING : place == Place.SYMBOLS ? Look.SEARCHING : Look.CARD;
        Role edge = place == Place.FILES ? Look.RIM : place == Place.SYMBOLS ? Look.SEARCHING_RIM : Look.PICKING_RIM;
        root.background(gui.theme().color(fill)).border(Type.RULE, gui.theme().color(edge));
    }

    /**
     * Put the card in {@code to}: the chip pressed and saying where it is, the place's own nodes shown and the rest
     * hidden, and a sweep down it as it leaves the files, so the change is seen from the document.
     */
    private void place(Place to) {
        if (to == place) {
            return;
        }
        place = to;
        // Symbols hides the chip as it is, so it fades out as drawn, and changes it only as it comes back.
        chip.node().visible(to != Place.SYMBOLS);
        if (to != Place.SYMBOLS) {
            chip.show(to != Place.FILES);
            chip.label(chipLabel());
        }
        symbolHeader.visible(to == Place.SYMBOLS);
        path.node().visible(to == Place.FILES || to == Place.OPEN);
        tree.node().visible(to == Place.FILES);
        picker.node().visible(to == Place.OPEN);
        scratchPane.visible(to == Place.SCRATCHES);
        symbolPane.visible(to == Place.SYMBOLS);
        symbolList.showing(to == Place.SYMBOLS);
        if (to != Place.SCRATCHES) {
            scratchWanted = null;
            endRename();   // a rename left unfinished when the scratches went is a rename not made
        }
        paint();
        if (to != Place.FILES) {
            motion.cues.play(root, Cue.scanline(gui.theme().color(to == Place.SYMBOLS ? Look.SEARCHING_RIM : Role.ACCENT)));
        }
    }

    /** Where the card is. */
    Place place() {
        return place;
    }

    // ------------------------------------------------------------------ Open

    /** Whether Open is up. */
    boolean opened() {
        return place == Place.OPEN;
    }

    /**
     * Put Open in place of the files, starting from the folder above the one shown, or take it away. The keyboard
     * goes to Open's tree when it comes.
     */
    void open(boolean value) {
        if (value == opened()) {
            return;
        }
        place(value ? Place.OPEN : Place.FILES);
        if (value) {
            picker.start(folder);
            picker.focus();
        }
    }

    // ------------------------------------------------------------------ symbols

    /** What Symbols' header says. */
    static final String SYMBOLS = "Symbols";

    /** What the note says before anything is typed. */
    static final String TYPE_A_NAME = "Type a name: a class, a method, a field";

    /** Whether Symbols is up. */
    boolean symbolsUp() {
        return place == Place.SYMBOLS;
    }

    /**
     * Put Symbols where the files were, with the keyboard in its field and what was typed last selected, so typing
     * replaces it and Enter goes to it again; or put the files back.
     */
    void symbols(boolean value) {
        if (value != symbolsUp()) {
            place(value ? Place.SYMBOLS : Place.FILES);
        }
        if (value) {
            gui.focus(symbolField.node());
            symbolField.select(0, symbolField.text().length());
        }
    }

    /** Down from the field: the keyboard in the list, on its first row if none is selected. */
    void focusSymbolList() {
        if (symbols.isEmpty()) {
            return;
        }
        if (symbolList.selection().lead() == null) {
            symbolList.selection().at(symbols.getFirst());
        }
        gui.focus(symbolList.node());
    }

    /** The row Enter in the field goes to: the one selected, or the first. Null when the list is empty. */
    Declaration symbolChosen() {
        Declaration lead = symbolList.selection().lead();
        return lead != null ? lead : symbols.isEmpty() ? null : symbols.getFirst();
    }

    /** What {@code FindSymbols} answered: listed, the best first and selected, and the note saying what it is. */
    void symbolsFound(SymbolsFound m) {
        symbols = m.rows();
        symbolList.items(symbols);
        symbolList.selection().clear();
        if (!symbols.isEmpty()) {
            symbolList.selection().at(symbols.getFirst());
            symbolList.reveal(symbols.getFirst());
        }
        indexButton.node().visible(!m.indexed() && m.problem().isEmpty());
        symbolNote.text(note(m));
    }

    /** The line under the list: why there is nothing, or how many there are. */
    static String note(SymbolsFound m) {
        if (!m.problem().isEmpty()) {
            return "Nothing to search: " + m.problem();
        }
        if (!m.indexed()) {
            return Picker.label(m.project()) + " has no index yet";
        }
        if (m.typed().isEmpty()) {
            return TYPE_A_NAME;
        }
        int found = m.rows().size() + m.more();
        if (found == 0) {
            return "No declaration is named like " + m.typed();
        }
        String count = found == 1 ? "1 declaration" : found + " declarations";
        return m.more() == 0 ? count : count + ", the first " + m.rows().size() + " listed";
    }

    /**
     * What a row says under its name: what it is, and the type it is a member of, or the last two parts of the package
     * a type is in, which is what tells two of a name apart in a narrow navigator.
     */
    static String detail(Declaration d) {
        if (d.owner().isEmpty()) {
            return d.kind();
        }
        if (TYPES.contains(d.kind())) {
            int last = d.owner().lastIndexOf('.');
            int before = last < 0 ? -1 : d.owner().lastIndexOf('.', last - 1);
            return d.kind() + " in " + d.owner().substring(before + 1);
        }
        int dot = d.owner().lastIndexOf('.');
        return d.kind() + " of " + d.owner().substring(dot + 1);
    }

    /** The kinds that declare a type, as {@code FindSymbols} names them. */
    private static final Set<String> TYPES = Set.of("class", "interface", "enum", "record", "annotation");

    /** An index is being built: the button goes, and the note says so. */
    void indexing(Path project) {
        indexButton.node().visible(false);
        symbolNote.text("Indexing " + Picker.label(project) + "…");
    }

    /** What is typed in Symbols' field. */
    String symbolText() {
        return symbolField.text();
    }

    String symbolFieldAddress() {
        return symbolField.address();
    }

    Node symbolFieldNode() {
        return symbolField.node();
    }

    String symbolListAddress() {
        return symbolList.address();
    }

    Node symbolListNode() {
        return symbolList.node();
    }

    String indexButtonAddress() {
        return indexButton.address();
    }

    // ------------------------------------------------------------------ the scratches

    /** Whether the scratches are laid over the files. */
    boolean scratching() {
        return place == Place.SCRATCHES;
    }

    /**
     * Lay the scratches over the files, listed afresh and with the keyboard in their tree, or put the files back.
     * Coming up ends Open, since there is one place for either.
     */
    void scratches(boolean value) {
        if (value == scratching()) {
            return;
        }
        place(value ? Place.SCRATCHES : Place.FILES);
        if (value) {
            scratchTree.refresh();
            scratchTree.focus();
        }
    }

    /** What to ask {@code ListFolder} for a level the scratches' tree wants: the folder opened, or their own. */
    Listing scratchesWanted(Object parent) {
        return parent instanceof Path dir ? new Listing(scratchAddress(), dir, false, false)
                : new Listing(scratchAddress(), scratches.dir(), true, false);
    }

    /** {@code ListFolder}'s answer for the scratches' tree, whose every row offers the same two actions. */
    TreeView.Children scratchChildren(Listed listed) {
        List<TreeView.Entry> entries = new ArrayList<>(listed.items().size());
        for (Item item : listed.items()) {
            if (item.folder()) {
                folders.add(item.path());
                entries.add(TreeView.Entry.branch(item.path(), item.name()));
            } else {
                entries.add(TreeView.Entry.leaf(item.path(), item.name()));
            }
        }
        return new TreeView.Children(scratchAddress(), listed.roots() ? null : listed.dir(), entries);
    }

    /**
     * A scratch was made: while the scratches are up, list them again and select its row once it is there. The row
     * comes with the refresh's answers, which land after this frame, so the walk to it is begun as each lands
     * ({@link #scratchListed}) and tried again when it ends short ({@link #scratchRevealed}), a few times at most.
     */
    void scratchMade(Path file) {
        if (!scratching()) {
            return;
        }
        scratchWanted = file;
        scratchTries = REVEAL_TRIES;
        scratchWalking = false;
        scratchTree.refresh();
    }

    /** A level of the scratches' tree was answered: look for the scratch wanted, if no look is under way. */
    void scratchListed() {
        if (scratchWanted != null && !scratchWalking) {
            walkToScratch();
        }
    }

    /** A walk in the scratches' tree ended: done when it got there, and tried again when it did not. */
    void scratchRevealed(Object item, boolean reached) {
        if (scratchWanted == null || !item.equals(scratchWanted)) {
            return;
        }
        scratchWalking = false;
        if (reached) {
            scratchWanted = null;
        } else if (scratchTries > 0) {
            walkToScratch();
        } else {
            scratchWanted = null;
        }
    }

    private void walkToScratch() {
        List<Path> chain = ListFolder.chain(scratches.dir(), scratchWanted);
        if (chain.isEmpty()) {
            scratchWanted = null;
            return;
        }
        scratchTries--;
        scratchWalking = true;
        scratchTree.revealPath(chain);
    }

    /**
     * Where a row dropped or pasted at {@code where} in the scratches' tree goes: into the folder it is on, the folder
     * of the rows it is between, or the scratches' own; null where that is no move ({@link Scratches#into}).
     */
    Path placedTo(Path moved, Placement<?> where) {
        Path folder;
        if (where.isRoot()) {
            folder = scratches.dir();
        } else if (where.relation() == Placement.Relation.INTO && where.reference() instanceof Path p) {
            folder = p;
        } else if (where.reference() instanceof Path p) {
            folder = p.getParent();
        } else {
            return null;
        }
        return scratches.into(moved, folder);
    }

    /**
     * Rename on a row's menu: the name field, holding {@code item}'s name with all but a file's extension selected, so
     * typing replaces the part that is usually meant.
     */
    void beginRename(Path item) {
        if (!scratches.holds(item) || scratches.is(item)) {
            return;
        }
        renaming = item;
        String name = String.valueOf(item.getFileName());
        int dot = name.lastIndexOf('.');
        renameField.text(name);
        renameField.select(0, dot > 0 && !isFolder(item) ? dot : name.length());
        renameField.node().visible(true);
        gui.focus(renameField.node());
    }

    /** What the name field is for, or null while it is put away. */
    Path renaming() {
        return renaming;
    }

    /** Where what is being renamed would be once called {@code name}; null for a name that cannot be one. */
    Path renamedTo(String name) {
        return renaming == null ? null : scratches.named(renaming, name);
    }

    /** A name that cannot be had: the field stays, ringed, to be changed. */
    void refuseRename() {
        motion.cues.play(renameField.node(), Cue.ring(gui.theme().color(Role.DANGER), 1));
        gui.focus(renameField.node());
    }

    /** Put the name field away, and the keyboard back in the tree if the field had it. */
    void endRename() {
        if (renaming == null) {
            return;
        }
        renaming = null;
        renameField.node().visible(false);
        if (scratching()) {
            scratchTree.focus();
        }
    }

    /** The name field's address, which its messages carry, and its node, for Escape. */
    String renameAddress() {
        return renameField.address();
    }

    Node renameNode() {
        return renameField.node();
    }

    /** Whether the scratches' tree has a row for {@code item}. */
    boolean hasScratchRow(Path item) {
        return scratchTree.rowNode(item) != null;
    }

    /** The row selected in the scratches' tree, or null. */
    Path scratchSelected() {
        return scratchTree.selected();
    }

    /** Open a folder's row in the scratches' tree. */
    void expandScratch(Path item) {
        scratchTree.expand(item);
    }

    /** The scratches' tree's address, which its messages carry. */
    String scratchAddress() {
        return scratchTree.address();
    }

    /** New scratch's address, which its {@code Pressed} carries. */
    String newScratchAddress() {
        return newScratch.address();
    }

    /** Start Open's tree at {@code from}, one of its way up's ancestors. */
    void widen(Path from) {
        picker.startAt(from, folder);
        picker.focus();
    }

    Picker picker() {
        return picker;
    }

    /** The chip's address, which its {@code Toggled} carries. */
    String chipAddress() {
        return chip.address();
    }

    /** What the chip says: the folder shown's name, or Scratches while they are over its files. */
    String chipLabel() {
        return scratching() ? Scratches.NAME : chipText;
    }

    Node node() {
        return root;
    }

    /** The path bar's address, which its messages carry. */
    String pathAddress() {
        return path.address();
    }

    /** What the path bar says. */
    String pathText() {
        return path.text();
    }

    /** The tree's address, which its messages carry. */
    String treeAddress() {
        return tree.address();
    }

    /** The folder shown, or null for none yet. */
    Path folder() {
        return folder;
    }

    /**
     * Show {@code shown}: say in the path bar which folder it is, and have the tree ask for its levels again, which
     * the view answers as it answers every question the tree asks. Another folder puts the files back from wherever
     * the card was; the same one again, which is the editor saying its files have changed, ends only Open.
     */
    void show(Path shown) {
        boolean another = !shown.equals(folder);
        folder = shown;
        if (another) {
            git = null;   // another folder's colours are not this one's; its own status is asked for
            listed.clear();
            folded.clear();
        }
        naming = Made.FILE;
        namingFrom = null;
        path.text(String.valueOf(shown));
        chipText = scratches.is(shown) ? Scratches.NAME : Picker.label(shown);
        tree.refresh();
        if (another || opened()) {
            place(Place.FILES);
        }
        chip.label(chipLabel());
    }

    /**
     * What to ask {@code ListFolder} for a level the tree wants: {@code parent} is a folder opened in it, or null for
     * its top, the folder shown. Null when there is nothing to list, no folder shown yet.
     */
    Listing wanted(Object parent) {
        if (parent instanceof Path dir) {
            return new Listing(treeAddress(), dir, false, false, true);
        }
        return folder == null ? null : new Listing(treeAddress(), folder, true, false, true);
    }

    /**
     * {@code ListFolder}'s answer, as the tree's entries for the level it asked about. A compacted folder's row stands
     * for the folders between the level and it, which are noted in {@link #folded} for a reveal to walk through.
     */
    TreeView.Children children(Listed listed) {
        List<TreeView.Entry> entries = new ArrayList<>(listed.items().size());
        for (Item item : listed.items()) {
            this.listed.add(item.path());
            folded.remove(item.path());   // a row of its own now, whatever it was folded into before
            for (Path p = item.path().getParent(); p != null && !p.equals(listed.dir()); p = p.getParent()) {
                folded.put(p, item.path());
            }
            TreeView.Entry entry;
            if (item.folder()) {
                folders.add(item.path());
                // A module where a project is around, a project where none is: one or the other, never both.
                entry = TreeView.Entry.branch(item.path(), item.name())
                        .hides(OPEN, RUN, item.inProject() ? NEW_PROJECT : NEW_MODULE);
            } else {
                entry = TreeView.Entry.leaf(item.path(), item.name()).hides(SHOW, NEW_PROJECT, NEW_MODULE, ARCHIVE);
                // Rasterware opens pictures, videos and folders of them, so another file is not offered to it.
                FileFamily family = ContentsView.family(item.name());
                if (family != FileFamily.IMAGES && family != FileFamily.VIDEO) {
                    entry = entry.hides("open-in-rasterware");
                }
                if (!RUNNABLE.contains(extension(item.name()))) {
                    entry = entry.hides(RUN);
                }
            }
            // Where a link takes privilege to make, it is not offered: a line that fails for most people teaches nothing.
            entries.add(links ? entry : entry.hides(LINK));
        }
        return new TreeView.Children(treeAddress(), listed.roots() ? null : listed.dir(), entries);
    }

    // ------------------------------------------------------------------ git's colours

    /**
     * What git last said of the folder shown ({@code ReadGitStatus}), for the rows' inks: none (null) outside a
     * repository, and until it answers.
     */
    void git(GitStatusRead read) {
        git = read.root().isPresent() && read.folder().equals(folder)
                ? Status.parse(read.root().get(), read.text()) : null;
    }

    /** The ink of every row listed so far, from what git last said: sent when it says something new. */
    TreeView.Inks inks() {
        return inks(listed);
    }

    /** The ink of each row {@code ListFolder} just listed: sent beside the level, which keeps them for its rows. */
    TreeView.Inks inks(Listed level) {
        List<Path> paths = new ArrayList<>(level.items().size());
        for (Item item : level.items()) {
            paths.add(item.path());
        }
        return inks(paths);
    }

    private TreeView.Inks inks(java.util.Collection<Path> paths) {
        Map<Object, Role> inks = new java.util.HashMap<>();
        for (Path p : paths) {
            inks.put(p, ink(git, p, folders.contains(p)));
        }
        return new TreeView.Inks(treeAddress(), inks);
    }

    /** The ink each state of a path is drawn in. Ignored is only ever an untracked path's: git says so. */
    private static final Map<Status.State, Role> INKS = Map.of(
            Status.State.CONFLICTED, Role.DANGER,
            Status.State.STAGED_AND_MODIFIED, Role.CAUTION,
            Status.State.MODIFIED, Role.CAUTION,
            Status.State.STAGED, Role.CAUTION,
            Status.State.ADDED, Role.SUCCESS,
            Status.State.UNTRACKED, Role.SUCCESS,
            Status.State.IGNORED, Role.FAINT);

    /**
     * What {@code p}'s label is drawn in, from {@code git}: its own state, or that of an untracked or ignored folder it
     * is in; for a folder git says nothing of itself, the most pressing of what is changed inside it, a conflict, then
     * a change, then something new. Git's state wins over a {@code .gitignore}, because git reports a path as ignored
     * only when nothing else is true of it. The ink when git says nothing, or there is no repository.
     */
    static Role ink(Status git, Path p, boolean folder) {
        if (git == null) {
            return Role.INK;
        }
        Status.State own = git.at(p);
        if (own != null) {
            return INKS.getOrDefault(own, Role.CAUTION);
        }
        if (!folder) {
            return Role.INK;
        }
        Status.Inside inside = git.inside(p);
        return inside.conflicted() > 0 ? Role.DANGER
                : inside.changed() > 0 ? Role.CAUTION
                : inside.untracked() > 0 ? Role.SUCCESS
                : Role.INK;
    }

    /** A file name's extension, lower case, or empty for none (a dot file has none). */
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** An empty top level, for a tree that asks before a folder is shown. */
    TreeView.Children nothing() {
        return new TreeView.Children(treeAddress(), null, List.of());
    }

    /** The files tree's selected row, or null. */
    Path selected() {
        return tree.selected();
    }

    /** Whether the tree has a row for {@code item}: listed, and under folders that are open. */
    boolean hasRow(Path item) {
        return tree.rowNode(item) != null;
    }

    /** Whether {@code item} is a folder, as its listing said. */
    boolean isFolder(Path item) {
        return folders.contains(item);
    }

    /** Open a folder's row in place. */
    void expand(Path item) {
        tree.expand(item);
    }

    /**
     * Unfold the tree down to {@code file} and select its row; when the walk lands, {@link #revealed} rings it, since
     * a row the walk scrolled to is one the eye has not found yet. The editor may just have shown another folder, whose
     * rows come with the listings that land after this frame, so the walk is begun again as each lands
     * ({@link #listed}) and tried again when it ends short, a few times at most, as a new scratch's is. A file outside
     * the folder is left alone: there is no way down to it from here.
     */
    void reveal(Path file) {
        revealing = file;
        revealTries = REVEAL_TRIES;
        revealWalking = false;
        walkToReveal();
    }

    /** A level of the files tree was answered: look for the file being revealed, if no look is under way. */
    void listed() {
        if (revealing != null && !revealWalking) {
            walkToReveal();
        }
    }

    /** A walk ended: ring its row if it was the reveal's and it got there, and try again if it did not. */
    void revealed(Object item, boolean reached) {
        if (revealing == null || !item.equals(revealing)) {
            return;
        }
        revealWalking = false;
        if (reached) {
            Node row = tree.rowNode(revealing);
            if (row != null) {
                motion.cues.play(row, Cue.ring(gui.theme().color(Role.ACCENT), 1));
            }
            revealing = null;
        } else if (revealTries > 0) {
            walkToReveal();
        } else {
            revealing = null;
        }
    }

    private void walkToReveal() {
        List<Path> chain = rows(folder == null ? List.of() : ListFolder.chain(folder, revealing));
        if (chain.isEmpty()) {
            revealing = null;
            return;
        }
        revealTries--;
        revealWalking = true;
        tree.revealPath(chain);
    }

    /**
     * The way down to a file as the files tree's rows: each folder a compacted row stands for is that row, once. A
     * level not listed yet is left as the folder it is; the walk stops short there, and the next try, once that level
     * is listed and its folds known, goes on past it.
     */
    List<Path> rows(List<Path> chain) {
        List<Path> rows = new ArrayList<>(chain.size());
        for (Path p : chain) {
            Path row = folded.getOrDefault(p, p);
            if (rows.isEmpty() || !rows.getLast().equals(row)) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Put the keyboard in the path bar, everything in it selected so typing replaces it. The scratches and Symbols hide
     * the bar, so they go first.
     */
    void focusPath() {
        scratches(false);
        symbols(false);
        naming = Made.FILE;
        namingFrom = null;
        gui.focus(path.node());
        path.select(0, path.text().length());
    }

    /**
     * Name something new in {@code in}: the folder in the bar with the caret after it, so the person types only the
     * name. {@code folder}: what is named is a folder, so {@link #submitted} ends it with a separator.
     */
    void startNew(Path in, boolean folder) {
        startNew(in, folder ? Made.FOLDER : Made.FILE, "", null);
    }

    /**
     * Name {@code what} in {@code in}, starting from {@code suggested}, which is selected so typing replaces it and
     * Enter takes it as it is. {@code from}: what an archive holds or a link points at.
     */
    void startNew(Path in, Made what, String suggested, Path from) {
        String prefix = in.toString().endsWith(File.separator) ? in.toString() : in + File.separator;
        naming = what;
        namingFrom = from;
        path.text(prefix + suggested);
        gui.focus(path.node());
        path.select(prefix.length(), prefix.length() + suggested.length());
    }

    /** What a row's menu put the bar in mind of: {@link Made#FILE} for none, where the text says what it names. */
    Made naming() {
        return naming;
    }

    /** What an archive being named holds or a link being named points at; null for the rest. */
    Path namingFrom() {
        return namingFrom;
    }

    /** Offer "Link to this…" on rows listed from now on, or not, whatever the platform: for a test. */
    void offerLinks(boolean offer) {
        links = offer;
    }

    /**
     * What the bar's submitted text asks for: a folder when New ▸ Folder… put it there and it has no separator.
     * The bar's mind goes back to its own.
     */
    String submitted(String text) {
        String asked = text.strip();
        if (naming == Made.FOLDER && !asked.isEmpty() && !asked.endsWith("/") && !asked.endsWith("\\")) {
            asked += File.separator;
        }
        naming = Made.FILE;
        namingFrom = null;
        return asked;
    }

    /** Put the keyboard in what is shown: the scratches' tree, Symbols' field, or the files' tree. */
    void focusTree() {
        if (scratching()) {
            scratchTree.focus();
        } else if (symbolsUp()) {
            symbols(true);
        } else {
            tree.focus();
        }
    }
}
