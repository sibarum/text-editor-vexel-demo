package dev.vexelray.demo.editor;

import dev.vexelray.gui.fileicons.FileFamily;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentsViewTest {

    @Test
    void aLongFolderPartIsCutFromTheLeft() {
        assertEquals("", ContentsView.folderPart(""));
        assertEquals("docs/", ContentsView.folderPart("docs"));
        String cut = ContentsView.folderPart("a/very/long/path/that/goes/on/and/on/past/the/limit/here");
        assertTrue(cut.startsWith("…"));
        assertTrue(cut.endsWith("limit/here/"));
        assertEquals(44, cut.length());
    }

    @Test
    void aFilesFamilyIsItsExtensions() {
        assertEquals(FileFamily.ofExtension("md"), ContentsView.family("notes.MD"));
        assertEquals(FileFamily.UNKNOWN, ContentsView.family(".gitignore"), "a dot file has no extension");
        assertEquals(FileFamily.UNKNOWN, ContentsView.family("Makefile"));
    }
}
