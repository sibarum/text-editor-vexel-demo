package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.ListFolder;
import dev.vexelray.demo.editor.core.Messages.Item;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Listing;
import dev.vexelray.demo.editor.core.Scratches;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Breadcrumb;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.TreeView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The navigator's Open: the folders another one can be picked from, in place of the files. The {@link View}'s, on the
 * GUI thread, through the {@link Navigator}.
 *
 * <h2>Recent first, then the folder above</h2>
 *
 * <p>The folders shown before, newest first, are the shortest way back, so they come first, in a drop-down that is one
 * line high however many there are, and under it, once there is a scratch, the way to the scratches as a folder of
 * their own ({@link Scratches}). Under
 * them is a tree of folders only, starting at the folder above the one shown, which is unfolded and selected among its
 * siblings. Above the tree is the way up: the start's own ancestors, each a button that starts the tree there instead.
 * Going up does not change the folder shown; it only widens what can be picked.
 *
 * <p>A click on a folder, or Enter, picks it: the view sends it to the editor as the path bar would, and showing it
 * ends Open. The arrow keys only move, so walking the folders does not change the folder shown at every step. Esc, in
 * the tree, goes back to the files: the view claims it on each tree ({@link #treeNode}), since a claim sends an
 * intent, and the intents are the view's.
 *
 * <p>What a click would pick is in view before the click: the row of the tree under the pointer, or the recent folder
 * the drop-down's arrow keys have reached, is pictured where the editor stood ({@link Preview}), and off them, the
 * selected row, wherever the arrow keys have walked to.
 *
 * <h2>A tree for each start</h2>
 *
 * <p>Each time Open starts somewhere it builds its tree afresh. A tree's first listing is the one a walk down it waits
 * for, so the reveal of the folder shown lands however long the listing takes; a refreshed tree would be walked before
 * its new top was there. The old tree is closed, and its address with it, so a late answer for it finds nobody.
 */
final class Picker {

    /** How many recent folders are offered: as many as the editor keeps. */
    static final int RECENT = 8;

    private final Gui gui;
    private final Node root;
    /** The line between Recent and the tree: there only when Recent is. */
    private final Node rule;
    /** The recent folders, one line high however many there are: a click shows them all. */
    private final Select<Path> recent;
    private final Scratches scratches;
    /** The way to the scratches as a folder of their own: under the recent folders, once there is a scratch. */
    private final Button scratchesButton;
    private boolean recentShown;
    private boolean scratchesShown;
    private final Breadcrumb<Path> ancestry;
    /** Where the tree goes: replaced, tree and all, each time Open starts somewhere. */
    private final Node treeSlot;
    private TreeView<Path> tree;
    /** Where the tree starts: its one top row. */
    private Path top;

    Picker(Gui gui, Scratches scratches) {
        this.gui = gui;
        this.scratches = scratches;

        // A way to go, not a setting: it always reads Recent folders, and picking one is not an edit to undo.
        recent = new Select<Path>(gui, Picker::label).name("recent").placeholder("Recent folders").undoable(false);
        recent.node().width(Length.FILL).visible(false);
        scratchesButton = new Button(gui, Scratches.NAME).kind(Button.Kind.GHOST).name("scratches");
        scratchesButton.node().width(Length.FILL).visible(false);
        Node offered = gui.column().alignItems(AlignItems.STRETCH).gap(Type.TIGHT)
                .children(recent.node(), scratchesButton.node());
        gui.landmark(Landmarks.RECENT, recent.node());
        gui.landmark(Landmarks.OPEN_SCRATCHES, scratchesButton.node());

        ancestry = new Breadcrumb<Path>(gui, Picker::label).name("ancestry").maxSegments(2);
        // Two segments can still be wider than the navigator, when a folder's name is long: cut at its edge rather
        // than drawn over the editor.
        ancestry.node().width(Length.FILL).clip(true);
        gui.landmark(Landmarks.ANCESTRY, ancestry.node());

        rule = gui.box().height(Type.RULE).background(gui.theme().color(Look.PICKING_RIM)).visible(false);
        treeSlot = gui.column().width(Length.FILL).height(Length.grow(1f)).alignItems(AlignItems.STRETCH);

        root = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .children(offered, rule,
                        ancestry.node(), treeSlot);
    }

    Node node() {
        return root;
    }

    /** The tree's address, which its messages carry; empty before Open has been shown. */
    String treeAddress() {
        return tree == null ? "" : tree.address();
    }

    String ancestryAddress() {
        return ancestry.address();
    }

    /** The recent folders' address, which its {@code Changed} and {@code Committed} carry. */
    String recentAddress() {
        return recent.address();
    }

    /**
     * The recent folders, as the editor last said them, but the scratches, which have a button of their own and are
     * not one of the folders a person was working in.
     */
    void recent(List<Path> folders) {
        List<Path> shown = folders.stream().filter(p -> !scratches.is(p)).limit(RECENT).toList();
        recent.options(shown);
        recentShown = !shown.isEmpty();
        recent.node().visible(recentShown);
        heading();
    }

    /** The drop-down back to saying Recent folders, once one of them has been picked. */
    void recentPicked() {
        recent.show(null);
    }

    /** Whether there is a scratch to offer, as {@code FindScratches} last said. */
    void scratchesThere(boolean any) {
        scratchesShown = any;
        scratchesButton.node().visible(any);
        heading();
    }

    /** The line under what is offered above the tree, there while anything is. */
    private void heading() {
        rule.visible(recentShown || scratchesShown);
    }

    /** Whether the Scratches button is offered: for a test. */
    boolean scratchesOffered() {
        return scratchesShown;
    }

    /** The Scratches button's address, which its {@code Pressed} carries. */
    String scratchesAddress() {
        return scratchesButton.address();
    }

    /** The scratches' folder, which the Scratches button picks. */
    Path scratchesFolder() {
        return scratches.dir();
    }

    /** What the recent folders offer, top to bottom: for a test. */
    List<String> recentLabels() {
        List<String> out = new ArrayList<>();
        var order = recent.list().order();
        for (int i = 0; i < order.size(); i++) {
            out.add(label(order.at(i)));
        }
        return out;
    }

    /** The recent folders' drop-down: for a test. */
    Select<Path> recentSelect() {
        return recent;
    }

    /** Start from the folder above {@code shown}, with {@code shown} unfolded and selected among its siblings. */
    void start(Path shown) {
        startAt(above(shown), shown);
    }

    /** Start the tree at {@code from}, one of the way up's ancestors; {@code shown} is selected if it is under it. */
    void startAt(Path from, Path shown) {
        top = from.toAbsolutePath().normalize();
        List<Path> way = new ArrayList<>();
        for (Path p = top; p != null; p = p.getParent()) {
            way.addFirst(p);
        }
        ancestry.path(way);

        if (tree != null) {
            tree.close();
        }
        tree = new TreeView<Path>(gui, "picker")
                .hoverStyle(Look.PICK_HOVER, Role.ACCENT)
                .rowCursor(CursorShape.POINTER)
                .action(MenuAction.of(Navigator.SHOW, "»", "Show this folder"))
                .action(MenuAction.of(Navigator.COPY_PATH, "Copy path"));
        tree.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.PICKER, tree.node());
        treeSlot.arrange(tree.node());

        List<Path> chain = new ArrayList<>();
        chain.add(top);
        if (shown != null) {
            chain.addAll(ListFolder.chain(top, shown));
        }
        tree.revealPath(chain);
    }

    /** What to ask {@code ListFolder} for a level the tree wants; null for its top, which is {@link #top()}. */
    Listing wanted(Object parent) {
        return parent instanceof Path dir ? new Listing(treeAddress(), dir, false, true) : null;
    }

    /** The tree's top level: the one folder it starts at. */
    TreeView.Children top() {
        List<TreeView.Entry> entries = top == null ? List.of() : List.of(TreeView.Entry.branch(top, label(top)));
        return new TreeView.Children(treeAddress(), null, entries);
    }

    /** {@code ListFolder}'s answer, as the tree's entries: folders only, every one of which opens. */
    TreeView.Children children(Listed listed) {
        List<TreeView.Entry> entries = new ArrayList<>(listed.items().size());
        for (Item item : listed.items()) {
            entries.add(TreeView.Entry.branch(item.path(), item.name()));
        }
        return new TreeView.Children(treeAddress(), listed.dir(), entries);
    }

    /** Where the tree starts, or null before Open has been shown. */
    Path topFolder() {
        return top;
    }

    /** The tree's node, which is new each time Open starts somewhere. */
    Node treeNode() {
        return tree == null ? null : tree.node();
    }

    /** The row selected in the tree, or null. */
    Path selected() {
        return tree == null ? null : tree.selected();
    }

    /** Whether the tree has a row for {@code folder}. */
    boolean hasRow(Path folder) {
        return tree != null && tree.rowNode(folder) != null;
    }

    void focus() {
        if (tree != null) {
            tree.focus();
        }
    }

    /**
     * Where Open starts for {@code shown}: the folder above it, so its siblings are on show beside it, or the folder
     * itself at the top of a drive. With nothing shown, the home folder.
     */
    static Path above(Path shown) {
        if (shown == null) {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        Path p = shown.toAbsolutePath().normalize();
        return p.getParent() != null ? p.getParent() : p;
    }

    /** A folder's name, or the whole of it for the top of a drive, which has none. */
    static String label(Path p) {
        Path name = p.getFileName();
        return name != null ? name.toString() : p.toString();
    }
}
