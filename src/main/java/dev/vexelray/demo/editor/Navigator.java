package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.widget.Breadcrumb;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.gui.widget.TreeView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * The file navigator, in one of two modes: <b>Edit</b>, the files under the root, and <b>Open</b>, the folders a
 * new root can be picked from. Clicking the root's name, the card's heading, switches between them.
 *
 * <p>In Edit, selecting a file opens it — a click or an arrow key onto its row — which is what the previous editor
 * did and what makes walking a folder with the keyboard a way of reading it. Enter on a folder opens it in place.
 * A folder's own menu can make it the navigator's root.
 *
 * <p>Open is the same card showing folders only: the roots used recently, then the folder above the root with the
 * root selected among its siblings. A click on a folder, or Enter, makes it the root and goes back to Edit. The
 * arrow keys only move, so walking the folders does not change the root at every step.
 *
 * <p>There is one tree per mode for the life of the window, and changing folder re-points a source and refreshes
 * its tree. The refresh lists the new folder, which is I/O, so it runs on the offload lane rather than wherever the
 * request came from.
 */
final class Navigator {

    /** What the card is showing. */
    enum Mode { EDIT, OPEN }

    /** How many recent roots Open lists. One fewer than {@link Doc#RECENT}, which may hold the current root. */
    private static final int RECENT_SHOWN = Doc.RECENT - 1;

    private final Gui gui;
    private final Motion motion;
    private final Executor io;
    private final FolderSource source = new FolderSource(null);
    private final RootSource roots = new RootSource();
    private final TreeView<Path> tree;
    private final TreeView<Path> picker;
    private final Button heading;
    private final Tooltip hint;
    private final Node label;
    private final Node empty;
    private final Node editPane;
    private final Node openPane;
    private final Node recentLabel;
    private final Button[] recentButtons = new Button[RECENT_SHOWN];
    private final AtomicReferenceArray<Path> recentPaths = new AtomicReferenceArray<>(RECENT_SHOWN);
    private final Breadcrumb<Path> ancestry;
    private final Node root;

    private volatile Mode mode = Mode.EDIT;
    private volatile Consumer<Path> openFile = p -> { };
    private volatile Consumer<Path> openFolder = p -> { };

    Navigator(Gui gui, Motion motion) {
        this.gui = gui;
        this.motion = motion;
        this.io = gui.offload();

        // A line saying what the card is for over the root's name, as the design has it. The design sets the name
        // bold, which is not something a node can ask for (framework-notes FN-15).
        label = gui.text("Editing")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.FAINT))
                .padding(Length.ZERO, Type.TIGHT);
        // The root's name is what switches between Edit and Open: the thing to change is the thing to press. A
        // button rather than a clickable text, so Tab, Enter and Space reach it too.
        heading = new Button(gui, "No folder").kind(Button.Kind.GHOST)
                .onPress(() -> mode(mode == Mode.EDIT ? Mode.OPEN : Mode.EDIT));
        heading.node().font(Type.UI).wordWrap(false);
        gui.landmark(Landmarks.FOLDER, heading.node());
        hint = new Tooltip(gui).attach(heading.node(),
                () -> mode == Mode.EDIT ? "Pick another root" : "Back to the files");

        empty = gui.text("Click above to pick a folder, or press Ctrl+Shift+O")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.FAINT));

        tree = new TreeView<>(gui, source).motion(motion.arrival);
        // The tree sits in the card rather than in a well of its own. Its border is the tree's to repaint on every
        // change of focus, so that stays (framework-notes FN-14).
        tree.node().width(Length.FILL).height(Length.grow(1)).visible(false)
                .background(gui.theme().color(Role.NONE));
        gui.landmark(Landmarks.TREE, tree.node());
        tree.onSelect(p -> {
            if (Files.isRegularFile(p)) {
                openFile.accept(p);
            }
        });
        tree.onActivate(p -> {
            if (Files.isDirectory(p)) {
                tree.expand(p);
            }
        });
        tree.action(TreeView.Action.<Path>of("›", "Open", (p, job) -> openFile.accept(p))
                .enabledWhen(Files::isRegularFile));
        tree.action(TreeView.Action.<Path>of("»", "Make this the root", (p, job) -> pick(p))
                .shownWhen(Files::isDirectory));
        tree.onContextMenu((p, menu) -> {
            boolean vexplore = Suite.hasVexplore();
            menu.separator()
                    .item(Suite.vexploreLabel(vexplore), vexplore, () -> Suite.showInVexplore(p, gui.offload()))
                    .item("Copy path", () -> gui.clipboard().set(p.toString()));
        });
        editPane = gui.column()
                .width(Length.FILL).height(Length.grow(1))
                .alignItems(AlignItems.STRETCH)
                .children(empty, tree.node());

        // Open: the recent roots first, as the shortest way back, then the folder above the root.
        recentLabel = gui.text("Recent")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.FAINT))
                .padding(Length.ZERO, Type.TIGHT)
                .visible(false);
        Node recent = gui.column().alignItems(AlignItems.STRETCH).children(recentLabel);
        for (int i = 0; i < RECENT_SHOWN; i++) {
            int slot = i;
            Button b = new Button(gui, "").kind(Button.Kind.GHOST).onPress(() -> {
                Path p = recentPaths.get(slot);
                if (p != null) {
                    pick(p);
                }
            });
            b.node().visible(false);
            recentButtons[i] = b;
            recent.append(b.node());
        }
        gui.landmark(Landmarks.RECENT, recent);

        // The way up: the top folder's ancestors, each a button that lists the folders from there. Moving up does
        // not change the root, it only widens what can be picked; the root stays unfolded and selected below.
        ancestry = new Breadcrumb<Path>(gui, Navigator::crumbLabel).font(Type.UI).maxSegments(2)
                .onNavigate(this::widen);
        ancestry.node().padding(Length.ZERO, Type.TIGHT);
        gui.landmark(Landmarks.ANCESTRY, ancestry.node());
        // No arrival ramp here. Open unfolds the top and scrolls to the root in one go, and with the folders above
        // the root growing in from nothing, the scroll is measured against rows a frame old and lands short.
        picker = new TreeView<>(gui, roots);
        picker.node().width(Length.FILL).height(Length.grow(1))
                .background(gui.theme().color(Role.NONE));
        gui.landmark(Landmarks.PICKER, picker.node());
        picker.onClick(this::pick);
        picker.onActivate(this::pick);
        picker.action(TreeView.Action.<Path>of("»", "Make this the root", (p, job) -> pick(p)));
        picker.onContextMenu((p, menu) -> menu.separator()
                .item("Copy path", () -> gui.clipboard().set(p.toString())));
        openPane = gui.column()
                .width(Length.FILL).height(Length.grow(1))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .visible(false)
                .children(recent, gui.box().height(Type.RULE).background(gui.theme().color(Look.RIM)),
                        ancestry.node(), picker.node());

        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .gap(Type.TIGHT)
                // Narrow at the sides: the gap beside the card is the divider's now, and the tree keeps its own inset.
                .padding(Type.WIDE, Length.dp(3))
                .background(gui.theme().color(Look.CARD))
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Look.RIM))
                .alignItems(AlignItems.STRETCH)
                .children(label, heading.node(), editPane, openPane);
    }

    Node node() {
        return root;
    }

    void onOpenFile(Consumer<Path> handler) {
        this.openFile = handler;
    }

    void onOpenFolder(Consumer<Path> handler) {
        this.openFolder = handler;
    }

    /** The folder shown, or null for none. */
    Path folder() {
        return source.base();
    }

    Mode mode() {
        return mode;
    }

    /** Open's tree of folders. Package-private, so a test can read where it starts and what is selected. */
    TreeView<Path> picker() {
        return picker;
    }

    /** The folder Open lists from, or null before Open has been shown. */
    Path openTop() {
        return roots.top();
    }

    /**
     * Show the files under the root, or the folders a root can be picked from. Going to Open lists the folder
     * above the root, which is I/O, so that part runs on the offload lane.
     */
    void mode(Mode value) {
        mode = value;
        boolean open = value == Mode.OPEN;
        label.text(open ? "Pick a folder to make it the root" : "Editing");
        // Open is a different place, and looks it: the card steeps in the accent and wears it as its edge, the line
        // above the name says so in it, and a sweep runs down the card as it changes, so the switch is seen even
        // by an eye that was on the document.
        Theme theme = gui.theme();
        root.background(theme.color(open ? Look.PICKING : Look.CARD))
                .border(Type.RULE, theme.color(open ? Look.PICKING_RIM : Look.RIM));
        label.textColor(theme.color(open ? Role.ACCENT : Role.FAINT));
        if (open) {
            motion.cues.play(root, Cue.scanline(theme.color(Role.ACCENT)));
        }
        editPane.visible(!open);
        openPane.visible(open);
        if (open) {
            io.execute(() -> listRoots(RootSource.above(source.base())));
        }
    }

    /**
     * Point the picker at {@code folder}, show its ancestry above it, and unfold the way down to the root and select
     * it, when the root is under it. Re-listed every time: Open is where a folder made since the last look is
     * expected to be.
     */
    private void listRoots(Path folder) {
        Path current = source.base();
        roots.top(folder);
        picker.refresh();
        Path shown = roots.top();
        ancestry.path(ancestors(shown));
        if (current != null && current.startsWith(shown) && !current.equals(shown)) {
            // Every folder between is a row, since nothing is merged here, so the chain is just the walk down.
            List<Path> chain = new ArrayList<>();
            chain.add(shown);
            for (Path part : shown.relativize(current)) {
                chain.add(chain.getLast().resolve(part));
            }
            // Scrolled to here as well: when the root is already the selected row, as it is on every Open after the
            // first, selecting it again is a no-op and does not scroll, and a pane that was hidden starts at the top.
            picker.revealPath(chain, () -> {
                Node row = picker.rowNode(current);
                if (row != null) {
                    row.scrollIntoView();
                }
            });
        } else {
            picker.expand(roots.top());
        }
    }

    /** List Open's folders from {@code folder}, an ancestor of the top: the breadcrumb's step up. The root stays. */
    void widen(Path folder) {
        io.execute(() -> listRoots(folder));
    }

    /** {@code folder} and every folder above it, the top of the drive first: the breadcrumb's chain. */
    static List<Path> ancestors(Path folder) {
        List<Path> chain = new ArrayList<>();
        for (Path p = folder; p != null; p = p.getParent()) {
            chain.addFirst(p);
        }
        return chain;
    }

    /** A folder's name, or the drive's own name ({@code C:}) for the top of one. */
    private static String crumbLabel(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString().replaceAll("[\\\\/]+$", "") : n.toString();
    }

    /** Make {@code folder} the root, and go back to its files. Picking the root it already is just goes back. */
    void pick(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        if (!folder.toAbsolutePath().normalize().equals(source.base())) {
            openFolder.accept(folder);
        }
        mode(Mode.EDIT);
    }

    /**
     * The roots Open lists as recent, newest first. {@code current} is left out, since it is not somewhere to go
     * back to; as many as there is room for are shown.
     */
    void recent(List<Path> recent, Path current) {
        List<Path> shown = recent.stream().filter(p -> !Objects.equals(p, current)).limit(RECENT_SHOWN).toList();
        for (int i = 0; i < RECENT_SHOWN; i++) {
            Path p = i < shown.size() ? shown.get(i) : null;
            recentPaths.set(i, p);
            if (p != null) {
                recentButtons[i].label(recentLabel(p));
            }
            recentButtons[i].node().visible(p != null);
        }
        recentLabel.visible(!shown.isEmpty());
    }

    /** A recent root's name, and the name of the folder it is in, since two projects can both have a {@code docs}. */
    private static String recentLabel(Path p) {
        Path name = p.getFileName();
        Path parent = p.getParent();
        Path parentName = parent == null ? null : parent.getFileName();
        String own = String.valueOf(name == null ? p : name);
        return parentName == null ? own : own + "   in " + parentName;
    }

    /**
     * Show {@code folder}, or nothing for null. {@code then} runs once the new root is listed, on the offload lane.
     */
    void show(Path folder, Runnable then) {
        io.execute(() -> {
            source.base(folder);
            tree.refresh();
            Path shown = source.base();
            Path name = shown == null ? null : shown.getFileName();
            heading.label(shown == null ? "No folder" : String.valueOf(name == null ? shown : name));
            empty.visible(shown == null);
            tree.node().visible(shown != null);
            if (then != null) {
                then.run();
            }
        });
    }

    /**
     * Unfold the tree down to {@code file} and select its row. A file outside the folder is left alone: there is
     * no way down to it from here, and moving the root under the user is not what a reveal asks for.
     */
    boolean reveal(Path file) {
        if (!source.holds(file)) {
            return false;
        }
        if (mode == Mode.OPEN) {
            mode(Mode.EDIT);
        }
        // The way down depends on which folders are merged into one row, which is a listing per level: offloaded.
        io.execute(() -> {
            var chain = source.chainTo(file);
            if (chain.isEmpty()) {
                return;   // the folder changed under the request
            }
            // Ringed once it is there, since a row the walk scrolled to is a row the eye has not found yet.
            Path last = chain.getLast();
            tree.revealPath(chain, () -> {
                Node row = tree.rowNode(last);
                if (row != null) {
                    motion.cues.play(row, Cue.ring(gui.theme().color(Role.ACCENT), 1));
                }
            });
        });
        return true;
    }

    /** Put the keyboard in whichever tree is showing. */
    void focus() {
        if (mode == Mode.OPEN) {
            picker.focus();
        } else {
            tree.focus();
        }
    }
}
