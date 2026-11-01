package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.ListFolder;
import dev.vexelray.demo.editor.core.Messages.Item;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Listing;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Breadcrumb;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.MenuAction;
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
 * <p>The folders shown before, newest first, are the shortest way back, so they come first, one button each. Under
 * them is a tree of folders only, starting at the folder above the one shown, which is unfolded and selected among its
 * siblings. Above the tree is the way up: the start's own ancestors, each a button that starts the tree there instead.
 * Going up does not change the folder shown; it only widens what can be picked.
 *
 * <p>A click on a folder, or Enter, picks it: the view sends it to the editor as the path bar would, and showing it
 * ends Open. The arrow keys only move, so walking the folders does not change the folder shown at every step. Esc, in
 * the tree, goes back to the files: the view claims it on each tree ({@link #treeNode}), since a claim sends an
 * intent, and the intents are the view's.
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
    private final Node recentLabel;
    /** The line between Recent and the tree: there only when Recent is. */
    private final Node rule;
    private final Button[] recentButtons = new Button[RECENT];
    private final Path[] recentPaths = new Path[RECENT];
    private final Breadcrumb<Path> ancestry;
    /** Where the tree goes: replaced, tree and all, each time Open starts somewhere. */
    private final Node treeSlot;
    private TreeView<Path> tree;
    /** Where the tree starts: its one top row. */
    private Path top;

    Picker(Gui gui) {
        this.gui = gui;

        recentLabel = gui.text("Recent").font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.DIM))
                .visible(false);
        Node recent = gui.column().alignItems(AlignItems.STRETCH).children(recentLabel);
        for (int i = 0; i < RECENT; i++) {
            Button b = new Button(gui, "").kind(Button.Kind.GHOST).name("recent." + i);
            b.node().font(Type.UI).visible(false);
            recentButtons[i] = b;
            recent.append(b.node());
        }
        gui.landmark(Landmarks.RECENT, recent);

        ancestry = new Breadcrumb<Path>(gui, Picker::label).name("ancestry").font(Type.UI).maxSegments(2);
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
                .children(recent, rule,
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

    /** The folder a recent button stands for, or null when {@code button} is not one of them. */
    Path recentAt(String button) {
        for (int i = 0; i < RECENT; i++) {
            if (recentButtons[i].address().equals(button)) {
                return recentPaths[i];
            }
        }
        return null;
    }

    /** The recent folders, as the editor last said them. */
    void recent(List<Path> folders) {
        for (int i = 0; i < RECENT; i++) {
            Path p = i < folders.size() ? folders.get(i) : null;
            recentPaths[i] = p;
            recentButtons[i].label(p == null ? "" : label(p));
            recentButtons[i].node().visible(p != null);
        }
        recentLabel.visible(!folders.isEmpty());
        rule.visible(!folders.isEmpty());
    }

    /** The address of the recent button at {@code index}, which its {@code Pressed} carries: for a test. */
    String recentAddress(int index) {
        return recentButtons[index].address();
    }

    /** What the recent buttons say, top to bottom: for a test. */
    List<String> recentLabels() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < RECENT; i++) {
            if (recentPaths[i] != null) {
                out.add(label(recentPaths[i]));
            }
        }
        return out;
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
