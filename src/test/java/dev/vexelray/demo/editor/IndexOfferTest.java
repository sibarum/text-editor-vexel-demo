package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.krono.KronoGui;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pieces of ordinary logic behind Ctrl+click that have nothing to do with the GUI: deciding that an
 * opened folder holds a Maven project, and landing a caret on a name whose line has moved.
 *
 * <p>Both are about being wrong gracefully. The first must not offer to index a directory that is not a
 * project, or offer the modules of one project separately from it; the second must not put the caret in an
 * arbitrary place because the index is a few edits out of date.
 */
class IndexOfferTest {

    // --- which folders hold a project --------------------------------------------------------------

    @Test
    void aFolderWithItsOwnPomIsTheRoot(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.createDirectory(dir.resolve("module-a"));
        Files.writeString(dir.resolve("module-a").resolve("pom.xml"), "<project/>");

        // The root pom names its own modules and Concordance reads them from it, so the modules must not be
        // offered separately -- indexing the root is indexing them.
        assertEquals(List.of(dir), FileActions.mavenRoots(dir));
    }

    @Test
    void aFolderOfCheckoutsOffersEachOfThem(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("alpha"));
        Files.writeString(dir.resolve("alpha").resolve("pom.xml"), "<project/>");
        Files.createDirectory(dir.resolve("beta"));
        Files.writeString(dir.resolve("beta").resolve("pom.xml"), "<project/>");
        Files.createDirectory(dir.resolve("notes"));   // no pom: not a project

        List<Path> roots = FileActions.mavenRoots(dir);

        assertEquals(List.of(dir.resolve("alpha"), dir.resolve("beta")), roots);
    }

    @Test
    void aFolderWithNoPomAnywhereOffersNothing(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("docs"));
        Files.writeString(dir.resolve("README.md"), "hello");

        assertTrue(FileActions.mavenRoots(dir).isEmpty());
    }

    @Test
    void somethingThatIsNotADirectoryOffersNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pom.xml");
        Files.writeString(file, "<project/>");

        assertTrue(FileActions.mavenRoots(file).isEmpty());
        assertTrue(FileActions.mavenRoots(null).isEmpty());
    }

    /** Two levels and no further: a pom buried deeper is not a root this offers to index. */
    @Test
    void aPomTwoLevelsDownIsNotFound(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("repos").resolve("alpha"));
        Files.writeString(dir.resolve("repos").resolve("alpha").resolve("pom.xml"), "<project/>");

        assertTrue(FileActions.mavenRoots(dir).isEmpty());
    }

    // --- which project a file belongs to ------------------------------------------------------------

    /**
     * A file in a module resolves to the <b>reactor root</b>, not its own module.
     *
     * <p>Indexing the module would look right and be subtly useless: Concordance would see that module's
     * sources and nothing it is built with, so half of what the reader Ctrl+clicked would resolve to nothing.
     */
    @Test
    void aFileInAModuleBelongsToTheReactorRoot(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("project");
        Path module = root.resolve("core");
        Path source = module.resolve("src/main/java/demo");
        Files.createDirectories(source);
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path file = source.resolve("Thing.java");
        Files.writeString(file, "class Thing {}");

        assertEquals(root, SymbolLinks.projectRootOf(file));
    }

    @Test
    void aFileInASingleModuleProjectBelongsToIt(@TempDir Path dir) throws IOException {
        // Nested one level below the temp directory on purpose: the run of poms has to break somewhere above
        // the fixture, and a machine whose temp directory happens to contain a pom.xml would otherwise make
        // this test about that pom rather than about this project. (One really does, on the machine this was
        // written on, which is how the walk-to-the-outermost-pom bug was found.)
        Path root = dir.resolve("project");
        Path source = root.resolve("src/main/java");
        Files.createDirectories(source);
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path file = source.resolve("Thing.java");
        Files.writeString(file, "class Thing {}");

        assertEquals(root, SymbolLinks.projectRootOf(file));
    }

    /**
     * A stray {@code pom.xml} in some distant ancestor — a temp folder, a home directory — must not capture
     * the file. Walking to the outermost pom did exactly that, and turned "index the project you opened" into
     * "index everything under a directory nobody was looking at".
     */
    @Test
    void aStrayPomInAnAncestorDoesNotCaptureTheFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");        // the stray, far above
        Path unrelated = dir.resolve("somewhere/else/project");
        Path source = unrelated.resolve("src/main/java");
        Files.createDirectories(source);
        Files.writeString(unrelated.resolve("pom.xml"), "<project/>");   // the real root
        Path file = source.resolve("Thing.java");
        Files.writeString(file, "class Thing {}");

        assertEquals(unrelated, SymbolLinks.projectRootOf(file),
                "the run of poms breaks above the real root, and the stray one is none of our business");
    }

    /**
     * A file that is not in a source tree but does sit under a project still belongs to that project — the
     * nearest pom above it wins, and there is nothing closer to prefer.
     */
    @Test
    void aLooseFileUnderAProjectBelongsToThatProject(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("project");
        Path loose = root.resolve("scratch");
        Files.createDirectories(loose);
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path file = loose.resolve("Scratch.java");
        Files.writeString(file, "class Scratch {}");

        assertEquals(root, SymbolLinks.projectRootOf(file));
    }

    // --- landing the caret when the index is stale --------------------------------------------------

    private record Harness(Gui gui, KronoGui krono, Workspace ws) implements AutoCloseable {
        static Harness open() {
            Gui gui = new Gui(Atchung.create(), Runnable::run);
            KronoGui krono = KronoGui.attach(gui);
            return new Harness(gui, krono, new Workspace(gui, krono));
        }

        @Override
        public void close() {
            ws.close();
            krono.close();
            gui.close();
        }
    }

    /** Where the selection sits, as the text it covers — the only part of a caret worth asserting on. */
    private static String selected(EditorTab tab) {
        return tab.editor.document().value().selectedText();
    }

    @Test
    void theCaretLandsOnTheNameAndSelectsIt() {
        try (Harness h = Harness.open()) {
            Path file = Path.of("Shape.java").toAbsolutePath().normalize();
            EditorTab tab = h.ws.newTab("class Shape {\n    int area() { return 1; }\n}\n", file, false);

            h.ws.caretOn(file, "area", 2);

            assertEquals("area", selected(tab));
        }
    }

    /**
     * The index said line 2; the file has since grown a few lines at the top. The caret must still find the
     * name rather than landing on whatever line 2 now holds.
     */
    @Test
    void aLineThatHasMovedIsStillFound() {
        try (Harness h = Harness.open()) {
            Path file = Path.of("Shape.java").toAbsolutePath().normalize();
            String text = "// a comment added since the index was built\n"
                    + "// and another\n"
                    + "class Shape {\n"
                    + "    int area() { return 1; }\n"
                    + "}\n";
            EditorTab tab = h.ws.newTab(text, file, false);

            h.ws.caretOn(file, "area", 2);   // really on line 4 now

            assertEquals("area", selected(tab));
        }
    }

    /** A name that is nowhere near the recorded line leaves the caret alone rather than guessing. */
    @Test
    void aNameThatIsNotThereLeavesTheCaretWhereItWas() {
        try (Harness h = Harness.open()) {
            Path file = Path.of("Shape.java").toAbsolutePath().normalize();
            EditorTab tab = h.ws.newTab("class Shape {\n    int perimeter() { return 1; }\n}\n", file, false);

            h.ws.caretOn(file, "area", 2);

            assertEquals("", selected(tab), "nothing found means nothing moved");
        }
    }

    /**
     * The jump matches whole words, like the links do. A caret that landed on the {@code area} inside
     * {@code areaOf} would be a link that underlined one thing and went to another.
     */
    @Test
    void theJumpDoesNotLandInsideALongerIdentifier() {
        try (Harness h = Harness.open()) {
            Path file = Path.of("Shape.java").toAbsolutePath().normalize();
            String text = "class Shape {\n    int areaOf() { return area(); }\n}\n";
            EditorTab tab = h.ws.newTab(text, file, false);

            h.ws.caretOn(file, "area", 2);

            assertEquals("area", selected(tab));
            // Specifically the call, not the first four characters of areaOf.
            assertEquals(text.indexOf("area()"), tab.editor.document().value().selectionStart());
        }
    }

    /** A request aimed at a file that is not the one in front is not applied to the one that is. */
    @Test
    void aRequestForAnotherFileIsIgnored() {
        try (Harness h = Harness.open()) {
            Path shown = Path.of("Shape.java").toAbsolutePath().normalize();
            EditorTab tab = h.ws.newTab("class Shape {\n    int area() { return 1; }\n}\n", shown, false);

            h.ws.caretOn(Path.of("Other.java").toAbsolutePath().normalize(), "area", 2);

            assertEquals("", selected(tab));
        }
    }
}
