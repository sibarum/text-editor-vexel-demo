package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Make;
import dev.vexelray.demo.editor.core.Messages.GitCommitted;
import dev.vexelray.demo.editor.core.Messages.GitPushed;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitTree;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.Template;
import dev.vexelray.demo.editor.core.Scratches;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Breadcrumb;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
import sibarum.tactroller.api.Key;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
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
 * arrow keys only move, so walking the folders does not change the root at every step. Whichever folder the pointer
 * is on, or the keys have reached, is handed to {@link #onPreview}, which pictures it where the editor stands.
 *
 * <p>Inside a repository a fourth, <b>Git</b>, is a chip beside the root's name saying the branch (Ctrl+Shift+G): what
 * is changed and not staged, and what is staged, in place of the files ({@link GitPane}). A row opens its file.
 *
 * <p>A third mode, <b>Scratches</b>, lays the scratches folder ({@link Scratches}) over the root's files for a moment,
 * on Alt+N: a file picked there opens in a tab as one from the root's tree would, and the root, its tabs and the
 * session stay as they are. The way back is Alt+N again, or the heading, which says Scratches while it is up.
 * Open offers the same folder as a root of its own, once there is something in it.
 *
 * <p>The scratches tree is the one tree here that changes the disk. A row dragged onto a folder, or between the rows
 * of one, moves there, and Rename on a row's menu opens a name field above the tree. Both are performed through the
 * window's undo history, so Ctrl+Z puts them back, and both are told to {@link #onRelocated}, so a tab open on what
 * moved follows it.
 *
 * <p>There is one tree per mode for the life of the window, and changing folder re-points a source and refreshes
 * its tree. The refresh lists the new folder, which is I/O, so it runs on the offload lane rather than wherever the
 * request came from.
 */
final class Navigator {

    /** What the card is showing. */
    enum Mode { EDIT, OPEN, SCRATCHES, GIT }

    /** How many recent roots Open lists: as many as the editor offers, the root itself left out. */
    private static final int RECENT_SHOWN = 3;

    private final Gui gui;
    private final Motion motion;
    private final Executor io;
    private final FolderSource source = new FolderSource(null);
    private final RootSource roots = new RootSource();
    private final Scratches scratches;
    private final FolderSource scratchSource = new FolderSource(null);
    private final TreeView<Path> tree;
    private final TreeView<Path> picker;
    private final TreeView<Path> scratchTree;
    private final Node scratchPane;
    /**
     * Rename's field, above the scratches tree while a row is being renamed. A field of the pane rather than of the
     * row or a dialog, since the framework has neither an inline edit nor a question that takes text (FN-22).
     */
    private final TextField renameField;
    /** The scratch or folder the name field is for, or null while it is put away. */
    private volatile Path renaming;
    /** Open's way to the scratches as a root, shown once there is at least one. */
    private final Button scratchesButton;
    private final Button heading;
    /** The way into Git mode, beside the root's name: the branch, shown only inside a repository. */
    private final Button gitChip;
    private final GitPane gitPane;
    /** Whether the root is in a repository, as the last word from the repository said. */
    private volatile boolean repository;
    private volatile Consumer<Boolean> gitShown = on -> { };
    /**
     * Add's name field, above the root's tree while something is being named: the same field-of-the-pane as Rename's,
     * for the same reason (FN-22). What a name goes to is {@link #naming}; null while it is put away.
     */
    private final TextField nameField;
    private volatile Consumer<String> naming;
    private volatile Adding adding = (folder, what, template) -> { };
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
    private volatile Consumer<Path> newScratch = p -> { };
    private volatile BiConsumer<Path, Path> relocated = (from, to) -> { };
    /** The root's name, for the heading: what it says again when Scratches goes. */
    private volatile String rootLabel = "No folder";
    private volatile Consumer<Mode> modeChanged = m -> { };
    private volatile Consumer<Path> previewing = p -> { };
    private volatile BiConsumer<Runnable, Runnable> opening = (proceed, cancel) -> proceed.run();
    private volatile Path recentHover;
    private volatile Path treeHover;

    Navigator(Gui gui, Motion motion, Scratches scratches) {
        this.gui = gui;
        this.motion = motion;
        this.io = gui.offload();
        this.scratches = scratches;

        // The root's name is what switches between Edit and Open: the thing to change is the thing to press. A chip,
        // outlined and so plainly pressable, that stays pressed while Open is up, so the way back is the same name
        // held down. A button rather than a clickable text, so Tab, Enter and Space reach it too.
        heading = new Button(gui, "No folder").kind(Button.Kind.SECONDARY).toggle(true)
                .onToggle(on -> {
                    if (on) {
                        askToOpen();
                    } else {
                        mode(Mode.EDIT);
                    }
                });
        heading.node().font(Type.UI).wordWrap(false).width(Length.grow(1));
        gui.landmark(Landmarks.FOLDER, heading.node());
        // Git mode's way in is a chip of the same kind beside it, saying the branch: the thing Git mode is about, and
        // held down while it is up, as the root's name is while Open is. Only a repository has one.
        gitChip = new Button(gui, "").kind(Button.Kind.SECONDARY).toggle(true)
                .onToggle(on -> mode(on ? Mode.GIT : Mode.EDIT));
        gitChip.node().font(Type.UI).wordWrap(false).visible(false);
        gui.landmark(Landmarks.GIT, gitChip.node());
        gitPane = new GitPane(gui);
        // The root is a folder like any row, so its name has a folder's menu; with no root there is nothing to offer.
        gui.onContextMenu(heading.node(), menu -> {
            Path base = source.base();
            if (base != null) {
                addMenu(base, menu);
                folderMenu(base, menu);
                menu.item("Copy path", () -> gui.clipboard().set(base.toString()));
            }
        });

        tree = new TreeView<>(gui, source).motion(motion.arrival);
        // The tree sits in the card rather than in a well of its own. Its border is the tree's to repaint on every
        // change of focus, so that stays (framework-notes FN-14); Look.ring quiets it.
        tree.node().width(Length.FILL).height(Length.grow(1)).visible(false)
                .background(gui.theme().color(Role.NONE));
        Look.ring(gui, tree.node());
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
        tree.action(TreeView.Action.<Path>of(Icon.CHEVRON_RIGHT, "Open", (p, job) -> openFile.accept(p))
                .enabledWhen(Files::isRegularFile));
        tree.action(TreeView.Action.<Path>of(TextEditorIcon.MAKE_ROOT, "Make this the root", (p, job) -> pick(p))
                .shownWhen(Files::isDirectory));
        tree.onContextMenu((p, menu) -> {
            // Into the folder itself, or beside the file: the folder a row stands for is where Add puts things.
            addMenu(Files.isDirectory(p) ? p : p.getParent(), menu);
            if (Files.isDirectory(p)) {
                folderMenu(p, menu);
            } else {
                boolean vexplore = Suite.hasVexplore();
                menu.separator()
                        .item(Suite.vexploreLabel(vexplore), vexplore, () -> Suite.showInVexplore(p, gui.offload()));
            }
            menu.item("Copy path", () -> gui.clipboard().set(p.toString()));
        });
        // Enter makes it, Escape puts the field away. Its typing has a history of its own, as Rename's has.
        nameField = new TextField(gui, "").history(new History()).onSubmit(this::submitName);
        nameField.node().width(Length.FILL).font(Type.UI).visible(false);
        gui.claim(nameField.node(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, this::endName);
        gui.landmark(Landmarks.NAME, nameField.node());
        editPane = gui.column()
                .width(Length.FILL).height(Length.grow(1))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .children(nameField.node(), tree.node());

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
            gui.onState(b.node(), state -> {
                Path p = recentPaths.get(slot);
                if (state != InteractionState.NORMAL) {
                    recentHover = p;
                } else if (Objects.equals(recentHover, p)) {
                    recentHover = null;   // a late leave of an entry the pointer has since left clears nothing else
                }
                repicture();
            });
            recentButtons[i] = b;
            recent.append(b.node());
        }
        gui.landmark(Landmarks.RECENT, recent);

        // The scratches, as a root like any other: a project of their own, offered once there is one to open.
        scratchesButton = new Button(gui, Scratches.NAME).kind(Button.Kind.GHOST)
                .onPress(() -> pick(scratches.dir()));
        scratchesButton.node().visible(false);
        gui.onState(scratchesButton.node(), state -> {
            Path p = scratches.dir();
            if (state != InteractionState.NORMAL) {
                recentHover = p;
            } else if (Objects.equals(recentHover, p)) {
                recentHover = null;
            }
            repicture();
        });
        gui.landmark(Landmarks.OPEN_SCRATCHES, scratchesButton.node());
        recent.append(scratchesButton.node());

        // The way up: the top folder's ancestors, each a button that lists the folders from there. Moving up does
        // not change the root, it only widens what can be picked; the root stays unfolded and selected below.
        ancestry = new Breadcrumb<Path>(gui, Navigator::crumbLabel).font(Type.UI).maxSegments(2)
                .onNavigate(this::widen);
        ancestry.node().padding(Length.ZERO, Type.TIGHT);
        gui.landmark(Landmarks.ANCESTRY, ancestry.node());
        // No arrival ramp here. Open unfolds the top and scrolls to the root in one go, and with the folders above
        // the root growing in from nothing, the scroll is measured against rows a frame old and lands short.
        // A row here lights strongly and takes the hand, because a click on it changes the whole window; and the
        // folder under the pointer is the one pictured where the editor was, so what a click would choose is in
        // view before the click. Off the rows, the picture is of the selected row: the root, or wherever the arrow
        // keys have walked to.
        picker = new TreeView<>(gui, roots)
                .hoverStyle(Look.PICK_HOVER, Role.ACCENT)
                .rowCursor(CursorShape.POINTER);
        picker.node().width(Length.FILL).height(Length.grow(1))
                .background(gui.theme().color(Role.NONE));
        Look.ring(gui, picker.node());
        gui.landmark(Landmarks.PICKER, picker.node());
        picker.onHover(p -> {
            treeHover = p;
            repicture();
        });
        picker.onSelect(p -> repicture());
        picker.onClick(this::pick);
        picker.onActivate(this::pick);
        picker.action(TreeView.Action.<Path>of(TextEditorIcon.MAKE_ROOT, "Make this the root", (p, job) -> pick(p)));
        picker.onContextMenu((p, menu) -> {
            folderMenu(p, menu);
            menu.item("Copy path", () -> gui.clipboard().set(p.toString()));
        });
        openPane = gui.column()
                .width(Length.FILL).height(Length.grow(1))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .visible(false)
                .children(recent, gui.box().height(Type.RULE).background(gui.theme().color(Look.RIM)),
                        ancestry.node(), picker.node());

        // Scratches: the scratches folder's files, as the root's are shown in Edit, and a way to start another.
        scratchTree = new TreeView<>(gui, scratchSource).motion(motion.arrival);
        scratchTree.node().width(Length.FILL).height(Length.grow(1))
                .background(gui.theme().color(Role.NONE));
        Look.ring(gui, scratchTree.node());
        gui.landmark(Landmarks.SCRATCHES, scratchTree.node());
        scratchTree.onSelect(p -> {
            if (Files.isRegularFile(p)) {
                openFile.accept(p);
            }
        });
        scratchTree.onActivate(p -> {
            if (Files.isDirectory(p)) {
                scratchTree.expand(p);
            }
        });
        // Moving is a drag, onto a folder or between the rows of one (Ctrl+X and Ctrl+V too, which the tree gives
        // every reorderable tree). The window's history performs it, so it undoes; the framework's Window sets that
        // as the GUI's drop history. A drop also focuses the tree, so the next Ctrl+Z is the window's and not the
        // last-typed document's, whose field claims Ctrl+Z only while it has the keyboard.
        scratchTree.reorderable(this::scratchMove);
        scratchTree.onContextMenu((p, menu) -> {
            Path folder = Files.isDirectory(p) ? p : p.getParent();
            menu.item("New scratch here", () -> newScratch.accept(folder))
                    .item("New folder here", () -> io.execute(() -> newFolder(folder)))
                    .item("Rename…", () -> beginRename(scratchSource.top(p)));
            if (Files.isDirectory(p)) {
                folderMenu(p, menu);
            } else {
                boolean vexplore = Suite.hasVexplore();
                menu.separator()
                        .item(Suite.vexploreLabel(vexplore), vexplore, () -> Suite.showInVexplore(p, io));
            }
            menu.item("Copy path", () -> gui.clipboard().set(p.toString()));
        });
        Button fresh = new Button(gui, "New scratch").kind(Button.Kind.GHOST).onPress(() -> newScratch.accept(null));
        gui.landmark(Landmarks.NEW_SCRATCH, fresh.node());
        // Enter renames, Escape puts the field away. Its typing has a history of its own, kept out of the window's
        // where the renames themselves go; and an own history that is empty lets Ctrl+Z through (TextField.undo), so
        // the rename just made undoes even from the field.
        renameField = new TextField(gui, "").history(new History()).onSubmit(this::submitRename);
        renameField.node().width(Length.FILL).font(Type.UI).visible(false);
        gui.claim(renameField.node(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, this::endRename);
        gui.landmark(Landmarks.RENAME_SCRATCH, renameField.node());
        scratchPane = gui.column()
                .width(Length.FILL).height(Length.grow(1))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .visible(false)
                .children(fresh.node(), gui.box().height(Type.RULE).background(gui.theme().color(Look.RIM)),
                        renameField.node(), scratchTree.node());

        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .gap(Type.TIGHT)
                // Narrow at the sides: the gap beside the card is the divider's now, and the tree keeps its own inset.
                .padding(Type.WIDE, Length.dp(3))
                .background(gui.theme().color(Look.CARD))
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Look.RIM))
                .alignItems(AlignItems.STRETCH)
                .children(gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                                .children(heading.node(), gitChip.node()),
                        editPane, openPane, scratchPane, gitPane.node());
    }

    /** What the navigator's Add asks for: something made in a folder, named first ({@link #askName}). */
    interface Adding {
        /** Make {@code what} in {@code folder}; {@code template} says what a file starts as, and is null otherwise. */
        void add(Path folder, Made what, Template template);
    }

    /** Be asked to add something to a folder, from a row's or the root's menu. Runs on a worker. */
    void onAdd(Adding handler) {
        this.adding = handler == null ? (folder, what, template) -> { } : handler;
    }

    /**
     * Add, on the menu of a folder of the root's: a folder or a file of any type always; a link where the platform
     * makes them without privilege; a Maven project where there is none around the folder, else a module of the one
     * there is; and an archive of the folder once there is a file in it. What does not apply is greyed out rather than
     * missing, as every menu here does it. Asks the disk a little (is there a pom above, a file below), which a menu
     * opening on a worker can afford.
     */
    private void addMenu(Path folder, MenuSink menu) {
        if (folder == null) {
            return;
        }
        Path project = Make.projectOf(folder);
        boolean files = Make.anyFile(folder);
        menu.submenu("Add", add -> add
                .item("Folder", () -> adding.add(folder, Made.FOLDER, null))
                .submenu("File", file -> {
                    for (Template t : Template.values()) {
                        file.item(label(t), () -> adding.add(folder, Made.FILE, t));
                    }
                })
                .item("Symlink", Make.links(), () -> adding.add(folder, Made.SYMLINK, null))
                .separator()
                .item("Project", project == null, () -> adding.add(folder, Made.PROJECT, null))
                .item("Submodule", project != null, () -> adding.add(folder, Made.SUBMODULE, null))
                .separator()
                .item("Archive", files, () -> adding.add(folder, Made.ARCHIVE, null)));
    }

    /** What the File submenu calls each template. */
    static String label(Template t) {
        return switch (t) {
            case BLANK -> "Blank";
            case TEXT -> "Text";
            case JAVA -> "Java";
            case MARKDOWN -> "Markdown";
            case POWERSHELL -> "PowerShell";
            case BASH -> "Bash";
            case ZSH -> "Zsh";
        };
    }

    /**
     * Ask for a name in {@code folder}, starting from {@code suggested} with all but its extension selected, so typing
     * replaces the part that is usually meant: the name field above the root's tree. Enter gives the name to
     * {@code named}; Escape puts the field away and asks nothing. The root's files are shown for it, and the folder
     * unfolded, so what is being added to is in view.
     */
    void askName(Path folder, String suggested, Consumer<String> named) {
        if (mode != Mode.EDIT) {
            mode(Mode.EDIT);
        }
        naming = named;
        int dot = suggested.lastIndexOf('.');
        nameField.text(suggested).select(0, dot > 0 ? dot : suggested.length());
        nameField.node().visible(true);
        gui.focus(nameField.node());
        if (folder != null && !folder.equals(source.base()) && source.holds(folder)) {
            io.execute(() -> {
                List<Path> chain = source.chainTo(folder);
                if (!chain.isEmpty()) {
                    tree.revealPath(chain, null);
                }
            });
        }
    }

    /** Enter in the name field: the name goes where it was asked for, and the field goes away. */
    void submitName(String name) {
        Consumer<String> named = naming;
        endName();
        if (named != null) {
            named.accept(name);
        }
    }

    /** Put the name field away, and the keyboard back in the tree. */
    private void endName() {
        naming = null;
        nameField.node().visible(false);
        if (mode == Mode.EDIT) {
            tree.focus();
        }
    }

    /**
     * What a folder's menu offers wherever the folder appears (a row in either tree, the root's name): the suite's
     * other two applications on it. Each is greyed out, saying so, when it is not installed.
     */
    private void folderMenu(Path folder, MenuSink menu) {
        boolean vexplore = Suite.hasVexplore();
        boolean mainFrame = Suite.hasMainFrame();
        menu.separator()
                .item(Suite.vexploreLabel(vexplore), vexplore, () -> Suite.showInVexplore(folder, io))
                .item(Suite.mainFrameLabel(mainFrame), mainFrame, () -> Suite.openInMainFrame(folder, io));
    }

    /**
     * Picture what the pointer is on, Recent before the tree, else where the picture rests. Worked out from both
     * every time rather than from whichever event came last, because the leave of one control and the enter of
     * the next arrive from a pool in no promised order.
     */
    private void repicture() {
        Path r = recentHover;
        Path t = treeHover;
        previewing.accept(r != null ? r : t != null ? t : resting());
    }

    /** What Open pictures with nothing under the pointer: the selected folder, else the root, else the top. */
    private Path resting() {
        Path selected = picker.selected();
        if (selected != null) {
            return selected;
        }
        Path base = source.base();
        return base != null ? base : roots.top();
    }

    /** Be told when the card changes mode. Runs on whichever thread asked for the change. */
    void onMode(Consumer<Mode> handler) {
        this.modeChanged = handler == null ? m -> { } : handler;
    }

    /**
     * Be asked before the user goes to Open: {@code gate} runs exactly one of the two it is given, the first to go
     * on, the second to stay in Edit. Runs on a worker.
     */
    void onOpening(BiConsumer<Runnable, Runnable> gate) {
        this.opening = gate == null ? (proceed, cancel) -> proceed.run() : gate;
    }

    /**
     * The user's way to Open, the root's name pressed: through {@link #onOpening}'s gate, since Open is where the
     * root changes and whatever has to be settled first is settled before it. Stopped there, the name is released
     * and the files stay.
     */
    void askToOpen() {
        opening.accept(() -> mode(Mode.OPEN), () -> heading.show(false));
    }

    /** Be told which folder Open is pointing at, to picture it. Runs on a worker. */
    void onPreview(Consumer<Path> handler) {
        this.previewing = handler == null ? p -> { } : handler;
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

    /** Be asked for a new scratch, in the folder given, or in the scratches folder for null. Runs on a worker. */
    void onNewScratch(Consumer<Path> handler) {
        this.newScratch = handler == null ? p -> { } : handler;
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
        Mode was = mode;
        mode = value;
        boolean open = value == Mode.OPEN;
        boolean scratch = value == Mode.SCRATCHES;
        boolean git = value == Mode.GIT;
        heading.show(open || scratch);
        heading.label(scratch ? Scratches.NAME : rootLabel);
        gitChip.show(git);
        // Open is a different place, and looks it: the card steeps in the accent and wears it as its edge, the name
        // stays pressed, and a sweep runs down the card as it changes, so the switch is seen even by an eye that was
        // on the document. Scratches and Git are only laid over the root, with the editor still beside it: the edge
        // and the sweep, not the steeping.
        Theme theme = gui.theme();
        root.background(theme.color(open ? Look.PICKING : Look.CARD))
                .border(Type.RULE, theme.color(open || scratch || git ? Look.PICKING_RIM : Look.RIM));
        modeChanged.accept(value);
        if (open) {
            recentHover = null;
            treeHover = null;
            repicture();
        }
        if ((open || scratch || git) && was != value) {
            motion.cues.play(root, Cue.scanline(theme.color(Role.ACCENT)));
        }
        editPane.visible(value == Mode.EDIT);
        openPane.visible(open);
        scratchPane.visible(scratch);
        gitPane.node().visible(git);
        if ((was == Mode.GIT) != git) {
            gitShown.accept(git);
        }
        if (!scratch && renaming != null) {
            renaming = null;   // a rename left unfinished when the scratches went is a rename not made
            renameField.node().visible(false);
        }
        if (open) {
            io.execute(() -> {
                scratchesButton.node().visible(scratches.any());
                listRoots(RootSource.above(source.base()));
            });
        }
        if (scratch) {
            io.execute(this::listScratches);
        }
    }

    /** Alt+N: lay the scratches over the root's files, or take them away again. */
    void toggleScratches() {
        mode(mode == Mode.SCRATCHES ? Mode.EDIT : Mode.SCRATCHES);
    }

    /** Ctrl+Shift+G: Git mode, or back to Edit. Outside a repository there is no Git mode, and nothing happens. */
    void toggleGit() {
        if (mode == Mode.GIT) {
            mode(Mode.EDIT);
        } else if (repository) {
            mode(Mode.GIT);
        }
    }

    /** Be told when Git mode comes up ({@code true}) or goes. Runs on whichever thread changed the mode. */
    void onGit(Consumer<Boolean> handler) {
        this.gitShown = handler == null ? on -> { } : handler;
    }

    /** Be asked to ask git again: Git mode's Refresh. Runs on a worker. */
    void onGitRefresh(Runnable handler) {
        gitPane.onRefresh(handler);
    }

    /** Be asked to show the history where the editor stands: Git mode's History. Runs on a worker. */
    void onGitHistory(Runnable handler) {
        gitPane.onHistory(handler);
    }

    /** Be told a Git mode row was picked, to show its diff. */
    void onGitPick(Consumer<GitPane.Row> handler) {
        gitPane.onPick(handler);
    }

    /** Be asked to open a Git mode row's file: Enter or a double click on it. Runs on a worker. */
    void onGitOpen(Consumer<Path> handler) {
        gitPane.onOpenFile(handler);
    }

    /** Let go of the Git mode row picked: its diff was put away. */
    void gitUnpick() {
        gitPane.unpick();
    }

    /** Be asked to stage ({@code true}) or unstage a Git mode row, or everything for a null row. Runs on a worker. */
    void onGitStage(BiConsumer<GitPane.Row, Boolean> handler) {
        gitPane.onStage(handler);
    }

    /** Be asked to commit what is staged, with the message as typed. Runs on whichever thread pressed Commit. */
    void onGitCommit(Consumer<String> handler) {
        gitPane.onCommit(handler);
    }

    /** How a commit went, for the message box. */
    void gitCommitted(GitCommitted c) {
        gitPane.committed(c);
    }

    /** Be asked to push the branch checked out: Push. */
    void onGitPush(Runnable handler) {
        gitPane.onPush(handler);
    }

    /** How a push went, for Push. */
    void gitPushed(GitPushed p) {
        gitPane.pushed(p);
    }

    /** The Git mode row picked, staged if it is not and unstaged if it is: the diff's Stage or Unstage. */
    void gitMovePicked() {
        gitPane.movePicked();
    }

    /** The repository's top, which a Git mode row's path is relative to; null outside one. */
    Path gitTop() {
        return gitPane.top();
    }

    /**
     * Where the root stands in git. Outside a repository the chip goes, and Git mode with it, since there is nothing
     * in it to show.
     */
    void git(GitHead h) {
        repository = h.repository();
        gitChip.label(h.chip());
        gitChip.node().visible(h.repository());
        gitPane.head(h);
        if (!h.repository()) {
            gitPane.clear();
            if (mode == Mode.GIT) {
                mode(Mode.EDIT);
            }
        }
    }

    /** Every changed path, for Git mode's lists. */
    void gitTree(GitTree t) {
        gitPane.tree(t);
    }

    /** Point the scratches tree at the folder, made if this is its first look, and list it. For the offload lane. */
    private void listScratches() {
        try {
            scratches.ensure();
        } catch (IOException | RuntimeException e) {
            // listed as empty, as any unreadable folder is
        }
        scratchSource.base(scratches.dir());
        scratchTree.refresh();
    }

    /**
     * {@code file} is a new scratch: list it, and unfold the way to it and select it, when the scratches are what is
     * showing. For the offload lane.
     */
    void scratchMade(Path file) {
        if (mode == Mode.SCRATCHES) {
            listAndSelect(file);
        }
    }

    /** "New folder here" in the scratches tree. For the offload lane. */
    private void newFolder(Path parent) {
        try {
            listAndSelect(scratches.folder(parent));
        } catch (IOException | RuntimeException e) {
            // nothing made; the tree shows what is there
        }
    }

    private void listAndSelect(Path made) {
        listScratches();
        List<Path> chain = scratchSource.chainTo(made);
        if (!chain.isEmpty()) {
            scratchTree.revealPath(chain, null);
        }
    }

    /**
     * What dropping {@code moved} at {@code where} in the scratches tree does: the move into the folder that is,
     * or null where it would do nothing or could not be done. Asked once a frame on the GUI thread while a row is
     * carried, so it decides and does not move anything. Package-private, so a test can make the drop's change.
     */
    Change scratchMove(Path moved, Placement<Path> where, DropEffect effect) {
        if (effect != DropEffect.MOVE) {
            return null;   // a copy of a note is a second note, which nothing has asked for
        }
        Path folder;
        if (where.isRoot()) {
            folder = scratches.dir();
        } else if (where.relation() == Placement.Relation.INTO) {
            folder = where.reference();   // a merged row's item is the deepest of its run: what it opens onto
        } else {
            folder = scratchSource.top(where.reference()).getParent();   // between rows: the folder they are in
        }
        Path subject = scratchSource.top(moved);
        Path to = scratches.into(subject, folder);
        return to == null ? null : relocation(subject, to);
    }

    /**
     * Moving {@code from} to {@code to} as an undoable change, whose reverse is the move back. The move itself is
     * one rename on the disk, made where the history applies it, since a change has to say whether it happened; the
     * listing and the tabs that follow it go to the offload lane. An undo after the files have changed some other
     * way (in Vexplore, say) is refused by its check rather than moving something that is no longer what it was.
     */
    private Change relocation(Path from, Path to) {
        return new Change() {
            @Override
            public Change apply() {
                try {
                    scratches.relocate(from, to);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                io.execute(() -> relocated(from, to));
                return relocation(to, from);
            }

            @Override
            public Check check() {
                return Files.exists(from) && !Files.exists(to) ? Check.OK : Check.CONFLICT;
            }
        };
    }

    /** {@code from} is {@code to} now: tell whoever has it open, and show it where it went. For the offload lane. */
    private void relocated(Path from, Path to) {
        relocated.accept(from, to);
        if (source.holds(from) || source.holds(to)) {
            tree.refresh();   // the scratches are the root as well
        }
        if (mode == Mode.SCRATCHES) {
            listAndSelect(to);
        } else {
            listScratches();
        }
    }

    /** Be told when a scratch or a folder of them has moved or been renamed, from where to where. On the offload lane. */
    void onRelocated(BiConsumer<Path, Path> handler) {
        this.relocated = handler == null ? (from, to) -> { } : handler;
    }

    /**
     * Rename on a scratch row's menu: the name field, holding {@code file}'s name with all but its extension
     * selected, so typing replaces the part that is usually meant. Package-private, for a test.
     */
    void beginRename(Path file) {
        if (file == null || !scratches.holds(file) || scratches.is(file)) {
            return;
        }
        renaming = file;
        String name = String.valueOf(file.getFileName());
        int dot = name.lastIndexOf('.');
        renameField.text(name).select(0, dot > 0 && !Files.isDirectory(file) ? dot : name.length());
        renameField.node().visible(true);
        gui.focus(renameField.node());
    }

    /**
     * Enter in the name field: rename what it is for, through the window's history. A name that cannot be had — blank,
     * taken, another folder's — leaves the field up, ringed, to be changed. Package-private, for a test.
     */
    void submitRename(String name) {
        Path file = renaming;
        if (file == null) {
            return;
        }
        io.execute(() -> {
            Path to = scratches.renamed(file, name);
            if (to == null || !perform(relocation(file, to))) {
                motion.cues.play(renameField.node(), Cue.ring(gui.theme().color(Role.DANGER), 1));
                return;
            }
            endRename();
        });
    }

    /** Put the name field away, and the keyboard back in the tree. */
    private void endRename() {
        renaming = null;
        renameField.node().visible(false);
        if (mode == Mode.SCRATCHES) {
            scratchTree.focus();
        }
    }

    /** Do {@code change} in the window's history, as a drop is done; false if it could not be done. */
    private boolean perform(Change change) {
        History history = gui.dropHistory();
        try {
            if (history != null) {
                history.perform(change);
            } else {
                change.apply();   // a tree with no window around it: done, with nothing to undo it
            }
            return true;
        } catch (RuntimeException e) {
            return false;
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
        if (scratches.is(folder)) {
            try {
                scratches.ensure();   // offered only once it is there, but it can go between the offer and the click
            } catch (IOException | RuntimeException e) {
                return;
            }
        }
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
        // The scratches have a button of their own, so they are not one of the recent roots as well.
        List<Path> shown = recent.stream().filter(p -> !Objects.equals(p, current) && !scratches.is(p))
                .limit(RECENT_SHOWN).toList();
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
            rootLabel = shown == null ? "No folder" : scratches.is(shown) ? Scratches.NAME
                    : String.valueOf(name == null ? shown : name);
            if (mode != Mode.SCRATCHES) {
                heading.label(rootLabel);
            }
            tree.node().visible(shown != null);
            // A root arriving from anywhere (the folder dialog, the command line) is somewhere to edit; Open was
            // for finding one.
            if (shown != null && mode == Mode.OPEN) {
                mode(Mode.EDIT);
            }
            if (then != null) {
                then.run();
            }
        });
    }

    /**
     * Bring the trees into line with the disk, after something may have written to it: a save, a run that ended,
     * the window coming back from wherever else the user was. Nothing the user arranged moves: the trees keep
     * their rows, which folders are open, the selection and the scroll ({@code TreeView.refresh}), and only what
     * appeared or went is added or taken away. Only opened folders are re-read, and the listing is offloaded.
     *
     * <p>A renamed folder is a row gone and another come, so it comes back closed. A selected row that went takes
     * the selection to the nearest folder above it still there, which is the tree's rule and not a lie.
     */
    void refresh() {
        io.execute(() -> {
            if (source.base() != null) {
                tree.refresh();
            }
            if (scratchSource.base() != null) {
                scratchTree.refresh();
            }
            if (mode == Mode.OPEN && roots.top() != null) {
                picker.refresh();
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
        if (mode != Mode.EDIT) {
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
        switch (mode) {
            case OPEN -> picker.focus();
            case SCRATCHES -> scratchTree.focus();
            case GIT -> gitPane.focus();
            case EDIT -> tree.focus();
        }
    }
}
