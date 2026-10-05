package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.CloseRequest;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Every command the editor has, and the questions some of them have to ask first.
 *
 * <p><b>Threads.</b> Commands arrive on workers — a shortcut, a menu item, a dialog's answer. Reading and writing
 * files goes to the offload lane ({@link Gui#offload}), never to the frame loop, so a slow disk is a slow command
 * rather than a frozen window. The dialogs post themselves to the frame loop, which is theirs to block.
 *
 * <p><b>Nothing is lost without being asked.</b> Closing a tab with unsaved work, closing several, and closing
 * the window all go through one question — save, discard, or cancel — and a save that fails cancels whatever
 * was waiting on it.
 */
final class Actions {

    private static final Log LOG = Log.of("editor.files");

    private final Gui gui;
    private final Model model;
    private final Ui ui;
    private final Workspace ws;
    private final Navigator nav;
    private final Executor io;

    private volatile Dialogs dialogs = Dialogs.NONE;
    /**
     * Where a question goes. The framework's dialogs, which are a process-wide static installed with the window;
     * a test hands its own, since there is no window for those to open in. See docs/framework-notes.md, FN-6.
     */
    private volatile Consumer<Modal> ask = Modals::show;

    Actions(Gui gui, Model model, Ui ui) {
        this.gui = gui;
        this.model = model;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.io = gui.offload();
        // From the navigator the keyboard stays in the navigator, so walking it with the arrows keeps walking.
        nav.onOpenFile(file -> open(file, false));
        nav.onOpenFolder(this::showFolder);
        ws.tabs().onContextMenu((index, menu) -> {
            List<Buffer> all = ws.all();
            if (index < 0 || index >= all.size()) {
                return;
            }
            Buffer b = all.get(index);
            menu.item("Close", () -> close(b))
                    .item("Close others", all.size() > 1, () -> closeAll(others(b)))
                    .item("Close all", () -> closeAll(ws.all()))
                    .separator()
                    .item("Reveal in navigator", b.path() != null, () -> reveal(b))
                    .item("Copy path", b.path() != null, () -> gui.clipboard().set(String.valueOf(b.path())));
        });
    }

    /** Replace where questions go: a test answers them itself. */
    void ask(Consumer<Modal> ask) {
        this.ask = ask;
    }

    /** The window exists: from here on the dialogs are real. */
    void dialogs(Dialogs dialogs) {
        this.dialogs = dialogs == null ? Dialogs.NONE : dialogs;
    }

    /** Every chord, as a {@code GLOBAL} claim: a focused field still owns the keys it claims for itself. */
    void shortcuts() {
        gui.shortcut(Key.N, this::newFile, Modifier.CONTROL);
        gui.shortcut(Key.O, this::open, Modifier.CONTROL);
        gui.shortcut(Key.O, this::openFolder, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.S, this::save, Modifier.CONTROL);
        gui.shortcut(Key.S, this::saveAs, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.W, this::closeFront, Modifier.CONTROL);
        gui.shortcut(Key.TAB, () -> ws.cycle(1), Modifier.CONTROL);
        gui.shortcut(Key.TAB, () -> ws.cycle(-1), Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.PAGE_DOWN, () -> ws.cycle(1), Modifier.CONTROL);
        gui.shortcut(Key.PAGE_UP, () -> ws.cycle(-1), Modifier.CONTROL);
        gui.shortcut(Key.B, ui::toggleNavigator, Modifier.CONTROL);
        gui.shortcut(Key.E, nav::focus, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.Z, this::toggleWrap, Modifier.ALT);
    }

    // ------------------------------------------------------------------ opening

    void newFile() {
        ws.untitled();
    }

    void open() {
        dialogs.openFile(startDir(), this::open);
    }

    void openFolder() {
        dialogs.openFolder(startDir(), this::showFolder);
    }

    /** Open {@code file} in a tab, or bring forward the tab it is already in. */
    void open(Path file) {
        open(file, true);
    }

    /** As {@link #open(Path)}; {@code focus} says whether the document takes the keyboard. */
    void open(Path file, boolean focus) {
        Buffer already = ws.find(file);
        if (already != null) {
            ws.show(already, focus);
            return;
        }
        io.execute(() -> load(file, focus));
    }

    /**
     * Read {@code file} and open it, on this thread. For the offload lane only: it is a disk read, and the
     * session's restore calls it in sequence so the tabs come back in the order they were in.
     */
    void load(Path file) {
        load(file, true);
    }

    private void load(Path file, boolean focus) {
        if (ws.find(file) != null) {
            return;
        }
        TextFile.Loaded loaded;
        try {
            loaded = TextFile.load(file);
        } catch (TextFile.Unsupported e) {
            refuse(file, e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not read {}", file, e);
            refuse(file, String.valueOf(e.getMessage()));
            return;
        }
        ws.add(file.toAbsolutePath().normalize(), loaded, focus);
        model.say(loaded.notes().isEmpty() ? "Opened " + file.getFileName()
                : "Opened " + file.getFileName() + " (" + String.join(", ", loaded.notes()) + ")");
    }

    private void refuse(Path file, String reason) {
        model.say("Could not open " + file.getFileName());
        ui.alert();
        tell("Could not open " + file.getFileName(), reason);
    }

    /** Point the navigator at {@code folder}. */
    void showFolder(Path folder) {
        if (folder != null && !Files.isDirectory(folder)) {
            model.say("Not a folder: " + folder);
            return;
        }
        nav.show(folder, () -> model.folder(nav.folder()));
        ui.showNavigator(true);
    }

    void reveal(Buffer b) {
        if (!nav.reveal(b.path())) {
            model.say(b.name() + " is not inside the open folder");
        } else {
            ui.showNavigator(true);
        }
    }

    // ------------------------------------------------------------------ saving

    void save() {
        Buffer b = ws.front();
        if (b != null) {
            save(b, () -> { }, () -> { });
        }
    }

    void saveAs() {
        Buffer b = ws.front();
        if (b != null) {
            saveAs(b, () -> { }, () -> { });
        }
    }

    /** Save {@code b} where it is, or ask where if it has never been saved. Exactly one of the two runs. */
    private void save(Buffer b, Runnable saved, Runnable failed) {
        if (b.path() == null) {
            saveAs(b, saved, failed);
        } else {
            write(b, b.path(), saved, failed);
        }
    }

    /**
     * Ask where, then save. Cancelling the dialog is a save that did not happen, so it is {@code failed}: a quit
     * that was waiting on it stays open.
     */
    private void saveAs(Buffer b, Runnable saved, Runnable failed) {
        Path at = b.path();
        Path dir = at != null && at.getParent() != null ? at.getParent() : startDir();
        String name = at != null ? String.valueOf(at.getFileName()) : "untitled.txt";
        dialogs.saveFile(dir, name, target -> write(b, target, saved, failed), failed);
    }

    /**
     * Write {@code b} to {@code target} on the offload lane: to a temporary file beside it, then moved over it,
     * so a failure part way leaves the old file whole rather than half-written.
     */
    private void write(Buffer b, Path target, Runnable saved, Runnable failed) {
        byte[] bytes = b.takeForSave();
        io.execute(() -> {
            Path dir = target.toAbsolutePath().getParent();
            Path tmp = null;
            try {
                tmp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomic) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not save {}", target, e);
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // the write already failed; a stray temporary file is the lesser problem
                    }
                }
                b.saveFailed();
                model.say("Could not save " + target.getFileName());
                ui.alert();
                tell("Could not save " + target.getFileName(), String.valueOf(e.getMessage()));
                failed.run();
                return;
            }
            Path saved0 = target.toAbsolutePath().normalize();
            b.savedAs(saved0);
            model.saved(b.id, saved0, b.language());
            model.say("Saved " + target.getFileName());
            saved.run();
        });
    }

    /** Save every one of {@code buffers} in turn; {@code done} once all have, {@code failed} on the first that does not. */
    private void saveAll(List<Buffer> buffers, Runnable done, Runnable failed) {
        if (buffers.isEmpty()) {
            done.run();
            return;
        }
        Buffer first = buffers.getFirst();
        List<Buffer> rest = buffers.subList(1, buffers.size());
        save(first, () -> saveAll(rest, done, failed), failed);
    }

    // ------------------------------------------------------------------ closing

    void closeFront() {
        Buffer b = ws.front();
        if (b != null) {
            close(b);
        }
    }

    /** Close {@code b}, asking first if it has unsaved work. */
    void close(Buffer b) {
        if (!b.dirty()) {
            ws.close(b.id);
            return;
        }
        String name = b.name() == null ? "Untitled" : b.name();
        ask.accept(Modal.of("Unsaved changes", "Save the changes to " + name + " before closing it?")
                .defaultButton("Save", () -> save(b, () -> ws.close(b.id), () -> { }))
                .button("Don't save", () -> ws.close(b.id))
                .cancelButton("Cancel", () -> { }));
    }

    /** Close every one of {@code buffers}, asking once about all the unsaved ones together. */
    void closeAll(List<Buffer> buffers) {
        List<Buffer> unsaved = buffers.stream().filter(Buffer::dirty).toList();
        Runnable closeThem = () -> buffers.forEach(b -> ws.close(b.id));
        if (unsaved.isEmpty()) {
            closeThem.run();
            return;
        }
        ask.accept(Modal.of("Unsaved changes", unsavedMessage(unsaved))
                .defaultButton("Save all", () -> saveAll(unsaved, closeThem, () -> { }))
                .button("Discard", closeThem)
                .cancelButton("Cancel", () -> { }));
    }

    private List<Buffer> others(Buffer keep) {
        List<Buffer> rest = new ArrayList<>(ws.all());
        rest.remove(keep);
        return rest;
    }

    /**
     * The close gate. The window is still open and drawing while this is unanswered, so the question is an
     * ordinary dialog. Every path through it answers the request exactly once.
     */
    void guardClose(CloseRequest request) {
        guardClose(request::proceed, request::cancel);
    }

    /** The gate, over its two answers — separate because a {@code CloseRequest} cannot be made outside gui-core. */
    void guardClose(Runnable proceed, Runnable cancel) {
        List<Buffer> unsaved = ws.all().stream().filter(Buffer::dirty).toList();
        if (unsaved.isEmpty()) {
            proceed.run();
            return;
        }
        ask.accept(Modal.of("Quit with unsaved changes?", unsavedMessage(unsaved))
                .defaultButton("Save all", () -> saveAll(unsaved, proceed, cancel))
                .button("Discard", proceed)
                .cancelButton("Cancel", cancel));
    }

    private void tell(String title, String message) {
        ask.accept(Modal.of(title, message).defaultButton("OK", () -> { }));
    }

    private static String unsavedMessage(List<Buffer> unsaved) {
        String names = unsaved.stream()
                .map(b -> b.name() == null ? "Untitled" : b.name())
                .collect(Collectors.joining(", "));
        return unsaved.size() == 1
                ? names + " has unsaved changes."
                : unsaved.size() + " documents have unsaved changes: " + names + ".";
    }

    // ------------------------------------------------------------------ view

    void toggleWrap() {
        model.say(ws.toggleWrap() ? "Word wrap on" : "Word wrap off");
    }

    /** Where a dialog starts: beside the document in front, else the open folder, else home. */
    private Path startDir() {
        Buffer b = ws.front();
        if (b != null && b.path() != null && b.path().getParent() != null) {
            return b.path().getParent();
        }
        Path folder = nav.folder();
        return folder != null ? folder : Path.of(System.getProperty("user.home"));
    }
}
