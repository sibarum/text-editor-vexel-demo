package dev.vexelray.demo.editor;

import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
    /** How a question is answered: by default it fails the test, since nothing should be asked unexpectedly. */
    private volatile Consumer<Modal> answers = modal -> {
        throw new AssertionError("asked unexpectedly: " + modal.title());
    };
    /** Where Save As answers, or null to cancel it. */
    private volatile Path saveTo;

    @BeforeEach
    void build() {
        wiring = new TextEditorAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
        // This test's dialogs in place of the framework's, which would hold every question for a window that never
        // comes. Taken back as late as WINDOW, so handing them over after the tree is built is in time.
        shell.dialogs(new Answering());
    }

    /** Dialogs that answer a question with {@link #answers}, Save As with {@link #saveTo}, and cancel the rest. */
    private final class Answering implements Dialogs {

        @Override
        public void ask(Modal modal) {
            answers.accept(modal);
        }

        @Override
        public void openFile(Path start, List<FileDialog.Filter> filters, Consumer<Path> picked, Runnable cancelled) {
            cancelled.run();
        }

        @Override
        public void openFolder(Path start, Consumer<Path> picked, Runnable cancelled) {
            cancelled.run();
        }

        @Override
        public void saveFile(Path start, String name, List<FileDialog.Filter> filters, Consumer<Path> picked,
                             Runnable cancelled) {
            Path target = saveTo;
            if (target == null) {
                cancelled.run();
            } else {
                picked.accept(target);
            }
        }
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
        saveTo = target;
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
        answers = pressing("Cancel", asked);
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
        answers = pressing("Cancel", asked);
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
        saveTo = null;
        answers = pressing("Save all", new java.util.ArrayList<>());
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
        saveTo = dir.resolve("b.txt");
        answers = pressing("Save all", new java.util.ArrayList<>());
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
        answers = pressing("Don't save", asked);
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

    /** The files open now, in tab order, by name. */
    private java.util.List<String> tabNames() {
        return wiring.ui().workspace().all().stream()
                .map(b -> b.path() == null ? "Untitled" : String.valueOf(b.path().getFileName()))
                .toList();
    }

    @Test
    void switchingRootsPutsTheTabsAwayAndSwitchingBackBringsThemBack() throws Exception {
        Path one = Files.createDirectories(dir.resolve("one"));
        Path two = Files.createDirectories(dir.resolve("two"));
        Path a = Files.writeString(one.resolve("a.txt"), "a");
        Path b = Files.writeString(one.resolve("b.txt"), "b");
        Path c = Files.writeString(two.resolve("c.txt"), "c");
        Actions actions = wiring.actions();
        Workspace ws = wiring.ui().workspace();

        actions.showFolder(one);
        actions.load(a);
        actions.load(b);
        actions.open(a);
        eventually("a in front of a and b",
                () -> tabNames().equals(java.util.List.of("a.txt", "b.txt")) && "a.txt".equals(name(ws.front())));

        actions.switchRoot(two);
        eventually("a root never visited to start empty", () -> tabNames().equals(java.util.List.of("Untitled")));
        actions.load(c);
        eventually("c", () -> tabNames().equals(java.util.List.of("c.txt")));

        actions.switchRoot(one);
        eventually("a and b back, a in front",
                () -> tabNames().equals(java.util.List.of("a.txt", "b.txt")) && "a.txt".equals(name(ws.front())));

        actions.switchRoot(two);
        eventually("c back", () -> tabNames().equals(java.util.List.of("c.txt")));
    }

    /** A root {@code one} with {@code a.txt} open in it and typed into, unsaved. */
    private Buffer dirtyTabIn(Path one) throws Exception {
        Path a = Files.writeString(one.resolve("a.txt"), "a");
        wiring.actions().showFolder(one);
        wiring.actions().load(a);
        Workspace ws = wiring.ui().workspace();
        eventually("a", () -> tabNames().equals(java.util.List.of("a.txt")));
        Buffer b = ws.front();
        b.field.insert("!");
        eventually("dirty", b::dirty);
        return b;
    }

    @Test
    void goingToOpenAsksAboutUnsavedWorkAndCancelStaysInEdit() throws Exception {
        Buffer b = dirtyTabIn(Files.createDirectories(dir.resolve("one")));
        java.util.List<String> asked = new java.util.ArrayList<>();
        answers = pressing("Cancel", asked);
        Navigator nav = wiring.ui().navigator();
        nav.askToOpen();
        assertEquals(java.util.List.of("Unsaved changes"), asked);
        assertEquals(Navigator.Mode.EDIT, nav.mode());
        assertTrue(b.dirty());
    }

    @Test
    void discardingOnTheWayToOpenPutsTheFileBackAndKeepsItsTab() throws Exception {
        Buffer b = dirtyTabIn(Files.createDirectories(dir.resolve("one")));
        java.util.List<String> asked = new java.util.ArrayList<>();
        answers = pressing("Discard", asked);
        Navigator nav = wiring.ui().navigator();
        nav.askToOpen();
        eventually("Open", () -> nav.mode() == Navigator.Mode.OPEN);
        assertEquals(java.util.List.of("Unsaved changes"), asked);
        assertFalse(b.dirty());
        assertEquals("a", b.field.text());
        assertEquals(java.util.List.of("a.txt"), tabNames());
    }

    @Test
    void goingToOpenWithNothingUnsavedAsksNothing() throws Exception {
        java.util.List<String> asked = new java.util.ArrayList<>();
        answers = pressing("Cancel", asked);
        Navigator nav = wiring.ui().navigator();
        nav.askToOpen();
        eventually("Open", () -> nav.mode() == Navigator.Mode.OPEN);
        assertTrue(asked.isEmpty());
    }

    @Test
    void aLaunchOnAFileIsPlainUntilAFolderIsOpened() throws Exception {
        Path script = Files.writeString(dir.resolve("env.ps1"), "$env:A = 1\n");
        Path more = Files.writeString(dir.resolve("more.txt"), "more");
        Path project = Files.createDirectories(dir.resolve("project"));
        Ui ui = wiring.ui();
        Actions actions = wiring.actions();

        wiring.session().restore(actions, java.util.List.of(script), Runnable::run);
        assertTrue(ui.isPlain());
        assertFalse(ui.navigatorShown());
        assertEquals(java.util.List.of("env.ps1"), tabNames());

        actions.load(more);
        eventually("more", () -> tabNames().equals(java.util.List.of("env.ps1", "more.txt")));
        assertTrue(ui.isPlain(), "another file keeps the window plain");

        // The folder dialog's route; the first root of a window joins the tabs it has rather than replacing them.
        actions.switchRoot(project);
        eventually("the navigator on the folder",
                () -> ui.navigatorShown() && project.toAbsolutePath().normalize().equals(ui.navigator().folder()));
        assertFalse(ui.isPlain());
        assertEquals(java.util.List.of("env.ps1", "more.txt"), tabNames());
    }

    @Test
    void aLaunchWithAFolderIsNotPlain() throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path file = Files.writeString(project.resolve("a.txt"), "a");
        Ui ui = wiring.ui();
        wiring.session().restore(wiring.actions(), java.util.List.of(project, file), Runnable::run);
        assertFalse(ui.isPlain());
        eventually("the navigator on the folder",
                () -> ui.navigatorShown() && project.toAbsolutePath().normalize().equals(ui.navigator().folder()));
    }

    @Test
    void ctrlBInAPlainWindowShowsTheNavigatorAndHidesItAsUsual() {
        Ui ui = wiring.ui();
        wiring.actions().plain();
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
    void openCanWidenToAnyAncestorAndTheRootStaysSelectedUnderIt() throws Exception {
        Path module = Files.createDirectories(dir.resolve("code/project/module"));
        Path top = dir.toAbsolutePath().normalize();
        Navigator nav = wiring.ui().navigator();
        wiring.actions().showFolder(module);
        eventually("the root", () -> module.toAbsolutePath().normalize().equals(wiring.model().doc().folder()));

        nav.mode(Navigator.Mode.OPEN);
        eventually("the root selected", () -> module.toAbsolutePath().normalize().equals(nav.picker().selected()));
        nav.widen(top);
        eventually("the top widened", () -> top.equals(nav.openTop()));
        eventually("the root unfolded and selected under it",
                () -> module.toAbsolutePath().normalize().equals(nav.picker().selected()));
        assertEquals(module.toAbsolutePath().normalize(), wiring.model().doc().folder(), "widening is not picking");
        assertEquals(Navigator.Mode.OPEN, nav.mode());
    }

    @Test
    void theAncestryRunsFromTheTopOfTheDriveDownToTheFolder() {
        Path folder = dir.toAbsolutePath().normalize();
        java.util.List<Path> chain = Navigator.ancestors(folder);
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
            Actions actions = wiring.actions();
            actions.showFolder(project);
            eventually("the index, with the library",
                    () -> wiring.projectIndex().current() != null && !wiring.projectIndex().current()
                            .namesContaining("greet").isEmpty());
        } finally {
            if (was == null) {
                System.clearProperty("maven.repo.local");
            } else {
                System.setProperty("maven.repo.local", was);
            }
        }
        Actions actions = wiring.actions();
        actions.load(main);
        Workspace ws = wiring.ui().workspace();
        eventually("Main", () -> ws.front() != null && main.equals(ws.front().path()));
        ws.front().field.caret(mainText.indexOf("greet") + 1);

        actions.goToDeclaration();
        eventually("the library's class, on greet", () -> {
            Buffer b = ws.front();
            if (b == null || b.library() == null) {
                return false;
            }
            Buffer.Word w = b.wordAtCaret();
            return w != null && w.text().equals("greet") && b.field.text().contains("class Greeter");
        });
        Buffer lib = ws.front();
        assertTrue(lib.readOnly() && lib.field.readOnly());
        assertEquals("Greeter.java", wiring.model().doc().front().title());
        assertTrue(wiring.model().doc().files().stream().noneMatch(p -> p.getFileName().toString().equals("Greeter.java")),
                "a library's source is not a file the session remembers");
        eventually("where it went", () -> wiring.model().doc().status().contains("lib-1"));

        actions.save();
        eventually("the refusal", () -> wiring.model().doc().status().contains("read-only"));
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
