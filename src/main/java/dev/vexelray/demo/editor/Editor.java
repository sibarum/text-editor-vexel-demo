package dev.vexelray.demo.editor;

import dev.mainframe.eval.Registry;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.input.MenuSink;

import java.nio.file.Path;
import java.util.List;

/**
 * The editor as one of the things MainFrame opens.
 *
 * <h2>Which way round this goes</h2>
 * {@link TextEditorApp#main} is the editor as its own program, with a MainFrame terminal available beside it on
 * Ctrl+`. This is the inversion: MainFrame is the program, the shell is the main window, and the editor is
 * something it opens — so a whole application becomes
 *
 * <pre>{@code
 * Desktop.run("mainframe", "MainFrame",
 *         (settings, memory) -> List.of(new Editor(memory)), args);
 * }</pre>
 *
 * <p>Both arrangements are built out of {@link TextEditorApp.Window}, so neither is a fork of the other and
 * neither has to be kept in step with the other by hand. What differs is who owns the frame loop and who is
 * ticking; the tabs, the highlighter, the file dialogs and the file tree are the same code either way.
 *
 * <h2>What the shell gains</h2>
 * {@code edit} and {@code reveal}, which are {@link EditorApp}'s and are registered here by handing it three
 * callbacks pointed at the window — so they arrive with the same argument checking, the same {@code help} and
 * the same errors they have in the standalone editor. {@code edit ./pom.xml} opens the editor with that file in
 * a tab, and {@code ls | where ext == "java" | first 3 | edit} opens three, which is the reason a shell inside
 * an editor was ever worth having.
 *
 * <h2>Threads</h2>
 * A command body runs on the shell's job thread; a window is built on the frame loop. Every one of the three
 * callbacks therefore goes through {@link ConsoleContext#onGuiThread}, and each of them opens the window before
 * doing its work — {@code edit} on a closed editor has to mean "open the editor and put this in it", not
 * "quietly do nothing".
 */
public final class Editor implements ConsoleApp {

    private final TextEditorApp.Window window;

    /**
     * What the shell should be asked to run when a file has just been opened, or null for nothing.
     *
     * <p>A function of a path rather than anything to do with what is in the file, because this class has no
     * business knowing. A host that wants to say something about a kind of file it recognises answers with a
     * line of MainFrame and the console runs it, so what happened is in the scrollback and can be typed again;
     * everything else answers null.
     */
    private final java.util.function.Function<Path, String> shellLineFor;

    /**
     * @param memory where the editor's windows keep their placement and zoom — the desk's, shared with the
     *               console and with the file tree, because a window memory is one file with one key per window
     */
    public Editor(WindowMemory memory) {
        this(memory, file -> null);
    }

    /**
     * @param memory       as above
     * @param shellLineFor asked about every file that is opened; a line it answers with is run in the console
     */
    public Editor(WindowMemory memory, java.util.function.Function<Path, String> shellLineFor) {
        this.window = new TextEditorApp.Window(memory);
        this.shellLineFor = shellLineFor == null ? file -> null : shellLineFor;
    }

    @Override
    public String name() {
        return "editor";
    }

    @Override
    public String summary() {
        return "open files in tabs, and point the file tree at a directory";
    }

    /**
     * {@code edit} and {@code reveal}, which are {@link EditorApp}'s commands and not restated here.
     *
     * <p>An inner {@code EditorApp} rather than a copy of its two command bodies: they carry a page of argument
     * handling each — paths from the line or a {@code path} column out of the pipe, a directory that turns out
     * to be a file, the error codes for both — and a second copy of that is a copy that drifts. What this class
     * supplies is the only thing that differs between the two arrangements, which is where the three callbacks
     * land.
     */
    @Override
    public void commands(Registry registry, ConsoleContext console) {
        new EditorApp(
                file -> onEditor(console, () -> window.openPath(file)),
                dir -> onEditor(console, () -> window.revealPath(dir)),
                () -> onEditor(console, () -> { }))
                .commands(registry, console);
        // Here rather than in the constructor because this is the first hook handed the console, and running a
        // line is the only thing the answer is ever used for. Submitted rather than acted on directly: an app
        // that wants something done in this shell asks for it the way a person would, and it lands in the
        // scrollback as a line anybody could have typed.
        window.onOpened(file -> {
            String line = shellLineFor.apply(file);
            if (line != null) {
                console.run(line);
            }
        });
    }

    /** The editor window always exists while MainFrame is running, so it can always be opened. */
    @Override
    public boolean launchable() {
        return true;
    }

    /**
     * {@code launch "editor"}, and the {@code editor} command the console names after every launchable app:
     * open the editor, or raise it if it is already up.
     *
     * <p>Frame loop — {@code launch} does the thread hop. Empty when the console is running headless, which is
     * the capture path: there is no application to open onto, and {@code launch} has already refused for that
     * reason before reaching here.
     */
    @Override
    public void launch(ConsoleContext console) {
        console.host().ifPresent(window::show);
    }

    /** The request queue and the frame clock are drained here. See {@link TextEditorApp.Window#tick}. */
    @Override
    public void tick() {
        window.tick();
    }

    /** The documents and the file tree, so the host binds the OS clipboard on both. */
    @Override
    public List<Gui> windows() {
        return window.windows();
    }

    @Override
    public void menu(MenuSink menu, ConsoleContext console) {
        menu.item("Edit a file here...", () -> console.run("ls | where kind == \"file\" | first 1 | edit"));
        menu.item("Show this directory in the file tree", () -> console.run("reveal"));
    }

    /**
     * On the frame loop, with the editor up: what all three callbacks do first.
     *
     * <p>{@code show} before the action rather than after, because the action is a request onto the window's own
     * queue and there is no queue until the window has been opened once. Both happen on the same frame, so
     * {@code edit ./x} opens the editor and the file together rather than opening an empty editor and wanting a
     * second command.
     */
    private void onEditor(ConsoleContext console, Runnable action) {
        console.onGuiThread(() -> {
            console.host().ifPresent(window::show);
            action.run();
        });
    }

    /** Kept for symmetry with the standalone editor's shutdown; the host's console is not ours to close. */
    public void close() {
        window.close();
    }

    /** Open {@code file} in a tab from outside the shell — what a host with its own menus would call. */
    public void open(Path file) {
        window.openPath(file);
    }
}
