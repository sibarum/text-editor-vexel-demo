package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Scratches;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.widget.Placement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.vexelray.demo.editor.TreeHarness.eventually;
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

    private TreeHarness h;
    private Shell shell;
    private TextEditorAppWiring wiring;

    @AfterEach
    void dispose() {
        if (h != null) {
            h.close();
        }
        System.clearProperty("text-editor.home");
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
    void aScratchClosedWithNothingInItGoesAndAnythingElseStays() throws Exception {
        Scratches s = new Scratches(dir.resolve("scratches"));
        Path empty = s.create(null);
        s.closed(empty);
        assertFalse(Files.exists(empty), "made and left empty: gone");

        Path written = Files.writeString(s.create(null), "x");
        s.closed(written);
        assertTrue(Files.exists(written), "something in it: kept");

        Path outside = Files.writeString(dir.resolve("theirs.md"), "");
        s.closed(outside);
        assertTrue(Files.exists(outside), "not among the scratches: kept");
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

    // ------------------------------------------------------------------ in the editor

    /** Build the real tree with its home, and so its scratches, under the temporary folder. */
    private void build() {
        System.setProperty("text-editor.home", dir.resolve("home").toString());
        h = new TreeHarness();
        wiring = h.wiring;
        shell = h.shell;
    }

    /** Make {@code folder} the root and wait for the navigator to show it. */
    private void rooted(Path folder) throws InterruptedException {
        h.root(folder);
        eventually("the root", () -> folder.toAbsolutePath().normalize().equals(h.nav().folder()));
    }

    @Test
    void aNewScratchClosedEmptyGoesAndOneWrittenToStays() throws Exception {
        build();
        h.front();
        h.shell.bus().publish(Messages.NEW_SCRATCH_TOPIC, new Messages.NewScratch(null));
        Workspace ws = h.ws();
        eventually("the scratch", () -> ws.front() != null && ws.front().path() != null);
        Buffer first = ws.front();
        Path empty = first.path();
        h.tell(Intent.Command.CLOSE, first.id);
        eventually("made and closed empty: gone", () -> !Files.exists(empty));

        // Within the minute the name is free again, so the next one is told apart by its tab.
        h.shell.bus().publish(Messages.NEW_SCRATCH_TOPIC, new Messages.NewScratch(null));
        eventually("another", () -> ws.front() != null && ws.front().path() != null && ws.front().id != first.id);
        Buffer b = ws.front();
        Path kept = b.path();
        h.tell(Intent.Command.SAVE_FRONT, Messages.NO_TAB);
        eventually("saved", () -> h.status().startsWith("Saved"));
        h.tell(Intent.Command.CLOSE, b.id);
        eventually("the tab closed", () -> ws.byId(b.id) == null);
        Thread.sleep(100);
        assertTrue(Files.exists(kept), "saved, even empty: kept");
    }

    @Test
    void aFreshScratchFiledAwayIsKeptWhenClosedEmpty() throws Exception {
        build();
        h.front();
        Path folder = Files.createDirectories(scratchesDir().resolve("ideas"));
        h.shell.bus().publish(Messages.NEW_SCRATCH_TOPIC, new Messages.NewScratch(null));
        Workspace ws = h.ws();
        eventually("the scratch", () -> ws.front() != null && ws.front().path() != null);
        Buffer b = ws.front();
        Path made = b.path();
        shell.gui().dropHistory().perform(h.nav().scratchMove(made, Placement.into(folder), DropEffect.MOVE));
        Path moved = folder.resolve(made.getFileName());
        eventually("the tab follows", () -> moved.equals(b.path()));
        h.tell(Intent.Command.CLOSE, b.id);
        eventually("the tab closed", () -> ws.byId(b.id) == null);
        Thread.sleep(100);
        assertTrue(Files.exists(moved), "filed away is meant to be kept");
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
        h.front();
        Path project = Files.createDirectories(dir.resolve("project"));
        rooted(project);

        h.shell.bus().publish(Messages.NEW_SCRATCH_TOPIC, new Messages.NewScratch(null));
        Workspace ws = h.ws();
        eventually("the scratch in front", () -> ws.front() != null && ws.front().path() != null
                && ws.front().path().startsWith(scratchesDir()));
        assertEquals(project.toAbsolutePath().normalize(), h.nav().folder());
        assertEquals("Markdown", ws.front().language());
    }

    @Test
    void altNLaysTheScratchesOverTheNavigatorAndAFilePickedThereOpensInATab() throws Exception {
        build();
        h.front();
        Path project = Files.createDirectories(dir.resolve("project"));
        Path note = Files.writeString(Files.createDirectories(scratchesDir()).resolve("note.md"), "# note");
        rooted(project);
        Navigator nav = h.nav();

        nav.toggleScratches();
        assertEquals(Navigator.Mode.SCRATCHES, nav.mode());
        h.shell.bus().publish(Messages.OPEN_TOPIC, new Messages.Open(note, false));
        Workspace ws = h.ws();
        eventually("the note", () -> ws.find(note) != null);
        assertEquals(Navigator.Mode.SCRATCHES, nav.mode(), "opening a scratch leaves them showing");
        assertEquals(project.toAbsolutePath().normalize(), nav.folder());

        nav.toggleScratches();
        assertEquals(Navigator.Mode.EDIT, nav.mode());
    }

    @Test
    void anUntitledDocumentCanBeKeptAsAScratchOnClosing() throws Exception {
        build();
        Workspace ws = h.ws();
        Buffer b = h.front();
        b.field.insert("a thought");
        eventually("dirty", b::dirty);
        List<String> offered = new ArrayList<>();
        h.answers = modal -> {
            modal.buttons().forEach(button -> offered.add(button.label()));
            modal.buttons().stream().filter(button -> button.label().equals("Keep as scratch"))
                    .findFirst().orElseThrow().action().run();
        };
        h.tell(Intent.Command.CLOSE, b.id);
        eventually("a scratch on disk", () -> wiring.scratches().any());
        eventually("the tab closed", () -> ws.all().stream().noneMatch(x -> x == b));
        assertTrue(offered.contains("Keep as scratch"));
        try (var files = Files.list(scratchesDir())) {
            Path kept = files.findFirst().orElseThrow();
            assertEquals("a thought", Files.readString(kept));
        }
    }

    @Test
    void aFileWithAPlaceIsNotOfferedToBeKeptAsAScratch() throws Exception {
        build();
        h.front();
        Path file = Files.writeString(dir.resolve("a.txt"), "a");
        h.open(file);
        Workspace ws = h.ws();
        eventually("the file", () -> ws.front() != null && ws.front().path() != null);
        Buffer b = ws.front();
        b.field.insert("!");
        eventually("dirty", b::dirty);
        List<String> offered = new ArrayList<>();
        h.answers = modal -> {
            modal.buttons().forEach(button -> offered.add(button.label()));
            modal.cancelButton().action().run();
        };
        h.tell(Intent.Command.CLOSE, b.id);
        eventually("the question", () -> !offered.isEmpty());
        assertFalse(offered.contains("Keep as scratch"));
    }

    /** Open {@code file} and wait for its tab, made dirty so following it is seen not to save it. */
    private Buffer openDirty(Path file) throws InterruptedException {
        h.front();
        h.shell.bus().publish(Messages.OPEN_TOPIC, new Messages.Open(file, false));
        Workspace ws = h.ws();
        eventually("the tab", () -> ws.find(file) != null);
        Buffer b = ws.find(file);
        b.field.insert("more ");
        eventually("dirty", b::dirty);
        return b;
    }

    @Test
    void aScratchDroppedOnAFolderMovesThereWithItsTabAndUndoesBack() throws Exception {
        build();
        Path folder = Files.createDirectories(scratchesDir().resolve("ideas"));
        Path note = Files.writeString(scratchesDir().resolve("note.md"), "# note");
        Buffer b = openDirty(note);
        Navigator nav = h.nav();
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
        eventually("the tab follows", () -> moved.equals(b.path()));
        eventually("and the editor with it", () -> "• note.md".equals(h.title(b.id)));
        assertTrue(b.dirty(), "following a file does not save it");

        assertTrue(history.undo());
        assertTrue(Files.exists(note) && !Files.exists(moved));
        eventually("the tab follows back", () -> note.equals(b.path()));
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
        Navigator nav = h.nav();

        // After a row inside b: into b.
        shell.gui().dropHistory().perform(nav.scratchMove(folder, Placement.after(beside), DropEffect.MOVE));

        Path moved = into.resolve("a").resolve("note.md");
        eventually("the tab follows its folder", () -> moved.equals(b.path()));
    }

    @Test
    void renamingAScratchRenamesItsTabAndItsLanguageFollowsTheName() throws Exception {
        build();
        Path note = Files.writeString(Files.createDirectories(scratchesDir()).resolve("note.md"), "# note");
        Files.writeString(scratchesDir().resolve("taken.txt"), "");
        Buffer b = openDirty(note);
        Navigator nav = h.nav();
        nav.toggleScratches();
        assertEquals("Markdown", b.language());

        nav.beginRename(note);
        nav.submitRename("taken.txt");
        Thread.sleep(200);
        assertTrue(Files.exists(note), "a taken name renames nothing");

        nav.submitRename("idea.txt");
        Path renamed = scratchesDir().resolve("idea.txt");
        eventually("renamed on disk", () -> Files.exists(renamed) && !Files.exists(note));
        eventually("the tab follows", () -> renamed.equals(b.path()));
        assertNotEquals("Markdown", b.language());
        assertTrue(b.dirty());
        eventually("the title", () -> h.title(b.id) != null && h.title(b.id).endsWith("idea.txt"));

        assertTrue(shell.gui().dropHistory().undo(), "a rename undoes like a drop");
        eventually("the old name back", () -> note.equals(b.path()) && Files.exists(note));
    }
}
