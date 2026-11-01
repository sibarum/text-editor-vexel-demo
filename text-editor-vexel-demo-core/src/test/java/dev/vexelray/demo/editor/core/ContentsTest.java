package dev.vexelray.demo.editor.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContentsTest {

    private static Contents.Entry entry(String dir, String name) {
        return new Contents.Entry(dir, name, 1);
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

        Contents c = Contents.of(dir);
        assertEquals(2, c.files());
        assertFalse(c.truncated());
        assertEquals(310, c.bytes());
        assertEquals(300, c.largest());
        assertEquals("notes.md", c.entries().get(0).path());
        Contents.Entry a = c.entries().get(1);
        assertEquals("src", a.dir());
        assertEquals("src/A.java", a.path());
    }

    @Test
    void archiveOrderKeepsAFoldersFilesTogetherAheadOfItsFolders() {
        List<Contents.Entry> entries = new ArrayList<>(List.of(
                entry("src/main", "Main.java"),
                entry("src-extra", "x.txt"),
                entry("src", "b.txt"),
                entry("", "README.md"),
                entry("src", "A.txt"),
                entry("Docs", "guide.md")));
        entries.sort(Contents.ORDER);
        assertEquals(List.of("README.md", "Docs/guide.md", "src/A.txt", "src/b.txt", "src/main/Main.java",
                "src-extra/x.txt"), entries.stream().map(Contents.Entry::path).toList());
    }

    @Test
    void sizesReadAsAPersonReadsThem() {
        assertEquals("0 B", Contents.size(0));
        assertEquals("1023 B", Contents.size(1023));
        assertEquals("1.0 KB", Contents.size(1024));
        assertEquals("14 KB", Contents.size(14 * 1024 + 100));
        assertEquals("2.1 MB", Contents.size((long) (2.1 * 1024 * 1024)));
    }
}
