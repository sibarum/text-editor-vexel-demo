package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.CloseRequest;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.nfd.FileDialog;
import dev.mainframe.gui.console.Console;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * File actions over the native OS dialogs in vexelray-gui-nfd, operating on the workspace's active tab.
 *
 * <p>NFD dialogs are modal and must run on the GUI thread, but shortcut handlers run on worker threads —
 * so nothing here acts where it was called. Every command goes to {@link GuiApp#post}, which is the frame
 * loop's own queue and the one window operations already use, so tab-structure changes, file I/O and
 * opening a window are all ordered against each other.
 */
final class FileActions implements AutoCloseable {
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
        // which is before this object exists. So EditorWindow builds it and passes it down (see its windows()),
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
                : new Console(TextEditorApp.consoleSpec(memory)
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

    /** Be told when a file has been loaded into a tab. See {@link EditorWindow#onOpened}. */
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
     * <p>This is the other half of {@link EditorWindow#onOpened}: a host wires that, and this is what it calls.
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
        app.post(() -> loadInto(file));
    }

    /**
     * Point the folder window at {@code dir} — MainFrame's {@code reveal}.
     *
     * <p>On {@link GuiApp#post} rather than this class's own queue, and the difference is a frame
     * per hop. Both marshal onto the frame loop; only one of them is the queue the window operation
     * itself uses. {@code post} drains to exhaustion at the top of an iteration, so the
     * {@code app.post} that {@code show} makes internally is picked up by the <em>same</em> drain
     * and the window opens in this frame. A queue of the application's own - drained mid-frame, from
     * the host's {@code beforeFrame} hook - leaves that nested post for the next iteration, and the
     * reveal takes as many frames as it has steps.
     *
     * <p>Invisible while the loop redrew unconditionally, because the next frame was always a few
     * milliseconds away. Against a loop that parks it is the difference between a reveal and a
     * reveal you can watch happen.
     */
    void revealPath(Path dir) {
        app.post(() -> {
            folder.show(app, dir);
            ws.say("Folder: " + dir);
        });
    }

    /**
     * <b>Reveal in Navigator</b> on a tab: point the file tree at the folder holding {@code file} and select
     * its row. Marshalled onto the frame loop because the menu body runs on the handler executor, and
     * opening a window belongs to that loop — on {@link GuiApp#post}, which is the queue window
     * operations actually use, so the whole reveal lands in one frame. See {@link #revealPath}.
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
        app.post(() -> {
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
            app.post(this::closeAll);
            return;
        }
        Modals.show(Modal.of("Close all tabs", unsavedMessage(unsaved))
                .defaultButton("Save all", () -> app.post(this::saveAllThenCloseAll))
                .button("Discard", () -> app.post(this::closeAll))
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
            g.shortcut(Key.GRAVE_ACCENT, () -> app.post(this::toggleTerminal), Modifier.CONTROL);
        }
        g.shortcut(Key.O, () -> app.post(this::open), Modifier.CONTROL);
        g.shortcut(Key.O, () -> app.post(this::openFolder), Modifier.CONTROL, Modifier.SHIFT);
        g.shortcut(Key.S, () -> app.post(this::save), Modifier.CONTROL);
        g.shortcut(Key.S, () -> app.post(this::saveAs), Modifier.CONTROL, Modifier.SHIFT);
        g.shortcut(Key.N, () -> app.post(() -> ws.newTab("", null, false)), Modifier.CONTROL);
        g.shortcut(Key.W, () -> app.post(ws::closeActive), Modifier.CONTROL);
        g.shortcut(Key.TAB, () -> app.post(() -> ws.cycle(+1)), Modifier.CONTROL);
        g.shortcut(Key.TAB, () -> app.post(() -> ws.cycle(-1)), Modifier.CONTROL, Modifier.SHIFT);
    }

    /**
     * GUI thread, once per frame: the three things that have to be sampled rather than reported.
     *
     * <p>Despite the name this drains no queue of its own — commands go to {@link GuiApp#post}. It ticks
     * the console, puts the tab floor back if the bar has been emptied, and reads which windows are up
     * from the windows themselves.
     */
    void perFrame() {
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
        memory.open(FolderWindow.KEY, folder.isOpen());
        if (terminal != null) {
            memory.open(TextEditorApp.TERMINAL_KEY, terminal.isOpen());
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
        if (memory.wasOpen(FolderWindow.KEY)) {
            Path dir = savedFolder();
            if (dir == null) {
                app.post(() -> ws.warn("Last folder is no longer there - not reopening it"));
            } else {
                revealPath(dir);
            }
        }
        if (terminal != null && memory.wasOpen(TextEditorApp.TERMINAL_KEY)) {
            app.post(this::openTerminal);
        }
    }

    /** The remembered folder, or null if there is none, it is unreadable, or it is no longer a directory. */
    private Path savedFolder() {
        String saved = memory.shownPath(FolderWindow.KEY);
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
    void openTerminal() {
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
                .defaultButton("Save all", () -> app.post(() -> saveAllThenClose(request)))
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
            // This tab, not the selected one. Save all writes documents that are not in front, and a
            // never-saved one has just been given its first name here -- so the header that has to change
            // is the one belonging to the tab that was written.
            ws.retitle(tab);
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
