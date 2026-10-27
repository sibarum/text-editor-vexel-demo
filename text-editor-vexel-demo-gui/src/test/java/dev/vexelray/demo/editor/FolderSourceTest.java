package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The navigator's view of the disk: order, what is left out, and the way down to a file. */
class FolderSourceTest {

    @TempDir
    Path dir;

    private static List<String> names(FolderSource s, List<Path> items) {
        return items.stream().map(s::label).toList();
    }

    @Test
    void foldersComeFirstThenFilesCaseInsensitively() throws IOException {
        Files.createFile(dir.resolve("b.txt"));
        Files.createFile(dir.resolve("A.txt"));
        Files.createDirectory(dir.resolve("zeta"));
        Files.createDirectory(dir.resolve("Alpha"));
        FolderSource s = new FolderSource(dir);
        assertEquals(List.of("Alpha", "zeta", "A.txt", "b.txt"), names(s, s.roots()));
    }

    @Test
    void vcsAndBuildFoldersAreLeftOut() throws IOException {
        Files.createDirectory(dir.resolve(".git"));
        Files.createDirectory(dir.resolve("target"));
        Files.createDirectory(dir.resolve("src"));
        FolderSource s = new FolderSource(dir);
        assertEquals(List.of("src"), names(s, s.roots()));
    }

    @Test
    void noFolderIsNoRoots() {
        assertTrue(new FolderSource(null).roots().isEmpty());
    }

    @Test
    void theChainToAFileIsEveryFolderOnTheWayAndTheFile() throws IOException {
        Path file = Files.createDirectories(dir.resolve("a/b")).resolve("c.txt");
        Files.createFile(file);
        Files.createFile(dir.resolve("a/sibling.txt"));   // so a is a row of its own rather than merged into b
        FolderSource s = new FolderSource(dir);
        List<Path> chain = s.chainTo(file);
        assertEquals(List.of("a", "b", "c.txt"), names(s, chain));
        // Each step is the same Path the tree was handed for that row, or revealPath cannot find it.
        assertEquals(s.roots().getFirst(), chain.getFirst());
        assertEquals(s.children(chain.get(0)).getFirst(), chain.get(1));
    }

    @Test
    void aRunOfLoneFoldersIsOneRowStandingForTheDeepest() throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("src/test/java/dev/vexelray/demo/editor"));
        Files.createFile(pkg.resolve("A.java"));
        Files.createDirectories(dir.resolve("src/main"));
        FolderSource s = new FolderSource(dir);
        assertEquals(List.of("src"), names(s, s.roots()));
        List<Path> under = s.children(s.roots().getFirst());
        assertEquals(List.of("main", "test/java/dev/vexelray/demo/editor"), names(s, under));
        assertEquals(pkg, under.get(1));
        assertEquals(List.of("A.java"), names(s, s.children(pkg)));
    }

    @Test
    void aFolderOfFilesOrOfSeveralOrOfNothingIsNotMerged() throws IOException {
        Files.createFile(Files.createDirectories(dir.resolve("files")).resolve("only.txt"));
        Files.createDirectories(dir.resolve("two/x"));
        Files.createDirectories(dir.resolve("two/y"));
        Files.createDirectories(dir.resolve("empty"));
        FolderSource s = new FolderSource(dir);
        assertEquals(List.of("empty", "files", "two"), names(s, s.roots()));
    }

    @Test
    void hiddenEntriesDoNotStopAMerge() throws IOException {
        Files.createDirectories(dir.resolve("app/target"));
        Files.createFile(Files.createDirectories(dir.resolve("app/src")).resolve("a.txt"));
        FolderSource s = new FolderSource(dir);
        assertEquals(List.of("app" + FolderSource.MERGE + "src"), names(s, s.roots()));
    }

    @Test
    void theChainToAFileInAMergedRunSkipsTheFoldersThatHaveNoRow() throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("src/test/java/dev/editor"));
        Path file = Files.createFile(pkg.resolve("A.java"));
        Files.createDirectories(dir.resolve("src/main"));
        FolderSource s = new FolderSource(dir);
        List<Path> chain = s.chainTo(file);
        assertEquals(List.of(dir.resolve("src"), pkg, file), chain);
        // Every step is a row the tree was handed, at the level the walk will look for it.
        assertEquals(s.roots().getFirst(), chain.get(0));
        assertTrue(s.children(chain.get(0)).contains(chain.get(1)));
        assertEquals(List.of(chain.get(2)), s.children(chain.get(1)));
    }

    @Test
    void aRunThatGainsAnEntryIsSplitAndForgetsItsLabel() throws IOException {
        Path b = Files.createDirectories(dir.resolve("a/b"));
        Files.createFile(b.resolve("f.txt"));
        FolderSource s = new FolderSource(dir);
        assertEquals(List.of("a/b"), names(s, s.roots()));
        Files.createFile(dir.resolve("a/g.txt"));
        assertEquals(List.of("a"), names(s, s.roots()));
        assertEquals(List.of("b", "g.txt"), names(s, s.children(dir.resolve("a"))));
    }

    @Test
    void aFileOutsideTheFolderHasNoChain() throws IOException {
        Path other = Files.createTempFile("outside", ".txt");
        try {
            assertTrue(new FolderSource(dir.resolve("x")).chainTo(other).isEmpty());
        } finally {
            Files.deleteIfExists(other);
        }
    }
}
