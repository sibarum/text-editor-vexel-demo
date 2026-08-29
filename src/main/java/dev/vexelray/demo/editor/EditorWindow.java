package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.os.Decorations;

import java.nio.file.Path;
import java.util.List;

/**
 * The editor as a window somebody else owns.
 *
 * <p>{@link TextEditorApp#main} is the editor as its own program: it makes the frame loop, takes the main
 * window, and opens a MainFrame terminal beside itself on Ctrl+`. This is the same editor the other way round
 * — MainFrame is the program, the shell is the main window, and the editor is one of the things it opens.
 * Same tabs, same highlighter, same file dialogs, same file tree; what differs is who owns the loop and who
 * is ticking.
 *
 * <p>Both arrangements are assembled from the same package-private parts — {@link Workspace},
 * {@link FileActions}, {@link FolderWindow}, the sizes on {@link TextEditorApp} — so neither is a fork of the
 * other and they cannot drift apart. See {@link Editor}, which is what MainFrame actually plugs in.
 *
 * <p><b>The documents outlive the window.</b> The tree belongs to this object, so closing the editor
 * releases an OS window and leaves the tabs, the text and the zoom where they were: reopening it is the
 * same session, not a new one. That is the console's own arrangement too.
 *
 * <p>All methods run on the frame loop.
 */
public final class EditorWindow {

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
    public EditorWindow(WindowMemory memory) {
        this.memory = memory;
        gui.theme(Palettes.EDITOR);
        gui.minSize(Length.em(30), Length.em(22));
        this.krono = KronoGui.attach(gui);
        this.ws = new Workspace(gui, krono);
        // Method references on this, so the tree can be wired before the thing it reaches is built: both
        // of these queue onto FileActions once there is one, and drop the request until then.
        this.folder = new FolderWindow(this::openPath, this::revealPath, memory, krono);
        TextEditorApp.zoomShortcuts(gui);
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
                    .of(memory.config(KEY, "Text Editor", TextEditorApp.W, TextEditorApp.H).decorations(Decorations.CLIENT), gui)
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
     * Frame loop, once per frame, open or not. The per-frame work runs whether the window is up because a
     * request made just before a close still has to land somewhere, and the clock is ticked because the
     * file tree animates and outlives this window.
     */
    public void tick() {
        if (files != null) {
            files.perFrame();
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
        // Watched with its tree, so the UI zoom is remembered too: Ctrl+= is the same kind of decision as
        // dragging the window bigger, and losing it on quit is the same loss.
        WindowChrome.created(memory, KEY, ws.titleBar, created, TextEditorApp.W, TextEditorApp.H, gui);
    }

    /** The window is gone; the editor is not. What was recorded last stands. */
    private void onClosed() {
        WindowChrome.closed(memory, KEY, ws.titleBar);
    }

    /** Stop the file tree, the documents and the clock. The host's console is not ours to close. */
    public void close() {
        if (files != null) {
            files.close();
        }
        // The documents too: this object can be closed while the process carries on, so the tabs are not
        // released by the window going away the way they are when the standalone editor quits.
        ws.close();
        krono.close();
    }
}
