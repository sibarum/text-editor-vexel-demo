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
}
