package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.Caption;
import dev.vexelray.demo.editor.core.Messages.Go;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.Picture;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.demo.editor.core.SurveyFolder;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Cuts;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Sender;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole application, built headless by the generated wiring (no window, no GPU) and driven through the same
 * messages a user's gestures send.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and starts the components, so the editor and the
 * jobs run on their own lanes here, exactly as in the window. The view runs in frames, and there is no loop to run
 * them, so the test runs them while it waits ({@code VexelApplication.frame}): this thread is the GUI thread. No window
 * opens, so the framework never says {@code WindowOpened} for the main one: no session is restored and no settings are
 * written, and this test leaves the user's own settings alone.
 *
 * <p>The test listens on the bus as one more subscriber, which is how it sees what the editor told the view. Every
 * part answers in its own time, so the test waits for what it asked for, running frames, rather than sleeping.
 */
class ViewTest {

    @TempDir
    Path dir;

    private Shell shell;
    private TextEditorAppWiring wiring;
    private Workspace ws;
    /** Every caption and status line the editor sent, in order. */
    private final List<Object> said = new CopyOnWriteArrayList<>();
    /** How this test answers a question: by default, it fails the test, since nothing should be asked unexpectedly. */
    private volatile Consumer<Modal> answer = modal -> {
        throw new AssertionError("asked unexpectedly: " + modal.title());
    };

    @BeforeEach
    void build() {
        wiring = new TextEditorAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
        // This test's dialogs in place of the framework's, which would hold every question for a window that never
        // comes. Taken back as late as WINDOW, so handing them over after the tree is built is in time.
        shell.dialogs(new Answering());
        shell.bus().subscribe(Messages.CAPTION_TOPIC, said::add);
        shell.bus().subscribe(Messages.STATUS_TOPIC, said::add);
        ws = wiring.ui().workspace();
        cuts = Cuts.watch(shell.gui());
    }

    /**
     * What changed on screen in one frame while a test ran, which every test here then fails on: nothing this
     * application shows changes in one frame. The framework fades it all (a colour, an opacity, words, an image, a node
     * arriving, leaving, hidden or shown) as long as the view keeps its nodes and changes them. A cut found here means
     * a node was rebuilt where it should have been changed, or something stepped round the framework. Fix that; do not
     * take this watch out. If the framework cannot do it, that is a framework note (docs/framework-notes.md).
     */
    private Cuts cuts;

    /** Dialogs that answer a question with {@link #answer}, and cancel every file picker. */
    private final class Answering implements Dialogs {

        @Override
        public void ask(Modal modal) {
            answer.accept(modal);
        }

        @Override
        public void pick(PickFile request, Sender<Picked> picked) {
            picked.send(Picked.cancelled(request.id()));
        }
    }

    @AfterEach
    void dispose() {
        List<Cuts.Cut> found = cuts.found();
        cuts.close();
        shell.disposer().close();
        assertEquals(List.of(), found, "changed on screen in one frame; keep the node and change it (see cuts)");
    }

    /**
     * Run frames until {@code test} holds. The view hears only in a frame, and this thread runs them, so it is the GUI
     * thread here as the loop's is in the window: what the test reads of the view is read where the view writes it.
     */
    private void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        VexelApplication.frame(shell);
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
            VexelApplication.frame(shell);
        }
    }

    /**
     * Answer every question as pressing the button labelled {@code label} would: send the message the button declares,
     * which a dialog carries as data. Remember what was asked.
     */
    private Consumer<Modal> pressing(String label, List<String> asked) {
        return modal -> {
            asked.add(modal.title());
            Object message = modal.buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow()
                    .message();
            shell.bus().publish(Answer.TOPIC, (Answer) message);
        };
    }

    /**
     * Ask what the framework asks when the main window's close button is pressed, and run {@code proceeded} or
     * {@code cancelled} on the answer, as the framework does with the window's own two.
     */
    private void quit(Runnable proceeded, Runnable cancelled) {
        long ticket = tickets.incrementAndGet();
        shell.bus().subscribe(CloseAnswer.TOPIC, a -> {
            if (a.ticket() == ticket) {
                (a.proceed() ? proceeded : cancelled).run();
            }
        });
        shell.bus().publish(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, ticket));
    }

    /** Far from the framework's own tickets, which start at 1, so its gate ignores these answers. */
    private final AtomicInteger tickets = new AtomicInteger(1_000_000);

    private void tell(Intent.Command command) {
        shell.bus().publish(Intent.INTENTS, new Intent(command));
    }

    private Buffer opened(Path file) throws InterruptedException {
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(file, true));
        eventually("the file to open", () -> ws.find(file) != null);
        return ws.find(file);
    }

    @Test
    void itStartsWithNothingOpen() {
        assertTrue(ws.all().isEmpty());
    }

    @Test
    void aFileOpensThroughTheEditorAndTheReadingJob() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "hello"));
        assertEquals("hello", b.field.text());
        eventually("the caption", () -> said.contains(new Caption("a.txt")));
    }

    @Test
    void typingMakesAFileUnsavedAndTheCaptionSaysSo() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "hello"));
        assertFalse(b.dirty());
        b.field.insert("!");
        eventually("the editor to say so", () -> said.contains(new Caption("• a.txt")));
    }

    @Test
    void savingWritesTheFileAndKeepsItsLineEndings() throws Exception {
        Path file = dir.resolve("crlf.txt");
        Files.write(file, "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        Buffer b = opened(file);
        b.field.caret(b.field.text().length());
        b.field.insert("three\n");
        eventually("unsaved", b::dirty);
        tell(Intent.Command.SAVE_FRONT);
        // Clean the moment the text is taken, written a moment later: wait for the write to say it landed.
        eventually("the status to say so", () -> said.contains(new Status("Saved crlf.txt", false)));
        assertFalse(b.dirty());
        assertEquals("one\r\ntwo\r\nthree\r\n", Files.readString(file));
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanOpeningItTwice() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        opened(a);
        opened(Files.writeString(dir.resolve("b.txt"), "b"));
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(a, true));
        eventually("a in front", () -> ws.front() != null && ws.front().path.equals(a.toAbsolutePath().normalize()));
        assertEquals(2, ws.all().size());
    }

    /** A format with a grammar is coloured by MarkText, through the Grammars part the recipe builds; plain text is not. */
    @Test
    void aFileInAKnownFormatIsGivenSpansAndATextFileIsNot() throws Exception {
        Buffer md = opened(Files.writeString(dir.resolve("notes.md"), "# Title\n\nSome *emphasis*."));
        Buffer java = opened(Files.writeString(dir.resolve("A.java"), "final class A { int n = 4; }"));
        Buffer txt = opened(Files.writeString(dir.resolve("notes.txt"), "# Title\n\nSome *emphasis*."));
        eventually("the markdown to be marked", () -> !md.field.spans().isEmpty());
        eventually("the java to be marked", () -> !java.field.spans().isEmpty());
        assertTrue(txt.field.spans().isEmpty());
        assertEquals("Markdown", md.language());
        assertEquals("Java", java.language());
        assertEquals("Plain text", txt.language());
    }

    /** Ctrl+Shift+V on Markdown shows it formatted, read by FormatText; again, and it is the source once more. */
    @Test
    void markdownSwitchesToItsFormattedViewAndBack() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("README.md"), "# Title\n\nSome *emphasis*.\n\n- one\n- two\n"));
        tell(Intent.Command.TOGGLE_FORMATTED);
        eventually("the formatted view", () -> b.showingFormatted() && !b.formatted().texts().isEmpty());
        assertEquals(List.of("Title", "Some emphasis.", "one", "two"), b.formatted().texts());

        tell(Intent.Command.TOGGLE_FORMATTED);
        eventually("the source", () -> !b.showingFormatted());
    }

    /** The text changing under the formatted view, as a revert would, reads it again. */
    @Test
    void theFormattedViewFollowsTheText() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("README.md"), "# Title\n"));
        tell(Intent.Command.TOGGLE_FORMATTED);
        eventually("the formatted view", () -> b.formatted().texts().equals(List.of("Title")));
        b.field.text("# Other\n");
        eventually("it to follow", () -> b.formatted().texts().equals(List.of("Other")));
    }

    /** A text file has no formatted view to switch to, so the switch leaves its source in front. */
    @Test
    void onlyMarkdownHasAFormattedView() throws Exception {
        Buffer txt = opened(Files.writeString(dir.resolve("notes.txt"), "# Title"));
        assertNull(txt.formatted());
        tell(Intent.Command.TOGGLE_FORMATTED);
        // A message after the intent, to wait on: the view hears them in order, so once this is in, so was that.
        Buffer md = opened(Files.writeString(dir.resolve("README.md"), "# Title"));
        assertFalse(txt.showingFormatted());
        assertFalse(md.showingFormatted());
    }

    /**
     * Ctrl+O puts Open in place of the files, starting at the folder above the one shown with that one selected among
     * its siblings; a click on another shows it and ends Open, and Esc's intent ends it without picking.
     */
    @Test
    void openShowsTheFoldersSiblingsAndAClickPicksOne() throws Exception {
        Path a = Files.createDirectory(dir.resolve("a")).toAbsolutePath().normalize();
        Path b = Files.createDirectory(dir.resolve("b")).toAbsolutePath().normalize();
        Files.writeString(dir.resolve("note.txt"), "not a folder");
        Navigator nav = wiring.ui().navigator();
        shell.bus().publish(Messages.GO_TOPIC, new Go(a.toString()));
        eventually("a shown", () -> a.equals(nav.folder()));
        assertEquals("a", nav.chipLabel());

        tell(Intent.Command.TOGGLE_OPEN);
        Picker picker = nav.picker();
        eventually("Open, with a selected among its siblings", () -> nav.opened() && a.equals(picker.selected()));
        eventually("b beside it", () -> picker.hasRow(b));
        assertEquals(dir.toAbsolutePath().normalize(), picker.topFolder());
        assertFalse(picker.hasRow(dir.resolve("note.txt").toAbsolutePath().normalize()), "folders only");

        tell(Intent.Command.LEAVE_OPEN);
        eventually("Esc to go back to the files", () -> !nav.opened());
        assertEquals(a, nav.folder(), "nothing was picked");

        tell(Intent.Command.TOGGLE_OPEN);
        eventually("Open again", () -> nav.opened() && picker.hasRow(b));
        shell.bus().publish(TreeView.CLICKED, new TreeView.Clicked(picker.treeAddress(), b));
        eventually("b shown, and Open over", () -> b.equals(nav.folder()) && !nav.opened());
    }

    /**
     * Open pictures the folder shown where the editor stood, then the one under the pointer, and the shown one again
     * when the pointer leaves the rows; going back to the files puts the editor back.
     */
    @Test
    void openPicturesTheFolderShownAndThenTheOneUnderThePointer() throws Exception {
        Path a = Files.createDirectory(dir.resolve("a")).toAbsolutePath().normalize();
        Path b = Files.createDirectory(dir.resolve("b")).toAbsolutePath().normalize();
        Files.writeString(a.resolve("notes.md"), "# a");
        Files.writeString(b.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>bee</artifactId>
                <version>1</version></project>""");
        Ui ui = wiring.ui();
        Navigator nav = ui.navigator();
        Preview preview = ui.preview();
        shell.bus().publish(Messages.GO_TOPIC, new Go(a.toString()));
        eventually("a shown", () -> a.equals(nav.folder()));
        assertFalse(ui.picturing(), "the editor, while the files are shown");

        tell(Intent.Command.TOGGLE_OPEN);
        eventually("a pictured", () -> ui.picturing() && a.equals(preview.drawn()));
        eventually("b listed", () -> nav.picker().hasRow(b));

        shell.bus().publish(TreeView.HOVERED, new TreeView.Hovered(nav.picker().treeAddress(), b));
        eventually("b pictured", () -> b.equals(preview.drawn()));
        shell.bus().publish(TreeView.HOVERED, new TreeView.Hovered(nav.picker().treeAddress(), null));
        eventually("a again, off the rows", () -> a.equals(preview.drawn()));

        tell(Intent.Command.LEAVE_OPEN);
        eventually("the editor back", () -> !ui.picturing());
    }

    /**
     * One survey runs at a time. The pointer passing over folders while one runs asks for none of them; when it lands,
     * the folder the pointer is on now is asked for, and an answer for one it has left is kept but not drawn.
     */
    @Test
    void aSurveyLandingLateAsksForWhereThePointerIsNow() throws Exception {
        Path a = Files.createDirectory(dir.resolve("a")).toAbsolutePath().normalize();
        Path b = Files.createDirectory(dir.resolve("b")).toAbsolutePath().normalize();
        Path c = Files.createDirectory(dir.resolve("c")).toAbsolutePath().normalize();
        Preview p = new Preview(shell.gui());
        SurveyFolder survey = new SurveyFolder();

        assertEquals(new Picture(a), p.show(a));
        assertNull(p.show(b), "a survey is running");
        assertNull(p.show(c), "still running");
        assertEquals(new Picture(c), p.landed(survey.run(new Picture(a))), "where the pointer is now, not b");
        assertNull(p.drawn(), "a, left, is not drawn under c's name");

        assertNull(p.show(a), "a is in hand");
        assertEquals(a, p.drawn());
        assertNull(p.landed(survey.run(new Picture(c))), "nothing more is wanted");
        assertEquals(a, p.drawn(), "c is kept, but the pointer is on a");
    }

    /** The folder left is offered under Recent, and pressing it goes back there. */
    @Test
    void aFolderLeftIsRecentAndPressingItGoesBack() throws Exception {
        Path a = Files.createDirectory(dir.resolve("a")).toAbsolutePath().normalize();
        Path b = Files.createDirectory(dir.resolve("b")).toAbsolutePath().normalize();
        Navigator nav = wiring.ui().navigator();
        shell.bus().publish(Messages.GO_TOPIC, new Go(a.toString()));
        eventually("a shown", () -> a.equals(nav.folder()));
        shell.bus().publish(Messages.GO_TOPIC, new Go(b.toString()));
        eventually("a recent", () -> nav.picker().recentLabels().equals(List.of("a")));

        tell(Intent.Command.TOGGLE_OPEN);
        eventually("Open", nav::opened);
        shell.bus().publish(Button.PRESSED, new Button.Pressed(nav.picker().recentAddress(0)));
        eventually("back in a", () -> a.equals(nav.folder()) && !nav.opened());
        eventually("b recent now", () -> nav.picker().recentLabels().equals(List.of("b")));
    }

    /** Plain puts the navigator out of the way; Ctrl+B brings it back, and so does a folder being shown. */
    @Test
    void aPlainWindowHidesTheNavigatorUntilAskedOrAFolderIsShown() throws Exception {
        Ui ui = wiring.ui();
        shell.bus().publish(Messages.PLAIN_TOPIC, new Messages.Plain());
        eventually("the navigator to go", () -> !ui.navigatorShown());
        tell(Intent.Command.TOGGLE_NAVIGATOR);
        eventually("Ctrl+B to bring it back", ui::navigatorShown);
        assertTrue(ui.navigator().opened(), "with no folder to list, it comes back as Open");

        shell.bus().publish(Messages.PLAIN_TOPIC, new Messages.Plain());
        eventually("the navigator to go", () -> !ui.navigatorShown());
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        eventually("a folder to bring it back", ui::navigatorShown);
    }

    @Test
    void thePathBarCreatesAFileThatIsNotThereYet() throws Exception {
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        eventually("the folder", () -> dir.toAbsolutePath().equals(wiring.ui().navigator().folder()));
        shell.bus().publish(Messages.GO_TOPIC, new Go("new.txt"));
        Path created = dir.resolve("new.txt").toAbsolutePath().normalize();
        eventually("the file to be created and opened", () -> ws.find(created) != null);
        assertTrue(Files.isRegularFile(created));
    }

    /** A Java file named in the path bar is made a class, in the package its folder is in. */
    @Test
    void aJavaFileIsMadeFromItsTemplate() throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("src/main/java/com/example"));
        shell.bus().publish(Messages.GO_TOPIC, new Go(pkg.resolve("Thing.java").toString()));
        Path created = pkg.resolve("Thing.java").toAbsolutePath().normalize();
        eventually("the file to be created and opened", () -> ws.find(created) != null);
        assertEquals("package com.example;\n\npublic class Thing {\n}\n", ws.find(created).field.text());
        assertFalse(ws.find(created).dirty(), "what it was made with is what is on disk");
    }

    /** A row's "New folder here…" puts its folder in the path bar, and Enter on a name makes a folder of it. */
    @Test
    void newFolderHereMakesAFolderOfTheNameTyped() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("sub")).toAbsolutePath().normalize();
        Path file = Files.writeString(sub.resolve("a.txt"), "a");
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        Navigator nav = wiring.ui().navigator();
        eventually("the folder", () -> dir.toAbsolutePath().equals(nav.folder()));

        // A file's row names something in the folder it is in.
        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.treeAddress(), Navigator.NEW_FOLDER, file));
        String prefix = sub + java.io.File.separator;
        eventually("the folder in the path bar", () -> nav.pathText().equals(prefix));
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.pathAddress(), prefix + "made"));
        eventually("the folder to be made", () -> Files.isDirectory(sub.resolve("made")));
        assertTrue(ws.all().isEmpty(), "a folder opens no tab");
    }

    /** The tree asks for the folder shown, a job reads it, and the view answers the tree with its rows. */
    @Test
    void showingAFolderListsItInTheTree() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "a").toAbsolutePath().normalize();
        Files.createDirectory(dir.resolve("sub"));
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        Navigator nav = wiring.ui().navigator();
        eventually("a row for the file", () -> nav.hasRow(file));
        assertTrue(nav.isFolder(dir.resolve("sub").toAbsolutePath().normalize()), "the listing said which is a folder");
    }

    /**
     * More tabs than a mailbox holds, all unsaved, closed at once with Save all: one {@code Flush} and one
     * {@code CloseTabs} for the lot, and a retitle per tab that is the tab's last word rather than a backlog. Before
     * those were lists and state, this needed the view's mailboxes 4,096 deep.
     */
    @Test
    void closingMoreUnsavedTabsThanAMailboxHoldsSavesAndClosesThemAll() throws Exception {
        List<Buffer> tabs = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tabs.add(opened(Files.writeString(dir.resolve("f" + i + ".txt"), "")));
        }
        for (Buffer b : tabs) {
            b.field.insert("x");
        }
        eventually("every tab unsaved", () -> tabs.stream().allMatch(Buffer::dirty));
        List<String> asked = new CopyOnWriteArrayList<>();
        answer = pressing("Save all", asked);
        shell.bus().publish(Messages.CLOSE_TOPIC, new Messages.Close(tabs.stream().map(b -> b.id).toList()));
        eventually("every tab closed", () -> ws.all().isEmpty());
        assertEquals(List.of("Unsaved changes"), asked);
        for (int i = 0; i < 100; i++) {
            assertEquals("x", Files.readString(dir.resolve("f" + i + ".txt")));
        }
    }

    @Test
    void aBinaryFileIsRefusedOnTheStatusLine() throws Exception {
        Path binary = dir.resolve("blob.bin");
        Files.write(binary, new byte[] {0, 1, 2, 3});
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(binary, true));
        eventually("the refusal", () -> said.stream().anyMatch(s -> s instanceof Status st && st.alert()
                && st.message().startsWith("Could not open blob.bin")));
        assertTrue(ws.all().isEmpty());
    }

    @Test
    void closingAnUnsavedTabAsksAndDontSaveCloses() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "a"));
        b.field.insert("x");
        eventually("unsaved", b::dirty);
        List<String> asked = new CopyOnWriteArrayList<>();
        answer = pressing("Don't save", asked);
        shell.bus().publish(Tabs.CHOSEN, new Tabs.Chosen(ws.address(), Workspace.CLOSE, ws.all().indexOf(b)));
        eventually("the tab to close", () -> ws.byId(b.id) == null);
        assertEquals(List.of("Unsaved changes"), asked);
        assertEquals("a", Files.readString(dir.resolve("a.txt")), "Don't save does not save");
    }

    @Test
    void quittingACleanSessionGoesWithoutAsking() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        answer = pressing("Cancel", asked);
        AtomicInteger proceeded = new AtomicInteger();
        quit(proceeded::incrementAndGet, () -> { });
        eventually("the quit to proceed", () -> proceeded.get() == 1);
        assertTrue(asked.isEmpty());
    }

    @Test
    void quittingAsksAndCancelKeepsTheWindow() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("a.txt"), "a"));
        b.field.insert("x");
        eventually("unsaved", b::dirty);
        List<String> asked = new CopyOnWriteArrayList<>();
        answer = pressing("Cancel", asked);
        AtomicInteger proceeded = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        quit(proceeded::incrementAndGet, cancelled::incrementAndGet);
        eventually("the quit to be cancelled", () -> cancelled.get() == 1);
        assertEquals(List.of("Quit with unsaved changes?"), asked);
        assertEquals(0, proceeded.get());
    }

    @Test
    void saveAllThenQuitProceedsOnceEverythingIsWritten() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        Buffer first = opened(a);
        Buffer second = opened(c);
        first.field.insert("1");
        second.field.insert("2");
        eventually("both unsaved", () -> first.dirty() && second.dirty());
        answer = pressing("Save all", new CopyOnWriteArrayList<>());
        AtomicInteger proceeded = new AtomicInteger();
        quit(proceeded::incrementAndGet, () -> { });
        eventually("the quit to proceed", () -> proceeded.get() == 1);
        assertEquals("1a", Files.readString(a));
        assertEquals("2c", Files.readString(c));
    }
}
