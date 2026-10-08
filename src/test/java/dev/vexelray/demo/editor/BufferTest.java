package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where the breadcrumb over a document starts and what it steps through. */
class BufferTest {

    private static final Path FOLDER = Path.of("work", "project").toAbsolutePath();

    @Test
    void aFileInsideTheFolderIsWalkedFromJustBelowIt() {
        Path file = FOLDER.resolve("src").resolve("Ui.java");
        assertEquals(List.of(FOLDER.resolve("src"), file), Buffer.chain(file, FOLDER));
    }

    @Test
    void aFileOutsideTheFolderIsWalkedFromTheRoot() {
        Path file = FOLDER.resolveSibling("elsewhere").resolve("a.txt");
        List<Path> chain = Buffer.chain(file, FOLDER);
        assertEquals(file.getRoot(), chain.getFirst());
        assertEquals(file, chain.getLast());
        assertEquals(file.getNameCount() + 1, chain.size());
    }

    @Test
    void anUnsavedDocumentHasNoChain() {
        assertEquals(List.of(), Buffer.chain(null, FOLDER));
    }

    @Test
    void theWordAtTheCaretIsFoundFromInsideItAndJustAfterIt() {
        String text = "x = shape.area (); n = 42;";
        assertEquals(new Buffer.Word("area", 10, 14, true), Buffer.wordAt(text, 12));
        assertEquals(new Buffer.Word("area", 10, 14, true), Buffer.wordAt(text, 14));
        assertEquals(new Buffer.Word("shape", 4, 9, false), Buffer.wordAt(text, 4));
    }

    @Test
    void aNumberAndPunctuationAreNotNames() {
        String text = "n = 42; ";
        assertEquals(null, Buffer.wordAt(text, 5));
        assertEquals(null, Buffer.wordAt(text, 8));
    }

    @Test
    void aLineAndColumnBecomeAnOffsetClampedToTheirLine() {
        String text = "ab\ncdef\ng";
        assertEquals(0, Buffer.offsetOf(text, 1, 1));
        assertEquals(5, Buffer.offsetOf(text, 2, 3));
        assertEquals(7, Buffer.offsetOf(text, 2, 99));
        assertEquals(text.length(), Buffer.offsetOf(text, 9, 1));
    }
}
