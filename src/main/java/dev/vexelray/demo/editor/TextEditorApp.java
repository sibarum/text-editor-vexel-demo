package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowInput;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.mainframe.gui.app.ProjectScope;
import dev.mainframe.gui.console.Console;
import dev.mainframe.gui.console.ConsoleSpec;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.Ramp;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.os.Decorations;
import sibarum.kronometer.Dur;
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
    static final int W = 800;
    /** The title bar's own height, in dp — {@code TitleBar}'s, which is the Windows caption metric. */
    static final int BAR_H = 32;
    static final int H = 560 + BAR_H;
    /**
     * The margin the page leaves around itself — and, because it is declared as such, the window's resize grip.
     *
     * <p>It is one constant used twice on purpose. The ring of dead space a window draws around its content is
     * the easiest thing on screen to aim at and the only thing on screen that did nothing, so it is handed to the
     * window manager ({@code Gui.resizeBorder}): the pointer turns into a resize pointer the moment it crosses
     * into the margin, and the edge is as wide a target as the margin is. Padding it by one value and gripping by
     * another would leave either a strip of margin that does not resize or a strip of text that does.
     */
    static final Length GUTTER = Length.dp(16);

    /**
     * How long a <b>transition</b> takes here: a change between two states, both of which the eye can look at
     * and check. The tab change and the file tree's expanding rows are both this, and they are this length
     * because one application has one tempo — a window where every change is timed differently reads as several
     * programs sharing a frame.
     *
     * <p>Short enough that Ctrl+Tab held down never has to wait for it, which is the ceiling on this number.
     */
    static final Dur TRANSITION = Dur.ms(160);

    /**
     * How long a <b>cue</b> takes: a one-shot mark saying something happened, which — unlike a transition — has
     * no end state at all. It exists only in the middle, so the thing it has to clear is not "did it arrive"
     * but "was it noticed", and that threshold is the higher of the two. {@code Cue.scanline}'s own
     * documentation puts it at about 150ms of visible motion, which a 160ms cue does not have once its attack
     * and release are taken out of it.
     *
     * <p>So this and {@link #TRANSITION} differ on purpose: the tempo above is what a change is worth, and
     * this is what being seen costs.
     */
    static final Dur CUE = Dur.ms(240);

    /**
     * How long a cue takes when it is reporting a <b>failure</b>. Longer than {@link #CUE} for the reason
     * {@link Cues#play(Node, Cue, Ramp)} exists: an acknowledgement only has to be noticed, and a refusal is
     * asking to be read — the status line beneath it has just been given a sentence explaining what did not
     * happen, and a mark that has faded before the eye arrives at the words has marked nothing.
     */
    static final Dur ALERT = Dur.ms(400);

    /**
     * How far the status line's new text rises into place, in em — so it scales with zoom, and so the distance
     * stays the same fraction of the line's own height at every size.
     *
     * <p>Deliberately under half a line. The rise is there to say <em>this text is new</em> to someone who was
     * looking somewhere else; anything far enough to read as travel would also be far enough to read as the
     * layout moving, and the layout has not moved.
     */
    static final float STATUS_RISE_EM = 0.35f;

    static final String UNTITLED = "untitled.txt";
    static final String WELCOME =
            "Welcome to the deceptively simple text editor.\n\n"
                    + "Word wrap, line numbers, selection, cut/copy/paste, and caret-follow scrolling "
                    + "all come from the multiline TextField widget. Start typing.";

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        // --terminal opens the shell window at startup instead of on Ctrl+`, so the second window can be looked
        // at (and its shutdown exercised) without a hand on the keyboard.
        boolean withTerminal = java.util.Arrays.asList(args).contains("--terminal");
        args = java.util.Arrays.stream(args).filter(s -> !s.equals("--terminal")).toArray(String[]::new);
        // --profile turns on FpsProbe. Off by default, because it is not a passive instrument: besides
        // printing a line every three seconds it deliberately pokes the loop -- a timeline post, a node
        // mutated from a worker, a handler that does nothing -- to prove each wake path is still alive.
        // Those are exactly the things worth checking, and exactly the things a shipped run should not be
        // doing to itself on a timer.
        boolean profile = java.util.Arrays.asList(args).contains("--profile");
        args = java.util.Arrays.stream(args).filter(s -> !s.equals("--profile")).toArray(String[]::new);

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
            // Kernel to host: something arrived while you were asleep. Wired here, unconditionally, because it
            // is what makes render-on-demand safe -- a parked loop has no next frame on which to notice a
            // timeline post, so without this a click that starts an animation reaches an inbox nobody looks at
            // and the window stays frozen. It is a single-slot listener, so there is exactly one call to it.
            //
            // It used to be FpsProbe's, taken as a constructor argument and installed there. That was fine
            // while the probe was unconditional and fatal the moment it was not: making the instrument
            // optional would have made the wake path optional with it.
            krono.kron().onWork(app::postWake);
            FpsProbe probe = profile
                    ? new FpsProbe(krono.kron(), () -> gui.root().opacity(1f), gui.handlers())
                    : null;
            if (maxFrames <= 0) {
                // Render on demand: block until the kernel says a frame is due. Only on an uncapped run --
                // a frame cap is a script, and blocking would make N frames of a still window take forever.
                // Every deadline this application holds, in one place - which is what the supplier is for.
                // The clock knows about animations; it does not know the window placement is 700ms from
                // being written, and a loop that parks has no next frame to discover that on.
                app.pacing(() -> Math.min(
                        krono.kron().sleepTimeout().nanos(), memory.nanosUntilSettle()));
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
                    if (probe != null) {
                        probe.sample();
                    }
                });
            } finally {
                if (probe != null) {
                    probe.report("text editor, idle");
                    probe.close();
                }
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
    static ConsoleSpec.Builder consoleSpec(WindowMemory memory) {
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
        // Blank rather than null is the whole of "nothing remembered": WindowMemory reads it out of Settings
        // with "" for a default, so there is no null to test for. This used to check for one anyway, which
        // read as though the two spellings both happened -- and FileActions.savedFolder, doing the same job
        // three hundred lines down, tests only isBlank(). One of the two had to be wrong about the contract.
        String shown = memory.shownPath(FolderWindow.KEY);
        return shown.isBlank()
                ? ProjectScope.none()
                : ProjectScope.at(Path.of(shown), PROJECT_FILE);
    }

    static void zoomShortcuts(Gui gui) {
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
     * <p>Said once for every window rather than per window. The framework cannot do it alone: it speaks
     * {@code tactroller-api} and Atchung topics, but the bridge between them is chosen here, at the
     * application edge.
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







    private TextEditorApp() {
    }
}
