package dev.vexelray.demo.editor.terminal;

import dev.vexelray.canvas.Color;
import dev.vexelray.demo.editor.WindowMemory;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import dev.vexelray.os.NativeWindow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * MainFrame as its own OS window on the shared frame loop: a third {@link Gui} holding a tailing scrollback over
 * a prompt over a status line, opened as the named window {@code "terminal"} — so Ctrl+` means <em>the</em>
 * terminal, whether that has to create one or raise the one already there.
 *
 * <p>This is a <b>shell console, not a terminal emulator</b>: lines in, lines out. There is no character grid, no
 * pseudo-terminal and no escape-sequence state machine here, and MainFrame needs none — it is a Java library that
 * prints, so the window feeds it a line and renders what it prints.
 *
 * <p><b>The session outlives the window.</b> The tree belongs to this object, not to the OS window, so closing
 * the terminal releases a window and leaves MainFrame running: reopen it and the scrollback, the history and the
 * working directory are where you left them. Only application shutdown stops the shell.
 *
 * <p>All methods run on the main thread except {@link MainFrameShell#submit} (a handler thread) and the job
 * thread behind it.
 */
public final class TerminalWindow implements AutoCloseable {

    private static final Color BG = Color.rgb(0x11141b);
    private static final Color PANEL = Color.rgb(0x161b26);
    private static final Color DIM = Color.rgb(0x93a0b4);

    /** The title bar's own height, in dp — {@code TitleBar}'s, which is the Windows caption metric. */
    private static final int BAR_H = 32;

    /**
     * The default size, used the first time — after that, whatever the user left it at. The height carries the
     * title bar this window draws itself, since the client area now covers the whole window.
     */
    private static final int DEFAULT_W = 720;
    private static final int DEFAULT_H = 480 + BAR_H;

    private final Consumer<Path> openFile;
    private final Consumer<Path> openDir;
    private final WindowMemory memory;
    private final Gui gui = new Gui();
    private final Node output;
    private final Node promptLabel;
    private final Node status;
    private final TextField prompt;
    private final Scrollback scrollback;
    private final TitleBar titleBar;
    private final List<String> history = new ArrayList<>();

    private MainFrameShell shell;
    /** The framework.s handle on this window, claimed the first time it is shown. */
    private AppWindow handle;
    private int recall;
    private String shownPrompt = "";
    private String shownStatus = "";

    public TerminalWindow(Consumer<Path> openFile, Consumer<Path> openDir, WindowMemory memory) {
        this.openFile = openFile;
        this.openDir = openDir;
        this.memory = memory;

        this.output = gui.column().width(Length.FILL).height(Length.grow(1))
                .background(PANEL).corner(Length.rem(0.5f), Length.rem(0.5f))
                .padding(Length.dp(10))
                .scrollLock(LayoutEnums.ScrollLock.BOTTOM);
        this.scrollback = new Scrollback(gui, output);

        this.promptLabel = gui.text("")
                .width(Length.AUTO).height(Length.FILL)
                .font(1).textSize(Length.rem(0.8125f)).textColor(Ansi.CYAN)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        this.prompt = new TextField(gui, "");
        prompt.node().width(Length.FILL).height(Length.FILL)
                .font(1).textSize(Length.rem(0.8125f)).textColor(Ansi.BRIGHT);
        Node promptRow = gui.row().width(Length.FILL).height(Length.rem(1.75f)).gap(Length.em(0.5f))
                .children(promptLabel, prompt.node());

        this.status = gui.text("")
                .width(Length.FILL).height(Length.rem(1.5f))
                .textSize(Length.rem(0.8125f)).textColor(DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);

        Node root = gui.column().width(Length.FILL).height(Length.grow(1))
                .padding(Length.dp(12)).gap(Length.rem(0.5f))
                .children(output, promptRow, status);
        // This window draws its own frame too, so all three match. The bar is bound in onCreated: a popup's
        // window does not exist until the main thread services the request, and it is that window the buttons
        // command — not the main one.
        this.titleBar = new TitleBar(gui, WindowControls.NONE, "Terminal");
        gui.root().background(BG).children(titleBar.node(), root);
        gui.minSize(Length.em(24), Length.em(14));
        gui.zoomRange(0.5f, 3f, 1.25f);
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);

        prompt.onSubmit(this::onLine);
        claims();
    }

    /** This window's Gui, so the app can bind its shortcuts and its clipboard here too. */
    public Gui gui() {
        return gui;
    }

    /**
     * Open the window on the next frame, starting MainFrame in {@code cwd}.
     *
     * <p>Only the first call opens a window. A second Ctrl+` raises the one that exists and puts the caret back
     * in the prompt — "open the terminal" has to mean the terminal, not a second terminal, and a window that is
     * already open but behind something else has to come forward or the shortcut looks broken.
     *
     * <p>Its position and size come from {@link WindowMemory}, clamped to a monitor that exists, and go back
     * there as the user moves it.
     */
    public void show(GuiApp app, Path cwd) {
        start(cwd);
        // One call for both cases: show() creates the window if it is closed and raises it if it is not.
        if (handle == null) {
            handle = app.window("terminal", () -> WindowSpec
                    .of(memory.config("terminal", "Terminal", DEFAULT_W, DEFAULT_H)
                            .decorations(Decorations.CLIENT), gui)
                    .onCreated(this::onCreated)
                    .onClosed(this::onClosed));
        }
        handle.show();
        gui.focus(prompt.node());
    }

    /**
     * Start MainFrame in {@code cwd} and greet, without opening a window. Separate from {@link #show} so the
     * headless capture path can render this tree — the window's look is reviewable without a GPU or a keyboard.
     */
    public void start(Path cwd) {
        if (shell != null) {
            return;
        }
        shell = new MainFrameShell(scrollback, cwd, openFile, openDir, this::onShellExit);
        scrollback.post("MainFrame in " + cwd, List.of(Span.foreground(0, 9, Ansi.BRIGHT)));
        scrollback.post("type help to see every command, or try: ls | where kind == \"file\" | sort-by size",
                List.of());
        scrollback.post("", List.of());
    }

    /** Run one line as if it had been typed. The entry point for the capture path and for scripted checks. */
    public void submit(String line) {
        onLine(line);
    }

    /** True while a command is running — the capture path waits on this. */
    public boolean busy() {
        return shell != null && shell.busy();
    }

    /** Whether the window is up right now — polled each frame so it can be reopened next launch. */
    public boolean isOpen() {
        return handle != null && handle.open();
    }

    // ---- the prompt ------------------------------------------------------------------

    /** Handler thread: echo the line, remember it, hand it to the job thread. */
    private void onLine(String line) {
        if (shell == null) {
            return;
        }
        prompt.text("");
        String label = promptText();
        scrollback.post(label + line, List.of(Span.foreground(0, label.length(), Ansi.CYAN)));
        if (line.isBlank()) {
            return;
        }
        // Written here on a handler thread, read by the Up/Down claims on the GUI thread.
        synchronized (history) {
            history.remove(line);
            history.add(line);
            recall = history.size();
        }
        shell.submit(line);
    }

    /**
     * The prompt's keys, claimed on the field.
     *
     * <p>A claim is preemption declared in advance: while the field has focus these run and nothing else sees the
     * key, which is what lets Up mean "previous command" here and "previous line" in the editor with no
     * subclassing and no {@code preventDefault}. They use the <em>ordered</em> claim, because each one edits the
     * state typing also edits.
     */
    private void claims() {
        Node node = prompt.node();
        gui.claimUi(node, Shortcut.of(Key.UP), ClaimScope.FOCUSED, () -> recall(-1));
        gui.claimUi(node, Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, () -> recall(+1));
        gui.claimUi(node, Shortcut.of(Key.L, Modifier.CONTROL), ClaimScope.FOCUSED, scrollback::clear);
        // Ctrl+C is the one real conflict: the field handles it as copy in its own key stage, and a FOCUSED claim
        // preempts that. So the claim decides — job running, interrupt it; otherwise copy the selection, which
        // this window can do itself because the document is public and the clipboard is writable.
        gui.claimUi(node, Shortcut.of(Key.C, Modifier.CONTROL), ClaimScope.FOCUSED, this::interruptOrCopy);
        // Ctrl+D on an empty line closes the window, as it does in a shell. On a line with something on it, it
        // does nothing rather than deleting forward: this prompt is one line, so there is no "delete the rest".
        gui.claimUi(node, Shortcut.of(Key.D, Modifier.CONTROL), ClaimScope.FOCUSED, () -> {
            if (prompt.text().isEmpty()) {
                onShellExit();
            }
        });
    }

    private void recall(int direction) {
        String line;
        synchronized (history) {
            if (history.isEmpty()) {
                return;
            }
            recall = Math.max(0, Math.min(history.size(), recall + direction));
            line = recall < history.size() ? history.get(recall) : "";
        }
        prompt.text(line);
        prompt.caret(line.length());
    }

    private void interruptOrCopy() {
        if (shell != null && shell.busy()) {
            if (shell.interrupt()) {
                scrollback.post("interrupt sent -- it reaches a program being waited on, but MainFrame's own "
                        + "loops run to the end", List.of());
            }
            return;
        }
        Document document = prompt.document().value();
        if (document.hasSelection()) {
            gui.clipboard().set(document.selectedText());
        }
    }

    /**
     * {@code exit} asked to leave. It travels the ordinary close route, so the frame loop tears the window down
     * on its own terms and {@link #onClosed} still runs — and MainFrame keeps running behind it, so this is
     * "put the terminal away", not "throw the session away". Safe from the job thread: the command only enqueues.
     */
    private void onShellExit() {
        AppWindow w = handle;
        if (w != null) {
            w.close();
        }
    }

    // ---- per frame -------------------------------------------------------------------

    /**
     * Main thread, once per frame: publish the output that arrived since the last frame, and refresh the two
     * labels that track the shell. Input is the framework's business now.
     */
    public void tick() {
        if (shell == null) {
            return;
        }
        scrollback.flush();
        String label = promptText();
        if (!label.equals(shownPrompt)) {
            shownPrompt = label;
            promptLabel.text(label);
        }
        String line = statusText();
        if (!line.equals(shownStatus)) {
            shownStatus = line;
            status.text(line);
        }
    }

    private String promptText() {
        if (shell == null) {
            return "";
        }
        Path cwd = shell.cwd();
        Path home = Path.of(System.getProperty("user.home"));
        String where = cwd.equals(home) ? "~"
                : cwd.startsWith(home) ? "~/" + home.relativize(cwd).toString().replace('\\', '/')
                : cwd.toString();
        return where + " > ";
    }

    private String statusText() {
        if (shell.busy()) {
            return "running - Ctrl+C interrupt - Ctrl+L clear - Up/Down history";
        }
        String error = shell.lastError();
        return error.isEmpty()
                ? "ready - Ctrl+L clear - Up/Down history - help lists every command - edit <file> opens a tab"
                : error;
    }

    // ---- lifecycle -------------------------------------------------------------------

    /**
     * The window exists, and its input is already attached and pumping — the framework did that from the factory
     * the app supplied. What is left is what only this window knows: which window its own title bar commands,
     * where it should be, and that the caret belongs in the prompt.
     */
    private void onCreated(NativeWindow created) {
        titleBar.controls(WindowControls.of(created));
        if (memory.maximized("terminal")) {
            created.maximize();
        } else {
            memory.restoreBounds("terminal", created, DEFAULT_W, DEFAULT_H);
        }
        memory.watch("terminal", created);
        gui.focus(prompt.node());
    }

    /**
     * The window is gone, and <b>MainFrame is not</b>: the shell keeps running and the scrollback stays, so
     * reopening comes back to the same session — same working directory, same history, same output above the
     * prompt — rather than a fresh greeting.
     *
     * <p>That is the whole reason this window is a named window rather than a popup. The tree was never the
     * window's; closing releases an OS window and its input backend, and nothing else.
     */
    private void onClosed() {
        // Stop reading placement off a window that is being destroyed; what was recorded last stands.
        memory.forget("terminal");
        // The window this bar commanded is gone; the tree outlives it and is shown again on the next Ctrl+`, so
        // the buttons go back to commanding nothing until onCreated rebinds them.
        titleBar.controls(WindowControls.NONE);
    }

    /** Application shutdown: this is where MainFrame's job thread actually stops. */
    @Override
    public void close() {
        if (shell != null) {
            shell.close();
            shell = null;
        }
        prompt.close();
    }
}
