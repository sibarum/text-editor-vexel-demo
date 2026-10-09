package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Open's view of the disk: one row for the folder above the root, and folders only under it, none merged. */
class RootSourceTest {

    @TempDir
    Path dir;

    private static List<String> names(RootSource s, List<Path> items) {
        return items.stream().map(s::label).toList();
    }

    @Test
    void openStartsAboveTheRootSoItsSiblingsShow() {
        assertEquals(dir.toAbsolutePath().normalize(), RootSource.above(dir.resolve("project")));
    }

    @Test
    void theTopOfADriveStartsAtItself() {
        Path drive = dir.toAbsolutePath().getRoot();
        assertEquals(drive, RootSource.above(drive));
    }

    @Test
    void theTopIsTheOneRow() {
        RootSource s = new RootSource();
        assertEquals(List.of(), s.roots());
        s.top(dir);
        assertEquals(List.of(dir.toAbsolutePath().normalize()), s.roots());
    }

    @Test
    void foldersOnlyCaseInsensitivelyAndTheBuildFoldersLeftOut() throws IOException {
        Files.createFile(dir.resolve("pom.xml"));
        Files.createDirectory(dir.resolve("zeta"));
        Files.createDirectory(dir.resolve("Alpha"));
        Files.createDirectory(dir.resolve("target"));
        Files.createDirectory(dir.resolve(".git"));
        RootSource s = new RootSource();
        s.top(dir);
        assertEquals(List.of("Alpha", "zeta"), names(s, s.children(s.top())));
    }

    @Test
    void aRunOfLoneFoldersIsNotMergedSinceEachMayBeTheRoot() throws IOException {
        Files.createDirectories(dir.resolve("src/main/java"));
        RootSource s = new RootSource();
        s.top(dir);
        List<Path> under = s.children(s.top());
        assertEquals(List.of("src"), names(s, under));
        assertEquals(List.of("main"), names(s, s.children(under.getFirst())));
    }
}
