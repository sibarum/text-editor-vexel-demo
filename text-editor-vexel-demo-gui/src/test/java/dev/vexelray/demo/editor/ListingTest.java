package dev.vexelray.demo.editor;

import dev.vexelray.gui.fileicons.FileFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingTest {

    private static Listing.Entry entry(String dir, String name) {
        return new Listing.Entry(dir, name, 1, FileFamily.UNKNOWN);
    }

    @Test
    void theWalkSkipsWhatTheNavigatorLeavesOutAndReadsSizes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("notes.md"), "x".repeat(300));
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src").resolve("A.java"), "class A {}");
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("target").resolve("out.class"), "nope");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git").resolve("HEAD"), "nope");

        Listing l = Listing.of(dir, () -> true);
        assertNotNull(l);
        assertEquals(2, l.files());
        assertFalse(l.truncated());
        assertEquals(310, l.bytes());
        assertEquals(300, l.largest());
        Listing.Entry notes = l.entries().get(0);
        assertEquals("notes.md", notes.path());
        assertEquals(FileFamily.ofExtension("md"), notes.family());
        Listing.Entry a = l.entries().get(1);
        assertEquals("src", a.dir());
        assertEquals("src/A.java", a.path());
        assertEquals(FileFamily.ofExtension("java"), a.family());
    }

    @Test
    void aWalkNoLongerWantedReturnsNothing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "a");
        assertNull(Listing.of(dir, () -> false));
    }

    @Test
    void archiveOrderKeepsAFoldersFilesTogetherAheadOfItsFolders() {
        List<Listing.Entry> entries = new ArrayList<>(List.of(
                entry("src/main", "Main.java"),
                entry("src-extra", "x.txt"),
                entry("src", "b.txt"),
                entry("", "README.md"),
                entry("src", "A.txt"),
                entry("Docs", "guide.md")));
        entries.sort(Listing.ORDER);
        assertEquals(List.of("README.md", "Docs/guide.md", "src/A.txt", "src/b.txt", "src/main/Main.java",
                "src-extra/x.txt"), entries.stream().map(Listing.Entry::path).toList());
    }

    @Test
    void sizesReadAsAPersonReadsThem() {
        assertEquals("0 B", Listing.size(0));
        assertEquals("1023 B", Listing.size(1023));
        assertEquals("1.0 KB", Listing.size(1024));
        assertEquals("14 KB", Listing.size(14 * 1024 + 100));
        assertEquals("2.1 MB", Listing.size((long) (2.1 * 1024 * 1024)));
    }

    @Test
    void aLongFolderPartIsCutFromTheLeft() {
        assertEquals("", ListingView.folderPart(""));
        assertEquals("docs/", ListingView.folderPart("docs"));
        String cut = ListingView.folderPart("a/very/long/path/that/goes/on/and/on/past/the/limit/here");
        assertTrue(cut.startsWith("…"));
        assertTrue(cut.endsWith("limit/here/"));
        assertEquals(44, cut.length());
    }
}
