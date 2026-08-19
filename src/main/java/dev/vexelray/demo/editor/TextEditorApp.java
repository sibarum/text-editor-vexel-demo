package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
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

    /** Window and capture size, in the engine's logical coordinates. */
    private static final int W = 800;
    private static final int H = 560;

    private static final Color BG = Color.rgb(0x11141b);
    private static final Color PANEL = Color.rgb(0x1b2130);
    private static final Color LINE = Color.rgb(0x2b3346);
    private static final Color DIM = Color.rgb(0x93a0b4);

    private static final String UNTITLED = "untitled.txt";
    private static final String WELCOME =
            "Welcome to the deceptively simple text editor.\n\n"
                    + "Word wrap, line numbers, selection, cut/copy/paste, and caret-follow scrolling "
                    + "all come from the multiline TextField widget. Start typing.";

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        Gui gui = new Gui();
        gui.minSize(Length.em(30), Length.em(20));
        Workspace ws = new Workspace(gui);
        zoomShortcuts(gui);

        if (args.length >= 1 && args[0].equals("--capture")) {
            GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, args.length >= 2 ? args[1] : "text-editor.png");
            System.out.println("captured");
            return;
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        try (Tactroller input = openInput();
             GuiApp app = new GuiApp("Text Editor", W, H);
             Clipboard clipboard = openClipboard(gui)) {
            attachInput(input, app);
            FileActions files = new FileActions(gui, ws, app);
            files.shortcuts();
            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
            app.run(gui, maxFrames, () -> {
                pump(bridge);
                files.drain();
            });
        }
        gui.close();
        System.out.println("clean shutdown");
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
    private static Clipboard openClipboard(Gui gui) {
        try {
            Clipboard clip = Clipboard.open();
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
            return clip;
        } catch (ClipboardException e) {
            System.out.println("clipboard unavailable (" + e.getMessage() + "); cut/copy/paste use in-memory buffer");
            return null;
        }
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

        EditorTab(TextField editor, Highlighter highlighter, Node body) {
            this.editor = editor;
            this.highlighter = highlighter;
            this.body = body;
        }

        String title() {
            return file != null ? file.getFileName().toString() : UNTITLED;
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
        final List<EditorTab> open = new ArrayList<>();

        Workspace(Gui gui) {
            this.gui = gui;
            this.tabs = new Tabs(gui);
            this.status = gui.text("Ctrl+O open - Ctrl+Shift+O folder - Ctrl+S save - Ctrl+Shift+S save as - "
                            + "Ctrl+N new - Ctrl+W close - Ctrl+Tab next tab")
                    .width(Length.FILL).height(Length.rem(1.75f))
                    .textSize(Length.rem(0.875f)).textColor(DIM)
                    .align(dev.vexelray.text.TextLayout.HAlign.LEFT, dev.vexelray.text.TextLayout.VAlign.MIDDLE);

            Node root = gui.column().width(Length.FILL).height(Length.FILL)
                    .padding(Length.dp(16)).gap(Length.rem(0.625f))
                    .children(tabs.node(), status);
            gui.root().background(BG).children(root);

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
                    .background(PANEL).corner(Length.ZERO, Length.rem(0.75f))
                    .lit(true).elevation(Length.rem(1))
                    .padding(Length.dp(12))
                    .children(editor.node());
            EditorTab tab = new EditorTab(editor, new Highlighter(gui, editor), body);
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
     * {@link TreeView}, opened via {@link GuiApp#requestPopup}. The popup gets its own input backend attached
     * to its own window handle, bridged onto its own bus — so both windows take focus and input from the OS
     * like one application, while every model change (opening a file into a tab) crosses to the main window
     * through {@link FileActions}' request queue on the one shared thread.
     *
     * <p>All methods run on the main thread: {@code show}/{@code setFolder} from the drain, the two popup
     * callbacks from the frame loop, {@code pump} from beforeFrame.
     */
    private static final class FolderWindow {
        private final java.util.function.Consumer<Path> openFile;
        private final Gui gui = new Gui();
        private final Node label;
        private final Node column;
        private TreeView<Path> tree;
        private Tactroller input;
        private TactrollerInputBridge bridge;
        private boolean shown;

        FolderWindow(java.util.function.Consumer<Path> openFile) {
            this.openFile = openFile;
            this.label = gui.text("")
                    .width(Length.FILL).height(Length.rem(1.5f))
                    .textSize(Length.rem(0.875f)).textColor(DIM)
                    .align(dev.vexelray.text.TextLayout.HAlign.LEFT, dev.vexelray.text.TextLayout.VAlign.MIDDLE);
            this.column = gui.column().width(Length.FILL).height(Length.FILL)
                    .padding(Length.dp(12)).gap(Length.dp(6))
                    .children(label);
            gui.root().background(BG).children(column);
        }

        /** Show {@code folder}, opening the window on the next frame if it is not already up. */
        void show(GuiApp app, Path folder) {
            setFolder(folder);
            if (!shown) {
                shown = true;
                app.requestPopup("Files", 340, 560, gui, this::attachInput, this::onClosed);
            }
        }

        private void setFolder(Path folder) {
            if (tree != null) {
                tree.node().remove();
                tree.close();
            }
            Path name = folder.getFileName();
            label.text(name != null ? name.toString() : folder.toString());
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

        /** Attach a second input backend to the popup's own window handle, feeding this Gui's bus. */
        private void attachInput(long hwnd) {
            try {
                input = Tactroller.open();
                input.attach(NativeWindow.ofHwnd(hwnd));
                input.setCoordinateSpace(CoordinateSpace.CLIENT);
                bridge = new TactrollerInputBridge(input, gui.bus());
            } catch (BackendException e) {
                System.out.println("folder window input unavailable (" + e.getMessage() + ")");
                closeInput();
            }
        }

        private void onClosed() {
            closeInput();
            shown = false;
        }

        private void closeInput() {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception e) {
                    // best effort — the backend is going away regardless
                }
                input = null;
                bridge = null;
            }
        }

        /** Poll the popup's input, if the window is up — called once per frame alongside the main pump. */
        void pump() {
            if (bridge != null) {
                try {
                    bridge.pump();
                } catch (BackendException e) {
                    // Transient poll failure — drop this frame's input rather than tear down the loop.
                }
            }
        }
    }

    /**
     * File actions over the native OS dialogs in vexelray-gui-nfd, operating on the workspace's active tab.
     *
     * <p>NFD dialogs are modal and must run on the GUI thread, but shortcut handlers run on worker threads —
     * so the shortcuts only enqueue, and {@link #drain()} services one request per frame from the app's
     * beforeFrame hook. Tab-structure changes ride the same queue so they are ordered with the file I/O.
     */
    private static final class FileActions {
        private static final List<FileDialog.Filter> FILTERS =
                List.of(FileDialog.Filter.of("Text files", "txt", "md", "java", "json"));

        private final Gui gui;
        private final Workspace ws;
        private final GuiApp app;
        private final long window;
        private final FolderWindow folder;
        private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> requests =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        FileActions(Gui gui, Workspace ws, GuiApp app) {
            this.gui = gui;
            this.ws = ws;
            this.app = app;
            this.window = app.windowHandle();
            this.folder = new FolderWindow(this::openPath);
        }

        /** Enqueue opening {@code file} into a tab — how the folder window's tree reaches the editor. */
        void openPath(Path file) {
            requests.add(() -> loadInto(file));
        }

        void shortcuts() {
            gui.shortcut(Key.O, () -> requests.add(this::open), Modifier.CONTROL);
            gui.shortcut(Key.O, () -> requests.add(this::openFolder), Modifier.CONTROL, Modifier.SHIFT);
            gui.shortcut(Key.S, () -> requests.add(this::save), Modifier.CONTROL);
            gui.shortcut(Key.S, () -> requests.add(this::saveAs), Modifier.CONTROL, Modifier.SHIFT);
            gui.shortcut(Key.N, () -> requests.add(() -> ws.newTab("", null, false)), Modifier.CONTROL);
            gui.shortcut(Key.W, () -> requests.add(ws::closeActive), Modifier.CONTROL);
            gui.shortcut(Key.TAB, () -> requests.add(() -> ws.cycle(+1)), Modifier.CONTROL);
            gui.shortcut(Key.TAB, () -> requests.add(() -> ws.cycle(-1)), Modifier.CONTROL, Modifier.SHIFT);
        }

        /** GUI thread, once per frame. One request at a time — each may block on a modal dialog. */
        void drain() {
            folder.pump();
            Runnable r = requests.poll();
            if (r != null) {
                r.run();
            }
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

        private void write(EditorTab tab, Path target) {
            try {
                java.nio.file.Files.write(target, TextFile.encode(tab.editor.text(), tab.crlf));
                tab.file = target;
                ws.retitleActive();
                ws.status.text("Saved " + target);
            } catch (java.io.IOException e) {
                ws.status.text("Save failed: " + e.getMessage());
            }
        }

        private Path startDir() {
            EditorTab tab = ws.active();
            return tab != null && tab.file != null ? tab.file.getParent() : null;
        }
    }

    private TextEditorApp() {
    }
}
