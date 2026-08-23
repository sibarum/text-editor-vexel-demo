package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.CloseRequest;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowInput;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.demo.editor.terminal.TerminalWindow;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.os.Decorations;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A deceptively simple text editor on vexelray-gui: a tab bar of open files, a multiline {@link TextField}
 * per tab (word wrap, line numbers, syntax highlighting, cut/copy/paste), and a status line. Files open and
 * save through the native OS dialogs in vexelray-gui-nfd.
 *
 * <p>Run: {@code TextEditorApp} (windowed), {@code TextEditorApp --capture [out.png]} (headless).
 * Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class TextEditorApp {

    /**
     * Window and capture size, in the engine's logical coordinates. The GUI draws the frame now, so the client
     * area covers the whole window and the height carries {@link #BAR_H} of the application's own title bar on
     * top of the 560 the tabs and editor had beneath an OS one.
     */
    private static final int W = 800;
    /** The title bar's own height, in dp — {@code TitleBar}'s, which is the Windows caption metric. */
    static final int BAR_H = 32;
    private static final int H = 560 + BAR_H;
    /**
     * The margin the page leaves around itself — and, because it is declared as such, the window's resize grip.
     *
     * <p>It is one constant used twice on purpose. The ring of dead space a window draws around its content is
     * the easiest thing on screen to aim at and the only thing on screen that did nothing, so it is handed to the
     * window manager ({@code Gui.resizeBorder}): the pointer turns into a resize pointer the moment it crosses
     * into the margin, and the edge is as wide a target as the margin is. Padding it by one value and gripping by
     * another would leave either a strip of margin that does not resize or a strip of text that does.
     */
    private static final Length GUTTER = Length.dp(16);

    private static final String UNTITLED = "untitled.txt";
    private static final String WELCOME =
            "Welcome to the deceptively simple text editor.\n\n"
                    + "Word wrap, line numbers, selection, cut/copy/paste, and caret-follow scrolling "
                    + "all come from the multiline TextField widget. Start typing.";

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        // --terminal opens the shell window at startup instead of on Ctrl+`, so the second window can be looked
        // at (and its shutdown exercised) without a hand on the keyboard.
        boolean withTerminal = java.util.Arrays.asList(args).contains("--terminal");
        args = java.util.Arrays.stream(args).filter(s -> !s.equals("--terminal")).toArray(String[]::new);

        Gui gui = new Gui();
        // The editor keeps the framework's own look, unshifted — it is the reference the other two windows are
        // departures from (see Palettes). Stated rather than left to the default, because the choice is now one
        // of three and a default is not a choice anyone can read.
        gui.theme(Palettes.EDITOR);
        // Two em more height than the page needs on its own: the title bar sits inside the canvas now, so the
        // smallest layout has to hold it as well as the tabs and the status line.
        gui.minSize(Length.em(30), Length.em(22));
        Workspace ws = new Workspace(gui);
        zoomShortcuts(gui);

        if (args.length >= 1 && args[0].equals("--capture")) {
            GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, args.length >= 2 ? args[1] : "text-editor.png");
            System.out.println("captured");
            return;
        }

        if (args.length >= 1 && args[0].equals("--capture-terminal")) {
            captureTerminal(args.length >= 2 ? args[1] : "terminal.png");
            return;
        }

        if (args.length >= 1 && args[0].equals("--capture-folder")) {
            captureFolder(args.length >= 2 ? args[1] : "folder.png");
            return;
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        // Placement is read before the window exists, so it is created where it was left rather than moved there
        // after appearing — and clamped on the way, because the desk may have changed shape since.
        WindowMemory memory = new WindowMemory(Settings.open("text-editor"));
        try (Tactroller input = openInput();
             GuiApp app = new GuiApp(memory.config("main", "Text Editor", W, H)
                     .decorations(Decorations.CLIENT));
             Clipboard clipboard = openClipboard()) {
            // The window exists at last, so the chrome can be pointed at it. Until now the bar has been a
            // working bar against WindowControls.NONE — which is also what --capture renders.
            ws.titleBar.controls(app.controls());
            if (memory.maximized("main")) {
                app.window().maximize();
            }
            memory.watch("main", app.window());
            // The main window is created before this class exists, so it still wires its own input; every other
            // window the framework opens gets one from here.
            attachInput(input, app);
            app.input(TextEditorApp::windowInput);
            FileActions files = new FileActions(gui, ws, app, memory);
            files.shortcuts();
            // Dialogs, and the one that matters most: closing the main window is quitting, so it goes through a
            // gate that can still ask about unsaved work while the window stays open.
            Modals dialogs = Modals.install(app);
            app.onCloseRequest(files::guardClose);
            // Every window gets the OS clipboard, not just the main one: copy out of the terminal's prompt has to
            // reach the same place copy out of a tab does.
            if (clipboard != null) {
                for (Gui window : files.windows()) {
                    bindClipboard(window, clipboard);
                }
            }
            // Whatever was up last time comes back up. --terminal on top of that is harmless: opening a window
            // that is already open focuses it.
            files.restore();
            if (withTerminal) {
                files.openTerminal();
            }
            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
            try {
                app.run(gui, maxFrames, () -> {
                    pump(bridge);
                    files.drain();
                    memory.poll();
                });
            } finally {
                // Drop any dialog still queued: an application on its way out must not be held up by a question
                // there is nobody left to answer.
                dialogs.close();
                files.close();
                memory.save();
            }
        }
        gui.close();
        System.out.println("clean shutdown");
    }

    /**
     * Render the terminal window headlessly: start MainFrame, run a few real lines against the real filesystem,
     * let the per-frame flush publish them, and write the PNG. {@code GuiApp.capture} takes one tree, so the
     * terminal needs its own entry point — and this one doubles as a smoke test of the whole path with no GPU
     * window and no keyboard.
     */
    private static void captureTerminal(String path) throws Exception {
        // No window is opened here, so the memory is never asked for a placement and never written to.
        WindowMemory unused = new WindowMemory(Settings.open("text-editor"));
        try (TerminalWindow terminal = new TerminalWindow(f -> { }, d -> { }, unused)) {
            terminal.start(Path.of("").toAbsolutePath());
            for (String line : List.of("version", "ls | where kind == \"file\" | select name size ext",
                    "ls | where nmae == \"x\"")) {
                terminal.submit(line);
                long deadline = System.nanoTime() + 10_000_000_000L;
                Thread.sleep(50);
                while (terminal.busy() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                Thread.sleep(50);
                terminal.tick();
            }
            terminal.tick();
            // The display knows its own size and its own bezel colour, so the capture is its call, not this one.
            terminal.capture(path);
        }
        System.out.println("captured " + path);
    }

    /**
     * Render the file tree headlessly, for the same reason the terminal has an entry point: a window whose look
     * cannot be looked at without a GPU and a mouse is a window whose look nobody checks. It shows this
     * repository, which is the one folder every checkout is guaranteed to have.
     */
    private static void captureFolder(String path) throws Exception {
        // No window is opened here, so the memory is never asked for a placement and never written to.
        WindowMemory unused = new WindowMemory(Settings.open("text-editor"));
        FolderWindow folder = new FolderWindow(f -> { }, unused);
        folder.setFolder(Path.of("").toAbsolutePath());
        Color page = folder.gui().theme().color(Role.PAGE);
        GuiApp.capture(folder.gui(), FolderWindow.DEFAULT_W, FolderWindow.DEFAULT_H,
                page.r(), page.g(), page.b(), path);
        System.out.println("captured " + path);
    }

    private static void zoomShortcuts(Gui gui) {
        gui.zoomRange(0.5f, 3f, 1.25f);
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
    }

    private static Tactroller openInput() {
        try {
            Tactroller t = Tactroller.open();
            System.out.println("input: " + t.backendName());
            return t;
        } catch (BackendException e) {
            System.out.println("input unavailable (" + e.getMessage() + "); running without pointer input");
            return null;
        }
    }

    /**
     * How input reaches every window the framework opens for us — the file tree, the terminal, any dialog. One
     * backend per window, attached at creation, pumped by the frame loop, released with the window.
     *
     * <p>This is the same four lines each of those windows used to run for itself, plus the per-frame pump and
     * the teardown, said once. The framework cannot do it alone: it speaks {@code tactroller-api} and Atchung
     * topics, but the bridge between them is chosen here, at the application edge.
     */
    private static WindowInput windowInput(dev.vexelray.os.NativeWindow window, Gui gui) {
        try {
            Tactroller backend = Tactroller.open();
            backend.attach(NativeWindow.ofHwnd(window.osHandle()));
            backend.setCoordinateSpace(CoordinateSpace.CLIENT);
            TactrollerInputBridge bridge = new TactrollerInputBridge(backend, gui.bus());
            return new WindowInput() {
                @Override
                public void pump() {
                    try {
                        bridge.pump();
                    } catch (BackendException e) {
                        // Transient poll failure — drop this frame's input rather than tear down the loop.
                    }
                }

                @Override
                public void close() {
                    try {
                        backend.close();
                    } catch (Exception e) {
                        // best effort — the backend is going away regardless
                    }
                }
            };
        } catch (BackendException e) {
            System.out.println("window input unavailable (" + e.getMessage() + "); that window takes no input");
            return WindowInput.NONE;
        }
    }

    /** CLIENT space, density left at 1.0 — the engine's canvas is logical; see vexelray-gui-demo's attachInput. */
    private static void attachInput(Tactroller input, GuiApp app) {
        if (input == null) {
            return;
        }
        try {
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.out.println("input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /** OS clipboard for cut/copy/paste; falls back to the in-memory default when no backend is present. */
    private static Clipboard openClipboard() {
        try {
            return Clipboard.open();
        } catch (ClipboardException e) {
            System.out.println("clipboard unavailable (" + e.getMessage() + "); cut/copy/paste use in-memory buffer");
            return null;
        }
    }

    /** Point one window's clipboard at the OS one. Each Gui carries its own, so each window is bound. */
    private static void bindClipboard(Gui gui, Clipboard clip) {
        gui.clipboard(new TextClipboard() {
            @Override
            public String get() {
                try {
                    return clip.getText().orElse("");
                } catch (ClipboardException e) {
                    return "";
                }
            }

            @Override
            public void set(String text) {
                try {
                    clip.setText(text);
                } catch (ClipboardException e) {
                    // best effort — a transient clipboard failure just drops the copy
                }
            }
        });
    }

    private static void pump(TactrollerInputBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.pump();
        } catch (BackendException e) {
            // Transient poll failure — drop this frame's input rather than tear down the loop.
        }
    }

    /** One open document: its widgets, its highlighter, and what saving must know about its file. */
    private static final class EditorTab {
        final TextField editor;
        final Highlighter highlighter;
        final Node body;
        Path file;
        /** Line-ending convention of the file, restored on save. New files save with {@code \n}. */
        boolean crlf;
        /**
         * The text as last loaded or saved — what {@link #dirty()} compares against.
         *
         * <p>A snapshot rather than a flag set from {@code onChange}, because change handlers run on worker
         * threads: a flag would race with the programmatic {@code editor.text(...)} that loading a file does, and
         * lose. Comparing on demand cannot race with anything, and it answers the question more honestly —
         * typing something and then undoing it back leaves the document clean, which is what it is.
         */
        String savedText;

        EditorTab(TextField editor, Highlighter highlighter, Node body, String savedText) {
            this.editor = editor;
            this.highlighter = highlighter;
            this.body = body;
            this.savedText = savedText;
        }

        String title() {
            return file != null ? file.getFileName().toString() : UNTITLED;
        }

        /** Whether this document has edits that are not on disk. */
        boolean dirty() {
            return !editor.text().equals(savedText);
        }

        /** This document's name for a message about losing it. */
        String describe() {
            return file != null ? file.getFileName().toString() : UNTITLED + " (never saved)";
        }
    }

    /**
     * The window's content: a {@link Tabs} panel of {@link EditorTab}s over a status line. The parallel
     * {@code open} list and the widget's tab order are kept in lockstep — every structural change (add,
     * close) goes through here, on the GUI thread via {@link FileActions#drain()}.
     */
    private static final class Workspace {
        final Gui gui;
        final Tabs tabs;
        final Node status;
        final TitleBar titleBar;
        final List<EditorTab> open = new ArrayList<>();

        Workspace(Gui gui) {
            this.gui = gui;
            this.tabs = new Tabs(gui);
            // AUTO, not a fixed line: this line also reports what was opened or saved, and a long path wraps.
            // A fixed height clips the second line outside the padding instead of making room for it.
            this.status = gui.text("Ctrl+O open - Ctrl+Shift+O folder - Ctrl+` terminal - Ctrl+S save - "
                            + "Ctrl+N new - Ctrl+W close")
                    .width(Length.FILL).height(Length.AUTO)
                    .textSize(Length.rem(0.875f)).textColor(gui.theme().color(Role.DIM))
                    .align(dev.vexelray.text.TextLayout.HAlign.LEFT, dev.vexelray.text.TextLayout.VAlign.MIDDLE)
                    .scroll(false, false);

            Node root = gui.column().width(Length.FILL).height(Length.grow(1))
                    .padding(GUTTER).gap(Length.rem(0.625f))
                    .children(tabs.node(), status);
            // The same gutter, said to the window manager: everything outside the page and below the bar is a
            // grip. The bar is not — it declares itself caption, and a declared region keeps the system's own
            // thin band, so this buys the three dead edges without costing the fourth its drag.
            gui.resizeBorder(GUTTER);
            // The window's own title bar: ordinary widgets, plus the declarations that tell the window manager
            // which pixels are caption. Bound to the real window in main(); here it commands
            // WindowControls.NONE, which is what --capture draws.
            this.titleBar = new TitleBar(gui, WindowControls.NONE, "Text Editor");
            gui.root().background(gui.theme().color(Role.PAGE)).children(titleBar.node(), root);

            newTab(WELCOME, null, false);
        }

        /** Open a new tab holding {@code content}, select it, and return it. */
        EditorTab newTab(String content, Path file, boolean crlf) {
            TextField editor = new TextField(gui, content).multiline(true).wordWrap(true).lineNumbers(true);
            editor.node().width(Length.FILL).height(Length.FILL);
            // Square shoulders and no border on top, so the page meets the tab bar seamlessly — the framework
            // strokes borders as one ring (no per-side control), so the seam-free look means no stroke at all;
            // lit + elevation keep the card reading as a panel without it.
            Node body = gui.column().width(Length.FILL).height(Length.FILL)
                    .background(gui.theme().color(Role.PANEL)).corner(Length.ZERO, Length.rem(0.75f))
                    .lit(gui.theme().lit()).elevation(Length.rem(1))
                    .padding(Length.dp(12))
                    .children(editor.node());
            EditorTab tab = new EditorTab(editor, new Highlighter(gui, editor), body, content);
            tab.file = file;
            tab.crlf = crlf;
            open.add(tab);
            tabs.add(tab.title(), body);
            tabs.select(open.size() - 1);
            tab.highlighter.language(tab.title());
            return tab;
        }

        EditorTab active() {
            int i = tabs.selected();
            return i >= 0 && i < open.size() ? open.get(i) : null;
        }

        /** Every open document with edits that are not on disk, in tab order. */
        List<EditorTab> unsaved() {
            List<EditorTab> out = new ArrayList<>();
            for (EditorTab tab : open) {
                if (tab.dirty()) {
                    out.add(tab);
                }
            }
            return out;
        }

        int indexOf(Path file) {
            for (int i = 0; i < open.size(); i++) {
                if (file.equals(open.get(i).file)) {
                    return i;
                }
            }
            return -1;
        }

        /** Re-label the active tab and re-pick its grammar — after an open-into or a save-as. */
        void retitleActive() {
            EditorTab tab = active();
            if (tab != null) {
                tabs.title(tabs.selected(), tab.title());
                tab.highlighter.language(tab.title());
            }
        }

        /** Close the active tab. The last tab is not removed but reset to an empty untitled document. */
        void closeActive() {
            int i = tabs.selected();
            EditorTab tab = active();
            if (tab == null) {
                return;
            }
            if (open.size() == 1) {
                tab.file = null;
                tab.crlf = false;
                tab.editor.text("");
                tab.savedText = "";
                retitleActive();
                status.text("Closed - one empty tab remains");
                return;
            }
            open.remove(i);
            tabs.remove(i);   // removes header and body from the tree; registrations die with them
            tab.highlighter.close();
            tab.editor.close();
        }

        /** Select the next ({@code +1}) or previous ({@code -1}) tab, wrapping around the ends. */
        void cycle(int direction) {
            int n = tabs.count();
            if (n > 1) {
                tabs.select(((tabs.selected() + direction) % n + n) % n);
            }
        }
    }

    /** The filesystem as a lazy {@link TreeView.Source}: directories first, then files, case-insensitive. */
    private static final class FolderSource implements TreeView.Source<Path> {
        private final Path base;

        FolderSource(Path base) {
            this.base = base;
        }

        @Override
        public List<Path> roots() {
            return children(base);
        }

        @Override
        public String label(Path item) {
            Path name = item.getFileName();
            return name != null ? name.toString() : item.toString();
        }

        @Override
        public boolean hasChildren(Path item) {
            return java.nio.file.Files.isDirectory(item);
        }

        @Override
        public List<Path> children(Path item) {
            try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(item)) {
                return s.sorted(java.util.Comparator
                                .comparing((Path p) -> !java.nio.file.Files.isDirectory(p))
                                .thenComparing(p -> label(p).toLowerCase(java.util.Locale.ROOT)))
                        .toList();
            } catch (java.io.IOException e) {
                return List.of();   // unreadable directory: shown as empty, not fatal
            }
        }
    }

    /**
     * The folder explorer as its own OS window on the shared frame loop: a second {@link Gui} holding a
     * {@link TreeView}, opened as the named window {@code "folder"} so Ctrl+Shift+O always means this one. The
     * framework attaches and pumps its input from the factory the app supplied, so both windows take focus and
     * input from the OS like one application, while every model change (opening a file into a tab) crosses to the
     * main window through {@link FileActions}' request queue on the one shared thread.
     *
     * <p>All methods run on the main thread: {@code show}/{@code setFolder} from the drain, the two lifecycle
     * callbacks from the frame loop.
     */
    private static final class FolderWindow {
        /** The default size, used the first time — after that, whatever the user left it at. */
        private static final int DEFAULT_W = 340;
        private static final int DEFAULT_H = 560 + BAR_H;
        /** This window's margin, and so its resize grip — see {@link TextEditorApp#GUTTER}. Tighter: it is narrow. */
        private static final Length GUTTER = Length.dp(12);

        private final java.util.function.Consumer<Path> openFile;
        private final WindowMemory memory;
        private final Gui gui = new Gui();
        private final Node column;
        private final TitleBar titleBar;
        private TreeView<Path> tree;
        /** The framework's handle on this window, claimed the first time it is shown. */
        private AppWindow handle;

        /** This window.s Gui, so the app can bind its shortcuts here as well as on the main window. */
        Gui gui() {
            return gui;
        }

        /** Whether the window is up right now — polled each frame so it can be reopened next launch. */
        boolean isOpen() {
            return handle != null && handle.open();
        }

        FolderWindow(java.util.function.Consumer<Path> openFile, WindowMemory memory) {
            this.openFile = openFile;
            this.memory = memory;
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
            zoomShortcuts(gui);
        }

        /**
         * Show {@code folder}, opening the window on the next frame if it is not already up. If it is, it comes
         * forward showing the new folder rather than staying behind the editor looking unresponsive.
         */
        void show(GuiApp app, Path folder) {
            setFolder(folder);
            // Remembered so the next launch can point the tree at the same place, not just at the same rectangle.
            memory.shownPath("folder", folder);
            // One call for both cases: show() creates the window if it is closed and raises it if it is not.
            if (handle == null) {
                handle = app.window("folder", () -> WindowSpec
                        .of(memory.config("folder", "Files", DEFAULT_W, DEFAULT_H)
                                .decorations(Decorations.CLIENT), gui)
                        .onCreated(this::onCreated)
                        .onClosed(this::onClosed));
            }
            handle.show();
        }

        private void setFolder(Path folder) {
            if (tree != null) {
                tree.node().remove();
                tree.close();
            }
            Path name = folder.getFileName();
            titleBar.title(name != null ? name.toString() : folder.toString());
            tree = new TreeView<>(gui, new FolderSource(folder));
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
            column.append(tree.node());
            tree.focus();
        }

        /**
         * The window exists, and its input is already attached and pumping — the framework did that from the
         * factory the app supplied. What is left is the two things only this window knows: which window its own
         * title bar commands, and where it should be.
         */
        private void onCreated(dev.vexelray.os.NativeWindow window) {
            titleBar.controls(WindowControls.of(window));
            if (memory.maximized("folder")) {
                window.maximize();
            } else {
                memory.restoreBounds("folder", window, DEFAULT_W, DEFAULT_H);
            }
            memory.watch("folder", window);
        }

        private void onClosed() {
            // Stop reading placement off a window that is being destroyed; what was recorded last stands.
            memory.forget("folder");
            // The window this bar commanded is gone; the tree outlives it and is shown again on the next
            // Ctrl+Shift+O, so the buttons go back to commanding nothing until onCreated rebinds them.
            titleBar.controls(WindowControls.NONE);
        }
    }

    /**
     * File actions over the native OS dialogs in vexelray-gui-nfd, operating on the workspace's active tab.
     *
     * <p>NFD dialogs are modal and must run on the GUI thread, but shortcut handlers run on worker threads —
     * so the shortcuts only enqueue, and {@link #drain()} services one request per frame from the app's
     * beforeFrame hook. Tab-structure changes ride the same queue so they are ordered with the file I/O.
     */
    private static final class FileActions implements AutoCloseable {
        private static final List<FileDialog.Filter> FILTERS =
                List.of(FileDialog.Filter.of("Text files", "txt", "md", "java", "json"));

        private final Gui gui;
        private final Workspace ws;
        private final GuiApp app;
        private final long window;
        private final WindowMemory memory;
        private final FolderWindow folder;
        private final TerminalWindow terminal;
        private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> requests =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        FileActions(Gui gui, Workspace ws, GuiApp app, WindowMemory memory) {
            this.gui = gui;
            this.ws = ws;
            this.app = app;
            this.window = app.windowHandle();
            this.memory = memory;
            this.folder = new FolderWindow(this::openPath, memory);
            // MainFrame reaches the editor the same way the file tree does: by enqueueing onto this one queue, so
            // a shell command that opens a tab is ordered with the modal dialogs and the tab-structure changes.
            this.terminal = new TerminalWindow(this::openPath, this::revealPath, memory);
        }

        /** Enqueue opening {@code file} into a tab — how the folder window's tree reaches the editor. */
        void openPath(Path file) {
            requests.add(() -> loadInto(file));
        }

        /** Enqueue pointing the folder window at {@code dir} — MainFrame's {@code reveal}. */
        void revealPath(Path dir) {
            requests.add(() -> {
                folder.show(app, dir);
                ws.status.text("Folder: " + dir);
            });
        }

        /** Every Gui this application presents, main window first. */
        List<Gui> windows() {
            return List.of(gui, folder.gui(), terminal.gui());
        }

        /**
         * Bind the app.s shortcuts on <em>every</em> window. The folder window is part of the same
         * application, so its keys must reach the same actions -- otherwise Ctrl+W does nothing while the
         * file tree has focus, which is what happens if only the main window is bound.
         *
         * <p>This does not double-fire: keys are focal, so tactroller delivers them only to the focused
         * window, and a chord bound on both Guis therefore runs once.
         */
        void shortcuts() {
            bind(gui);
            bind(folder.gui());
            bind(terminal.gui());
        }

        private void bind(Gui g) {
            g.shortcut(Key.GRAVE_ACCENT, () -> requests.add(this::openTerminal), Modifier.CONTROL);
            g.shortcut(Key.O, () -> requests.add(this::open), Modifier.CONTROL);
            g.shortcut(Key.O, () -> requests.add(this::openFolder), Modifier.CONTROL, Modifier.SHIFT);
            g.shortcut(Key.S, () -> requests.add(this::save), Modifier.CONTROL);
            g.shortcut(Key.S, () -> requests.add(this::saveAs), Modifier.CONTROL, Modifier.SHIFT);
            g.shortcut(Key.N, () -> requests.add(() -> ws.newTab("", null, false)), Modifier.CONTROL);
            g.shortcut(Key.W, () -> requests.add(ws::closeActive), Modifier.CONTROL);
            g.shortcut(Key.TAB, () -> requests.add(() -> ws.cycle(+1)), Modifier.CONTROL);
            g.shortcut(Key.TAB, () -> requests.add(() -> ws.cycle(-1)), Modifier.CONTROL, Modifier.SHIFT);
        }

        /** GUI thread, once per frame. One request at a time — each may block on a modal dialog. */
        void drain() {
            terminal.tick();
            // Which windows are up is read from the windows themselves, every frame, rather than written when
            // they open and close: see WindowMemory.open for why that distinction is the whole feature.
            memory.open("folder", folder.isOpen());
            memory.open("terminal", terminal.isOpen());
            Runnable r = requests.poll();
            if (r != null) {
                r.run();
            }
        }

        /**
         * Reopen what was open when the application last closed: the file tree, pointed back at the same folder,
         * and the terminal. Enqueued rather than done here, so both open through the one path everything else
         * uses — one per frame, ordered with the dialogs.
         *
         * <p>A folder that has since been deleted, renamed or unmounted is <em>not</em> reopened, and says so
         * rather than opening an empty tree. That also forgets it: the next frame records the window as closed,
         * so a folder that has gone away stops being asked for.
         */
        void restore() {
            if (memory.wasOpen("folder")) {
                Path dir = savedFolder();
                if (dir == null) {
                    requests.add(() -> ws.status.text("Last folder is no longer there - not reopening it"));
                } else {
                    revealPath(dir);
                }
            }
            if (memory.wasOpen("terminal")) {
                requests.add(this::openTerminal);
            }
        }

        /** The remembered folder, or null if there is none, it is unreadable, or it is no longer a directory. */
        private Path savedFolder() {
            String saved = memory.shownPath("folder");
            if (saved.isBlank()) {
                return null;
            }
            try {
                Path dir = Path.of(saved);
                return java.nio.file.Files.isDirectory(dir) ? dir : null;
            } catch (java.nio.file.InvalidPathException e) {
                return null;   // the settings file is hand-editable, so this is reachable
            }
        }

        /**
         * Open the terminal where the work is: the active tab's directory, else where the editor was started.
         * MainFrame keeps its own cwd from there on — {@code cd} moves it and the prompt follows.
         */
        private void openTerminal() {
            Path start = startDir();
            terminal.show(app, start != null ? start : Path.of("").toAbsolutePath());
            ws.status.text("Terminal: MainFrame - Ctrl+` to return to it");
        }

        private void open() {
            try {
                // No filter: every file is visible. TextFile refuses what the editor can't hold (binary,
                // oversized, wrong charset), which is a better gate than hiding files by extension.
                Path picked = FileDialog.open(window, null, startDir()).orElse(null);
                if (picked != null) {
                    loadInto(picked);
                }
            } catch (RuntimeException e) {
                ws.status.text("Open failed: " + e.getMessage());
            }
        }

        private void openFolder() {
            try {
                Path dir = FileDialog.pickFolder(window, startDir()).orElse(null);
                if (dir == null) {
                    return;
                }
                folder.show(app, dir);
                ws.status.text("Folder: " + dir);
            } catch (RuntimeException e) {
                ws.status.text("Open folder failed: " + e.getMessage());
            }
        }

        /** Load {@code picked} into a tab: switch to it if already open, else reuse an empty untitled or add. */
        private void loadInto(Path picked) {
            try {
                int existing = ws.indexOf(picked);
                if (existing >= 0) {
                    ws.tabs.select(existing);
                    ws.status.text("Already open: " + picked.getFileName());
                    return;
                }
                TextFile.Loaded loaded;
                try {
                    loaded = TextFile.load(picked);
                } catch (TextFile.Unsupported e) {
                    // Refused, not failed: no tab is touched and the reason is shown.
                    ws.status.text("Can't open " + picked.getFileName() + ": " + e.getMessage());
                    return;
                }
                EditorTab tab = ws.active();
                if (tab != null && tab.file == null && tab.editor.text().isEmpty()) {
                    // An empty untitled tab is a placeholder, not content — load into it instead of beside it.
                    tab.file = picked;
                    tab.crlf = loaded.crlf();
                    tab.editor.text(loaded.text());
                    tab.savedText = loaded.text();
                    ws.retitleActive();
                } else {
                    ws.newTab(loaded.text(), picked, loaded.crlf());
                }
                String note = loaded.notes().isEmpty() ? "" : " (" + String.join("; ", loaded.notes()) + ")";
                ws.status.text("Opened " + picked + note);
            } catch (RuntimeException | java.io.IOException e) {
                ws.status.text("Open failed: " + e.getMessage());
            }
        }

        private void save() {
            EditorTab tab = ws.active();
            if (tab == null) {
                return;
            }
            if (tab.file == null) {
                saveAs();
                return;
            }
            write(tab, tab.file);
        }

        private void saveAs() {
            EditorTab tab = ws.active();
            if (tab == null) {
                return;
            }
            try {
                FileDialog.save(window, FILTERS, startDir(), tab.title()).ifPresent(target -> write(tab, target));
            } catch (RuntimeException e) {
                ws.status.text("Save failed: " + e.getMessage());
            }
        }

        /**
         * Stand between the user and losing work: the main window is closing, and closing it is quitting.
         *
         * <p>The handler runs on a worker thread and the window stays open, live and drawing until the request is
         * answered — which is what lets the answer come from a dialog rather than from a guess. Every path answers
         * it exactly once, including the one where the user dismisses the dialog without choosing.
         */
        void guardClose(CloseRequest request) {
            List<EditorTab> unsaved = ws.unsaved();
            if (unsaved.isEmpty()) {
                request.proceed();
                return;
            }
            Modals.show(Modal.of("Unsaved changes", unsavedMessage(unsaved))
                    .defaultButton("Save all", () -> requests.add(() -> saveAllThenClose(request)))
                    .button("Discard", request::proceed)
                    .cancelButton("Cancel", request::cancel));
        }

        private static String unsavedMessage(List<EditorTab> unsaved) {
            StringBuilder sb = new StringBuilder(unsaved.size() == 1
                    ? "One document has changes that are not on disk:\n\n"
                    : unsaved.size() + " documents have changes that are not on disk:\n\n");
            for (EditorTab tab : unsaved) {
                sb.append("    ").append(tab.describe()).append('\n');
            }
            return sb.append("\nSave all closes after writing every one of them. Discard closes now and "
                    + "loses those edits.").toString();
        }

        /**
         * GUI thread: save every changed document, then answer the close request with what actually happened.
         *
         * <p>It runs from the request queue because a never-saved document needs the native save dialog, which is
         * modal and belongs to this thread. Any document that does not land — a cancelled dialog, an unwritable
         * path — cancels the close: the user asked to save everything, so quitting anyway would be exactly the
         * loss the question existed to prevent.
         */
        private void saveAllThenClose(CloseRequest request) {
            for (EditorTab tab : ws.unsaved()) {
                if (!saveTab(tab)) {
                    request.cancel();
                    ws.status.text("Still open: " + tab.describe() + " was not saved");
                    return;
                }
            }
            request.proceed();
        }

        /** Save one tab wherever it belongs, asking for a path if it has never had one. */
        private boolean saveTab(EditorTab tab) {
            if (tab.file != null) {
                return write(tab, tab.file);
            }
            // Selecting it first is not cosmetic: the save dialog's start directory and suggested name come from
            // the active tab, so a Save As for a tab the user cannot see would be labelled from another one.
            ws.tabs.select(ws.open.indexOf(tab));
            try {
                Path target = FileDialog.save(window, FILTERS, startDir(), tab.title()).orElse(null);
                return target != null && write(tab, target);
            } catch (RuntimeException e) {
                ws.status.text("Save failed: " + e.getMessage());
                return false;
            }
        }

        /** Write {@code tab} to {@code target}. Returns false if it did not land, so a caller can stop. */
        private boolean write(EditorTab tab, Path target) {
            try {
                // The exact text that goes to disk is the text this tab is now clean against — read once, so an
                // edit arriving between the write and the snapshot cannot be mistaken for saved.
                String text = tab.editor.text();
                java.nio.file.Files.write(target, TextFile.encode(text, tab.crlf));
                tab.file = target;
                tab.savedText = text;
                ws.retitleActive();
                ws.status.text("Saved " + target);
                return true;
            } catch (java.io.IOException e) {
                ws.status.text("Save failed: " + e.getMessage());
                return false;
            }
        }

        private Path startDir() {
            EditorTab tab = ws.active();
            return tab != null && tab.file != null ? tab.file.getParent() : null;
        }

        /** Main window gone: stop MainFrame's job thread before the process starts tearing down. */
        @Override
        public void close() {
            terminal.close();
        }
    }

    private TextEditorApp() {
    }
}
