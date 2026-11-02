package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.CreateFile;
import dev.vexelray.demo.editor.core.ListFolder;
import dev.vexelray.demo.editor.core.Messages.Item;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Listing;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Scratches;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * <p>So it is where new things are named, too. A row's "New file here…" puts the folder into the bar with the caret
 * after it, and Enter makes the name typed there, from the template its extension asks for. A name ending in a
 * separator is a folder, and "New folder here…" adds that separator itself, so the person types only the name.
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
 * it and still the thing being worked in. Above the tree is New scratch, and each row's menu has New scratch here.
 * A scratch made while it is up is listed and selected once its listing lands ({@link #scratchMade}).
 */
final class Navigator {

    /** Where the card is: the folder's files, Open in their place, or the scratches over them. */
    enum Place { FILES, OPEN, SCRATCHES }

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

    /** How many times a new scratch's row is looked for before it is left unselected. */
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
    private final Node scratchPane;
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
    /** The folder shown, or null for none yet. */
    private Path folder;
    /** The file a reveal is walking to, to ring when the walk lands. */
    private Path revealing;
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
                .action(MenuAction.of(SHOW, "»", "Show this folder"))
                .action(MenuAction.of(COPY_PATH, "Copy path"))
                .action(MenuAction.of(NEW_FILE, "+", "New file here…"))
                .action(MenuAction.of(NEW_FOLDER, "New folder here…"))
                .action(MenuAction.of(NEW_PROJECT, "New project here…"))
                .action(MenuAction.of(NEW_MODULE, "New module here…"))
                .action(MenuAction.of(ARCHIVE, "Archive this folder…"))
                .action(MenuAction.of(LINK, "Link to this…"));
        tree.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.TREE, tree.node());

        // A chip, outlined and so plainly pressable, saying the thing that Open changes: the thing to change is the
        // thing to press. A button rather than a clickable text, so Tab, Enter and Space reach it too.
        chip = new Button(gui, NO_FOLDER).name("folder").kind(Button.Kind.SECONDARY).toggle(true);
        chip.node().font(Type.UI).width(Length.FILL);
        gui.landmark(Landmarks.FOLDER, chip.node());

        picker = new Picker(gui, scratches);
        picker.node().visible(false);

        scratchTree = new TreeView<Path>(gui, "scratches").motion(motion.arrival)
                .action(MenuAction.of(NEW_SCRATCH, "+", "New scratch here"))
                .action(MenuAction.of(COPY_PATH, "Copy path"));
        scratchTree.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.SCRATCHES, scratchTree.node());
        newScratch = new Button(gui, "New scratch").kind(Button.Kind.GHOST).name("new-scratch");
        newScratch.node().font(Type.UI).width(Length.FILL);
        gui.landmark(Landmarks.NEW_SCRATCH, newScratch.node());
        scratchPane = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .children(newScratch.node(), scratchTree.node());
        scratchPane.visible(false);

        // The edge is there in every place, in the panel's own colour while the files are shown, so it costs no
        // layout when it turns to the accent.
        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .gap(Type.TIGHT)
                .padding(Type.TIGHT, Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .children(chip.node(), path.node(), tree.node(), picker.node(), scratchPane);
        paint();
    }

    /** What the chip says while no folder is shown. */
    static final String NO_FOLDER = "No folder";

    /** The card's colours for where it is: the panel, steeped in the accent for Open, edged in it for the scratches. */
    private void paint() {
        root.background(gui.theme().color(place == Place.OPEN ? Look.PICKING : Role.PANEL))
                .border(Type.RULE, gui.theme().color(place == Place.FILES ? Role.PANEL : Look.PICKING_RIM));
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
        chip.show(to != Place.FILES);
        chip.label(chipLabel());
        path.node().visible(to != Place.SCRATCHES);
        tree.node().visible(to == Place.FILES);
        picker.node().visible(to == Place.OPEN);
        scratchPane.visible(to == Place.SCRATCHES);
        if (to != Place.SCRATCHES) {
            scratchWanted = null;
        }
        paint();
        if (to != Place.FILES) {
            motion.cues.play(root, Cue.scanline(gui.theme().color(Role.ACCENT)));
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
            return new Listing(treeAddress(), dir, false, false);
        }
        return folder == null ? null : new Listing(treeAddress(), folder, true, false);
    }

    /** {@code ListFolder}'s answer, as the tree's entries for the level it asked about. */
    TreeView.Children children(Listed listed) {
        List<TreeView.Entry> entries = new ArrayList<>(listed.items().size());
        for (Item item : listed.items()) {
            TreeView.Entry entry;
            if (item.folder()) {
                folders.add(item.path());
                // A module where a project is around, a project where none is: one or the other, never both.
                entry = TreeView.Entry.branch(item.path(), item.name())
                        .hides(OPEN, item.inProject() ? NEW_PROJECT : NEW_MODULE);
            } else {
                entry = TreeView.Entry.leaf(item.path(), item.name()).hides(SHOW, NEW_PROJECT, NEW_MODULE, ARCHIVE);
            }
            // Where a link takes privilege to make, it is not offered: a line that fails for most people teaches nothing.
            entries.add(links ? entry : entry.hides(LINK));
        }
        return new TreeView.Children(treeAddress(), listed.roots() ? null : listed.dir(), entries);
    }

    /** An empty top level, for a tree that asks before a folder is shown. */
    TreeView.Children nothing() {
        return new TreeView.Children(treeAddress(), null, List.of());
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
     * a row the walk scrolled to is one the eye has not found yet. A file outside the folder is left alone: there is
     * no way down to it from here.
     */
    boolean reveal(Path file) {
        List<Path> chain = ListFolder.chain(folder, file);
        if (chain.isEmpty()) {
            return false;
        }
        revealing = chain.getLast();
        tree.revealPath(chain);
        return true;
    }

    /** A walk ended: ring its row if it was the reveal's and it got there. */
    void revealed(Object item, boolean reached) {
        if (reached && item.equals(revealing)) {
            Node row = tree.rowNode(revealing);
            if (row != null) {
                motion.cues.play(row, Cue.ring(gui.theme().color(Role.ACCENT), 1));
            }
            revealing = null;
        }
    }

    /**
     * Put the keyboard in the path bar, everything in it selected so typing replaces it. The scratches hide the bar,
     * so they go first.
     */
    void focusPath() {
        scratches(false);
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
     * What the bar's submitted text asks for: a folder when "New folder here…" put it there and it has no separator.
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

    /** Put the keyboard in the tree shown: the scratches' while they are up, the files' otherwise. */
    void focusTree() {
        if (scratching()) {
            scratchTree.focus();
        } else {
            tree.focus();
        }
    }
}
