package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Ramp;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.os.Decorations;
import sibarum.kronometer.anim.Ease;

import java.nio.file.Path;

/**
 * The folder explorer as its own OS window on the shared frame loop: a second {@link Gui} holding a
 * {@link TreeView}, opened as the named window {@code "folder"} so Ctrl+Shift+O always means this one. The
 * framework attaches and pumps its input from the factory the app supplied, so both windows take focus and
 * input from the OS like one application, while every model change (opening a file into a tab) crosses to the
 * main window through {@link FileActions}' request queue on the one shared thread.
 *
 * <p>All methods run on the main thread: {@code show}/{@code setFolder} from the frame loop, the two lifecycle
 * callbacks from the frame loop.
 */
final class FolderWindow {
    /**
     * The name this window is opened, raised and remembered under, and the settings key its placement and its
     * shown folder are stored beside. Named here rather than spelled out at each use: it is read from three
     * classes, and a literal that has to agree across files is a rename waiting to orphan somebody's window.
     */
    static final String KEY = "folder";

    /** The default size, used the first time — after that, whatever the user left it at. */
    static final int DEFAULT_W = 340;
    static final int DEFAULT_H = 560 + TextEditorApp.BAR_H;
    /** This window's margin, and so its resize grip — see {@link TextEditorApp#GUTTER}. Tighter: it is narrow. */
    private static final Length GUTTER = Length.dp(12);

    private final java.util.function.Consumer<Path> openFile;
    /** Where a directory row's "Open folder" sends it: the drawer re-roots there, as Ctrl+Shift+O does. */
    private final java.util.function.Consumer<Path> showFolder;
    private final WindowMemory memory;
    private final Gui gui = new Gui();
    private final Node column;
    private final TitleBar titleBar;
    /** How an expanding folder is timed, installed on every tree this window builds; null for the flip. */
    private final Ramp motion;
    /**
     * One-shot marks on this window's rows — see {@link #reveal}. Its own, not the editor's: a {@code Cues}
     * holds what is playing by node id, and the two windows have separate node spaces.
     */
    private final Cues cues;
    private TreeView<Path> tree;
    /**
     * The folder {@link #tree} is rooted at, absolute and normalized, or null before there is one. Kept in
     * that one form because the only thing it is for is being compared against — see {@link #reveal}, which
     * would otherwise rebuild a tree that is already showing the right folder under a different spelling.
     */
    private Path shown;
    /** The framework's handle on this window, claimed the first time it is shown. */
    private AppWindow handle;

    /** This window's Gui, so the app can bind its shortcuts here as well as on the main window. */
    Gui gui() {
        return gui;
    }

    /** Whether the window is up right now — polled each frame so it can be reopened next launch. */
    boolean isOpen() {
        return handle != null && handle.open();
    }

    FolderWindow(java.util.function.Consumer<Path> openFile, java.util.function.Consumer<Path> showFolder,
                 WindowMemory memory, KronoGui krono) {
        this.openFile = openFile;
        this.showFolder = showFolder;
        this.memory = memory;
        // Expanding a folder slides the rows below it down instead of teleporting them: the subtree's own
        // height grows, so the rows below are displaced by making room rather than by a transform, and the
        // tree's extent and its scrollbar go on describing the tree that is actually on screen.
        //
        // TRANSITION, the same as the tab change -- one application, one tempo -- but OUT_CUBIC where that
        // ramp is LINEAR, and the difference is not a preference: there the ramp drives opacity, which has
        // nowhere to arrive at, and here it drives a distance being covered.
        //
        // One clock, two windows, which is sound on two counts. Nothing but a DoubleConsumer and a Runnable
        // crosses this seam -- no node and no Gui -- so the clock never learns which window it is timing;
        // and both windows are presented by the one loop on the one thread, the tick running in the main
        // window's beforeFrame hook ahead of either window's frame.
        //
        // Null under --capture-folder, which has no loop to tick it: the tree flips instantly, which is also
        // what a reduced-motion path would be.
        this.motion = krono == null ? null
                : (progress, done) -> krono.ramp(TextEditorApp.TRANSITION, Ease.OUT_CUBIC, progress, done);
        this.cues = krono == null ? Cues.none()
                : new Cues((progress, done) -> krono.ramp(TextEditorApp.CUE, Ease.LINEAR, progress, done));
        // Before the first node: the drawer is the editor's own look swung round to the warm side of
        // neutral, so a glance at the taskbar tells the two windows apart before any text is read.
        gui.theme(Palettes.FILES);
        this.column = gui.column().width(Length.FILL).height(Length.grow(1))
                .padding(GUTTER).gap(Length.dp(6));
        gui.resizeBorder(GUTTER);
        // The popup draws its own frame too, so all three windows match. Its bar is bound in onCreated: a
        // popup's window does not exist until the main thread services the request, and it is that window the
        // buttons command — not the main one app.controls() would hand over.
        //
        // It also carries the folder's name, which is why there is no label row: a window that draws its own
        // caption has somewhere to say what it is showing, and saying it twice is just a row of lost height.
        this.titleBar = new TitleBar(gui, WindowControls.NONE, "Files");
        gui.root().background(gui.theme().color(Role.PAGE)).children(titleBar.node(), column);
        // Zoom is per-window: each Gui zooms itself, so the tree scales independently of the editor.
        TextEditorApp.zoomShortcuts(gui);
    }

    /**
     * Show {@code folder}, opening the window on the next frame if it is not already up. If it is, it comes
     * forward showing the new folder rather than staying behind the editor looking unresponsive.
     */
    void show(GuiApp app, Path folder) {
        setFolder(folder);
        raise(app, folder);
    }

    /**
     * Show {@code file} in the drawer — <b>Reveal in Navigator</b> on a tab: the folder holding it, with the
     * file's own row selected.
     *
     * <p>Re-rooted only when the drawer is somewhere else. A tree already showing this folder keeps whatever
     * the user has opened in it: rebuilding one to land on a row that is already there would shut every
     * other row on the way, which is the opposite of what asking to be shown where something is means.
     *
     * <p>Selecting the row is a selection like any other, so this drawer's own rule runs on it and the file
     * is brought forward in a tab. That is wanted rather than tolerated — a reveal is asked for <em>about</em>
     * a document, and the document arriving in front is what the two windows agreeing looks like.
     */
    void reveal(GuiApp app, Path file) {
        // In the tree's own spelling, not the caller's: its items come out of Files.list, so a row exists
        // for the absolute normalized path and for nothing else that names the same file.
        Path target = file.toAbsolutePath().normalize();
        Path dir = target.getParent();
        if (dir == null) {
            return;
        }
        if (tree != null && dir.equals(shown)) {
            raise(app, dir);
        } else {
            show(app, dir);
        }
        tree.select(target);
        mark(target);
    }

    /**
     * Pulse the row for {@code item}, so a reveal can be seen to have happened.
     *
     * <p><b>The case this exists for is the one where nothing else changes.</b> Revealing a file in a folder
     * the drawer is already showing is deliberately not a rebuild — see {@link #reveal} — so with the window
     * already forward and the row already selected, a correct reveal and one that silently failed look
     * alike. On the row rather than the tree, because which file was revealed is the whole answer.
     *
     * <p>One pulse of the accent, which is the tab bar's mark for the same event. The accent survives
     * {@link Palettes#FILES}' hue shift untouched, so it is the same colour in both windows rather than
     * merely the same idea.
     *
     * <p><b>A row that is not there is not an error.</b> A tree materialises rows as folders open, so an
     * item under something collapsed has none; nor has a file the source no longer offers. Both are simply
     * not marked.
     *
     * <p>A freshly-opened window is the weak case, knowingly: the tree was built this frame and not yet laid
     * out, so the cue paints nothing until it has been. {@code Cues} reads the box every sample rather than
     * capturing it, so the mark appears as soon as there is somewhere to put it, merely shortened.
     */
    private void mark(Path item) {
        Node row = tree.rowNode(item);
        if (row != null) {
            cues.play(row, Cue.ring(gui.theme().color(Role.ACCENT), 1));
        }
    }

    /** Bring the window up — creating it the first time — and remember what it is showing. */
    private void raise(GuiApp app, Path folder) {
        // Remembered so the next launch can point the tree at the same place, not just at the same rectangle.
        memory.shownPath(KEY, folder);
        // One call for both cases: show() creates the window if it is closed and raises it if it is not.
        if (handle == null) {
            handle = app.window(KEY, () -> WindowSpec
                    .of(memory.config(KEY, "Files", DEFAULT_W, DEFAULT_H)
                            .decorations(Decorations.CLIENT), gui)
                    .onCreated(this::onCreated)
                    .onClosed(this::onClosed));
        }
        handle.show();
    }

    void setFolder(Path folder) {
        if (tree != null) {
            tree.node().remove();
            tree.close();
        }
        Path name = folder.getFileName();
        titleBar.title(name != null ? name.toString() : folder.toString());
        shown = folder.toAbsolutePath().normalize();
        tree = new TreeView<>(gui, new FolderSource(folder));
        // Per tree, not once per window: pointing the drawer at another folder builds a new one, and a tree
        // that was never handed the ramp is a tree that flips.
        tree.motion(motion);
        tree.node().width(Length.FILL).height(Length.grow(1));
        // Selection opens files (click or keyboard walk); Enter additionally expands a selected directory.
        tree.onSelect(p -> {
            if (java.nio.file.Files.isRegularFile(p)) {
                openFile.accept(p);
            }
        });
        TreeView<Path> t = tree;
        tree.onActivate(p -> {
            if (java.nio.file.Files.isDirectory(p)) {
                t.expand(p);
            }
        });
        // The row menu. Expand and Collapse arrive on it already — recursive, marked with the same +/− the
        // row's own disclosure control uses, greyed on a row with nothing to open or nothing to shut — so
        // what is added here is only what this drawer knows that the tree cannot: what a row *is* to this
        // application. A file is something to open in a tab; a directory is somewhere to point the drawer.
        //
        // Both bodies enqueue rather than act, because both are the GUI thread's work and an action runs on
        // the tree's handler executor. They go through the one queue everything else reaches the editor by,
        // which is also what makes "Open folder" safe: by the time it is serviced, replacing the very tree
        // whose menu ran it is an ordinary tab-structure change like any other.
        tree.action(TreeView.Action.<Path>of("›", "Open", (p, job) -> openFile.accept(p))
                .enabledWhen(java.nio.file.Files::isRegularFile));
        tree.action(TreeView.Action.<Path>of("»", "Open folder", (p, job) -> showFolder.accept(p))
                .shownWhen(java.nio.file.Files::isDirectory));
        // The free-form door, for a line that is not a command on the item in that same sense: it is about
        // the path as text, it applies to every row alike, and it has nothing long enough to need a job. The
        // separator is unconditional — a rule that would open the menu or double another is dropped.
        tree.onContextMenu((p, menu) -> menu
                .separator()
                .item("•", "Copy path", () -> gui.clipboard().set(p.toString())));
        column.append(tree.node());
        tree.focus();
    }

    /**
     * The window exists, and its input is already attached and pumping — the framework did that from the
     * factory the app supplied. What is left is the two things only this window knows: which window its own
     * title bar commands, and where it should be.
     */
    private void onCreated(dev.vexelray.os.NativeWindow window) {
        // No Gui handed over, so this window's zoom is not remembered where the editor's is. That is how it
        // has always behaved rather than a decision made here, and it is now at least visible as one.
        WindowChrome.created(memory, KEY, titleBar, window, DEFAULT_W, DEFAULT_H, null);
    }

    private void onClosed() {
        WindowChrome.closed(memory, KEY, titleBar);
    }
}
