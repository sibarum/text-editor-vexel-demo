package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.NewScratch;
import dev.vexelray.demo.editor.core.Messages.Relocate;
import dev.vexelray.demo.editor.core.Messages.Relocated;
import dev.vexelray.demo.editor.core.Messages.ScratchFolderMade;
import dev.vexelray.demo.editor.core.Messages.ScratchFolderWanted;
import dev.vexelray.demo.editor.core.Messages.ScratchMade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scratches' folder against a real one: what is made, where, and what goes again. */
class ScratchesTest {

    @TempDir
    Path dir;

    private Scratches scratches() {
        return new Scratches(dir.resolve("scratches"));
    }

    @Test
    void anEmptyOrMissingFolderHasNoScratchesAndAFolderOfFoldersHasNoneEither() throws Exception {
        Scratches s = scratches();
        assertFalse(s.any(), "not there yet");
        Files.createDirectories(s.dir().resolve("a/b"));
        assertFalse(s.any(), "folders are not scratches");
        Files.writeString(s.dir().resolve("a/b/note.md"), "n");
        assertTrue(s.any());
    }

    @Test
    void scratchesAreNamedForTheMinuteAndNeverTakeAnotherOnesName() throws Exception {
        Scratches s = scratches();
        Path one = s.create(null);
        Path two = s.create(null);
        assertNotEquals(one, two);
        assertEquals(s.dir(), one.getParent());
        assertTrue(one.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}-\\d{4}(-\\d+)?\\.md"), one.toString());
        assertEquals(0, Files.size(one));
    }

    @Test
    void aScratchIsMadeInTheFolderAskedForOnlyWhenThatFolderIsAmongTheScratches() throws Exception {
        Scratches s = scratches();
        Path inner = Files.createDirectories(s.dir().resolve("inner"));
        assertEquals(inner, s.create(inner).getParent());
        assertEquals(s.dir(), s.create(dir).getParent(), "outside the scratches: in their own folder instead");
    }

    @Test
    void aScratchClosedWithNothingInItGoesAndAnythingElseStays() throws Exception {
        Scratches s = scratches();
        Path empty = s.create(null);
        Path written = Files.writeString(s.create(null), "kept");
        Path outside = Files.createFile(dir.resolve("empty.txt"));
        s.closed(empty);
        s.closed(written);
        s.closed(outside);
        s.closed(s.dir());
        assertFalse(Files.exists(empty));
        assertTrue(Files.exists(written));
        assertTrue(Files.exists(outside), "an empty file outside the scratches is the person's");
        assertTrue(Files.isDirectory(s.dir()));
    }

    @Test
    void aMoveIsIntoAnotherFolderAmongTheScratchesAndNeverIntoItself() throws Exception {
        Scratches s = scratches();
        Path a = Files.createDirectories(s.dir().resolve("a"));
        Path b = Files.createDirectories(s.dir().resolve("b"));
        Path note = Files.writeString(a.resolve("n.md"), "n");
        assertEquals(b.resolve("n.md"), s.into(note, b));
        assertEquals(s.dir().resolve("n.md"), s.into(note, s.dir()));
        assertNull(s.into(note, a), "where it is already");
        assertNull(s.into(a, a.resolve("deeper")), "into itself");
        assertNull(s.into(note, dir), "out of the scratches");
        assertNull(s.into(s.dir(), b), "the folder itself");
    }

    @Test
    void aNameIsInTheSameFolderAndNotBlankOrAWayOut() {
        Scratches s = scratches();
        Path note = s.dir().resolve("a/n.md");
        assertEquals(s.dir().resolve("a/m.txt"), s.named(note, " m.txt "));
        assertNull(s.named(note, "  "));
        assertNull(s.named(note, "n.md"), "no change");
        assertNull(s.named(note, ".."));
        assertNull(s.named(note, "b/m.md"));
        assertNull(s.named(note, "b\\m.md"));
        assertNull(s.named(dir.resolve("x.md"), "y.md"), "not a scratch");
    }

    @Test
    void relocatingMovesAndNeverOverwrites() throws Exception {
        Scratches s = scratches();
        Path a = Files.writeString(Files.createDirectories(s.dir()).resolve("a.md"), "a");
        Path b = Files.writeString(s.dir().resolve("b.md"), "b");
        IOException taken = assertThrows(IOException.class, () -> s.relocate(a, b));
        assertEquals("there is already a b.md there", taken.getMessage());
        assertEquals("b", Files.readString(b));

        Path c = s.dir().resolve("c.md");
        s.relocate(a, c);
        assertFalse(Files.exists(a));
        assertEquals("a", Files.readString(c));
        assertThrows(IOException.class, () -> s.relocate(a, s.dir().resolve("d.md")), "gone");
        assertThrows(IOException.class, () -> s.relocate(c, dir.resolve("out.md")), "out of the scratches");
    }

    @Test
    void newFoldersAreNumberedAndTheJobsAnswerWhatHappened() throws Exception {
        Scratches s = scratches();
        ScratchFolderMade one = new MakeScratchFolder(s).run(new ScratchFolderWanted(Optional.empty()));
        assertEquals(s.dir().resolve("Folder"), one.path());
        assertEquals(one.path().resolve("Folder"), s.folder(one.path()));
        assertEquals(s.dir().resolve("Folder 2"), s.folder(null));

        Path note = Files.writeString(s.dir().resolve("n.md"), "n");
        Relocated moved = new MoveScratch(s).run(new Relocate(note, one.path().resolve("n.md"), true));
        assertEquals("", moved.problem());
        assertTrue(Files.isRegularFile(one.path().resolve("n.md")));
        Relocated again = new MoveScratch(s).run(new Relocate(note, s.dir().resolve("m.md"), false));
        assertEquals("n.md is not there any more", again.problem());
    }

    @Test
    void theJobMakesTheFolderWithTheFirstScratch() {
        Scratches s = scratches();
        ScratchMade made = new MakeScratch(s).run(new NewScratch(Optional.empty()));
        assertEquals("", made.problem());
        assertTrue(Files.isRegularFile(made.path()));
        assertTrue(s.holds(made.path()));
    }
}
