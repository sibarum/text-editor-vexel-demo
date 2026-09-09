package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Reveal in Navigator</b>, minus the two windows: the way down from the folder the drawer is rooted at to the
 * file being revealed, which is what {@link FolderWindow} hands the tree instead of re-rooting it.
 *
 * <p>The rule the chain exists to serve is that a file inside the project the drawer is already showing does not
 * move the root — however deep it is. So the chain has to name every folder between the two, in the order they
 * have to be opened, and it has to stop at the root rather than walking on up to the volume.
 *
 * <p>Nothing here is on disk: {@code chainFrom} is about paths and never reads one. The temporary directory is
 * only somewhere real to hang absolute paths off, so the test says the same thing on either platform.
 */
class RevealChainTest {

    @Test
    void theChainNamesEveryFolderOnTheWayDownOutermostFirst(@TempDir Path root) {
        Path file = root.resolve("src").resolve("main").resolve("java").resolve("App.java");

        assertEquals(List.of(
                        root.resolve("src"),
                        root.resolve("src").resolve("main"),
                        root.resolve("src").resolve("main").resolve("java"),
                        file),
                FolderWindow.chainFrom(root, file),
                "each item is a row, each the child of the one before it");
    }

    /**
     * The root itself is not on it. {@link FolderSource} roots the tree at the folder's <em>contents</em>, so
     * there is no row for the folder and the first thing the walk can open is one of its children.
     */
    @Test
    void theRootIsNotOnTheChain(@TempDir Path root) {
        assertFalse(FolderWindow.chainFrom(root, root.resolve("README.md")).contains(root));
    }

    /** A file sitting directly in the root is one row: nothing to unfold, only something to select. */
    @Test
    void aFileInTheRootIsTheWholeChain(@TempDir Path root) {
        Path file = root.resolve("README.md");

        assertEquals(List.of(file), FolderWindow.chainFrom(root, file));
    }

    /**
     * The re-rooting case, arriving here the same way: a file from somewhere else re-roots the drawer at its own
     * folder first, so by the time the chain is built the root is that folder and the chain is one row again.
     */
    @Test
    void aRerootedDrawerAlsoEndsUpWithOneRow(@TempDir Path elsewhere) {
        Path file = elsewhere.resolve("notes").resolve("todo.txt");

        assertEquals(List.of(file), FolderWindow.chainFrom(file.getParent(), file));
    }

    /** What the caller checks before asking for a chain at all — and what makes the walk up terminate. */
    @Test
    void onlyAFileUnderTheRootIsRevealedWithoutMovingIt(@TempDir Path parent) {
        Path root = parent.resolve("project");

        assertTrue(root.resolve("src").resolve("App.java").startsWith(root), "under it, however deep");
        assertFalse(parent.resolve("project-other").resolve("App.java").startsWith(root),
                "a sibling whose name merely begins the same way is not inside it");
        assertFalse(parent.resolve("elsewhere").startsWith(root));
    }
}
