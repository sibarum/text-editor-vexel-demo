package dev.vexelray.demo.editor;

import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.nfd.FileDialog;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.Source;
import sibarum.concordance.index.SourceRef;
import sibarum.concordance.index.Symbol;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Console console;
    private final Scratches scratches;
    private final Preferences prefs;
    private final Executor io;
    /** The root last asked for — set as it is asked for, where the navigator's own folder arrives a listing later. */
    private volatile Path root;

    /**
     * Where every question goes, a {@code Modal} or a file dialog: the framework's, which hold a question until there
     * is a window to ask it in, or a test's, which answer it. See docs/framework-notes.md, FN-6.
     */
    private final Dialogs dialogs;

    /** What Save As offers: every language the highlighter knows, by extension. */
    private static final List<FileDialog.Filter> TEXT = List.of(FileDialog.Filter.of("Text files",
            Highlighter.knownExtensions().stream().sorted().toArray(String[]::new)));

    Actions(Gui gui, Model model, Ui ui, ProjectIndex concordance, Session session, Dialogs dialogs, Console console,
            Scratches scratches, Preferences prefs) {
        this.gui = gui;
        this.prefs = prefs;
        this.dialogs = dialogs;
        this.model = model;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.concordance = concordance;
        this.session = session;
        this.console = console;
        this.scratches = scratches;
        this.io = gui.offload();
        ws.wordWrap(prefs.wrap());
        ui.onPreferences(e -> gui.menus().present(e, preferencesMenu()));
        // From the navigator the keyboard stays in the navigator, so walking it with the arrows keeps walking.
        nav.onOpenFile(file -> open(file, false));
        nav.onOpenFolder(this::switchRoot);
        nav.onNewScratch(this::newScratch);
        nav.onRelocated(this::relocated);
        // A scratch made and closed with nothing written to it goes again: a deletion, so not on the closing thread.
        ws.onClosed(file -> {
            if (scratches.holds(file)) {
                io.execute(() -> scratches.closed(file));
            }
        });
        nav.onOpening((proceed, cancel) -> settleUnsaved(ws.all(), proceed, cancel));
        ws.runButton().onPress(this::runMenu);
        ws.formattedButton().onPress(this::toggleFormatted);
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
                    // A library's source has no path of its own, and its archive!/entry is the next best thing.
                    .item("Copy path", b.path() != null || b.library() != null, () -> gui.clipboard().set(
                            String.valueOf(b.path() != null ? b.path() : b.library())));
        });
    }

    /** Every chord, as a {@code GLOBAL} claim: a focused field still owns the keys it claims for itself. */
    void shortcuts() {
        gui.shortcut(Key.N, this::newFile, Modifier.CONTROL);
        gui.shortcut(Key.N, () -> newScratch(null), Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.N, this::toggleScratches, Modifier.ALT);
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
        gui.shortcut(Key.V, this::toggleFormatted, Modifier.CONTROL, Modifier.SHIFT);
        gui.shortcut(Key.F5, this::runMenu);
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
        dialogs.openFile(startDir(), List.of(), this::open, () -> { });
    }

    /**
     * Ctrl+Shift+N, and the scratches' own New: a new, empty scratch in {@code folder} (the scratches folder for null),
     * open in front. The file is made at once, so Ctrl+S just saves it; closed with nothing written, it goes again.
     */
    void newScratch(Path folder) {
        io.execute(() -> {
            Path made;
            try {
                made = scratches.create(folder);
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not make a scratch in {}", scratches.dir(), e);
                model.say("Could not make a scratch");
                ui.alert();
                tell("Could not make a scratch", String.valueOf(e.getMessage()));
                return;
            }
            load(made, true);
            nav.scratchMade(made);
            model.say("New scratch " + made.getFileName());
        });
    }

    /**
     * {@code from}, a file or a folder, moved or was renamed to {@code to} in the scratches tree: every tab open on it,
     * or on anything under it, follows it there. Unsaved work stays unsaved, and is saved to the new place.
     */
    void relocated(Path from, Path to) {
        Path a = from.toAbsolutePath().normalize();
        Path b = to.toAbsolutePath().normalize();
        for (Buffer buffer : ws.all()) {
            Path p = buffer.path();
            if (p == null) {
                continue;
            }
            Path at = p.toAbsolutePath().normalize();
            if (at.startsWith(a)) {
                Path now = b.resolve(a.relativize(at));
                buffer.savedAs(now);
                model.moved(buffer.id, now, buffer.language());
            }
        }
        ws.syncFormatted();
        model.say((a.getParent().equals(b.getParent()) ? "Renamed " : "Moved ") + b.getFileName());
    }

    /** Alt+N: the scratches over the navigator's files, or the files again. The navigator is shown for it. */
    void toggleScratches() {
        nav.toggleScratches();
        if (nav.mode() == Navigator.Mode.SCRATCHES) {
            ui.showNavigator(true);
        }
    }

    /** Ctrl+Shift+O: the folder dialog, as the way to a new root, so unsaved work is settled before it as Open settles it. */
    void openFolder() {
        settleUnsaved(ws.all(), () -> dialogs.openFolder(startDir(), this::switchRoot, () -> { }), () -> { });
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
     * Point the navigator at {@code folder}, take up its own preferences if it keeps any in {@code .vex}, and index
     * it if it is a Maven project and indexing is on. The tabs stay as they are: this is the session's restore and
     * the command line, which say which tabs themselves. A root the user picks goes through {@link #switchRoot}.
     */
    void showFolder(Path folder) {
        if (folder != null && !Files.isDirectory(folder)) {
            model.say("Not a folder: " + folder);
            return;
        }
        root = folder == null ? null : folder.toAbsolutePath().normalize();
        prefs.root(root);
        ws.wordWrap(prefs.wrap());
        nav.show(folder, () -> {
            model.folder(nav.folder());
            concordance.folder(prefs.index() ? nav.folder() : null);
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
        dialogs.ask(Modal.of("Unsaved changes", unsavedMessage(unsaved))
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

    /**
     * Make this a plain window, the tabs without the navigator: a launch on files alone, somebody's "edit this". More
     * files opened keep it plain; a folder made the root, by whatever route, brings the navigator back through
     * {@link #showFolder}, and so do Ctrl+B and the scratches.
     */
    void plain() {
        ui.plain();
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
        if (b.readOnly()) {
            refuseReadOnly(b, failed);
        } else if (b.path() == null) {
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
        if (b.readOnly()) {
            refuseReadOnly(b, failed);
            return;
        }
        Path at = b.path();
        Path dir = at != null && at.getParent() != null ? at.getParent() : startDir();
        String name = at != null ? String.valueOf(at.getFileName()) : "untitled.txt";
        dialogs.saveFile(dir, name, TEXT, target -> write(b, target, saved, failed), failed);
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
            scratches.written(saved0);
            b.savedAs(saved0);
            ws.syncFormatted();
            model.saved(b.id, saved0, b.language());
            model.say("Saved " + target.getFileName());
            saved.run();
        });
    }

    /** A library's source is shown, not kept: it cannot be changed, so there is nothing of the user's to save. */
    private void refuseReadOnly(Buffer b, Runnable failed) {
        model.say(b.name() + " is a library's source, and read-only");
        failed.run();
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

    // ------------------------------------------------------------------ running

    /**
     * The Run button, and F5: what can be run for the folder and the document in front ({@link Runs}), as a menu under
     * the button. Worked out on the offload lane, since it reads the disk.
     */
    void runMenu() {
        Buffer b = ws.front();
        Path project = model.doc().folder();
        Path file = b == null ? null : b.path();
        String text = b == null ? null : b.field.text();
        io.execute(() -> {
            List<MenuItem> items = new ArrayList<>();
            for (Runs.Run r : Runs.offered(project, file, text)) {
                items.add(r == null ? MenuItem.SEPARATOR : MenuItem.of(r.label(), () -> run(r)));
            }
            if (items.isEmpty()) {
                items.add(MenuItem.disabled("Nothing to run here"));
            }
            Node button = ws.runButton().node();
            NodeLayout at = button.layout();
            float x = at.present() ? at.rect().x() : 0f;
            float y = at.present() ? at.rect().y() + at.rect().h() : 0f;
            gui.menus().present(new ClickEvent(button.id(), MouseButton.LEFT, x, y), items);
        });
    }

    /**
     * Run {@code run} in the console, once every document that has a file and unsaved work has been saved: what runs
     * is what is on disk, so it has to be what is on screen. A save that fails runs nothing.
     */
    void run(Runs.Run run) {
        List<Buffer> unsaved = ws.all().stream().filter(b -> b.dirty() && b.path() != null && !b.readOnly()).toList();
        saveAll(unsaved, () -> console.run(run), () -> { });
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
        Modal question = Modal.of("Unsaved changes", "Save the changes to " + name + " before closing it?")
                .defaultButton("Save", () -> save(b, () -> ws.close(b.id), () -> { }));
        // A document that was never saved anywhere may belong nowhere: it can be kept without deciding where.
        if (b.path() == null && b.library() == null) {
            question.button("Keep as scratch", () -> keepAsScratch(b, () -> ws.close(b.id)));
        }
        dialogs.ask(question
                .button("Don't save", () -> ws.close(b.id))
                .cancelButton("Cancel", () -> { }));
    }

    /** Save {@code b}, an Untitled document, as a new scratch, then {@code then}; nothing happens if it cannot be. */
    private void keepAsScratch(Buffer b, Runnable then) {
        io.execute(() -> {
            Path target;
            try {
                target = scratches.name();
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not make the scratches folder {}", scratches.dir(), e);
                model.say("Could not keep it as a scratch");
                ui.alert();
                return;
            }
            write(b, target, () -> {
                model.say("Kept as scratch " + target.getFileName());
                then.run();
            }, () -> { });
        });
    }

    /** Close every one of {@code buffers}, asking once about all the unsaved ones together. */
    void closeAll(List<Buffer> buffers) {
        List<Buffer> unsaved = buffers.stream().filter(Buffer::dirty).toList();
        Runnable closeThem = () -> buffers.forEach(b -> ws.close(b.id));
        if (unsaved.isEmpty()) {
            closeThem.run();
            return;
        }
        dialogs.ask(Modal.of("Unsaved changes", unsavedMessage(unsaved))
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
     * The close gate, a {@code CloseGate}: the framework calls it when the main window is asked to close, and a test
     * calls it with answers of its own. The window is still open and drawing while this is unanswered, so the
     * question is an ordinary dialog. Every path through it answers exactly once.
     */
    void guardClose(Runnable proceed, Runnable cancel) {
        List<Buffer> unsaved = ws.all().stream().filter(Buffer::dirty).toList();
        if (unsaved.isEmpty()) {
            proceed.run();
            return;
        }
        dialogs.ask(Modal.of("Quit with unsaved changes?", unsavedMessage(unsaved))
                .defaultButton("Save all", () -> saveAll(unsaved, proceed, cancel))
                .button("Discard", proceed)
                .cancelButton("Cancel", cancel));
    }

    private void tell(String title, String message) {
        dialogs.ask(Modal.of(title, message).defaultButton("OK", () -> { }));
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
     * @param file    where it is, or null when it is in a library or in {@code in}, an Untitled document
     * @param library the library source it is in, or null
     * @param in      the open document showing it, if any: for one read from the text in front, that text, which
     *                is fresher than the index
     * @param context what it was declared beside, for its label; null to ask the index, which is asked only for the
     *                one target gone to, since a common name has hundreds of declarations in a project's libraries
     */
    private record Target(Symbol symbol, Path file, Source library, Buffer in, List<Symbol> context) {

        /** Where it is, for the status line: the file, or the library's jar. Empty in a document never saved. */
        String where() {
            if (file != null) {
                return " in " + file.getFileName();
            }
            return switch (library) {
                case Source.InArchive a -> " in " + a.archive().getFileName();
                case Source.Decompiled d -> " in " + d.archive().getFileName() + ", decompiled";
                case null, default -> "";
            };
        }
    }

    /**
     * Where each library declaration gone to turned out to be. A declaration read from a class file has no line
     * until its class is decompiled and searched, which is too slow to redo on every Ctrl+Enter; this is how
     * pressing again on one just reached knows it is there.
     */
    private final Map<Symbol, SourceRef> located = new ConcurrentHashMap<>();

    /** Where {@code t}'s declaration is in the text it is shown in, as far as is known without reading it. */
    private SourceRef position(Target t) {
        SourceRef known = located.get(t.symbol());
        return known != null ? known : t.symbol().at();
    }

    private String label(Target t, Index index) {
        List<Symbol> context = t.context() != null ? t.context()
                : index != null ? index.declaredIn(t.symbol().source()) : List.of(t.symbol());
        return Outline.label(t.symbol(), context);
    }

    /**
     * Ctrl+Enter: go to where the name under the caret is declared.
     *
     * <p>The document in front is read as it is now, and the rest of the project from its index. Names are
     * matched as Concordance matches them — by name, not by resolving them — so a common name can have several
     * declarations. They are visited in a fixed order, and pressing again on the one just reached goes on to the
     * next: a list nobody has to open, at the cost of not seeing them all at once. The project's own come before
     * its libraries', and within each which comes first depends on what the name is doing: before a {@code (} it
     * is a method or constructor, otherwise a type or a field.
     *
     * <p>A library's declaration opens its source read-only in a tab of its own: the text from its
     * {@code -sources.jar}, or its class decompiled when the library came without sources.
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
                    : concordance.current() == null && !prefs.index()
                    ? "No declaration of " + word.text() + " in this file; indexing is off in Preferences"
                    : concordance.current() == null
                    ? "No declaration of " + word.text() + " in this file"
                    : "No declaration of " + word.text() + " in this project");
            return;
        }
        int here = -1;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            SourceRef at = position(t);
            if (t.in() == b && b.declaredNameAt(t.symbol().name(), at.line(), at.column()) == word.start()) {
                here = i;
                break;
            }
        }
        int next = (here + 1) % targets.size();
        Target to = targets.get(next);
        String label = label(to, concordance.current());
        goTo(to, targets.size() == 1
                ? (here == 0 ? label + " is the only declaration of " + word.text() : label + to.where())
                : label + to.where() + " (" + (next + 1) + " of " + targets.size() + ", Ctrl+Enter for the next)");
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
        Source frontLibrary = front.library();
        List<Symbol> local = front.declarations();
        for (Symbol s : local) {
            if (s.name().equals(name)) {
                out.add(new Target(s, frontFile, frontLibrary, front, local));
            }
        }
        Index index = concordance.current();
        if (index != null) {
            for (Symbol s : index.namesContaining(name)) {
                if (!s.name().equals(name)) {
                    continue;
                }
                if (s.source() instanceof Source.OnDisk disk) {
                    Path file = disk.file().toAbsolutePath().normalize();
                    if (file.equals(frontFile) && !local.isEmpty()) {
                        continue;   // the front document's own declarations were read from its text, which is newer
                    }
                    out.add(new Target(s, file, null, ws.find(file), null));
                } else {
                    if (s.source().equals(frontLibrary) && !local.isEmpty()) {
                        continue;   // read from the text in front, which is placed where a class file's are not
                    }
                    out.add(new Target(s, null, s.source(), ws.findLibrary(s.source()), null));
                }
            }
        }
        out.sort(Comparator
                .comparingInt((Target t) -> t.library() == null ? 0 : 1)
                .thenComparingInt(t -> preference(t.symbol().kind(), word.call()))
                .thenComparing(t -> t.file() != null ? t.file().toString()
                        : t.library() != null ? t.library().toString() : "")
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
        if (to.library() != null && s.source().equals(to.library())) {
            // From the index, not from the text in front: read the source, and place the declaration in it.
            io.execute(() -> goToLibrary(to, message));
            return;
        }
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

    /**
     * {@link #goTo} for a library's declaration, on the offload lane: open its source read-only if it is not open
     * — which for a class without sources is decompiling it, a fraction of a second or, for a huge class, up to
     * Concordance's time limit — and place the declaration in that text.
     */
    private void goToLibrary(Target to, String message) {
        Symbol s = to.symbol();
        Source source = to.library();
        String name = Buffer.libraryName(source);
        Buffer open = ws.findLibrary(source);
        SourceRef at;
        try {
            if (open == null) {
                if (source instanceof Source.Decompiled) {
                    model.say("Decompiling " + name);
                }
                TextFile.Loaded loaded = TextFile.decode(source.read());
                open = ws.addLibrary(source, loaded);
            } else if (open != ws.front()) {
                ws.show(open);
            }
            at = source.locate(s);
        } catch (TextFile.Unsupported | IOException | RuntimeException e) {
            LOG.warn("could not show {}", source, e);
            model.say("Could not show " + name + ": " + e.getMessage());
            return;
        }
        located.put(s, at);
        open.selectDeclaration(s.name(), at.line(), at.column());
        model.say(message);
    }

    // ------------------------------------------------------------------ view

    /** Alt+Z, and the preferences menu: word wrap, remembered where the root's preferences are kept. */
    void toggleWrap() {
        boolean on = !ws.wrap();
        ws.wordWrap(on);
        io.execute(() -> prefs.wrap(on));
        model.say((on ? "Word wrap on" : "Word wrap off") + scope());
    }

    // ----------------------------------------------------------- preferences

    /**
     * The preferences button's menu. The theme is the editor's and takes effect at the next start; word wrap and
     * indexing apply now, to the root's own {@code .vex} preferences when it keeps them and to the editor's otherwise.
     */
    List<MenuItem> preferencesMenu() {
        Preferences.Shade next = prefs.theme();
        Preferences.Shade now = Look.light(gui.theme().palette()) ? Preferences.Shade.LIGHT : Preferences.Shade.DARK;
        Path at = prefs.root();
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.disabled(next == now ? "Theme" : "Theme (from the next start)"));
        items.add(check(next == Preferences.Shade.DARK, "Dark", () -> theme(Preferences.Shade.DARK)));
        items.add(check(next == Preferences.Shade.LIGHT, "Light", () -> theme(Preferences.Shade.LIGHT)));
        items.add(MenuItem.SEPARATOR);
        items.add(MenuItem.disabled(at != null && prefs.projectScoped() ? "Editor, for this project" : "Editor"));
        items.add(check(ws.wrap(), "Word wrap", this::toggleWrap));
        items.add(check(prefs.index(), "Index Maven projects", this::toggleIndex));
        items.add(MenuItem.SEPARATOR);
        items.add(check(prefs.projectScoped(), at == null ? "Keep for this project (no folder open)"
                : "Keep for this project, in " + Preferences.DIR, at != null, this::toggleProjectScoped));
        items.add(check(prefs.gitignore(), "Add " + Preferences.DIR + " to .gitignore", true, this::toggleGitignore));
        return items;
    }

    private static MenuItem check(boolean on, String label, Runnable action) {
        return check(on, label, true, action);
    }

    private static MenuItem check(boolean on, String label, boolean enabled, Runnable action) {
        return new MenuItem(null, label, action, enabled, on ? Icon.CHECK : null);
    }

    private void theme(Preferences.Shade shade) {
        io.execute(() -> prefs.theme(shade));
        boolean light = Look.light(gui.theme().palette());
        boolean same = light == (shade == Preferences.Shade.LIGHT);
        String name = shade == Preferences.Shade.LIGHT ? "Light" : "Dark";
        model.say(same ? name + " theme" : name + " theme from the next start of " + TextEditor.TITLE);
    }

    /** Indexing on or off: on indexes the root now if it is a Maven project, off drops the index there is. */
    void toggleIndex() {
        boolean on = !prefs.index();
        io.execute(() -> {
            prefs.index(on);
            concordance.folder(on ? nav.folder() : null);
        });
        model.say((on ? "Indexing Maven projects" : "Not indexing Maven projects") + scope());
    }

    private void toggleProjectScoped() {
        boolean keep = !prefs.projectScoped();
        io.execute(() -> {
            if (!prefs.keepForProject(keep)) {
                model.say("Could not " + (keep ? "write" : "take away") + " " + Preferences.DIR + "/"
                        + Preferences.FILE + "; the log says why");
                ui.alert();
                return;
            }
            // Leaving the project's: the editor's own values take over, and may differ.
            ws.wordWrap(prefs.wrap());
            concordance.folder(prefs.index() ? nav.folder() : null);
            model.say(keep ? "Preferences kept for this project, in " + Preferences.DIR + "/" + Preferences.FILE
                    : "This project uses the editor's preferences again");
        });
    }

    private void toggleGitignore() {
        boolean on = !prefs.gitignore();
        io.execute(() -> prefs.gitignore(on));
        model.say(on ? "A project's " + Preferences.DIR + " folder goes in its .gitignore"
                : "A project's " + Preferences.DIR + " folder is left out of its .gitignore");
    }

    /** Where a preference just changed is kept, for the status line. */
    private String scope() {
        return prefs.projectScoped() ? ", for this project" : "";
    }

    /** Ctrl+Shift+V, and the Formatted button: a Markdown document formatted, or back to its source. */
    void toggleFormatted() {
        String said = ws.toggleFormatted();
        if (said != null) {
            model.say(said);
        }
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
