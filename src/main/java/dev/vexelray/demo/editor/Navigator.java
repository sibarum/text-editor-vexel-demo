package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.TreeView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The file navigator: a heading naming the folder, and a tree of what is in it.
 *
 * <p>Selecting a file opens it — a click or an arrow key onto its row — which is what the previous editor did
 * and what makes walking a folder with the keyboard a way of reading it. Enter on a folder opens it in place.
 * A folder's own menu can make it the navigator's root.
 *
 * <p>There is one tree for the life of the window, and changing folder re-points its source and refreshes it.
 * The refresh lists the new root, which is I/O, so it runs on the offload lane rather than wherever the request
 * came from.
 */
final class Navigator {

    private final Gui gui;
    private final Motion motion;
    private final Executor io;
    private final FolderSource source = new FolderSource(null);
    private final TreeView<Path> tree;
    private final Node heading;
    private final Node empty;
    private final Node root;

    private volatile Consumer<Path> openFile = p -> { };
    private volatile Consumer<Path> openFolder = p -> { };

    Navigator(Gui gui, Motion motion) {
        this.gui = gui;
        this.motion = motion;
        this.io = gui.offload();

        heading = gui.text("NO FOLDER")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.DIM));
        gui.landmark(Landmarks.FOLDER, heading);

        empty = gui.text("Open a folder with Ctrl+Shift+O")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.FAINT));

        tree = new TreeView<>(gui, source).motion(motion.arrival);
        tree.node().width(Length.FILL).height(Length.grow(1)).visible(false);
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
        tree.action(TreeView.Action.<Path>of("»", "Make this the root", (p, job) -> openFolder.accept(p))
                .shownWhen(Files::isDirectory));
        tree.onContextMenu((p, menu) -> menu
                .separator()
                .item("Copy path", () -> gui.clipboard().set(p.toString())));

        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .gap(Type.TIGHT)
                .padding(Type.TIGHT, Type.TIGHT)
                .background(gui.theme().color(Role.PANEL))
                .alignItems(AlignItems.STRETCH)
                .children(heading, empty, tree.node());
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

    /**
     * Show {@code folder}, or nothing for null. {@code then} runs once the new root is listed, on the offload lane.
     */
    void show(Path folder, Runnable then) {
        io.execute(() -> {
            source.base(folder);
            tree.refresh();
            Path shown = source.base();
            Path name = shown == null ? null : shown.getFileName();
            heading.text(shown == null ? "NO FOLDER" : String.valueOf(name == null ? shown : name).toUpperCase());
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
        var chain = source.chainTo(file);
        if (chain.isEmpty()) {
            return false;
        }
        // Ringed once it is there, since a row the walk scrolled to is a row the eye has not found yet.
        Path last = chain.getLast();
        tree.revealPath(chain, () -> {
            Node row = tree.rowNode(last);
            if (row != null) {
                motion.cues.play(row, Cue.ring(gui.theme().color(Role.ACCENT), 1));
            }
        });
        return true;
    }

    void focus() {
        tree.focus();
    }
}
