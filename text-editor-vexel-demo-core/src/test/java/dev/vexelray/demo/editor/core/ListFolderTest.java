package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Item;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Listing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ListFolder}: a level of the navigator's tree, read from the disk, and the way down to a file in it. */
class ListFolderTest {

    @TempDir
    Path dir;

    private static List<String> names(List<Item> items) {
        return items.stream().map(Item::name).toList();
    }

    @Test
    void foldersComeFirstThenFilesCaseInsensitively() throws IOException {
        Files.createFile(dir.resolve("b.txt"));
        Files.createFile(dir.resolve("A.txt"));
        Files.createDirectory(dir.resolve("zeta"));
        Files.createDirectory(dir.resolve("Alpha"));
        Listed listed = new ListFolder().run(new Listing("tree", dir, true, false));
        assertEquals(List.of("Alpha", "zeta", "A.txt", "b.txt"), names(listed.items()));
        assertEquals(List.of(true, true, false, false), listed.items().stream().map(Item::folder).toList());
        assertEquals("tree", listed.tree(), "the answer says which tree asked");
    }

    /** A package's folders are one row, named as the package and standing at the deepest of them. */
    @Test
    void aChainOfLoneFoldersIsOneRowNamedAsItsPackage() throws IOException {
        Path java = Files.createDirectories(dir.resolve("src/main/java"));
        Path widget = Files.createDirectories(java.resolve("dev/vexelray/gui/widget"));
        Files.createFile(widget.resolve("TreeView.java"));
        Files.createDirectories(java.resolve("dev/vexelray/gui/widget/inner"));
        List<Item> items = new ListFolder().run(new Listing("tree", java, false, false, true)).items();
        assertEquals(List.of("dev.vexelray.gui.widget"), names(items));
        assertEquals(widget, items.getFirst().path(), "the row is the folder that holds more than one");
        assertTrue(items.getFirst().folder());
    }

    /** A chain stops where the packages start, so the source root and the package are rows of their own. */
    @Test
    void aChainDoesNotRunIntoTheSourceRoot() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.createDirectories(src.resolve("main/java/dev/x"));
        Files.createFile(src.resolve("main/java/dev/x/A.java"));
        Files.createDirectories(src.resolve("test"));
        List<Item> items = new ListFolder().run(new Listing("tree", src, false, false, true)).items();
        assertEquals(List.of("main/java", "test"), names(items));
        Path java = src.resolve("main/java");
        assertEquals(List.of("dev.x"), names(new ListFolder().run(new Listing("tree", java, false, false, true)).items()));
    }

    /** Outside a source root the joined names read as the path they are. */
    @Test
    void outsideASourceRootTheNamesAreJoinedAsAPath() throws IOException {
        Files.createDirectories(dir.resolve("docs/guides/deep"));
        Files.createFile(dir.resolve("docs/guides/deep/a.md"));
        Files.createFile(dir.resolve("b.md"));
        List<Item> items = new ListFolder().run(new Listing("tree", dir, true, false, true)).items();
        assertEquals(List.of("docs/guides/deep", "b.md"), names(items));
    }

    /** Open's tree and the scratches' do not ask: every folder there is a row of its own. */
    @Test
    void unaskedNothingIsCompacted() throws IOException {
        Files.createDirectories(dir.resolve("a/b/c"));
        assertEquals(List.of("a"), names(new ListFolder().run(new Listing("picker", dir, true, true)).items()));
    }

    /** A folder holding one folder and a file, or nothing at all, is a row of its own. */
    @Test
    void aFolderWithMoreThanOneFolderOrNothingStopsTheChain() throws IOException {
        Files.createDirectories(dir.resolve("a/b"));
        Files.createFile(dir.resolve("a/note.txt"));
        Files.createDirectories(dir.resolve("empty"));
        List<Item> items = new ListFolder().run(new Listing("tree", dir, true, false, true)).items();
        assertEquals(List.of("a", "empty"), names(items));
    }

    @Test
    void opensTreeIsListedFoldersOnly() throws IOException {
        Files.createFile(dir.resolve("a.txt"));
        Files.createDirectory(dir.resolve("sub"));
        assertEquals(List.of("sub"), names(new ListFolder().run(new Listing("picker", dir, false, true)).items()));
    }

    @Test
    void vcsAndBuildFoldersAreLeftOut() throws IOException {
        Files.createDirectory(dir.resolve(".git"));
        Files.createDirectory(dir.resolve("target"));
        Files.createDirectory(dir.resolve("src"));
        assertEquals(List.of("src"), names(ListFolder.list(dir)));
    }

    @Test
    void anUnreadableFolderIsEmpty() {
        assertTrue(ListFolder.list(dir.resolve("not-there")).isEmpty());
    }

    @Test
    void theChainToAFileIsEveryFolderOnTheWayAndTheFile() throws IOException {
        Path file = Files.createDirectories(dir.resolve("a/b")).resolve("c.txt");
        Files.createFile(file);
        List<Path> chain = ListFolder.chain(dir, file);
        assertEquals(List.of("a", "b", "c.txt"), chain.stream().map(p -> p.getFileName().toString()).toList());
        // Each step is the same Path the listing gave that row, or the tree's walk cannot find it.
        assertEquals(ListFolder.list(dir.toAbsolutePath().normalize()).getFirst().path(), chain.getFirst());
    }

    @Test
    void aFileOutsideTheFolderHasNoChain() {
        assertTrue(ListFolder.chain(dir.resolve("x"), dir.resolve("y.txt")).isEmpty());
        assertTrue(ListFolder.chain(null, dir).isEmpty());
    }
}
