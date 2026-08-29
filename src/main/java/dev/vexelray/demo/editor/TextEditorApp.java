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
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.nfd.FileDialog;
import dev.mainframe.gui.app.ProjectScope;
import dev.mainframe.gui.console.Console;
import dev.mainframe.gui.console.ConsoleSpec;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.Ramp;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.os.Decorations;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;
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
import java.util.Comparator;
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

    /**
     * How long a <b>transition</b> takes here: a change between two states, both of which the eye can look at
     * and check. The tab change and the file tree's expanding rows are both this, and they are this length
     * because one application has one tempo — a window where every change is timed differently reads as several
     * programs sharing a frame.
     *
     * <p>Short enough that Ctrl+Tab held down never has to wait for it, which is the ceiling on this number.
     */
    private static final Dur TRANSITION = Dur.ms(160);

    /**
     * How long a <b>cue</b> takes: a one-shot mark saying something happened, which — unlike a transition — has
     * no end state at all. It exists only in the middle, so the thing it has to clear is not "did it arrive"
     * but "was it noticed", and that threshold is the higher of the two. {@code Cue.scanline}'s own
     * documentation puts it at about 150ms of visible motion, which a 160ms cue does not have once its attack
     * and release are taken out of it.
     *
     * <p>So the two numbers differ on purpose, and the departure is the point rather than an oversight: the
     * tempo above is what a change is worth, and this is what being seen costs.
     */
    private static final Dur CUE = Dur.ms(240);

    /**
     * How long a cue takes when it is reporting a <b>failure</b>. Longer than {@link #CUE} for the reason
     * {@link Cues#play(Node, Cue, Ramp)} exists: an acknowledgement only has to be noticed, and a refusal is
     * asking to be read — the status line beneath it has just been given a sentence explaining what did not
     * happen, and a mark that has faded before the eye arrives at the words has marked nothing.
     */
    private static final Dur ALERT = Dur.ms(400);

    /**
     * How far the status line's new text rises into place, in em — so it scales with zoom, and so the distance
     * stays the same fraction of the line's own height at every size.
     *
     * <p>Deliberately under half a line. The rise is there to say <em>this text is new</em> to someone who was
     * looking somewhere else; anything far enough to read as travel would also be far enough to read as the
     * layout moving, and the layout has not moved.
     */
    private static final float STATUS_RISE_EM = 0.35f;

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
        // The frame clock. Attached before the UI is built, because a widget that animates is handed its timing
        // at construction, and ticked from the run loop's beforeFrame hook below -- one tick per presented frame.
        KronoGui krono = KronoGui.attach(gui);
        Workspace ws = new Workspace(gui, krono);
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
        // One Settings for the whole application, shared rather than opened twice: two instances over the same
        // file each hold their own copy of it, so the second one to save would drop whatever the first had added.
        Settings settings = Settings.open("text-editor");
        WindowMemory memory = new WindowMemory(settings);
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
            // The editor is this application's main window, so that is what a dialog parents to, and it already
            // exists by the time anything can ask.
            FileActions files =
                    new FileActions(gui, ws, app, memory, true, krono, app::windowHandle, null);
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
            FpsProbe probe = new FpsProbe(krono.kron(), app::postWake, () -> gui.root().opacity(1f), gui.handlers());
            if (maxFrames <= 0) {
                // Render on demand: block until the kernel says a frame is due. Only on an uncapped run --
                // a frame cap is a script, and blocking would make N frames of a still window take forever.
                app.pacing(() -> krono.kron().sleepTimeout().nanos());
                app.idleRefresh(200_000_000L)   // 5 Hz floor while focused: a missed wake is late, never lost
                   .maxFrameRate(16_666_666L);  // 60 Hz ceiling while animating
            }
            try {
                app.run(gui, maxFrames, () -> {
                    pump(bridge);
                    files.drain();
                    // The clock after the queue, so a tab change serviced by drain() starts its crossfade on the
                    // same frame that selected it rather than a frame later. The tick returns with its batch
                    // complete, so the opacities this frame's motion produced are on the bus before Gui.frame
                    // reconciles them -- the frame that presents a value is the frame that computed it.
                    krono.tick();
                    memory.poll();
                    probe.sample();
                });
            } finally {
                probe.report("text editor, idle");
                // Drop any dialog still queued: an application on its way out must not be held up by a question
                // there is nobody left to answer.
                dialogs.close();
                files.close();
                memory.save();
            }
        }
        krono.close();   // the clock outlives the window but not the process: closed with the GUI it drove
        gui.close();
        System.out.println("clean shutdown");
    }

    /**
     * The MainFrame console as this application wants it.
     *
     * <p>Said once and built twice — the real window and the headless capture below — because the whole point of
     * a spec is that "which console this is" is a value rather than a constructor call scattered about. Note how
     * little of it there is: the console is a component now, and everything on here is a place this application
     * genuinely differs from any other that embeds one.
     */
    private static ConsoleSpec.Builder consoleSpec(WindowMemory memory) {
        return ConsoleSpec.builder()
                // This window has been called the terminal since before it was reusable, and the name is also
                // the settings key its placement is stored under -- renaming it would move everyone's window.
                .windowName("terminal")
                .title("Terminal")
                .memory(memory)
                .project(() -> projectOf(memory))
                .status(STATUS);
    }

    /** The bottom line, in this application's words: it has an editor menu, and the console does not know that. */
    private static final ConsoleSpec.Status STATUS = new ConsoleSpec.Status() {
        @Override
        public String ready() {
            return "Ready.  right-click for the editor   help lists every command"
                    + "   Ctrl+D closes this display";
        }

        @Override
        public String running() {
            return ConsoleSpec.Status.DEFAULT.running();
        }

        @Override
        public String answering() {
            return ConsoleSpec.Status.DEFAULT.answering();
        }
    };

    /**
     * Render the terminal window headlessly: start MainFrame, run a few real lines against the real filesystem,
     * let the per-frame flush publish them, and write the PNG. {@code GuiApp.capture} takes one tree, so the
     * terminal needs its own entry point — and this one doubles as a smoke test of the whole path with no GPU
     * window and no keyboard.
     */
    private static void captureTerminal(String path) throws Exception {
        // No window is opened here, so the memory is never asked for a placement and never written to.
        Settings settings = Settings.open("text-editor");
        WindowMemory unused = new WindowMemory(settings);
        // The editor's own commands are registered here too, pointed at nothing: the capture is a smoke test of
        // the command surface the real window has, and a surface missing edit and reveal is not that surface.
        try (Console terminal = new Console(consoleSpec(unused)
                .app(new EditorApp(f -> { }, d -> { }, () -> { }))
                .build())) {
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
        // No clock either: a capture is one frame, and a tree mid-expansion is not what the still is of.
        FolderWindow folder = new FolderWindow(f -> { }, d -> { }, unused, null);
        folder.setFolder(Path.of("").toAbsolutePath());
        Color page = folder.gui().theme().color(Role.PAGE);
        GuiApp.capture(folder.gui(), FolderWindow.DEFAULT_W, FolderWindow.DEFAULT_H,
                page.r(), page.g(), page.b(), path);
        System.out.println("captured " + path);
    }

    /**
     * The file a project's own settings go in. Dotted, so it sorts out of the way of the project's own files,
     * and meant to be committed — so it records a <em>name</em> for something and never a path to it.
     */
    static final String PROJECT_FILE = ".vtext";

    /**
     * The project, as of right now: the folder the file tree is showing.
     *
     * <p>Read on every call rather than captured, because it changes -- Ctrl+Shift+O picks a different one, and a
     * shell that had been told the old one at startup would go on writing that project's {@code .vtext}. The
     * folder outlives the file-tree window (closing the drawer does not close the project), which is why this
     * reads the remembered path rather than asking the window whether it is open.
     *
     * <p>What "a project" means is this application's decision, which is why the console takes one of these
     * rather than working one out: an editor's project is the folder it is showing, and something else's would
     * be something else.
     */
    static ProjectScope projectOf(WindowMemory memory) {
        String shown = memory.shownPath("folder");
        return shown == null || shown.isBlank()
                ? ProjectScope.none()
                : ProjectScope.at(Path.of(shown), PROJECT_FILE);
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

    /**
     * The editor as a window somebody else owns.
     *
     * <p>{@link #main} is the editor as its own program: it makes the frame loop, takes the main window, and
     * opens a MainFrame terminal beside itself on Ctrl+`. This is the same editor the other way round — MainFrame
     * is the program, the shell is the main window, and the editor is one of the things it opens. Same tabs, same
     * highlighter, same file dialogs, same file tree; what differs is who owns the loop and who is ticking.
     *
     * <p>So it is a nested class rather than a file of its own. Everything it needs — {@link Workspace},
     * {@link FileActions}, {@link FolderWindow}, the sizes — is private to {@link TextEditorApp} and stays that
     * way, and the two arrangements cannot drift apart because they are built out of the same parts. See
     * {@link Editor}, which is what MainFrame actually plugs in.
     *
     * <p><b>The documents outlive the window.</b> The tree belongs to this object, so closing the editor
     * releases an OS window and leaves the tabs, the text and the zoom where they were: reopening it is the
     * same session, not a new one. That is the console's own arrangement too.
     *
     * <p>All methods run on the frame loop.
     */
    public static final class Window {

        /** The name this window is opened, raised and remembered under. */
        private static final String KEY = "editor";

        private final WindowMemory memory;
        private final Gui gui = new Gui();
        private final KronoGui krono;
        private final Workspace ws;
        /**
         * The file tree, built now rather than with the rest of it, because the host binds the OS clipboard on
         * every window it is told about and it asks once, before anything has been opened. A tree whose Gui
         * turned up later would be the one window on the desk whose "Copy path" went nowhere.
         */
        private final FolderWindow folder;

        /**
         * Built on the first {@link #show}, not in the constructor, because it needs the application — the
         * dialogs and the tree have to be opened onto something, and there is nothing until a host turns up.
         */
        private FileActions files;
        private AppWindow handle;

        /**
         * Told about every file that has just been opened, so whoever is hosting this editor can decide whether
         * their shell has anything to say about it — see {@link #onOpened}.
         *
         * <p>Held here as well as handed on, because it is set before the first {@link #show} as often as after:
         * {@code Editor} wires it while the console's commands are being registered, which is long before there
         * is a window to open onto.
         */
        private volatile java.util.function.Consumer<Path> opened = file -> { };

        /**
         * Build the editor, without opening anything.
         *
         * @param memory where this window's placement, size and zoom are kept — shared with whatever else is on
         *               this desk, because a window memory is one file with one key per window
         */
        public Window(WindowMemory memory) {
            this.memory = memory;
            gui.theme(Palettes.EDITOR);
            gui.minSize(Length.em(30), Length.em(22));
            this.krono = KronoGui.attach(gui);
            this.ws = new Workspace(gui, krono);
            // Method references on this, so the tree can be wired before the thing it reaches is built: both
            // of these queue onto FileActions once there is one, and drop the request until then.
            this.folder = new FolderWindow(this::openPath, this::revealPath, memory, krono);
            zoomShortcuts(gui);
        }

        /** This window's Gui, so the host can bind its clipboard here as it does on every other window. */
        public Gui gui() {
            return gui;
        }

        /**
         * Be told when a file has been opened in a tab.
         *
         * <p>What a host does with that is theirs — this editor has no opinion about which files are interesting.
         * A host that recognises a kind of file answers with a line of MainFrame and submits it into the console
         * it already owns, so what happened is in the scrollback rather than in a dialog that has been dismissed.
         *
         * <p>Frame loop, on the frame the tab appeared.
         */
        public void onOpened(java.util.function.Consumer<Path> listener) {
            this.opened = listener == null ? file -> { } : listener;
            if (files != null) {
                files.onOpened(this.opened);
            }
        }

        /** Every tree this editor presents in a window of its own: the documents, and the file tree. */
        public List<Gui> windows() {
            return List.of(gui, folder.gui());
        }

        /** Whether the editor window is up right now. */
        public boolean open() {
            return handle != null && handle.open();
        }

        /**
         * Open the editor on {@code app}, or raise it if it is already up.
         *
         * <p>Asking for the editor has to mean <em>the</em> editor, which is why this is one {@code show()}
         * however many times it is called.
         */
        public void show(GuiApp app) {
            if (files == null) {
                // No console of our own: MainFrame is the console here, so the editor must not open one, and the
                // apps that would have gone in it are plugged into the host's console instead.
                // The dialog owner is read late — this window may not exist yet on the frame this runs on.
                files = new FileActions(gui, ws, app, memory, false, krono, this::ownerHandle, folder);
                files.onOpened(opened);
                files.shortcuts();
                files.restore();
            }
            if (handle == null) {
                handle = app.window(KEY, () -> WindowSpec
                        .of(memory.config(KEY, "Text Editor", W, H).decorations(Decorations.CLIENT), gui)
                        .onCreated(this::onCreated)
                        .onClosed(this::onClosed)
                        // Closing the editor is not quitting here, but it can still lose work, so the same gate
                        // the standalone editor puts in front of quitting goes in front of this close.
                        .onCloseRequest(files::guardClose));
            }
            handle.show();
        }

        /**
         * Where a modal file dialog parents: this window while it is up, and the desktop otherwise.
         *
         * <p>Zero is the right answer rather than a failure — a dialog raised by {@code edit} on a frame where
         * the window has not been created yet is still a dialog that should appear.
         */
        private long ownerHandle() {
            return handle != null && handle.open() ? handle.window().osHandle() : 0L;
        }

        /**
         * Frame loop, once per frame, open or not. The queue is drained whether the window is up because a
         * request made just before a close still has to land somewhere, and the clock is ticked because the
         * file tree animates and outlives this window.
         */
        public void tick() {
            if (files != null) {
                files.drain();
            }
            krono.tick();
        }

        /** Open {@code file} in a tab. Enqueued, so it is safe from any thread. */
        public void openPath(Path file) {
            if (files != null) {
                files.openPath(file);
            }
        }

        /** Point the file tree at {@code dir}. Enqueued, so it is safe from any thread. */
        public void revealPath(Path dir) {
            if (files != null) {
                files.revealPath(dir);
            }
        }

        /**
         * The window exists, and its input is already attached and pumping — the host did that from the factory
         * it gave the framework. What is left is what only this window knows: which window its own title bar
         * commands, where it should be, and what it was zoomed to.
         */
        private void onCreated(dev.vexelray.os.NativeWindow created) {
            ws.titleBar.controls(WindowControls.of(created));
            if (memory.maximized(KEY)) {
                created.maximize();
            } else {
                memory.restoreBounds(KEY, created, W, H);
            }
            // Watched with its tree, so the UI zoom is remembered too: Ctrl+= is the same kind of decision as
            // dragging the window bigger, and losing it on quit is the same loss.
            memory.watch(KEY, created, gui);
        }

        /** The window is gone; the editor is not. What was recorded last stands. */
        private void onClosed() {
            memory.forget(KEY);
            ws.titleBar.controls(WindowControls.NONE);
        }

        /** Stop the file tree and anything else this editor owns. The host's console is not ours to close. */
        public void close() {
            if (files != null) {
                files.close();
            }
            krono.close();
        }
    }

    /** One open document: its widgets, its highlighter, and what saving must know about its file. */
    static final class EditorTab {
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

        /**
         * Declare {@code text} to be what is on disk: the snapshot {@link #dirty()} compares against, and the
         * position the field's undo history calls saved.
         *
         * <p>Both, because they answer different questions. The snapshot answers the editor's â whether the
         * bytes differ from the file â and is deliberately not a flag, for the reason {@link #savedText} gives.
         * {@code History.mark()} answers the one the field and anything subscribed to its
         * {@code history().status()} asks, and it is a position rather than a flag for a related reason: undoing
         * back past a save is dirty again, and redoing up to it is clean again. Setting only the snapshot left
         * the history reporting dirty forever after the first keystroke of a session, which is what a menu item
         * or a dirty dot wired to it would have repeated.
         *
         * <p>Harmless where the text was replaced with {@code editor.text(...)} rather than typed: that clears
         * the history, so this marks the empty position it already stands at.
         */
        void savedAs(String text) {
            savedText = text;
            editor.history().mark();
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
     * {@code open} list and the widget's tab order are kept in lockstep — {@code open.get(i)} is the document
     * on tab {@code i}, and every index this class computes assumes it.
     *
     * <p>Most structural changes are asked for here and serviced on the GUI thread by
     * {@link FileActions#drain()}. One is not: the tab bar puts a <b>Close</b> item on every header's context
     * menu itself, which removes a tab straight from the handler lane without passing through the queue. That
     * arrives at {@link #tabRemoved}, and it is why the list is guarded rather than merely thread-confined —
     * the lock is the bar's own monitor, because the invariant being protected spans both structures and a
     * second lock taken in the other order would be a deadlock waiting for a right click during a frame.
     */
    static final class Workspace {
        final Gui gui;
        final Tabs tabs;
        final Node status;
        final TitleBar titleBar;
        final List<EditorTab> open = new ArrayList<>();

        /**
         * Timing for the status line's arrival, or null for no motion at all — the reduced-motion path, and what
         * a clock that is never ticked has to be given rather than a ramp that would deliver its 0 and stop.
         * That is not hypothetical: {@code ramp} delivers 0 before any time passes, so a status line faded in by
         * a stalled clock would be a status line at zero opacity holding the message nobody can read.
         */
        private final Ramp arrival;

        /**
         * One-shot feedback on this window's nodes. {@link Cues#none()} where there is no clock, which is the
         * honest collapse — a cue exists only in the middle, so "instantly" means "not at all".
         *
         * <p>Package-private, like {@link #tabs} and {@link #open} beside it and for the same reason: a cue is
         * played and then forgotten, so the only way a test can ask whether the right node was marked is to ask
         * this. {@link Cues#active()} is also the one number that says the class is at rest.
         */
        final Cues cues;

        /**
         * The longer timing {@link #warn} plays its ring on. Null alongside a {@link Cues#none()}, which plays
         * nothing whatever ramp it is handed — so the two travel together and neither has to check the other.
         */
        private final Ramp alert;

        /**
         * Which {@link #arrive} owns the status line. A second message during the first one's fade supersedes
         * it rather than fighting it for the node: two ramps writing one opacity every frame is a race settled
         * by whichever landed last, and the loser's settle would then snap the winner's fade to its end a
         * duration later. This is the same rule {@link Cues} keeps for overlays, kept here for the two
         * properties a cue is not allowed to touch.
         *
         * <p>Atomic rather than a plain int because it is the identity in that rule and not merely a counter,
         * even though every {@code say} here arrives on the GUI thread — everything that reports enqueues.
         */
        private final java.util.concurrent.atomic.AtomicInteger saying =
                new java.util.concurrent.atomic.AtomicInteger();

        Workspace(Gui gui, KronoGui krono) {
            this.gui = gui;
            this.tabs = new Tabs(gui);
            this.arrival = krono == null ? null
                    : (progress, done) -> krono.ramp(TRANSITION, Ease.LINEAR, progress, done);
            this.cues = krono == null ? Cues.none()
                    : new Cues((progress, done) -> krono.ramp(CUE, Ease.LINEAR, progress, done));
            this.alert = krono == null ? null
                    : (progress, done) -> krono.ramp(ALERT, Ease.LINEAR, progress, done);
            // Changing tabs dissolves, with the arriving document travelling a short way in from the side the
            // selection moved toward. Tabs supplies the motion -- opacity over both pages and a translate on the
            // arriving one, the outgoing page floated over it so nothing reflows for the duration -- and
            // Kronometer supplies the time; the seam between them is a DoubleConsumer and a Runnable, so this is
            // the only line where the two meet, and leaving it out gives back the instant switch.
            //
            // Slide rather than a bare crossfade because these are documents in a bar that has an order, and a
            // displacement is what makes the content agree with that order instead of the headers merely
            // asserting it. A dissolve alone has nothing in it that moves, which is what reads as mechanical.
            //
            // LINEAR, and it has to be. Tabs eases the travel itself -- out-cubic on the displacement, which is
            // what reads as weight in something arriving at a place -- and holds the fade underneath linear,
            // because opacity has nowhere to arrive at and the eye reads it about as it is given. An out-cubic
            // ramp here (which is what this line used to pass) is 87% through by the halfway point, so the whole
            // visible part finished in the first third and the rest was a stall with nothing moving: a delay and
            // then a jump, rather than a transition. Invisible to a test that checks only the endpoints, because
            // the endpoints are perfect either way. 160ms: long enough to read as one document replacing
            // another, short enough that Ctrl+Tab held down never has to wait for it.
            //
            // Harmless under --capture, which never ticks the clock: a panel with one tab has nothing to fade
            // from, and the first tab is selected before there is a second.
            this.tabs.transition(Tabs.slide(
                    (progress, done) -> krono.ramp(TRANSITION, Ease.LINEAR, progress, done)));
            // Before the welcome tab is added, so no tab can be removed without this being in place: the bar's
            // own Close item is a removal this class never calls for, and a removal it does not see leaves
            // `open` one document longer than the bar for the rest of the session.
            this.tabs.onRemove(this::tabRemoved);
            // AUTO, not a fixed line: this line also reports what was opened or saved, and a long path wraps.
            // A fixed height clips the second line outside the padding instead of making room for it.
            this.status = gui.text("Ctrl+O open - Ctrl+Shift+O folder - Ctrl+` terminal - Ctrl+S save - "
                            + "Ctrl+N new - Ctrl+W close - Ctrl+Z undo")
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

        /**
         * Report something that happened, on the status line.
         *
         * <p><b>Why this is a method and not {@code status.text(...)}.</b> A line of text replaced in place is
         * the one kind of change a person looking somewhere else cannot notice: nothing moves, nothing appears,
         * and the only evidence that anything happened is a sentence that was not there a moment ago in a strip
         * that always has a sentence in it. Saving the same file twice, in particular, produced no observable
         * difference whatever — the second save wrote the identical string over the first. So the change gets
         * motion of its own, and every report in this application goes through here or through {@link #warn}.
         */
        void say(String message) {
            arrive(message);
        }

        /**
         * Report something that did <em>not</em> happen — a refusal or a failure.
         *
         * <p>The same arrival as {@link #say}, plus a ring: {@code Cue.ring} pulses twice rather than once,
         * which is what distinguishes a refusal from an acknowledgement at a glance, and it is an outline rather
         * than a tint, so it does not lie over the sentence it is drawing attention to. That is the whole reason
         * this is a ring and the save acknowledgement is a wash — a wash over the status line would cover the
         * explanation at exactly the moment it asked to be read.
         *
         * <p>{@link #ALERT} rather than the house cue length, for the reason stated there.
         */
        void warn(String message) {
            arrive(message);
            cues.play(status, Cue.ring(gui.theme().color(Role.DANGER)), alert);
        }

        /**
         * Acknowledge that {@code tab} has just been written to disk: a wash over the page that was saved.
         *
         * <p>Ctrl+S is the action here that gets repeated most and read least. It already reported itself on the
         * status line, but a line of text is the wrong shape for an answer to a keystroke — by the time it has
         * been read, the question it answered has been forgotten. A tint over the document says <em>this
         * one, now</em> without being read at all, which is what a save wants and what a sentence cannot do.
         *
         * <p>{@link Role#HIGHLIGHT} rather than a bare accent: it is the accent already at the alpha a wash
         * wants, and a wash takes the colour's own alpha as its peak — handed an opaque one it would blank the
         * document it is confirming, for a frame, in the middle of the confirmation.
         *
         * <p>Played on the tab's page rather than the window, and so a save-all flashes each document as it
         * lands: the ones that are not on screen paint into an overlay nobody is looking at and clear it again,
         * which costs nothing and keeps this a statement about a document rather than about the window.
         */
        void saved(EditorTab tab) {
            cues.play(tab.body, Cue.wash(gui.theme().color(Role.HIGHLIGHT)));
        }

        /**
         * Mark {@code tab}'s header as having just received a file: a single pulse of the accent around it.
         *
         * <p><b>The header, not the page.</b> The page already animates — it slides in under {@code Tabs.slide}
         * — so a mark there would be motion laid over motion saying the same thing. The header is the half of a
         * tab that does <em>not</em> move when the selection lands on it, and it is the half that is still on
         * screen for every document this did not select. That is the case worth having: {@code ls | where ext ==
         * "java" | first 3 | edit} opens three tabs and can only select the last, and without this the other two
         * arrive as headers that were silently not there a moment ago.
         *
         * <p><b>One pulse, where {@link #warn} takes two.</b> {@code Cue.ring}'s repeat is what the eye reads as
         * insistence, which is the right shape for a refusal and the wrong one for a document turning up where
         * it was asked to be. Same cue, same colour vocabulary, one difference — so the two never have to be
         * told apart by reading the status line.
         *
         * <p>Null is an ordinary answer here: the caller is often handing over {@link #active()}, which is null
         * when there is nothing open. So is a tab that has been closed between the request and this call, which
         * the bar's own Close item makes possible without passing through the queue — the index is resolved
         * under the same lock every other index here is, and a tab that has gone is simply not marked.
         */
        void arrived(EditorTab tab) {
            if (tab == null) {
                return;
            }
            Node header;
            synchronized (tabs) {
                int i = open.indexOf(tab);
                if (i < 0) {
                    return;
                }
                header = tabs.header(i);
            }
            // Outside the lock: playing a cue is the bar's business only in that the node is one of its, and
            // Cues is safe from any thread. Nothing here needs the two structures to agree any more.
            cues.play(header, Cue.ring(gui.theme().color(Role.ACCENT), 1));
        }

        /**
         * Put {@code message} on the status line and bring it in: a fade with a short rise under it.
         *
         * <p><b>One ramp, two curves, and it has to be.</b> The fade is taken linear because opacity has nowhere
         * to arrive at — the eye reads it about as it is given, and easing it spends most of the duration at a
         * value indistinguishable from the end. The rise is a distance being covered and is eased into its
         * stop, which is what reads as weight rather than as a slide that was switched off. So the ease is
         * applied inside the sample rather than by the ramp: that is the same division {@code Tabs.slide} makes
         * between its own travel and the fade underneath it, and the reason the ramp driving both is LINEAR.
         */
        private void arrive(String message) {
            status.text(message);
            if (arrival == null) {
                return;
            }
            int mine = saying.incrementAndGet();
            arrival.run(
                    t -> {
                        if (saying.get() != mine) {
                            return;   // superseded: a newer message owns the line and will settle it
                        }
                        status.opacity((float) t);
                        status.translate(0f, STATUS_RISE_EM * (1f - Ease.OUT_CUBIC.at((float) t)));
                    },
                    () -> {
                        if (saying.get() == mine) {
                            status.opacity(1f);
                            status.translate(0f, 0f);
                        }
                    });
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
            synchronized (tabs) {
                open.add(tab);
                tabs.add(tab.title(), body);
                tabs.select(open.size() - 1);
            }
            tab.highlighter.language(tab.title());
            return tab;
        }

        EditorTab active() {
            synchronized (tabs) {
                int i = tabs.selected();
                return i >= 0 && i < open.size() ? open.get(i) : null;
            }
        }

        /** Every open document with edits that are not on disk, in tab order. */
        List<EditorTab> unsaved() {
            List<EditorTab> out = new ArrayList<>();
            synchronized (tabs) {
                for (EditorTab tab : open) {
                    if (tab.dirty()) {
                        out.add(tab);
                    }
                }
            }
            return out;
        }

        /** Bring the tab holding {@code file} to the front; false if no tab holds it. */
        boolean showFile(Path file) {
            synchronized (tabs) {
                for (int i = 0; i < open.size(); i++) {
                    if (file.equals(open.get(i).file)) {
                        tabs.select(i);
                        return true;
                    }
                }
                return false;
            }
        }

        /**
         * Bring {@code tab} to the front, if it is still open. Resolving the index and using it under one lock
         * rather than handing one out: an index is only true of the moment it was taken, and a close between
         * the two would make this select a different document — or, for a tab that has since gone, tab 0,
         * which is what {@link Tabs#select} clamps a -1 to.
         */
        void show(EditorTab tab) {
            synchronized (tabs) {
                int i = open.indexOf(tab);
                if (i >= 0) {
                    tabs.select(i);
                }
            }
        }

        /**
         * The document on tab {@code index}, or null if there is no such tab.
         *
         * <p>What a header's context menu is built from. That menu is built on a worker thread at the moment of
         * the click, off an index the bar resolved a moment earlier, so it asks under the same lock everything
         * else does rather than trusting the number it was handed — a tab removed in between answers null here,
         * and an item aimed at a document that is no longer open is an item that comes out greyed.
         */
        EditorTab at(int index) {
            synchronized (tabs) {
                return index >= 0 && index < open.size() ? open.get(index) : null;
            }
        }

        /** True when nothing is open at all — see {@link FileActions#drain()}, which is what fixes it. */
        boolean empty() {
            synchronized (tabs) {
                return open.isEmpty();
            }
        }

        /** Re-label the active tab and re-pick its grammar — after an open-into or a save-as. */
        void retitleActive() {
            EditorTab tab;
            synchronized (tabs) {
                tab = active();
                if (tab == null) {
                    return;
                }
                tabs.title(tabs.selected(), tab.title());
            }
            tab.highlighter.language(tab.title());
        }

        /** Close the active tab. The last tab is not removed but reset to an empty untitled document. */
        void closeActive() {
            synchronized (tabs) {
                EditorTab tab = active();
                if (tab == null) {
                    return;
                }
                if (open.size() == 1) {
                    tab.file = null;
                    tab.crlf = false;
                    tab.editor.text("");
                    tab.savedAs("");
                    retitleActive();
                    say("Closed - one empty tab remains");
                    return;
                }
                // Removing from the bar is the whole action: `open` shrinks in tabRemoved, which the bar calls
                // back into. Doing it here as well would drop two documents for one close -- and leaving it
                // here instead is what let the bar's own Close item drop none.
                tabs.remove(tabs.selected());
            }
        }

        /**
         * Close every tab — <b>Close all</b> on a header's context menu. The last one is emptied rather than
         * removed, exactly as {@link #closeActive} leaves it, so the editor is never without a document to type
         * into and {@link FileActions#drain()} never has to put the floor back.
         *
         * <p>Back to front, for two reasons. Removing from the end never shifts an index this loop has still to
         * use; and {@link Tabs#remove} reselects after every removal, so front to back would walk the selection
         * through each surviving document on the way out. Neither costs a transition — a removal reselects from
         * "nothing selected", which is the one path {@code Tabs} does not animate.
         *
         * <p>One lock for the whole sweep, not one per tab: {@code open} and the bar disagree in between, and a
         * thread that read {@link #active()} halfway through this would be told about a document that is on its
         * way out.
         */
        void closeAll() {
            synchronized (tabs) {
                for (int i = open.size() - 1; i > 0; i--) {
                    tabs.remove(i);
                }
                // Whatever survives is tab 0, which is where the selection has ended up — so this is the same
                // call Ctrl+W on a last tab makes, and the emptying rule is stated in exactly one place.
                closeActive();
            }
        }

        /**
         * A tab has left the bar — by {@link #closeActive}, or by <b>Close</b> on the header's context menu,
         * which is the bar's own and reaches it without passing through this class at all. This is the single
         * place {@code open} shrinks, so both routes cost exactly one document.
         *
         * <p>Called from inside {@code Tabs.remove}, on whichever thread asked for it, with both of the bar's
         * lists already shrunk and the selection not yet moved. Holding the bar's monitor for the list edit is
         * the point: a document removed a moment before or after its tab is a window in which {@code active()}
         * answers with the wrong file.
         */
        private void tabRemoved(int index) {
            EditorTab tab;
            synchronized (tabs) {
                if (index < 0 || index >= open.size()) {
                    return;
                }
                tab = open.remove(index);
            }
            // The page left the tree with the tab, so these came back dead rather than dormant. Closing them
            // releases what the removal did not: the highlighter's pending tokenize and the field's own state.
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

        /** This window.s Gui, so the app can bind its shortcuts here as well as on the main window. */
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
            // 160ms, the same as the tab change -- one application, one tempo -- but OUT_CUBIC where that ramp is
            // LINEAR, and the difference is not a preference. There the ramp drives opacity, which has no place
            // to arrive at (Tabs eases its own travel separately); here it drives a distance being covered, and
            // decelerating into the place it stops is what reads as weight.
            //
            // One clock, two windows. Sound because nothing but a DoubleConsumer and a Runnable crosses this seam
            // -- no node and no Gui, so the clock never learns which window it is timing -- and because both
            // windows are presented by the one loop on the one thread: the tick in the main window's beforeFrame
            // hook runs before either window's frame, so the rows this window presents are the ones that tick
            // computed.
            //
            // Null under --capture-folder, which has no loop to tick it: the tree flips instantly, which is what
            // every tree here did before there was motion and what a reduced-motion path would be.
            this.motion = krono == null ? null
                    : (progress, done) -> krono.ramp(TRANSITION, Ease.OUT_CUBIC, progress, done);
            this.cues = krono == null ? Cues.none()
                    : new Cues((progress, done) -> krono.ramp(CUE, Ease.LINEAR, progress, done));
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
         * the drawer is already showing is deliberately not a rebuild — see {@link #reveal} — so if the window
         * was already forward and the row already selected, a correct reveal and a reveal that silently failed
         * looked exactly alike: nothing moved, and the only difference was a line of text in the other window.
         * This is the difference, and it is on the row rather than on the tree because which file was revealed
         * is the entire content of the answer.
         *
         * <p>One pulse of the accent, which is the tab bar's mark for the same event — a thing you asked for
         * turning up where it lives. The accent survives {@link Palettes#FILES}' hue shift untouched, so it is
         * literally the same colour in both windows rather than merely the same idea.
         *
         * <p><b>A row that is not there is not an error.</b> A tree materialises rows as folders open, so an
         * item under something collapsed has none; nor has a file the source no longer offers. Both are ordinary
         * and both are simply not marked.
         *
         * <p>The freshly-opened window is the weak case and knowingly so: the tree was built this frame and has
         * not been laid out, so a cue on it paints nothing until it has been. {@code Cues} reads the box every
         * sample rather than capturing it, so the mark appears as soon as there is somewhere to put it and is
         * merely shortened — and that is the case where a whole window arriving has already said plenty.
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
                List.of(FileDialog.Filter.of("Text files", saveExtensions()));

        /**
         * The types the save dialogs offer, taken from the highlighter's extension table rather than
         * repeated here: every format the editor can colour is a format it should be able to save as, and a
         * hand-kept second copy of that list is a copy that goes stale. Plain {@code txt} leads because the
         * OS appends the first extension when the user types a bare name; the rest are sorted, since
         * {@code Map.of}'s iteration order is not stable across runs. Open takes no filter at all, on
         * purpose - see {@link #open()}.
         */
        private static String[] saveExtensions() {
            List<String> extensions = new ArrayList<>(Highlighter.knownExtensions());
            extensions.remove("txt");
            extensions.sort(Comparator.naturalOrder());
            extensions.add(0, "txt");
            return extensions.toArray(new String[0]);
        }

        private final Gui gui;
        private final Workspace ws;
        private final GuiApp app;
        /**
         * The OS window a modal file dialog parents to, asked for at the moment it is needed rather than
         * captured once.
         *
         * <p>It has to be a supplier because the editor is not always the application's main window. When
         * MainFrame is the program the editor is a window it opens, and a window opened that way does not exist
         * until the frame loop services the request — so there is no handle to capture at construction, and a
         * dialog parented to the {@code 0} that was captured instead is a dialog the window manager is entitled
         * to put anywhere, including behind the window that asked for it.
         */
        private final java.util.function.LongSupplier owner;
        private final WindowMemory memory;
        private final FolderWindow folder;
        /**
         * This application's own terminal window, or {@code null} when MainFrame is the one hosting the editor
         * and the console is therefore already on screen as the main window.
         *
         * <p>Null rather than a no-op stand-in because the difference is real and worth being able to see: with
         * a terminal there is a Ctrl+` that opens it, and without one there is nothing to open, because the
         * shell is what the editor was launched from.
         */
        private final Console terminal;
        private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> requests =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        /** Told about a file that has just been loaded into a tab. Set by whoever owns the console. */
        private volatile java.util.function.Consumer<Path> opened = file -> { };

        /**
         * @param ownConsole whether to open a terminal of this application's own — false when the editor is
         *                   hosted by a console it did not open and must not open a second one
         * @param owner      where a modal dialog parents; see {@link #owner}
         */
        FileActions(Gui gui, Workspace ws, GuiApp app, WindowMemory memory, boolean ownConsole,
                    KronoGui krono, java.util.function.LongSupplier owner, FolderWindow folder) {
            this.gui = gui;
            this.ws = ws;
            this.app = app;
            this.owner = owner;
            this.memory = memory;
            // Handed in, or made here when nobody needed it earlier.
            //
            // The tree is a window of this editor's whose Gui exists whether or not it is on screen, and a host
            // that binds the OS clipboard per window has to be able to see it before anything has been opened —
            // which is before this object exists. So Window builds it and passes it down (see Window.windows()),
            // while the standalone editor, which hands out its own windows and can wait, lets it be made here.
            // The clock goes through to it either way: it is the only other window here with anything to
            // animate, the terminal's scrollback being text arriving rather than a widget changing shape.
            this.folder = folder != null ? folder
                    : new FolderWindow(this::openPath, this::revealPath, memory, krono);
            // The console is a component from mainframe-vexel-gui and knows nothing about editing. What makes it
            // this application's console is the EditorApp plugged into it: edit, reveal, and a window to raise.
            // Each of those reaches the editor the same way the file tree does, by enqueueing onto this one
            // queue, so a shell command that opens a tab is ordered with the modal dialogs and the tab changes.
            //
            // Skipped entirely when MainFrame is the host: there the same three commands are registered against
            // the console that is already running, by Editor, so building one here would be a second shell in a
            // second window answering to the same keys.
            this.terminal = !ownConsole ? null
                    : new Console(consoleSpec(memory)
                            .app(new EditorApp(this::openPath, this::revealPath, this::raiseEditor))
                            .build());
            // The header menu, past the Close the bar puts there itself. These two are the application's because
            // both are about what a tab *is* here that the bar cannot know: one document among others, and a file
            // somewhere on disk. Wired here rather than in Workspace for the second of those — reveal needs the
            // file tree and the frame loop, neither of which the workspace has ever heard of.
            //
            // Built on a worker thread at the moment of the click, so what it reads it reads through `at`; both
            // bodies enqueue rather than act, like the file tree's own menu, because closing tabs and opening
            // windows are the GUI thread's work and they belong in the same order as the dialogs.
            ws.tabs.onContextMenu((index, menu) -> {
                Path file = fileOn(index);
                menu.item("Close all", this::closeAllTabs)
                        .separator()
                        // Greyed rather than absent on a never-saved document: the item is what this menu offers
                        // about a tab, and a tab with nothing on disk is a reason it cannot be taken, not a
                        // different menu. Same rule the tree's own Open follows on a row that is a directory.
                        .item("Reveal in Navigator", file != null, () -> revealFile(file));
            });
        }

        /** The path of the document on tab {@code index}, or null if it has none or the tab has gone. */
        private Path fileOn(int index) {
            EditorTab tab = ws.at(index);
            return tab == null ? null : tab.file;
        }

        /** Be told when a file has been loaded into a tab. See {@link Window#onOpened}. */
        void onOpened(java.util.function.Consumer<Path> listener) {
            this.opened = listener == null ? file -> { } : listener;
        }

        /**
         * Run {@code line} in this editor's own shell, with the shell where it can be answered.
         *
         * <p>Opening the window first is the whole of it: a line submitted this way may well be a form, and a
         * form asking questions into a window nobody can see is a shell that has silently stopped responding.
         * Only usable in the arrangement where the terminal is ours — where MainFrame is the host, its window is
         * already the main one and the line goes through {@code ConsoleContext.run} instead.
         *
         * <p>This is the other half of {@link Window#onOpened}: a host wires that, and this is what it calls.
         */
        void runInShell(String line) {
            if (terminal == null) {
                return;
            }
            openTerminal();
            terminal.run(line);
        }

        /** {@code launch "editor"}: bring the main window forward. Frame loop, via the console's own queue. */
        private void raiseEditor() {
            app.window().focus();
            ws.say("Editor");
        }

        /** Enqueue opening {@code file} into a tab — how the folder window's tree reaches the editor. */
        void openPath(Path file) {
            requests.add(() -> loadInto(file));
        }

        /** Enqueue pointing the folder window at {@code dir} — MainFrame's {@code reveal}. */
        void revealPath(Path dir) {
            requests.add(() -> {
                folder.show(app, dir);
                ws.say("Folder: " + dir);
            });
        }

        /**
         * <b>Reveal in Navigator</b> on a tab: point the file tree at the folder holding {@code file} and select
         * its row. Enqueued for the same reason every other structural request is — the menu body runs on the
         * handler executor, and opening a window belongs to the frame loop.
         *
         * <p>Absolute first, because a tab's path is only as absolute as whoever opened it: the dialogs hand over
         * absolute paths, but {@code edit} takes rows off a pipe and a {@code path} cell can be relative. A
         * relative one has no parent to root the drawer at, and the row this then looks for is one the tree can
         * never have built — the tree's own items come from {@code Files.list}, which is always absolute.
         *
         * <p>A file that has gone from disk since it was opened is reported rather than revealed. Pointing the
         * drawer at it anyway is worse than saying so: {@link FolderSource} shows an unreadable directory as an
         * empty one, so the result would be an empty tree under the right name — a folder that looks emptied.
         */
        private void revealFile(Path file) {
            requests.add(() -> {
                Path target = file.toAbsolutePath().normalize();
                if (target.getParent() == null || !java.nio.file.Files.exists(target)) {
                    ws.warn("Can't reveal " + target.getFileName() + ": it is no longer on disk");
                    return;
                }
                folder.reveal(app, target);
                ws.say("Revealed " + target);
            });
        }

        /**
         * <b>Close all</b> on a tab's context menu. Every document goes, and the last tab is emptied rather than
         * removed — the floor {@link Workspace#closeAll} keeps.
         *
         * <p>Unsaved work is asked about first, and that is the one way this differs from <b>Close</b> beside it.
         * Close risks the document the user is looking at and picked out; this risks every document open,
         * including the ones they last saw an hour ago and cannot see now. So it goes through the gate quitting
         * goes through, with the same three answers over the same message naming what is at stake.
         *
         * <p>Handler thread, like every other menu body: {@link Modals} queues the dialog onto the GUI thread
         * itself, and each answer enqueues, so the closing happens where tab structure belongs.
         */
        private void closeAllTabs() {
            List<EditorTab> unsaved = ws.unsaved();
            if (unsaved.isEmpty()) {
                requests.add(this::closeAll);
                return;
            }
            Modals.show(Modal.of("Close all tabs", unsavedMessage(unsaved))
                    .defaultButton("Save all", () -> requests.add(this::saveAllThenCloseAll))
                    .button("Discard", () -> requests.add(this::closeAll))
                    .cancelButton("Cancel", () -> { }));
        }

        /** GUI thread: close every tab, and say how many that was — the bar itself is about to look untouched. */
        private void closeAll() {
            int closed = ws.tabs.count();
            ws.closeAll();
            ws.say("Closed " + closed + (closed == 1 ? " tab" : " tabs") + " - one empty tab remains");
        }

        /**
         * GUI thread: save every changed document, then close them all. The twin of {@link #saveAllThenClose},
         * and the same rule — a document that does not land stops the sweep, because writing everything is what
         * was asked for and closing anyway is exactly the loss the question was put to prevent.
         */
        private void saveAllThenCloseAll() {
            for (EditorTab tab : ws.unsaved()) {
                if (!saveTab(tab)) {
                    ws.warn("Still open: " + tab.describe() + " was not saved");
                    return;
                }
            }
            closeAll();
        }

        /** Every Gui this application presents, main window first. */
        List<Gui> windows() {
            return terminal == null
                    ? List.of(gui, folder.gui())
                    : List.of(gui, folder.gui(), terminal.gui());
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
            if (terminal != null) {
                bind(terminal.gui());
            }
        }

        private void bind(Gui g) {
            // Only where there is a terminal of our own to open. Hosted by MainFrame there is not: the shell is
            // the main window, so Ctrl+` is left unbound rather than bound to nothing — a key that swallows the
            // chord and does nothing is worse than a key the window never claimed.
            if (terminal != null) {
                g.shortcut(Key.GRAVE_ACCENT, () -> requests.add(this::toggleTerminal), Modifier.CONTROL);
            }
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
            if (terminal != null) {
                terminal.tick();
            }
            // No tabs at all is not a state the rest of this class is written for: active() is null, so Ctrl+S
            // and every other command silently does nothing, which reads as an editor that has stopped working.
            // Ctrl+W never gets there — it empties the last tab rather than removing it — but Close on the
            // header's own menu does, and the bar is entitled to remove what it was asked to. So the floor is
            // put back here, on the GUI thread, where building a document's widgets belongs.
            if (ws.empty()) {
                ws.newTab("", null, false);
                ws.say("Closed - one empty tab remains");
            }
            // Which windows are up is read from the windows themselves, every frame, rather than written when
            // they open and close: see WindowMemory.open for why that distinction is the whole feature.
            memory.open("folder", folder.isOpen());
            if (terminal != null) {
                memory.open("terminal", terminal.isOpen());
            }
            // Everything queued, not one request: a loop that parks has no next frame to leave the rest for.
            for (Runnable r; (r = requests.poll()) != null; ) {
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
                    requests.add(() -> ws.warn("Last folder is no longer there - not reopening it"));
                } else {
                    revealPath(dir);
                }
            }
            if (terminal != null && memory.wasOpen("terminal")) {
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
            ws.say("Terminal: MainFrame - Ctrl+` toggles it");
        }

        /**
         * Ctrl+`: put the terminal up, or put it away when it is already up.
         *
         * <p>The same chord both ways. A surface you can only open is one you have to reach for the mouse to be
         * rid of, and that is the whole of why an always-there terminal starts to feel like it is in the way.
         *
         * <p>Away means <em>hidden</em>, not gone: {@code dismiss} closes the display and leaves the session
         * running behind it, so the scrollback, the history and anything still working are all still there when
         * it comes back. Only the Ctrl+` binding toggles — restoring a remembered window, {@code --terminal} and
         * the menu all still mean "show it", because none of those are someone asking for the opposite.
         */
        private void toggleTerminal() {
            if (terminal.isOpen()) {
                terminal.dismiss();
                return;
            }
            openTerminal();
        }

        private void open() {
            try {
                // No filter: every file is visible. TextFile refuses what the editor can't hold (binary,
                // oversized, wrong charset), which is a better gate than hiding files by extension.
                Path picked = FileDialog.open(owner.getAsLong(), null, startDir()).orElse(null);
                if (picked != null) {
                    loadInto(picked);
                }
            } catch (RuntimeException e) {
                ws.warn("Open failed: " + e.getMessage());
            }
        }

        private void openFolder() {
            try {
                Path dir = FileDialog.pickFolder(owner.getAsLong(), startDir()).orElse(null);
                if (dir == null) {
                    return;
                }
                folder.show(app, dir);
                ws.say("Folder: " + dir);
            } catch (RuntimeException e) {
                ws.warn("Open folder failed: " + e.getMessage());
            }
        }

        /** Load {@code picked} into a tab: switch to it if already open, else reuse an empty untitled or add. */
        private void loadInto(Path picked) {
            try {
                if (ws.showFile(picked)) {
                    ws.say("Already open: " + picked.getFileName());
                    // The tab showFile just selected, which is the whole answer to "where?" — this is the path
                    // where the request produced no new document and the least happened, so it is the one that
                    // most needs pointing at.
                    ws.arrived(ws.active());
                    return;
                }
                TextFile.Loaded loaded;
                try {
                    loaded = TextFile.load(picked);
                } catch (TextFile.Unsupported e) {
                    // Refused, not failed: no tab is touched and the reason is shown.
                    ws.warn("Can't open " + picked.getFileName() + ": " + e.getMessage());
                    return;
                }
                EditorTab tab = ws.active();
                if (tab != null && tab.file == null && tab.editor.text().isEmpty()) {
                    // An empty untitled tab is a placeholder, not content — load into it instead of beside it.
                    tab.file = picked;
                    tab.crlf = loaded.crlf();
                    tab.editor.text(loaded.text());
                    tab.savedAs(loaded.text());
                    ws.retitleActive();
                } else {
                    tab = ws.newTab(loaded.text(), picked, loaded.crlf());
                }
                String note = loaded.notes().isEmpty() ? "" : " (" + String.join("; ", loaded.notes()) + ")";
                ws.say("Opened " + picked + note);
                // Both branches above end with the document on `tab`, whichever way it got there — the reused
                // placeholder is as much an arrival as the added tab, and it is the one whose header did not
                // change shape to announce itself.
                ws.arrived(tab);
                // The file is in a tab and the status line says so; only now is it true that it was opened, and
                // only the path is passed on -- what is interesting about a file is not this class's business.
                opened.accept(picked);
            } catch (RuntimeException | java.io.IOException e) {
                ws.warn("Open failed: " + e.getMessage());
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
                FileDialog.save(owner.getAsLong(), FILTERS, startDir(), tab.title()).ifPresent(target -> write(tab, target));
            } catch (RuntimeException e) {
                ws.warn("Save failed: " + e.getMessage());
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
                    ws.warn("Still open: " + tab.describe() + " was not saved");
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
            ws.show(tab);
            try {
                Path target = FileDialog.save(owner.getAsLong(), FILTERS, startDir(), tab.title()).orElse(null);
                return target != null && write(tab, target);
            } catch (RuntimeException e) {
                ws.warn("Save failed: " + e.getMessage());
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
                tab.savedAs(text);
                ws.retitleActive();
                ws.say("Saved " + target);
                // After the line, not before it: both are reports of the same event, and the wash is the one
                // that will be seen first — a cue starts painting on the frame it is played, so playing it
                // ahead of the text would put the flash on a status line still holding the previous message.
                ws.saved(tab);
                return true;
            } catch (java.io.IOException e) {
                ws.warn("Save failed: " + e.getMessage());
                return false;
            }
        }

        private Path startDir() {
            EditorTab tab = ws.active();
            return tab != null && tab.file != null ? tab.file.getParent() : null;
        }

        /**
         * Main window gone: stop MainFrame's job thread before the process starts tearing down.
         *
         * <p>Only our own. A console we were handed belongs to whoever handed it over and is theirs to close —
         * closing it from here would stop the shell that is still running when the editor window shuts.
         */
        @Override
        public void close() {
            if (terminal != null) {
                terminal.close();
            }
        }
    }

    private TextEditorApp() {
    }
}
