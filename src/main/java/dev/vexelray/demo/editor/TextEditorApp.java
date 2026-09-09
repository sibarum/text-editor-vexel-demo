package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.mainframe.gui.app.ProjectScope;
import dev.mainframe.gui.console.Console;
import dev.mainframe.gui.console.ConsoleSpec;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Ramp;
import dev.vexelray.gui.widget.TextField;
import sibarum.kronometer.Dur;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.List;

/**
 * A deceptively simple text editor on vexelray-gui: a tab bar of open files, a multiline {@link TextField}
 * per tab (word wrap, line numbers, syntax highlighting, cut/copy/paste), and a status line. Files open and
 * save through the native OS dialogs in vexelray-gui-nfd.
 *
 * <p><b>This class used to be the application edge</b> — five hundred lines of input backend, clipboard
 * binding, window memory, chrome, frame loop, pacing, wakes and argument parsing, near-identical to the copy in
 * three other applications. That is {@code vexelray-framework}'s now, and what is left here is the entry point,
 * the constants the rest of this package reads, and the three headless captures. What this application builds
 * is in {@link TextEditorWiring}.
 *
 * <p>Run: {@code TextEditorApp} (a session), {@code TextEditorApp --terminal} (with the shell window up),
 * {@code TextEditorApp --profile} (with the frame probe), {@code TextEditorApp 600} (six hundred frames and
 * out), {@code TextEditorApp --capture [out.png]} (headless). Needs
 * {@code --enable-native-access=ALL-UNNAMED}.
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
     * What every window of this application is called.
     *
     * <p>Named because it is read from four places now — the framework is handed it as this application's
     * identity, and the two windows the framework did not open name it for themselves — and a literal that has
     * to agree across call sites is one rename away from disagreeing.
     */
    static final String TITLE = "Text Editor";

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

    /**
     * The settings key the terminal window is remembered under. Named rather than spelled out at each use, for
     * the reason {@link FolderWindow#KEY} gives: each is read from more than one place, and a literal that has
     * to agree across call sites is a rename waiting to orphan somebody's window.
     *
     * <p>There is no constant here for the main window any more. Its key is the framework's — one name for the
     * main window of every application on the desk — which is exactly the kind of literal that should not have
     * been in four applications at once.
     */
    static final String TERMINAL_KEY = "terminal";

    static final String UNTITLED = "untitled.txt";
    static final String WELCOME =
            "Welcome to the deceptively simple text editor.\n\n"
                    + "Word wrap, line numbers, selection, cut/copy/paste, and caret-follow scrolling "
                    + "all come from the multiline TextField widget. Start typing.";

    /** Printed for a flag in this application's own namespace that it does not know. */
    private static final String USAGE =
            "usage: text-editor [--capture|--capture-terminal|--capture-folder [out.png]]"
                    + " [--terminal] [--profile] [frames]";

    /** A usage error, the same code the framework uses: distinct from 1 so a script can tell the two apart. */
    private static final int EXIT_USAGE = 2;

    /**
     * The captures are this application's own and are taken before the framework sees {@code argv}; everything
     * else is the framework's.
     *
     * <p>That split is the seam {@code Launch} documents rather than a special case: an application with its
     * own richer capture tooling <i>"intercepts its own flag before handing the rest here, and gets a clear
     * 'unknown option' if it forgets to"</i>. And none of the three is a run mode — two of them build a window
     * that is not this application's main one, and the third wants the tree with no window at all.
     *
     * <p>What the framework does with the rest is more than this method used to: {@code --terminal} and
     * {@code --profile} are settings, a bare number is a frame cap, and anything unrecognised is refused by
     * name with the alternatives listed. The old hand-rolled parse threw {@code NumberFormatException} out of
     * {@code main} for a misspelled flag — a stack trace, before any window, for a typo.
     */
    public static void main(String[] args) throws Exception {
        // A shell or an IDE run configuration that expands an empty variable produces a blank argument, and it
        // has never meant anything. Dropped here as well as in Launch, because the capture branch below reads
        // args[0] directly.
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (args.length >= 1 && args[0].startsWith("--capture")) {
            capture(args);
            return;
        }
        VexelApplication.run(new TextEditorWiring(), args);
    }

    /** One of the three headless captures, named by {@code args[0]}, into {@code args[1]} or a default. */
    private static void capture(String[] args) throws Exception {
        String out = args.length >= 2 ? args[1] : null;
        if (args[0].equals("--capture")) {
            capturePage(out == null ? "text-editor.png" : out);
        } else if (args[0].equals("--capture-terminal")) {
            captureTerminal(out == null ? "terminal.png" : out);
        } else if (args[0].equals("--capture-folder")) {
            captureFolder(out == null ? "folder.png" : out);
        } else {
            System.err.println("unknown option: " + args[0]);
            System.err.println(USAGE);
            System.exit(EXIT_USAGE);
        }
    }

    /**
     * Render the editor's page headlessly: build this application's real tree and photograph it.
     *
     * <p><b>The real tree, through the real wiring.</b> {@code VexelApplication.tree} runs {@code CONFIG}
     * through {@code TREE} and stops — no window, no input backend and no window memory, so nothing reached
     * from here can write a placement. That matters more than it looks: a capture that built its tree by a
     * second route would be a capture of a different application, which is what this method used to be. It
     * cleared to a literal {@code 0.06f, 0.07f, 0.09f} while {@link #captureFolder} twenty lines below read
     * {@code Role.PAGE} off the theme — two spellings of one colour, and only one of them could stay right.
     *
     * <p>{@code GuiApp.capture} is static and builds its own device, which is why the framework has no capture
     * mode of its own: a tree carrying a marched viewport comes out correct about the chrome and silently wrong
     * about the content. This tree is a tab bar, a text field and a status line, so there is nothing
     * device-backed in it to be wrong about — which is the condition {@code tree} asks a caller to know about
     * its own content before reaching for this.
     */
    private static void capturePage(String out) throws java.io.IOException {
        // No arguments passed through: a still frame has nothing a setting override could change, and the
        // one setting this application declares opens a window.
        Shell shell = VexelApplication.tree(new TextEditorWiring(), new String[0]);
        try {
            Color page = shell.gui().theme().color(Role.PAGE);
            GuiApp.capture(shell.gui(), W, H, page.r(), page.g(), page.b(), out);
        } finally {
            shell.disposer().close();
        }
        System.out.println("captured " + out);
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
                .windowName(TERMINAL_KEY)
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
        if (shown.isBlank()) {
            return ProjectScope.none();
        }
        try {
            return ProjectScope.at(Path.of(shown), PROJECT_FILE);
        } catch (java.nio.file.InvalidPathException e) {
            // The settings file is hand-editable, so a string that is not a path is reachable. No project is
            // the right answer to that, as it is to no folder at all -- FileActions.savedFolder does the same
            // with the same value, and this asking the console to explode over it helped nobody.
            return ProjectScope.none();
        }
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0, on every window this application owns.
     *
     * <p>Still the application's rather than the framework's, on the grounds {@code CalculatorWiring} states:
     * which chord zooms, or whether zooming exists at all, is not something a framework should be choosing.
     * What the framework does own is that the zoom is <em>remembered</em> — it watches the main window with its
     * tree, so Ctrl+= survives a quit the way dragging the window bigger does.
     *
     * <p><b>And how far it goes.</b> This method used to open with {@code gui.zoomRange(0.5f, 3f, 1.25f)},
     * one of five copies of those numbers on the stack; it is {@code Appearance.ZoomRange} now, applied to the
     * main window by the framework before the first widget. The two windows the framework did not build apply
     * it themselves as they are constructed — see {@link FolderWindow} and {@link EditorWindow} — because a
     * range set here would be set after their trees already exist, and because only one of them is ever
     * running under this framework at all.
     */
    static void zoomShortcuts(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
    }

    private TextEditorApp() {
    }
}
