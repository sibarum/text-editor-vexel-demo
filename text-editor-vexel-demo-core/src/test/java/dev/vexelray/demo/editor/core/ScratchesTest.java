package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.NewScratch;
import dev.vexelray.demo.editor.core.Messages.ScratchMade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void theJobMakesTheFolderWithTheFirstScratch() {
        Scratches s = scratches();
        ScratchMade made = new MakeScratch(s).run(new NewScratch(Optional.empty()));
        assertEquals("", made.problem());
        assertTrue(Files.isRegularFile(made.path()));
        assertTrue(s.holds(made.path()));
    }
}
