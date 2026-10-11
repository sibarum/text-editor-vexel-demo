package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.CloseRequest;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.Symbol;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final ProjectIndex concordance;
    private final Session session;
    private final Executor io;
    /** The root last asked for — set as it is asked for, where the navigator's own folder arrives a listing later. */
    private volatile Path root;

    private volatile Dialogs dialogs = Dialogs.NONE;
    /**
     * Where a question goes. The framework's dialogs, which are a process-wide static installed with the window;
     * a test hands its own, since there is no window for those to open in. See docs/framework-notes.md, FN-6.
     */
    private volatile Consumer<Modal> ask = Modals::show;

    Actions(Gui gui, Model model, Ui ui, ProjectIndex concordance, Session session) {
        this.gui = gui;
        this.model = model;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.concordance = concordance;
        this.session = session;
        this.io = gui.offload();
        // From the navigator the keyboard stays in the navigator, so walking it with the arrows keeps walking.
        nav.onOpenFile(file -> open(file, false));
        nav.onOpenFolder(this::switchRoot);
        nav.onOpening((proceed, cancel) -> settleUnsaved(ws.all(), proceed, cancel));
        ws.tabs().onContextMenu((index, menu) -> {
            List<Buffer> all = ws.all();
            if (index < 0 || index >= all.size()) {
                return;
            }
            Buffer b = all.get(index);
            boolean vexplore = Suite.hasVexplore();
            menu.item("Close", () -> close(b))
                    .item("Close others", all.size() > 1, () -> closeAll(others(b)))
                    .item("Close all", () -> closeAll(ws.all()))
                    .separator()
                    .item("Reveal in navigator", b.path() != null, () -> reveal(b))
                    .item(Suite.vexploreLabel(vexplore), vexplore && b.path() != null,
                            () -> Suite.showInVexplore(b.path(), io))
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
        // The field's own Ctrl+Enter follows a hyperlink under the caret, and the editor gives its fields none;
        // without a link it fell through to inserting a newline, which this claim now outranks.
        gui.shortcut(Key.ENTER, this::goToDeclaration, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ENTER, this::goToDeclaration, Modifier.CONTROL);
    }

    // ------------------------------------------------------------------ opening

    void newFile() {
        ws.untitled();
    }

    void open() {
        dialogs.openFile(startDir(), this::open);
    }

    /** Ctrl+Shift+O: the folder dialog, as the way to a new root, so unsaved work is settled before it as Open settles it. */
    void openFolder() {
        settleUnsaved(ws.all(), () -> dialogs.openFolder(startDir(), this::switchRoot), () -> { });
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

    /**
     * Point the navigator at {@code folder}, and index it if it is a Maven project. The tabs stay as they are: this
     * is the session's restore and the command line, which say which tabs themselves. A root the user picks goes
     * through {@link #switchRoot}.
     */
    void showFolder(Path folder) {
        if (folder != null && !Files.isDirectory(folder)) {
            model.say("Not a folder: " + folder);
            return;
        }
        root = folder == null ? null : folder.toAbsolutePath().normalize();
        nav.show(folder, () -> {
            model.folder(nav.folder());
            concordance.folder(nav.folder());
        });
        ui.showNavigator(true);
    }

    /**
     * Make {@code folder} the root, and change the tabs with it: the ones open here are put away as this root's and
     * closed, and the ones {@code folder} had when it was last left come back, in their order, with the same one in
     * front.
     *
     * <p>Unsaved work was settled on the way here — on entering the navigator's Open, or before the folder dialog
     * — and the editor is out of sight while a root is picked, so nothing is normally left to ask about. The
     * question is still put if something is, since these tabs are about to close.
     *
     * <p>Tabs open with no root at all belong to no project, so the first root of a window joins them rather than
     * replacing them.
     */
    void switchRoot(Path folder) {
        if (!Files.isDirectory(folder)) {
            model.say("Not a folder: " + folder);
            return;
        }
        Path to = folder.toAbsolutePath().normalize();
        Path from = root;
        if (to.equals(from)) {
            return;
        }
        List<Buffer> open = from == null ? List.of() : ws.all();
        settleUnsaved(open, () -> io.execute(() -> swap(from, to, open)), () -> { });
    }

    /**
     * Save or throw away the unsaved work in {@code buffers}, asking which, then {@code proceed}; or {@code cancel},
     * and nothing changes. Throwing away puts a file back as it is on disk, and closes an Untitled document, so the
     * tabs are all still there for a root that stays the root.
     */
    void settleUnsaved(List<Buffer> buffers, Runnable proceed, Runnable cancel) {
        List<Buffer> unsaved = buffers.stream().filter(Buffer::dirty).toList();
        if (unsaved.isEmpty()) {
            proceed.run();
            return;
        }
        ask.accept(Modal.of("Unsaved changes", unsavedMessage(unsaved))
                .defaultButton("Save all", () -> saveAll(unsaved, proceed, cancel))
                .button("Discard", () -> io.execute(() -> {
                    unsaved.forEach(this::discard);
                    proceed.run();
                }))
                .cancelButton("Cancel", cancel));
    }

    /** Throw away {@code b}'s unsaved work, on the offload lane: back to its file, or closed if it has none to go back to. */
    private void discard(Buffer b) {
        if (b.path() != null) {
            try {
                b.revert(TextFile.load(b.path()));
                return;
            } catch (TextFile.Unsupported | IOException | RuntimeException e) {
                LOG.warn("could not reread {}; closing it instead", b.path(), e);
            }
        }
        ws.close(b.id);
    }

    /** {@link #switchRoot}'s work, once nothing is left to ask: on the offload lane, since it reads files. */
    private void swap(Path from, Path to, List<Buffer> open) {
        if (from != null) {
            session.leave(from, Session.Tabs.of(model.doc()));
        }
        showFolder(to);
        open.forEach(b -> ws.close(b.id));
        Session.Tabs back = session.tabsAt(to);
        int reopened = 0;
        for (Path p : back.files()) {
            if (Files.isRegularFile(p)) {
                load(p, false);
                reopened++;
            }
        }
        if (reopened > 0) {
            // Each load said "Opened" on its own; what happened is one thing.
            model.say("Reopened " + reopened + (reopened == 1 ? " tab" : " tabs") + " from last time in "
                    + (to.getFileName() == null ? to : to.getFileName()));
        }
        // The keyboard stays where the root was picked, the navigator or a dialog; the tab in front is just shown.
        Buffer front = back.front() == null ? null : ws.find(back.front());
        if (front != null) {
            ws.show(front, false);
        }
    }

    /** Put the navigator in Open, to pick a root. */
    void chooseRoot() {
        nav.mode(Navigator.Mode.OPEN);
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

    // ------------------------------------------------------------------ code

    /**
     * One declaration go to declaration may land on.
     *
     * @param file  where it is, or null when it is in {@code in}, an Untitled document
     * @param in    the open document it was read from when it was read from one, whose text is fresher than the
     *              index; else null
     * @param label how the status line names it
     */
    private record Target(Symbol symbol, Path file, Buffer in, String label) {
    }

    /**
     * Ctrl+Enter: go to where the name under the caret is declared.
     *
     * <p>The document in front is read as it is now, and the rest of the project from its index. Names are
     * matched as Concordance matches them — by name, not by resolving them — so a common name can have several
     * declarations. They are visited in a fixed order, and pressing again on the one just reached goes on to the
     * next: a list nobody has to open, at the cost of not seeing them all at once. Which comes first depends on
     * what the name is doing: before a {@code (} it is a method or constructor, otherwise a type or a field.
     */
    void goToDeclaration() {
        Buffer b = ws.front();
        if (b == null) {
            return;
        }
        Buffer.Word word = b.wordAtCaret();
        if (word == null) {
            model.say("Go to declaration: the caret is not on a name");
            return;
        }
        List<Target> targets = declarationsOf(b, word);
        if (targets.isEmpty()) {
            model.say(concordance.indexing()
                    ? "No declaration of " + word.text() + " in this file; the project is still being indexed"
                    : concordance.current() == null
                    ? "No declaration of " + word.text() + " in this file"
                    : "No declaration of " + word.text() + " in this project");
            return;
        }
        int here = -1;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.in() == b && b.declaredNameAt(t.symbol().name(), t.symbol().line(), t.symbol().at().column())
                    == word.start()) {
                here = i;
                break;
            }
        }
        int next = (here + 1) % targets.size();
        Target to = targets.get(next);
        String where = to.file() == null ? "" : " in " + to.file().getFileName();
        goTo(to, targets.size() == 1
                ? (here == 0 ? to.label() + " is the only declaration of " + word.text() : to.label() + where)
                : to.label() + where + " (" + (next + 1) + " of " + targets.size() + ", Ctrl+Enter for the next)");
    }

    /**
     * Every declaration named {@code word}, in the order {@link #goToDeclaration} visits them. The order does not
     * depend on which document is in front, or pressing again from the one just reached would start a different
     * walk and never arrive at the rest.
     */
    private List<Target> declarationsOf(Buffer front, Buffer.Word word) {
        String name = word.text();
        List<Target> out = new ArrayList<>();
        Path frontFile = front.path() == null ? null : front.path().toAbsolutePath().normalize();
        List<Symbol> local = front.declarations();
        for (Symbol s : local) {
            if (s.name().equals(name)) {
                out.add(new Target(s, frontFile, front, Outline.label(s, local)));
            }
        }
        Index index = concordance.current();
        if (index != null) {
            for (Symbol s : index.namesContaining(name)) {
                if (!s.name().equals(name)) {
                    continue;
                }
                Path file = s.file().toAbsolutePath().normalize();
                if (file.equals(frontFile) && !local.isEmpty()) {
                    continue;   // the front document's own declarations were read from its text, which is newer
                }
                Buffer open = ws.find(file);
                out.add(new Target(s, file, open, Outline.label(s, index.declaredIn(s.file()))));
            }
        }
        out.sort(Comparator
                .comparingInt((Target t) -> preference(t.symbol().kind(), word.call()))
                .thenComparing(t -> t.file() == null ? "" : t.file().toString())
                .thenComparingInt(t -> t.symbol().line())
                .thenComparingInt(t -> t.symbol().at().column()));
        return out;
    }

    /** Which kinds of declaration a name most likely means, smallest first: a name before a {@code (} is called. */
    private static int preference(Symbol.Kind kind, boolean call) {
        return switch (kind) {
            case METHOD, CONSTRUCTOR -> call ? 0 : 2;
            case FIELD -> call ? 2 : 1;
            default -> call ? 1 : 0;   // a type: `new Circle(` reaches the class when it declares no constructor
        };
    }

    /**
     * Bring {@code to}'s document forward, opening it if it is not open, select the declared name, and then say
     * {@code message} — after, because opening a file says so on the status line too, and the jump is the news.
     */
    private void goTo(Target to, String message) {
        Symbol s = to.symbol();
        Buffer open = to.in() != null ? to.in() : ws.find(to.file());
        if (open != null) {
            if (open != ws.front()) {
                ws.show(open);
            }
            open.selectDeclaration(s.name(), s.line(), s.at().column());
            model.say(message);
            return;
        }
        io.execute(() -> {
            load(to.file(), true);
            Buffer loaded = ws.find(to.file());
            if (loaded != null) {
                loaded.selectDeclaration(s.name(), s.line(), s.at().column());
                model.say(message);
            }
        });
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
