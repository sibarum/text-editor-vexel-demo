package dev.vexelray.demo.editor;

import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Placement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scratches: the folder on its own, and in the editor's real tree, built headless with its home moved into a
 * temporary folder ({@code text-editor.home}) so nothing here touches the user's own scratches or settings.
 */
class ScratchesTest {

    @TempDir
    Path dir;

    private Shell shell;
    private TextEditorAppWiring wiring;
    private volatile Consumer<Modal> answers = modal -> {
        throw new AssertionError("asked unexpectedly: " + modal.title());
    };

    @AfterEach
    void dispose() {
        if (shell != null) {
            shell.disposer().close();
        }
        System.clearProperty("text-editor.home");
    }

    private static void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    // ------------------------------------------------------------------ the folder

    @Test
    void anEmptyOrMissingFolderHasNoScratchesAndAFolderOfFoldersHasNoneEither() throws Exception {
        Scratches s = new Scratches(dir.resolve("scratches"));
        assertFalse(s.any(), "not there yet");
        Files.createDirectories(s.dir().resolve("a/b"));
        assertFalse(s.any(), "only folders");
        Files.writeString(s.dir().resolve("a/b/note.md"), "x");
        assertTrue(s.any(), "one file, two folders down");
    }

    @Test
    void scratchesAreNamedForTheMinuteAndNeverTakeAnotherOnesName() throws Exception {
        Scratches s = new Scratches(dir.resolve("scratches"));
        Path one = s.create(null);
        Path two = s.create(null);
        assertNotEquals(one, two);
        assertTrue(one.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.md"), one.toString());
        assertTrue(Files.isRegularFile(one) && Files.isRegularFile(two));
        assertEquals(s.dir(), one.getParent());
    }

    @Test
    void aScratchIsMadeInTheFolderAskedForOnlyWhenThatFolderIsAmongTheScratches() throws Exception {
        Scratches s = new Scratches(dir.resolve("scratches"));
        Path inside = s.folder(null);
        assertEquals("Folder", inside.getFileName().toString());
        assertEquals("Folder 2", s.folder(null).getFileName().toString());
        assertEquals(inside, s.create(inside).getParent());
        Path outside = Files.createDirectories(dir.resolve("elsewhere"));
        assertEquals(s.dir(), s.create(outside).getParent(), "never outside the scratches");
    }

    @Test
    void closingAFreshScratchWithNothingInItTakesItAwayAndAnythingElseStays() throws Exception {
        Scratches s = new Scratches(dir.resolve("scratches"));
        Path empty = s.create(null);
        s.closed(empty);
        assertFalse(Files.exists(empty), "made and left empty: gone");

        Path written = s.create(null);
        s.written(written);
        s.closed(written);
        assertTrue(Files.exists(written), "saved, even empty: kept");

        Path theirs = Files.writeString(s.dir().resolve("theirs.md"), "");
        s.closed(theirs);
        assertTrue(Files.exists(theirs), "not made here: kept");
    }

    @Test
    void aScratchMovesOnlyToSomewhereNewAmongTheScratches() throws Exception {
        Scratches s = new Scratches(dir.resolve("scratches"));
        Path folder = s.folder(null);
        Path note = Files.writeString(s.dir().resolve("note.md"), "x");
        assertEquals(folder.resolve("note.md"), s.into(note, folder));
        assertNull(s.into(note, s.dir()), "where it is already");
        assertNull(s.into(folder, folder), "into itself");
        assertNull(s.into(note, Files.createDirectories(dir.resolve("elsewhere"))), "out of the scratches");
        Files.writeString(folder.resolve("note.md"), "taken");
        assertNull(s.into(note, folder), "over another");
    }

    @Test
    void aRenameMustBeANewNameInTheSameFolder() throws Exception {
        Scratches s = new Scratches(dir.resolve("scratches"));
        Path note = Files.writeString(Files.createDirectories(s.dir()).resolve("note.md"), "x");
        Files.writeString(s.dir().resolve("other.md"), "y");
        assertEquals(s.dir().resolve("idea.md"), s.renamed(note, " idea.md "));
        assertNull(s.renamed(note, "note.md"), "no change");
        assertNull(s.renamed(note, "other.md"), "taken");
        assertNull(s.renamed(note, "  "));
        assertNull(s.renamed(note, ".."));
        assertNull(s.renamed(note, "a/b.md"));
        assertNull(s.renamed(s.dir(), "elsewhere"), "the folder itself is not a scratch");
    }

    @Test
    void aFreshScratchFiledAwayIsKeptWhenClosedEmpty() throws Exception {
        Scratches s = new Scratches(dir.resolve("scratches"));
        Path made = s.create(null);
        Path moved = s.folder(null).resolve(made.getFileName());
        s.relocate(made, moved);
        s.closed(moved);
        assertTrue(Files.exists(moved));
    }

    // ------------------------------------------------------------------ in the editor

    /** Build the real tree with its home, and so its scratches, under the temporary folder. */
    private void build() {
        System.setProperty("text-editor.home", dir.resolve("home").toString());
        wiring = new TextEditorAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
        shell.dialogs(new Dialogs() {
            @Override
            public void ask(Modal modal) {
                answers.accept(modal);
            }

            @Override
            public void openFile(Path start, List<FileDialog.Filter> filters, Consumer<Path> picked,
                                 Runnable cancelled) {
                cancelled.run();
            }

            @Override
            public void openFolder(Path start, Consumer<Path> picked, Runnable cancelled) {
                cancelled.run();
            }

            @Override
            public void saveFile(Path start, String name, List<FileDialog.Filter> filters, Consumer<Path> picked,
                                 Runnable cancelled) {
                cancelled.run();
            }
        });
    }

    private Path scratchesDir() {
        return dir.resolve("home").resolve("scratches").toAbsolutePath().normalize();
    }

    @Test
    void theScratchesLiveBesideTheSettings() {
        build();
        assertEquals(scratchesDir(), wiring.scratches().dir());
    }

    @Test
    void aNewScratchOpensInFrontWithoutChangingTheRoot() throws Exception {
        build();
        Path project = Files.createDirectories(dir.resolve("project"));
        wiring.actions().showFolder(project);
        eventually("the root", () -> project.toAbsolutePath().normalize().equals(wiring.model().doc().folder()));

        wiring.actions().newScratch(null);
        Workspace ws = wiring.ui().workspace();
        eventually("the scratch in front", () -> ws.front() != null && ws.front().path() != null
                && ws.front().path().startsWith(scratchesDir()));
        assertEquals(project.toAbsolutePath().normalize(), wiring.model().doc().folder());
        assertEquals("Markdown", ws.front().language());
    }

    @Test
    void altNLaysTheScratchesOverTheNavigatorAndAFilePickedThereOpensInATab() throws Exception {
        build();
        Path project = Files.createDirectories(dir.resolve("project"));
        Path note = Files.writeString(Files.createDirectories(scratchesDir()).resolve("note.md"), "# note");
        wiring.actions().showFolder(project);
        eventually("the root", () -> project.toAbsolutePath().normalize().equals(wiring.model().doc().folder()));
        Navigator nav = wiring.ui().navigator();

        wiring.actions().toggleScratches();
        assertEquals(Navigator.Mode.SCRATCHES, nav.mode());
        wiring.actions().open(note, false);
        Workspace ws = wiring.ui().workspace();
        eventually("the note", () -> ws.find(note) != null);
        assertEquals(Navigator.Mode.SCRATCHES, nav.mode(), "opening a scratch leaves them showing");
        assertEquals(project.toAbsolutePath().normalize(), wiring.model().doc().folder());

        wiring.actions().toggleScratches();
        assertEquals(Navigator.Mode.EDIT, nav.mode());
    }

    @Test
    void anUntitledDocumentCanBeKeptAsAScratchOnClosing() throws Exception {
        build();
        Workspace ws = wiring.ui().workspace();
        Buffer b = ws.front();
        b.field.insert("a thought");
        eventually("dirty", b::dirty);
        List<String> offered = new ArrayList<>();
        answers = modal -> {
            modal.buttons().forEach(button -> offered.add(button.label()));
            modal.buttons().stream().filter(button -> button.label().equals("Keep as scratch"))
                    .findFirst().orElseThrow().action().run();
        };
        wiring.actions().close(b);
        eventually("a scratch on disk", () -> wiring.scratches().any());
        assertTrue(offered.contains("Keep as scratch"));
        try (var files = Files.list(scratchesDir())) {
            Path kept = files.findFirst().orElseThrow();
            assertEquals("a thought", Files.readString(kept));
        }
        eventually("the tab closed", () -> ws.all().stream().noneMatch(x -> x == b));
    }

    @Test
    void aFileWithAPlaceIsNotOfferedToBeKeptAsAScratch() throws Exception {
        build();
        Path file = Files.writeString(dir.resolve("a.txt"), "a");
        wiring.actions().load(file);
        Workspace ws = wiring.ui().workspace();
        eventually("the file", () -> ws.front() != null && ws.front().path() != null);
        Buffer b = ws.front();
        b.field.insert("!");
        eventually("dirty", b::dirty);
        List<String> offered = new ArrayList<>();
        answers = modal -> {
            modal.buttons().forEach(button -> offered.add(button.label()));
            modal.cancelButton().action().run();
        };
        wiring.actions().close(b);
        assertFalse(offered.isEmpty());
        assertFalse(offered.contains("Keep as scratch"));
    }

    /** Open {@code file} and wait for its tab, made dirty so following it is seen not to save it. */
    private Buffer openDirty(Path file) throws InterruptedException {
        wiring.actions().open(file, false);
        Workspace ws = wiring.ui().workspace();
        eventually("the tab", () -> ws.find(file) != null);
        Buffer b = ws.find(file);
        b.field.insert("more ");
        eventually("dirty", b::dirty);
        return b;
    }

    private Path tabPath(Buffer b) {
        return wiring.model().doc().entry(b.id).path();
    }

    @Test
    void aScratchDroppedOnAFolderMovesThereWithItsTabAndUndoesBack() throws Exception {
        build();
        Path folder = Files.createDirectories(scratchesDir().resolve("ideas"));
        Path note = Files.writeString(scratchesDir().resolve("note.md"), "# note");
        Buffer b = openDirty(note);
        Navigator nav = wiring.ui().navigator();
        History history = shell.gui().dropHistory();
        assertNotNull(history, "the window's history takes drops");

        assertNull(nav.scratchMove(note, Placement.into(scratchesDir().resolve("note.md")), DropEffect.MOVE),
                "a file holds nothing");
        assertNull(nav.scratchMove(note, Placement.into(folder), DropEffect.COPY), "no copies");
        Change drop = nav.scratchMove(note, Placement.into(folder), DropEffect.MOVE);
        assertNotNull(drop);
        history.perform(drop);

        Path moved = folder.resolve("note.md");
        assertTrue(Files.exists(moved) && !Files.exists(note));
        eventually("the tab follows", () -> moved.equals(b.path()) && moved.equals(tabPath(b)));
        assertTrue(b.dirty(), "following a file does not save it");
        assertTrue(wiring.model().doc().entry(b.id).dirty());

        assertTrue(history.undo());
        assertTrue(Files.exists(note) && !Files.exists(moved));
        eventually("the tab follows back", () -> note.equals(b.path()) && note.equals(tabPath(b)));
    }

    @Test
    void aFolderDroppedBetweenRowsTakesTheTabsOfWhatIsInIt() throws Exception {
        build();
        Path into = Files.createDirectories(scratchesDir().resolve("b"));
        Path beside = Files.writeString(into.resolve("beside.md"), "");
        Path folder = Files.createDirectories(scratchesDir().resolve("a"));
        Path note = Files.writeString(folder.resolve("note.md"), "# note");
        Files.writeString(folder.resolve("other.md"), "");   // two things in it, so its row is its own
        Buffer b = openDirty(note);
        Navigator nav = wiring.ui().navigator();

        // After a row inside b: into b.
        shell.gui().dropHistory().perform(nav.scratchMove(folder, Placement.after(beside), DropEffect.MOVE));

        Path moved = into.resolve("a").resolve("note.md");
        eventually("the tab follows its folder", () -> moved.equals(b.path()) && moved.equals(tabPath(b)));
    }

    @Test
    void renamingAScratchRenamesItsTabAndItsLanguageFollowsTheName() throws Exception {
        build();
        Path note = Files.writeString(Files.createDirectories(scratchesDir()).resolve("note.md"), "# note");
        Files.writeString(scratchesDir().resolve("taken.txt"), "");
        Buffer b = openDirty(note);
        Navigator nav = wiring.ui().navigator();
        wiring.actions().toggleScratches();
        assertEquals("Markdown", b.language());

        nav.beginRename(note);
        nav.submitRename("taken.txt");
        Thread.sleep(200);
        assertTrue(Files.exists(note), "a taken name renames nothing");

        nav.submitRename("idea.txt");
        Path renamed = scratchesDir().resolve("idea.txt");
        eventually("renamed on disk", () -> Files.exists(renamed) && !Files.exists(note));
        eventually("the tab follows", () -> renamed.equals(b.path()) && renamed.equals(tabPath(b)));
        assertNotEquals("Markdown", b.language());
        assertTrue(b.dirty());
        eventually("the title", () -> wiring.model().doc().entry(b.id).title().endsWith("idea.txt"));

        assertTrue(shell.gui().dropHistory().undo(), "a rename undoes like a drop");
        eventually("the old name back", () -> note.equals(b.path()) && Files.exists(note));
    }
}
