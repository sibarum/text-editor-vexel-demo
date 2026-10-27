package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.Intent.Command;
import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.NewFile;
import dev.vexelray.demo.editor.core.ProjectIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.vexelray.demo.editor.TreeHarness.eventually;
import static dev.vexelray.demo.editor.TreeHarness.pressing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's real tree, built headless by the generated wiring (no window, no GPU), driven through the messages a
 * user's keystrokes send, with the editor, the view and the jobs each on their own lanes.
 */
class EditorTreeTest {

    @TempDir
    Path dir;

    private TreeHarness h;

    @BeforeEach
    void build() {
        h = new TreeHarness();
    }

    @AfterEach
    void dispose() {
        h.close();
    }

    /** Open {@code file} and wait for it to be the tab in front. */
    private Buffer opened(Path file) throws InterruptedException {
        h.open(file);
        Path want = file.toAbsolutePath().normalize();
        eventually("the file " + file.getFileName(), () -> h.ws().front() != null && want.equals(h.ws().front().path()));
        return h.ws().front();
    }

    /** Make {@code folder} the root and wait for the navigator to show it. */
    private void rooted(Path folder) throws InterruptedException {
        h.root(folder);
        Path want = folder.toAbsolutePath().normalize();
        eventually("the root " + folder.getFileName(), () -> want.equals(h.nav().folder()));
    }

    @Test
    void theTreeStartsWithOneEmptyDocument() throws Exception {
        Buffer b = h.front();
        assertEquals(1, h.ws().all().size());
        assertTrue(b.pristine());
    }

    @Test
    void openingAFileTakesOverThePristineTabAndHighlightsIt() throws Exception {
        h.front();
        Path file = Files.writeString(dir.resolve("Hello.java"), "class Hello { String s = \"hi\"; }\n");
        Buffer b = opened(file);
        eventually("the untitled tab to go", () -> h.ws().all().size() == 1);
        assertEquals("Java", b.language());
        eventually("highlighting", () -> !b.field.spans().isEmpty());
    }

    @Test
    void typingMakesADocumentDirtyAndTheTitleSaysSo() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "hello"));
        assertFalse(b.dirty());
        b.field.insert("!");
        eventually("dirty", b::dirty);
        eventually("the editor to retitle it", () -> "• a.txt".equals(h.title(b.id)));
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanOpeningItTwice() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        opened(a);
        opened(c);
        assertEquals(2, h.ws().all().size());
        h.open(a);
        eventually("a in front", () -> a.toAbsolutePath().normalize().equals(h.ws().front().path()));
        assertEquals(2, h.ws().all().size());
    }

    @Test
    void savingWritesTheFileKeepsItsLineEndingsAndCleansTheTab() throws Exception {
        Path file = dir.resolve("crlf.txt");
        Files.write(file, "one\r\ntwo\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Buffer b = opened(file);
        b.field.caret(b.field.text().length());
        b.field.insert("three\n");
        eventually("dirty", b::dirty);
        h.tell(Command.SAVE_FRONT, Messages.NO_TAB);
        // Clean the moment the text is taken, written a moment later: wait for the write to say it landed.
        eventually("the status to say so", () -> h.status().startsWith("Saved"));
        assertFalse(b.dirty());
        eventually("the editor to call it clean", () -> "crlf.txt".equals(h.title(b.id)));
        assertEquals("one\r\ntwo\r\nthree\r\n", Files.readString(file));
    }

    @Test
    void anUntitledDocumentIsSavedWhereTheDialogSays() throws Exception {
        Path target = dir.resolve("new.py");
        h.saveTo = target;
        Buffer b = h.front();
        b.field.insert("print('hi')");
        h.tell(Command.SAVE_FRONT, Messages.NO_TAB);
        eventually("the file to exist", () -> Files.exists(target));
        assertEquals("print('hi')", Files.readString(target));
        eventually("the tab to know its name", () -> target.toAbsolutePath().normalize().equals(b.path()));
        assertEquals("Python", b.language());
    }

    @Test
    void cancellingWhereToSaveLeavesTheDocumentUnsaved() throws Exception {
        h.saveTo = null;
        Buffer b = h.front();
        b.field.insert("kept");
        eventually("dirty", b::dirty);
        h.tell(Command.SAVE_FRONT, Messages.NO_TAB);
        eventually("unsaved again", b::dirty);
        Thread.sleep(100);
        assertTrue(b.dirty(), "a save that did not happen is no save");
        assertNull(b.path());
    }

    @Test
    void theCloseGateLetsACleanSessionGoWithoutAsking() throws Exception {
        h.front();
        List<String> asked = new ArrayList<>();
        h.answers = pressing("Cancel", asked);
        AtomicInteger proceeded = new AtomicInteger();
        h.wiring.gates().quit(proceeded::incrementAndGet, () -> { });
        eventually("the quit to proceed", () -> proceeded.get() == 1);
        assertTrue(asked.isEmpty());
    }

    @Test
    void theCloseGateAsksAboutUnsavedWorkAndCancelKeepsTheWindow() throws Exception {
        Buffer b = h.front();
        b.field.insert("unsaved");
        eventually("dirty", b::dirty);
        List<String> asked = new ArrayList<>();
        h.answers = pressing("Cancel", asked);
        AtomicInteger proceeded = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        h.wiring.gates().quit(proceeded::incrementAndGet, cancelled::incrementAndGet);
        eventually("an answer", () -> proceeded.get() + cancelled.get() > 0);
        assertEquals(List.of("Quit with unsaved changes?"), asked);
        assertEquals(0, proceeded.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    void saveAllThenQuitCancelsTheQuitWhenTheSaveDialogIsCancelled() throws Exception {
        Buffer b = h.front();
        b.field.insert("unsaved");
        eventually("dirty", b::dirty);
        h.saveTo = null;
        h.answers = pressing("Save all", new ArrayList<>());
        AtomicInteger proceeded = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        h.wiring.gates().quit(proceeded::incrementAndGet, cancelled::incrementAndGet);
        eventually("an answer", () -> proceeded.get() + cancelled.get() > 0);
        assertEquals(0, proceeded.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    void saveAllThenQuitProceedsOnceEverythingIsWritten() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Buffer file = opened(a);
        file.field.insert("1");
        h.shell.bus().publish(Messages.NEW_FILE_TOPIC, new NewFile());
        eventually("a second tab", () -> h.ws().all().size() == 2);
        Buffer untitled = h.ws().front();
        untitled.field.insert("2");
        eventually("both dirty", () -> file.dirty() && untitled.dirty());
        h.saveTo = dir.resolve("b.txt");
        h.answers = pressing("Save all", new ArrayList<>());
        AtomicInteger proceeded = new AtomicInteger();
        h.wiring.gates().quit(proceeded::incrementAndGet, () -> { });
        eventually("the quit to proceed", () -> proceeded.get() == 1);
        assertEquals("1a", Files.readString(a));
        assertEquals("2", Files.readString(dir.resolve("b.txt")));
    }

    @Test
    void closingADirtyTabAsksAndDontSaveCloses() throws Exception {
        Buffer b = h.front();
        b.field.insert("x");
        eventually("dirty", b::dirty);
        List<String> asked = new ArrayList<>();
        h.answers = pressing("Don't save", asked);
        h.tell(Command.CLOSE, b.id);
        eventually("the tab to go", () -> h.ws().byId(b.id) == null);
        assertEquals(List.of("Unsaved changes"), asked);
    }

    @Test
    void closingTheLastTabLeavesAnEmptyOne() throws Exception {
        Buffer only = h.front();
        h.tell(Command.CLOSE, only.id);
        eventually("another tab", () -> h.ws().front() != null && h.ws().front() != only);
        assertEquals(1, h.ws().all().size());
        assertTrue(h.ws().front().pristine());
    }

    @Test
    void openingAMavenProjectIndexesItAndOpeningAnotherFolderForgetsIt() throws Exception {
        Path project = Files.createDirectories(dir.resolve("shapes"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId><artifactId>shapes</artifactId><version>1</version></project>
                """);
        Path src = Files.createDirectories(project.resolve("src/main/java/shapes"));
        Files.writeString(src.resolve("Shape.java"), "package shapes; interface Shape { double area(); }\n");
        Files.writeString(src.resolve("Circle.java"),
                "package shapes; final class Circle implements Shape { public double area() { return 1; } }\n");

        ProjectIndex index = h.wiring.projectIndex();
        rooted(project);
        eventually("the index", () -> index.current() != null);
        assertEquals(project.toAbsolutePath().normalize(), index.root().toAbsolutePath().normalize());
        assertEquals(List.of("Circle"),
                index.current().implementationsOf("Shape").stream().map(s -> s.name()).toList());
        eventually("the status line to say so", () -> h.status().startsWith("Indexed shapes"));

        rooted(Files.createDirectories(dir.resolve("plain")));
        eventually("the index to be forgotten", () -> index.current() == null && index.root() == null);
    }

    @Test
    void switchingRootsPutsTheTabsAwayAndSwitchingBackBringsThemBack() throws Exception {
        Path one = Files.createDirectories(dir.resolve("one"));
        Path two = Files.createDirectories(dir.resolve("two"));
        Path a = Files.writeString(one.resolve("a.txt"), "a");
        Path b = Files.writeString(one.resolve("b.txt"), "b");
        Path c = Files.writeString(two.resolve("c.txt"), "c");
        h.front();

        rooted(one);
        opened(a);
        opened(b);
        opened(a);
        eventually("a in front of a and b",
                () -> h.tabNames().equals(List.of("a.txt", "b.txt")) && "a.txt".equals(name(h.ws().front())));

        h.root(two);
        eventually("a root never visited to start empty", () -> h.tabNames().equals(List.of("Untitled")));
        opened(c);
        eventually("c", () -> h.tabNames().equals(List.of("c.txt")));

        h.root(one);
        eventually("a and b back, a in front",
                () -> h.tabNames().equals(List.of("a.txt", "b.txt")) && "a.txt".equals(name(h.ws().front())));

        h.root(two);
        eventually("c back", () -> h.tabNames().equals(List.of("c.txt")));
    }

    /** A root {@code one} with {@code a.txt} open in it and typed into, unsaved. */
    private Buffer dirtyTabIn(Path one) throws Exception {
        Path a = Files.writeString(one.resolve("a.txt"), "a");
        h.front();
        rooted(one);
        Buffer b = opened(a);
        eventually("a", () -> h.tabNames().equals(List.of("a.txt")));
        b.field.insert("!");
        eventually("dirty", b::dirty);
        return b;
    }

    @Test
    void goingToOpenAsksAboutUnsavedWorkAndCancelStaysInEdit() throws Exception {
        Buffer b = dirtyTabIn(Files.createDirectories(dir.resolve("one")));
        List<String> asked = new ArrayList<>();
        h.answers = pressing("Cancel", asked);
        h.nav().askToOpen();
        eventually("the question", () -> !asked.isEmpty());
        Thread.sleep(100);
        assertEquals(List.of("Unsaved changes"), asked);
        assertEquals(Navigator.Mode.EDIT, h.nav().mode());
        assertTrue(b.dirty());
    }

    @Test
    void discardingOnTheWayToOpenPutsTheFileBackAndKeepsItsTab() throws Exception {
        Buffer b = dirtyTabIn(Files.createDirectories(dir.resolve("one")));
        List<String> asked = new ArrayList<>();
        h.answers = pressing("Discard", asked);
        h.nav().askToOpen();
        eventually("Open", () -> h.nav().mode() == Navigator.Mode.OPEN);
        assertEquals(List.of("Unsaved changes"), asked);
        eventually("put back", () -> !b.dirty() && "a".equals(b.field.text()));
        assertEquals(List.of("a.txt"), h.tabNames());
    }

    @Test
    void goingToOpenWithNothingUnsavedAsksNothing() throws Exception {
        h.front();
        List<String> asked = new ArrayList<>();
        h.answers = pressing("Cancel", asked);
        h.nav().askToOpen();
        eventually("Open", () -> h.nav().mode() == Navigator.Mode.OPEN);
        assertTrue(asked.isEmpty());
    }

    @Test
    void aLaunchOnAFileIsPlainUntilAFolderIsOpened() throws Exception {
        Path script = Files.writeString(dir.resolve("env.ps1"), "$env:A = 1\n");
        Path more = Files.writeString(dir.resolve("more.txt"), "more");
        Path project = Files.createDirectories(dir.resolve("project"));
        h.close();
        h = new TreeHarness(script.toString());
        Ui ui = h.ui();
        h.begin();
        eventually("the file, alone", () -> h.tabNames().equals(List.of("env.ps1")));
        assertTrue(ui.isPlain());
        assertFalse(ui.navigatorShown());

        opened(more);
        eventually("more", () -> h.tabNames().equals(List.of("env.ps1", "more.txt")));
        assertTrue(ui.isPlain(), "another file keeps the window plain");

        // The folder dialog's route; the first root of a window joins the tabs it has rather than replacing them.
        rooted(project);
        eventually("the navigator on the folder", ui::navigatorShown);
        assertFalse(ui.isPlain());
        assertEquals(List.of("env.ps1", "more.txt"), h.tabNames());
    }

    @Test
    void aLaunchWithAFolderIsNotPlain() throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path file = Files.writeString(project.resolve("a.txt"), "a");
        h.close();
        h = new TreeHarness(project.toString(), file.toString());
        Ui ui = h.ui();
        h.begin();
        eventually("the navigator on the folder",
                () -> ui.navigatorShown() && project.toAbsolutePath().normalize().equals(ui.navigator().folder()));
        eventually("the file", () -> h.tabNames().equals(List.of("a.txt")));
        assertFalse(ui.isPlain());
    }

    @Test
    void ctrlBInAPlainWindowShowsTheNavigatorAndHidesItAsUsual() {
        Ui ui = h.ui();
        ui.plain();
        assertFalse(ui.navigatorShown());
        ui.toggleNavigator();
        assertTrue(ui.navigatorShown());
        assertFalse(ui.isPlain());
        ui.toggleNavigator();
        assertFalse(ui.navigatorShown());
        assertFalse(ui.isPlain(), "Ctrl+B hides it as a split dragged shut, not as a plain window");
    }

    private static String name(Buffer b) {
        return b == null || b.path() == null ? null : String.valueOf(b.path().getFileName());
    }

    @Test
    void openShowsTheRootAmongItsSiblingsAndPickingOneMakesItTheRootAndGoesBackToEdit() throws Exception {
        Path code = Files.createDirectories(dir.resolve("code"));
        Path indexer = Files.createDirectories(code.resolve("indexer"));
        Path vexelray = Files.createDirectories(code.resolve("vexelray"));
        Navigator nav = h.nav();
        rooted(indexer);

        nav.mode(Navigator.Mode.OPEN);
        eventually("the root selected among its siblings",
                () -> indexer.toAbsolutePath().normalize().equals(nav.picker().selected()));

        nav.pick(vexelray);
        assertEquals(Navigator.Mode.EDIT, nav.mode());
        eventually("the new root", () -> vexelray.toAbsolutePath().normalize().equals(nav.folder()));
        eventually("the old root is the recent one",
                () -> indexer.toAbsolutePath().normalize().equals(h.recent().first()));
        assertNull(h.recent().second());

        nav.mode(Navigator.Mode.OPEN);
        nav.pick(vexelray);
        assertEquals(Navigator.Mode.EDIT, nav.mode(), "picking the root it already is just goes back");
        Thread.sleep(100);
        assertEquals(indexer.toAbsolutePath().normalize(), h.recent().first());
        assertNull(h.recent().second());
    }

    @Test
    void openCanWidenToAnyAncestorAndTheRootStaysSelectedUnderIt() throws Exception {
        Path module = Files.createDirectories(dir.resolve("code/project/module"));
        Path top = dir.toAbsolutePath().normalize();
        Navigator nav = h.nav();
        rooted(module);

        nav.mode(Navigator.Mode.OPEN);
        eventually("the root selected", () -> module.toAbsolutePath().normalize().equals(nav.picker().selected()));
        nav.widen(top);
        eventually("the top widened", () -> top.equals(nav.openTop()));
        eventually("the root unfolded and selected under it",
                () -> module.toAbsolutePath().normalize().equals(nav.picker().selected()));
        assertEquals(module.toAbsolutePath().normalize(), nav.folder(), "widening is not picking");
        assertEquals(Navigator.Mode.OPEN, nav.mode());
    }

    @Test
    void theAncestryRunsFromTheTopOfTheDriveDownToTheFolder() {
        Path folder = dir.toAbsolutePath().normalize();
        List<Path> chain = Navigator.ancestors(folder);
        assertEquals(folder.getRoot(), chain.getFirst());
        assertEquals(folder, chain.getLast());
        assertEquals(folder.getNameCount() + 1, chain.size());
    }

    @Test
    void theStatusLineNamesTheDeclarationTheCaretIsIn() throws Exception {
        String source = """
                package shapes;
                class Outer {
                    int count;
                    static class Inner {
                        void run() {
                            go();
                        }
                    }
                }
                """;
        Buffer b = opened(Files.writeString(dir.resolve("Outer.java"), source));
        b.field.caret(source.indexOf("go()"));
        eventually("the scope", () -> b.position().scope().equals("Outer › Inner › run"));
        b.field.caret(source.indexOf("count"));
        eventually("the field", () -> b.position().scope().equals("Outer › count"));
        b.field.caret(0);
        eventually("nothing before the first declaration", () -> b.position().scope().isEmpty());
    }

    @Test
    void ctrlEnterGoesToTheDeclarationAndAgainToTheNextOne() throws Exception {
        Path project = Files.createDirectories(dir.resolve("shapes"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId><artifactId>shapes</artifactId><version>1</version></project>
                """);
        Path src = Files.createDirectories(project.resolve("src/main/java/shapes"));
        Path shape = Files.writeString(src.resolve("Shape.java"),
                "package shapes;\ninterface Shape {\n    double area();\n}\n");
        Path circle = Files.writeString(src.resolve("Circle.java"),
                "package shapes;\nfinal class Circle implements Shape {\n    public double area() { return 1; }\n}\n");
        String mainText = "package shapes;\nclass Main {\n    double d = new Circle().area();\n}\n";
        Path main = Files.writeString(src.resolve("Main.java"), mainText);

        rooted(project);
        eventually("the index", () -> h.wiring.projectIndex().current() != null);
        Buffer m = opened(main);
        m.field.caret(mainText.indexOf("area") + 2);

        // Two declarations of area: Circle's and Shape's, visited in file order, then round again.
        h.tell(Command.DECLARATION, Messages.NO_TAB);
        eventually("Circle's area", () -> isOnArea(h.ws().front(), circle));
        eventually("the count", () -> h.status().contains("1 of 2"));
        h.tell(Command.DECLARATION, Messages.NO_TAB);
        eventually("Shape's area", () -> isOnArea(h.ws().front(), shape));
        h.tell(Command.DECLARATION, Messages.NO_TAB);
        eventually("Circle's again", () -> isOnArea(h.ws().front(), circle));
    }

    private static boolean isOnArea(Buffer b, Path file) {
        if (b == null || !file.toAbsolutePath().normalize().equals(b.path())) {
            return false;
        }
        Buffer.Word w = b.wordAtCaret();
        return w != null && w.text().equals("area") && w.start() == b.field.text().indexOf("area");
    }

    @Test
    void ctrlEnterOnALibraryNameOpensItsSourcesReadOnly() throws Exception {
        goesIntoTheLibrary(true);
    }

    @Test
    void ctrlEnterOnALibraryNameWithoutSourcesOpensItDecompiled() throws Exception {
        goesIntoTheLibrary(false);
    }

    /**
     * A project that depends on {@code g:lib:1}, in a local repository of the test's own, with or without its
     * sources jar: Ctrl+Enter on the library's method opens the library's class read-only, on the method, and
     * Ctrl+S says it cannot be saved.
     */
    private void goesIntoTheLibrary(boolean withSources) throws Exception {
        String libSource = """
                package lib;

                public class Greeter {
                    public String greet() {
                        return "hello";
                    }
                }
                """;
        Path repo = Files.createDirectories(dir.resolve("repo/g/lib/1"));
        Files.writeString(repo.resolve("lib-1.pom"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId><artifactId>lib</artifactId><version>1</version></project>
                """);
        Path classes = Files.createDirectories(dir.resolve("lib-classes"));
        Path javaFile = Files.writeString(Files.createDirectories(dir.resolve("lib-src/lib")).resolve("Greeter.java"),
                libSource);
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-d", classes.toString(), javaFile.toString()));
        jar(repo.resolve("lib-1.jar"), "lib/Greeter.class", Files.readAllBytes(classes.resolve("lib/Greeter.class")));
        if (withSources) {
            jar(repo.resolve("lib-1-sources.jar"), "lib/Greeter.java", libSource.getBytes());
        }

        Path project = Files.createDirectories(dir.resolve("app"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId><artifactId>app</artifactId><version>1</version>
                <dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId><version>1</version>
                </dependency></dependencies></project>
                """);
        String mainText = "package app;\nimport lib.Greeter;\nclass Main {\n    String s = new Greeter().greet();\n}\n";
        Path main = Files.writeString(Files.createDirectories(project.resolve("src/main/java/app")).resolve("Main.java"),
                mainText);

        String was = System.getProperty("maven.repo.local");
        System.setProperty("maven.repo.local", dir.resolve("repo").toString());
        try {
            rooted(project);
            eventually("the index, with the library",
                    () -> h.wiring.projectIndex().current() != null && !h.wiring.projectIndex().current()
                            .namesContaining("greet").isEmpty());
        } finally {
            if (was == null) {
                System.clearProperty("maven.repo.local");
            } else {
                System.setProperty("maven.repo.local", was);
            }
        }
        Buffer m = opened(main);
        m.field.caret(mainText.indexOf("greet") + 1);

        h.tell(Command.DECLARATION, Messages.NO_TAB);
        eventually("the library's class, on greet", () -> {
            Buffer b = h.ws().front();
            if (b == null || b.library() == null) {
                return false;
            }
            Buffer.Word w = b.wordAtCaret();
            return w != null && w.text().equals("greet") && b.field.text().contains("class Greeter");
        });
        Buffer lib = h.ws().front();
        assertTrue(lib.readOnly() && lib.field.readOnly());
        assertEquals("Greeter.java", lib.name());
        assertNull(lib.path(), "a library's source is not a file the session remembers");
        eventually("where it went", () -> h.status().contains("lib-1"));

        h.tell(Command.SAVE_FRONT, Messages.NO_TAB);
        eventually("the refusal", () -> h.status().contains("read-only"));
        assertFalse(lib.dirty());
    }

    private static void jar(Path at, String entry, byte[] bytes) throws java.io.IOException {
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(at))) {
            out.putNextEntry(new java.util.zip.ZipEntry(entry));
            out.write(bytes);
            out.closeEntry();
        }
    }
}
