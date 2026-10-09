package dev.vexelray.demo.editor;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's real tree, built headless by the generated wiring — no window, no GPU — and driven through the
 * same objects a user's keystrokes reach.
 */
class EditorTreeTest {

    @TempDir
    Path dir;

    private Shell shell;
    private TextEditorAppWiring wiring;

    @BeforeEach
    void build() {
        wiring = new TextEditorAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
    }

    @AfterEach
    void dispose() {
        shell.disposer().close();
    }

    private static void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void theTreeStartsWithOneEmptyDocument() {
        Workspace ws = wiring.ui().workspace();
        assertEquals(1, ws.all().size());
        assertTrue(ws.front().pristine());
    }

    @Test
    void openingAFileTakesOverThePristineTabAndHighlightsIt() throws Exception {
        Path file = Files.writeString(dir.resolve("Hello.java"), "class Hello { String s = \"hi\"; }\n");
        wiring.actions().load(file);
        Workspace ws = wiring.ui().workspace();
        eventually("the untitled tab to go", () -> ws.all().size() == 1 && ws.front().path() != null);
        Buffer b = ws.front();
        assertEquals("Java", b.language());
        eventually("highlighting", () -> !b.field.spans().isEmpty());
    }

    @Test
    void typingMakesADocumentDirtyAndTheTitleSaysSo() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "hello");
        wiring.actions().load(file);
        Workspace ws = wiring.ui().workspace();
        eventually("the file", () -> ws.front() != null && ws.front().path() != null);
        Buffer b = ws.front();
        assertFalse(b.dirty());
        b.field.insert("!");
        eventually("dirty", b::dirty);
        eventually("the model to hear", () -> wiring.model().doc().entry(b.id).dirty());
        assertEquals("• a.txt", wiring.model().doc().entry(b.id).title());
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanOpeningItTwice() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        Actions actions = wiring.actions();
        actions.load(a);
        actions.load(c);
        Workspace ws = wiring.ui().workspace();
        eventually("two tabs", () -> ws.all().size() == 2);
        actions.open(a);
        eventually("a in front", () -> a.toAbsolutePath().normalize().equals(ws.front().path()));
        assertEquals(2, ws.all().size());
    }

    /** Dialogs that answer Save As with {@code target}, or cancel when it is null. */
    private static Dialogs savingTo(Path target) {
        return new Dialogs() {
            @Override
            public void openFile(Path start, java.util.function.Consumer<Path> picked) {
            }

            @Override
            public void openFolder(Path start, java.util.function.Consumer<Path> picked) {
            }

            @Override
            public void saveFile(Path start, String name, java.util.function.Consumer<Path> picked, Runnable cancelled) {
                if (target == null) {
                    cancelled.run();
                } else {
                    picked.accept(target);
                }
            }
        };
    }

    /** Answer every question by pressing the button labelled {@code label}, and remember the question. */
    private static java.util.function.Consumer<dev.vexelray.gui.widget.Modal> pressing(String label,
            java.util.List<String> asked) {
        return modal -> {
            asked.add(modal.title());
            modal.buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow().action().run();
        };
    }

    @Test
    void savingWritesTheFileKeepsItsLineEndingsAndCleansTheTab() throws Exception {
        Path file = dir.resolve("crlf.txt");
        Files.write(file, "one\r\ntwo\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wiring.actions().load(file);
        Workspace ws = wiring.ui().workspace();
        eventually("the file", () -> ws.front() != null && ws.front().path() != null);
        Buffer b = ws.front();
        b.field.caret(b.field.text().length());
        b.field.insert("three\n");
        eventually("dirty", b::dirty);
        wiring.actions().save();
        // Clean the moment the bytes are taken, written a moment later: wait for the write to say it landed.
        eventually("the status to say so", () -> wiring.model().doc().status().startsWith("Saved"));
        assertFalse(b.dirty());
        assertFalse(wiring.model().doc().entry(b.id).dirty());
        assertEquals("one\r\ntwo\r\nthree\r\n", Files.readString(file));
    }

    @Test
    void anUntitledDocumentIsSavedWhereTheDialogSays() throws Exception {
        Path target = dir.resolve("new.py");
        Actions actions = wiring.actions();
        actions.dialogs(savingTo(target));
        Buffer b = wiring.ui().workspace().front();
        b.field.insert("print('hi')");
        actions.save();
        eventually("the file to exist", () -> Files.exists(target));
        assertEquals("print('hi')", Files.readString(target));
        eventually("the tab to know its name", () -> target.equals(wiring.model().doc().entry(b.id).path()));
        assertEquals("Python", b.language());
    }

    @Test
    void theCloseGateLetsACleanSessionGoWithoutAsking() {
        java.util.List<String> asked = new java.util.ArrayList<>();
        wiring.actions().ask(pressing("Cancel", asked));
        boolean[] proceeded = {false};
        wiring.actions().guardClose(() -> proceeded[0] = true, () -> { });
        assertTrue(proceeded[0]);
        assertTrue(asked.isEmpty());
    }

    @Test
    void theCloseGateAsksAboutUnsavedWorkAndCancelKeepsTheWindow() throws Exception {
        Buffer b = wiring.ui().workspace().front();
        b.field.insert("unsaved");
        eventually("dirty", b::dirty);
        java.util.List<String> asked = new java.util.ArrayList<>();
        wiring.actions().ask(pressing("Cancel", asked));
        boolean[] answer = {false, false};
        wiring.actions().guardClose(() -> answer[0] = true, () -> answer[1] = true);
        assertEquals(java.util.List.of("Quit with unsaved changes?"), asked);
        assertFalse(answer[0]);
        assertTrue(answer[1]);
    }

    @Test
    void saveAllThenQuitCancelsTheQuitWhenTheSaveDialogIsCancelled() throws Exception {
        Buffer b = wiring.ui().workspace().front();
        b.field.insert("unsaved");
        eventually("dirty", b::dirty);
        wiring.actions().dialogs(savingTo(null));
        wiring.actions().ask(pressing("Save all", new java.util.ArrayList<>()));
        java.util.concurrent.atomic.AtomicInteger proceeded = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cancelled = new java.util.concurrent.atomic.AtomicInteger();
        wiring.actions().guardClose(proceeded::incrementAndGet, cancelled::incrementAndGet);
        eventually("an answer", () -> proceeded.get() + cancelled.get() > 0);
        assertEquals(0, proceeded.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    void saveAllThenQuitProceedsOnceEverythingIsWritten() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Actions actions = wiring.actions();
        actions.load(a);
        Workspace ws = wiring.ui().workspace();
        eventually("a", () -> ws.front() != null && ws.front().path() != null);
        ws.front().field.insert("1");
        Buffer untitled = ws.untitled();
        untitled.field.insert("2");
        eventually("both dirty", () -> wiring.model().doc().unsaved().size() == 2);
        actions.dialogs(savingTo(dir.resolve("b.txt")));
        actions.ask(pressing("Save all", new java.util.ArrayList<>()));
        java.util.concurrent.atomic.AtomicInteger proceeded = new java.util.concurrent.atomic.AtomicInteger();
        actions.guardClose(proceeded::incrementAndGet, () -> { });
        eventually("the quit to proceed", () -> proceeded.get() == 1);
        assertEquals("1a", Files.readString(a));
        assertEquals("2", Files.readString(dir.resolve("b.txt")));
    }

    @Test
    void closingADirtyTabAsksAndDontSaveCloses() throws Exception {
        Workspace ws = wiring.ui().workspace();
        Buffer b = ws.front();
        b.field.insert("x");
        eventually("dirty", b::dirty);
        java.util.List<String> asked = new java.util.ArrayList<>();
        wiring.actions().ask(pressing("Don't save", asked));
        wiring.actions().close(b);
        assertEquals(java.util.List.of("Unsaved changes"), asked);
        assertTrue(ws.byId(b.id) == null);
    }

    @Test
    void closingTheLastTabLeavesAnEmptyOne() throws Exception {
        Workspace ws = wiring.ui().workspace();
        Buffer only = ws.front();
        ws.close(only.id);
        assertEquals(1, ws.all().size());
        assertNotNull(ws.front());
        assertTrue(ws.front() != only && ws.front().pristine());
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

        ProjectIndex index = wiring.projectIndex();
        wiring.actions().showFolder(project);
        eventually("the index", () -> index.current() != null);
        assertEquals(project.toAbsolutePath().normalize(), index.root().toAbsolutePath().normalize());
        assertEquals(java.util.List.of("Circle"),
                index.current().implementationsOf("Shape").stream().map(s -> s.name()).toList());
        eventually("the status line to say so",
                () -> wiring.model().doc().status().startsWith("Indexed shapes"));

        Path plain = Files.createDirectories(dir.resolve("plain"));
        wiring.actions().showFolder(plain);
        eventually("the index to be forgotten", () -> index.current() == null && index.root() == null);
    }

    @Test
    void openShowsTheRootAmongItsSiblingsAndPickingOneMakesItTheRootAndGoesBackToEdit() throws Exception {
        Path code = Files.createDirectories(dir.resolve("code"));
        Path indexer = Files.createDirectories(code.resolve("indexer"));
        Path vexelray = Files.createDirectories(code.resolve("vexelray"));
        Navigator nav = wiring.ui().navigator();
        wiring.actions().showFolder(indexer);
        eventually("the root", () -> indexer.toAbsolutePath().normalize().equals(wiring.model().doc().folder()));

        nav.mode(Navigator.Mode.OPEN);
        eventually("the root selected among its siblings",
                () -> indexer.toAbsolutePath().normalize().equals(nav.picker().selected()));

        nav.pick(vexelray);
        assertEquals(Navigator.Mode.EDIT, nav.mode());
        eventually("the new root", () -> vexelray.toAbsolutePath().normalize().equals(wiring.model().doc().folder()));
        assertEquals(java.util.List.of(indexer.toAbsolutePath().normalize()), wiring.model().doc().recent(),
                "the old root is the recent one");

        nav.mode(Navigator.Mode.OPEN);
        nav.pick(vexelray);
        assertEquals(Navigator.Mode.EDIT, nav.mode(), "picking the root it already is just goes back");
        assertEquals(java.util.List.of(indexer.toAbsolutePath().normalize()), wiring.model().doc().recent());
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
        Path file = Files.writeString(dir.resolve("Outer.java"), source);
        wiring.actions().load(file);
        Workspace ws = wiring.ui().workspace();
        eventually("the file", () -> ws.front() != null && ws.front().path() != null);
        Buffer b = ws.front();
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

        Actions actions = wiring.actions();
        actions.showFolder(project);
        eventually("the index", () -> wiring.projectIndex().current() != null);
        actions.load(main);
        Workspace ws = wiring.ui().workspace();
        eventually("Main", () -> ws.front() != null && main.equals(ws.front().path()));
        ws.front().field.caret(mainText.indexOf("area") + 2);

        // Two declarations of area: Circle's and Shape's, visited in file order, then round again.
        actions.goToDeclaration();
        eventually("Circle's area", () -> isOnArea(ws.front(), circle));
        eventually("the count", () -> wiring.model().doc().status().contains("1 of 2"));
        actions.goToDeclaration();
        eventually("Shape's area", () -> isOnArea(ws.front(), shape));
        actions.goToDeclaration();
        eventually("Circle's again", () -> isOnArea(ws.front(), circle));
    }

    private static boolean isOnArea(Buffer b, Path file) {
        if (b == null || !file.equals(b.path())) {
            return false;
        }
        Buffer.Word w = b.wordAtCaret();
        return w != null && w.text().equals("area") && w.start() == b.field.text().indexOf("area");
    }
}
