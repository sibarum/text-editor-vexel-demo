package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Editor;
import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.Caption;
import dev.vexelray.demo.editor.core.CreateFile;
import dev.vexelray.demo.editor.core.Messages.Go;
import dev.vexelray.demo.editor.core.Messages.Indexed;
import dev.vexelray.demo.editor.core.Messages.Item;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.Picture;
import dev.vexelray.demo.editor.core.Messages.ScratchesFound;
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
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.gui.widget.Select;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    /** The application's home, in this test's folder: the scratches go beside the settings, so they are made here. */
    private static final String HOME = TextEditor.APP + ".home";

    @BeforeEach
    void build() {
        System.setProperty(HOME, dir.resolve("home").toString());
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
        System.clearProperty(HOME);
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
     * Ask the editor what the framework asks it when the main window's close button is pressed, and run
     * {@code proceeded} or {@code cancelled} on the answer, as the framework does with the window's own two. The
     * request is addressed to the editor, as the framework addresses each part that hears it, in turn.
     */
    private void quit(Runnable proceeded, Runnable cancelled) {
        long ticket = tickets.incrementAndGet();
        shell.bus().subscribe(CloseAnswer.TOPIC, a -> {
            if (a.ticket() == ticket) {
                (a.proceed() ? proceeded : cancelled).run();
            }
        });
        shell.bus().publish(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, ticket, Editor.class.getName()));
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

    /** The folder left is offered in Recent folders, and picking it there goes back. */
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
        shell.bus().publish(Select.COMMITTED, new Select.Committed(nav.picker().recentAddress(), Set.of(a)));
        eventually("back in a", () -> a.equals(nav.folder()) && !nav.opened());
        eventually("b recent now", () -> nav.picker().recentLabels().equals(List.of("b")));
        eventually("the drop-down says Recent folders again", () -> nav.picker().recentSelect().value() == null);
    }

    /** The scratches' folder, beside this test's settings. */
    private Path scratches() {
        return dir.resolve("home").resolve("scratches").toAbsolutePath().normalize();
    }

    /** Ctrl+Shift+N makes a scratch and opens it in front; closed with nothing written to it, it goes again. */
    @Test
    void aNewScratchOpensInFrontAndGoesAgainIfClosedEmpty() throws Exception {
        tell(Intent.Command.NEW_SCRATCH);
        eventually("a scratch in front", () -> ws.front() != null && ws.front().path.startsWith(scratches()));
        Path made = ws.front().path;
        assertTrue(Files.isRegularFile(made));
        assertTrue(made.getFileName().toString().endsWith(".md"), made.toString());
        eventually("said", () -> said.contains(new Status("New scratch " + made.getFileName(), false)));

        tell(Intent.Command.CLOSE_FRONT);
        eventually("the tab closed and the scratch gone", () -> ws.all().isEmpty() && !Files.exists(made));
    }

    /** A scratch written to is kept: closed, even emptied again, it stays. */
    @Test
    void aScratchWrittenToIsKept() throws Exception {
        tell(Intent.Command.NEW_SCRATCH);
        eventually("a scratch in front", () -> ws.front() != null && ws.front().path.startsWith(scratches()));
        Buffer b = ws.front();
        b.field.insert("a note");
        tell(Intent.Command.SAVE_FRONT);
        eventually("saved", () -> said.contains(new Status("Saved " + b.path.getFileName(), false)));

        tell(Intent.Command.CLOSE_FRONT);
        eventually("the tab closed", () -> ws.all().isEmpty());
        assertEquals("a note", Files.readString(b.path));
    }

    /**
     * Open offers the scratches once there is one; pressed, they are the folder shown, which the chip calls Scratches,
     * and leaving them does not make them one of the recent folders.
     */
    @Test
    void openOffersTheScratchesOnceThereIsOne() throws Exception {
        Path a = Files.createDirectory(dir.resolve("a")).toAbsolutePath().normalize();
        Navigator nav = wiring.ui().navigator();
        shell.bus().publish(Messages.GO_TOPIC, new Go(a.toString()));
        eventually("a shown", () -> a.equals(nav.folder()));

        List<ScratchesFound> found = new CopyOnWriteArrayList<>();
        shell.bus().subscribe(Messages.SCRATCHES_FOUND_TOPIC, found::add);
        tell(Intent.Command.TOGGLE_OPEN);
        eventually("Open, and the scratches looked for", () -> nav.opened() && !found.isEmpty());
        VexelApplication.frame(shell);
        assertFalse(nav.picker().scratchesOffered(), "no scratch yet");
        tell(Intent.Command.LEAVE_OPEN);
        eventually("the files again", () -> !nav.opened());

        Files.createDirectories(scratches());
        Files.writeString(scratches().resolve("note.md"), "n");
        tell(Intent.Command.TOGGLE_OPEN);
        eventually("the scratches offered", () -> nav.opened() && nav.picker().scratchesOffered());
        shell.bus().publish(Button.PRESSED, new Button.Pressed(nav.picker().scratchesAddress()));
        eventually("the scratches shown", () -> scratches().equals(nav.folder()) && !nav.opened());
        assertEquals("Scratches", nav.chipLabel());

        shell.bus().publish(Messages.GO_TOPIC, new Go(a.toString()));
        eventually("a again", () -> a.equals(nav.folder()));
        assertEquals("a", nav.chipLabel());
        assertEquals(List.of(), nav.picker().recentLabels(), "the scratches are not a recent folder");
    }

    /**
     * Alt+N lays the scratches over the files without showing their folder: the chip says Scratches, a file picked
     * there opens, and Alt+N again, or the chip released, puts the files back.
     */
    @Test
    void altNLaysTheScratchesOverTheFilesAndBack() throws Exception {
        Path a = Files.createDirectory(dir.resolve("a")).toAbsolutePath().normalize();
        Path note = Files.writeString(Files.createDirectories(scratches()).resolve("note.md"), "n");
        Navigator nav = wiring.ui().navigator();
        shell.bus().publish(Messages.GO_TOPIC, new Go(a.toString()));
        eventually("a shown", () -> a.equals(nav.folder()));

        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the scratches, listed", () -> nav.scratching() && nav.hasScratchRow(note));
        assertEquals("Scratches", nav.chipLabel());
        assertEquals(a, nav.folder(), "the folder shown is still the one shown");

        shell.bus().publish(TreeView.SELECTED, new TreeView.Selected(nav.scratchAddress(), note));
        eventually("the scratch open", () -> ws.find(note) != null);

        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the files again", () -> !nav.scratching());
        assertEquals("a", nav.chipLabel());

        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the scratches again", nav::scratching);
        shell.bus().publish(Button.TOGGLED, new Button.Toggled(nav.chipAddress(), false));
        eventually("the chip released for the files", () -> !nav.scratching());
    }

    /**
     * Over the scratches, New scratch makes one, which the tree lists and selects; a row's New ▸ Scratch makes one
     * in its folder. The scratches' own folder being the one shown, its files listed again keep the scratches up.
     */
    @Test
    void newScratchOverTheScratchesListsAndSelectsIt() throws Exception {
        Path inner = Files.createDirectories(scratches().resolve("inner"));
        Navigator nav = wiring.ui().navigator();
        shell.bus().publish(Messages.GO_TOPIC, new Go(scratches().toString()));
        eventually("the scratches' folder shown", () -> scratches().equals(nav.folder()));
        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the scratches, listed", () -> nav.scratching() && nav.hasScratchRow(inner));

        shell.bus().publish(Button.PRESSED, new Button.Pressed(nav.newScratchAddress()));
        eventually("a scratch in front", () -> ws.front() != null && ws.front().path.startsWith(scratches()));
        Path made = ws.front().path;
        eventually("its row selected", () -> made.equals(nav.scratchSelected()));
        assertTrue(nav.scratching(), "the folder shown listed again is no reason to leave");

        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.scratchAddress(), Navigator.NEW_SCRATCH, inner));
        eventually("one in inner, in front", () -> ws.front() != null && inner.equals(ws.front().path.getParent()));
        Path here = ws.front().path;
        eventually("its row selected, inner opened to it", () -> here.equals(nav.scratchSelected()));
    }

    /** Open takes the scratches' place, and another folder shown puts the files back. */
    @Test
    void openAndAnotherFolderTakeTheScratchesAway() throws Exception {
        Path a = Files.createDirectory(dir.resolve("a")).toAbsolutePath().normalize();
        Path b = Files.createDirectory(dir.resolve("b")).toAbsolutePath().normalize();
        Navigator nav = wiring.ui().navigator();
        shell.bus().publish(Messages.GO_TOPIC, new Go(a.toString()));
        eventually("a shown", () -> a.equals(nav.folder()));

        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the scratches", nav::scratching);
        tell(Intent.Command.TOGGLE_OPEN);
        eventually("Open in their place", () -> nav.opened() && !nav.scratching());
        tell(Intent.Command.LEAVE_OPEN);
        eventually("the files", () -> nav.place() == Navigator.Place.FILES);

        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the scratches", nav::scratching);
        shell.bus().publish(Messages.GO_TOPIC, new Go(b.toString()));
        eventually("b, and its files", () -> b.equals(nav.folder()) && nav.place() == Navigator.Place.FILES);
        assertEquals("b", nav.chipLabel());
    }

    /**
     * A scratch dropped onto a folder in the scratches' tree is moved there, and its tab follows it, unsaved work and
     * all; the window's history then puts it back, and forward again.
     */
    @Test
    void aScratchDroppedOnAFolderMovesThereWithItsTabAndUndoPutsItBack() throws Exception {
        History history = new History();
        shell.gui().history(history);
        Path note = Files.writeString(Files.createDirectories(scratches()).resolve("note.md"), "n");
        Path box = Files.createDirectories(scratches().resolve("box"));
        Buffer b = opened(note);
        b.field.insert("more ");
        Navigator nav = wiring.ui().navigator();
        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the scratches, listed", () -> nav.hasScratchRow(note) && nav.hasScratchRow(box));

        Path moved = box.resolve("note.md");
        shell.bus().publish(TreeView.PLACED, new TreeView.Placed(nav.scratchAddress(), note, Placement.into(box),
                DropEffect.MOVE));
        eventually("moved, and the tab with it", () -> Files.isRegularFile(moved) && moved.equals(b.path));
        assertFalse(Files.exists(note));
        assertTrue(b.dirty(), "the unsaved work came too");
        eventually("its row selected where it went", () -> moved.equals(nav.scratchSelected()));
        eventually("in the history", history::canUndo);

        history.undo();
        eventually("back, and the tab with it", () -> Files.isRegularFile(note) && note.equals(b.path));
        history.redo();
        eventually("forward again", () -> Files.isRegularFile(moved) && moved.equals(b.path));
        assertTrue(history.canUndo() && !history.canRedo(), "an undo or redo is no new entry");
    }

    /**
     * Rename… puts the name field above the tree; Enter renames, and the tab takes the new name's language. A taken
     * name leaves the field up, and Escape puts it away renaming nothing.
     */
    @Test
    void renameNamesAScratchAndATakenNameIsRefused() throws Exception {
        Path note = Files.writeString(Files.createDirectories(scratches()).resolve("note.md"), "class A {}");
        Path other = Files.writeString(scratches().resolve("other.txt"), "o");
        Buffer b = opened(note);
        Navigator nav = wiring.ui().navigator();
        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the scratches, listed", () -> nav.hasScratchRow(note));

        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.scratchAddress(), Navigator.RENAME, note));
        eventually("the name field, for it", () -> note.equals(nav.renaming()));
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.renameAddress(), "other.txt"));
        eventually("refused, and said", () -> said.stream().anyMatch(s -> s instanceof Status st && st.alert()
                && st.message().equals("Could not rename note.md: there is already a other.txt there")));
        assertEquals(note, nav.renaming(), "the field stays up to be changed");
        assertEquals("o", Files.readString(other));

        Path java = scratches().resolve("A.java");
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.renameAddress(), "A.java"));
        eventually("renamed, and the tab with it", () -> java.equals(b.path) && nav.renaming() == null);
        assertEquals("Java", b.language());
        assertTrue(said.contains(new Status("Renamed A.java", false)));

        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.scratchAddress(), Navigator.RENAME, java));
        eventually("the name field again", () -> java.equals(nav.renaming()));
        tell(Intent.Command.LEAVE_RENAME);
        eventually("put away", () -> nav.renaming() == null);
        assertTrue(Files.isRegularFile(java));
    }

    /** New folder here makes one in the row's folder, and opens the name field on it. */
    @Test
    void newFolderHereMakesOneAndNamesIt() throws Exception {
        Path box = Files.createDirectories(scratches().resolve("box"));
        Navigator nav = wiring.ui().navigator();
        tell(Intent.Command.TOGGLE_SCRATCHES);
        eventually("the scratches, listed", () -> nav.hasScratchRow(box));

        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.scratchAddress(), Navigator.NEW_FOLDER, box));
        Path made = box.resolve("Folder");
        eventually("made, and being named", () -> Files.isDirectory(made) && made.equals(nav.renaming()));
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.renameAddress(), "ideas"));
        eventually("named", () -> Files.isDirectory(box.resolve("ideas")) && nav.renaming() == null);
    }

    /** The View menu's word wrap line sets the setting rather than flipping it, so choosing it twice flips nothing. */
    @Test
    void settingWordWrapTheWayItAlreadyIsChangesNothing() throws Exception {
        View view = wiring.view();
        assertTrue(view.wrapping(), "on at start");
        told(Intent.Command.WRAP_OFF, Intent.Command.WRAP_OFF);
        assertFalse(view.wrapping(), "off, and the second off left it off");
        told(Intent.Command.WRAP_ON, Intent.Command.WRAP_ON);
        assertTrue(view.wrapping(), "on, and the second on left it on");
        told(Intent.Command.TOGGLE_WRAP);
        assertFalse(view.wrapping(), "Alt+Z still flips it");
    }

    /**
     * Tell the view {@code commands}, and wait until it has heard them all: intents arrive in the order sent, so once
     * the navigator toggle sent after them has shown, they have been heard.
     */
    private void told(Intent.Command... commands) throws InterruptedException {
        boolean shown = wiring.ui().navigatorShown();
        for (Intent.Command c : commands) {
            tell(c);
        }
        tell(Intent.Command.TOGGLE_NAVIGATOR);
        eventually("the view to hear them", () -> wiring.ui().navigatorShown() != shown);
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

    /** A row's New ▸ Folder… puts its folder in the path bar, and Enter on a name makes a folder of it. */
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

    /** The New menu: a file, and a project or a module, never both; all off while no folder is shown. */
    @Test
    void theNewMenuOffersAProjectOrAModuleForTheFolderShown() {
        Sender<Intent> intents = Sender.collecting(Intent.INTENTS, new ArrayList<>());
        assertEquals(List.of("File…", "Project…", "Index this project"), labels(Ui.newMenuOf(true, false, intents)));
        assertEquals(List.of("File…", "Submodule…", "Index this project"), labels(Ui.newMenuOf(true, true, intents)));
        assertTrue(lines(Ui.newMenuOf(false, true, intents)).noneMatch(MenuItem::enabled),
                "nothing to name a thing in");
        assertTrue(lines(Ui.newMenuOf(true, true, intents)).allMatch(MenuItem::enabled));
        assertEquals(List.of(true, true, false), lines(Ui.newMenuOf(true, false, intents)).map(MenuItem::enabled)
                .toList(), "no project to index");
    }

    /** A menu's lines, its rules left out. */
    private static java.util.stream.Stream<MenuItem> lines(Menu menu) {
        return menu.items().stream().filter(i -> i.label() != null);
    }

    private static List<String> labels(Menu menu) {
        return lines(menu).map(MenuItem::label).toList();
    }

    /** New → Index this project: the reactor around the folder shown, indexed and kept beside the settings. */
    @Test
    void theNewMenuIndexesTheProjectAroundTheFolderShown() throws Exception {
        Path root = dir.resolve("whole").toAbsolutePath().normalize();
        Path source = Files.createDirectories(root.resolve("lib/src/main/java/demo"));
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>whole</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules><module>lib</module></modules>
                </project>
                """);
        Files.writeString(root.resolve("lib/pom.xml"), """
                <project>
                    <parent><groupId>com.example</groupId><artifactId>whole</artifactId><version>1</version></parent>
                    <artifactId>lib</artifactId>
                </project>
                """);
        Files.writeString(source.resolve("Greeter.java"), "package demo;\n\npublic class Greeter {\n"
                + "    public String greet() {\n        return \"hello\";\n    }\n}\n");
        List<Indexed> indexed = new CopyOnWriteArrayList<>();
        shell.bus().subscribe(Messages.INDEXED_TOPIC, indexed::add);
        shell.bus().publish(Messages.GO_TOPIC, new Go(root.resolve("lib").toString()));
        Navigator nav = wiring.ui().navigator();
        eventually("the module", () -> root.resolve("lib").equals(nav.folder()));

        tell(Intent.Command.NEW_INDEX);
        eventually("the index", () -> !indexed.isEmpty());
        Indexed m = indexed.getFirst();
        assertEquals("", m.problem());
        assertEquals(root, m.project(), "the reactor, not the module shown");
        assertTrue(m.summary().startsWith("1 types, 1 methods"), m.summary());
        try (var kept = Files.list(dir.resolve("home").resolve("index"))) {
            assertEquals(1, kept.count(), "kept beside the settings");
        }
    }

    /**
     * Ctrl+T: Symbols says a project has no index, its button builds one, and then what is typed lists declarations, and
     * Enter opens the best one's file with its name selected and puts the files back.
     */
    @Test
    void symbolsFindsADeclarationAndEnterGoesToIt() throws Exception {
        Path root = dir.resolve("p").toAbsolutePath().normalize();
        Path source = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("pom.xml"),
                "<project><groupId>x</groupId><artifactId>p</artifactId><version>1</version></project>\n");
        Path greeter = Files.writeString(source.resolve("Greeter.java"), "package demo;\n\npublic class Greeter {\n"
                + "    @Deprecated\n    public String greet() {\n        return \"hello\";\n    }\n}\n");
        List<Messages.SymbolsFound> found = new CopyOnWriteArrayList<>();
        shell.bus().subscribe(Messages.SYMBOLS_FOUND_TOPIC, found::add);
        shell.bus().publish(Messages.GO_TOPIC, new Go(root.toString()));
        Navigator nav = wiring.ui().navigator();
        eventually("the project shown", () -> root.equals(nav.folder()));

        tell(Intent.Command.TOGGLE_SYMBOLS);
        eventually("an answer", () -> !found.isEmpty());
        assertTrue(nav.symbolsUp());
        assertEquals(Navigator.SYMBOLS, nav.chipLabel());
        assertFalse(found.getLast().indexed());
        assertEquals("p has no index yet", Navigator.note(found.getLast()));

        shell.bus().publish(Button.PRESSED, new Button.Pressed(nav.indexButtonAddress()));
        eventually("asked again once indexed", () -> found.size() >= 2 && found.getLast().indexed());

        shell.bus().publish(TextField.CHANGED, new TextField.Changed(nav.symbolFieldAddress(), "greet", 1));
        eventually("greet listed", () -> nav.symbolChosen() != null && nav.symbolChosen().name().equals("greet"));
        assertEquals("method of Greeter", Navigator.detail(nav.symbolChosen()));

        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.symbolFieldAddress(), "greet"));
        eventually("Greeter.java open", () -> ws.find(greeter) != null);
        Buffer b = ws.find(greeter);
        eventually("the name selected", () -> b.field.caret() == b.field.text().indexOf("greet()") + "greet".length());
        assertFalse(nav.symbolsUp(), "going there puts the files back");
    }

    /** New → File… and Project… name their thing in the folder shown, from wherever the navigator was. */
    @Test
    void theNewMenuNamesThingsInTheFolderShown() throws Exception {
        Path shown = dir.toAbsolutePath().normalize();
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        Navigator nav = wiring.ui().navigator();
        eventually("the folder", () -> shown.equals(nav.folder()));

        tell(Intent.Command.TOGGLE_OPEN);
        eventually("Open", nav::opened);
        tell(Intent.Command.NEW_FILE);
        String prefix = shown + java.io.File.separator;
        eventually("the folder in the path bar", () -> nav.pathText().equals(prefix) && !nav.opened());
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.pathAddress(), prefix + "notes.md"));
        eventually("the file to be made", () -> Files.isRegularFile(shown.resolve("notes.md")));

        tell(Intent.Command.NEW_PROJECT);
        eventually("the project's name in the path bar", () -> nav.pathText().equals(prefix + "new-project"));
        assertEquals(Made.PROJECT, nav.naming());
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.pathAddress(), prefix + "new-project"));
        eventually("the project to be made", () -> Files.isRegularFile(shown.resolve("new-project/pom.xml")));
    }

    /** New ▸ Project… names one in the bar, selected, and Enter takes the name as it is. */
    @Test
    void newProjectHereMakesAProjectOfTheNameInTheBar() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("sub")).toAbsolutePath().normalize();
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        Navigator nav = wiring.ui().navigator();
        eventually("a row for the folder", () -> nav.hasRow(sub));

        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.treeAddress(), Navigator.NEW_PROJECT, sub));
        String named = sub + java.io.File.separator + "new-project";
        eventually("the name in the path bar", () -> nav.pathText().equals(named));
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.pathAddress(), named));
        eventually("the project to be made", () -> Files.isRegularFile(sub.resolve("new-project/pom.xml")));
        eventually("the status to say so", () -> said.stream().anyMatch(s -> s instanceof Status st
                && st.message().startsWith("Created new-project, a Maven project")));
        assertEquals(Made.FILE, nav.naming(), "the bar's mind goes back to its own");
    }

    /** Inside a project a folder offers a module rather than a project; its archive is named beside it. */
    @Test
    void aFolderInAProjectAddsAModuleAndArchivesBesideItself() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>whole</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                </project>
                """);
        Path lib = Files.createDirectory(dir.resolve("lib")).toAbsolutePath().normalize();
        Files.writeString(lib.resolve("a.txt"), "a");
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        Navigator nav = wiring.ui().navigator();
        eventually("a row for the folder", () -> nav.hasRow(lib));

        TreeView.Entry row = nav.children(new Listed(nav.treeAddress(), dir, false, true,
                List.of(new Item(lib, "lib", true, true)))).entries().getFirst();
        assertTrue(row.hidden().contains(Navigator.NEW_PROJECT) && !row.hidden().contains(Navigator.NEW_MODULE));

        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.treeAddress(), Navigator.NEW_MODULE, lib));
        String module = lib + java.io.File.separator + "new-module";
        eventually("the module's name in the path bar", () -> nav.pathText().equals(module));
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.pathAddress(), module));
        eventually("the module to be made", () -> Files.isRegularFile(lib.resolve("new-module/pom.xml")));
        assertTrue(Files.readString(dir.resolve("pom.xml")).contains("<module>lib/new-module</module>"));

        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.treeAddress(), Navigator.ARCHIVE, lib));
        String zip = dir.toAbsolutePath().normalize() + java.io.File.separator + "lib.zip";
        eventually("the archive's name in the path bar", () -> nav.pathText().equals(zip));
        assertEquals(lib, nav.namingFrom());
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.pathAddress(), zip));
        eventually("the archive to be made", () -> Files.isRegularFile(Path.of(zip)));
    }

    /**
     * "Link to this…" names a link beside the row, to it. Offered here whatever the platform, so this runs on Windows,
     * where making it takes privilege: then the status line says why not, and nothing is made.
     */
    @Test
    void linkToThisNamesALinkToTheRow() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "a").toAbsolutePath().normalize();
        Navigator nav = wiring.ui().navigator();
        nav.offerLinks(true);
        shell.bus().publish(Messages.GO_TOPIC, new Go(dir.toString()));
        eventually("a row for the file", () -> nav.hasRow(file));

        shell.bus().publish(TreeView.CHOSEN, new TreeView.Chosen(nav.treeAddress(), Navigator.LINK, file));
        String named = file.getParent() + java.io.File.separator + "a.txt-link";
        eventually("the link's name in the path bar", () -> nav.pathText().equals(named));
        assertEquals(Made.LINK, nav.naming());
        shell.bus().publish(TextField.SUBMITTED, new TextField.Submitted(nav.pathAddress(), named));
        eventually("the status to say what happened", () -> said.stream().anyMatch(s -> s instanceof Status st
                && (st.message().startsWith("Created link a.txt-link") || st.message().startsWith("Could not create"))));
        if (CreateFile.links()) {
            assertEquals(file, Files.readSymbolicLink(Path.of(named)));
        }
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

    // ------------------------------------------------------------------ Pontif, checked as it is typed

    /** The field's spans that are problems: underlined, on a ground. */
    private static List<dev.vexelray.gui.core.text.Span> problemSpans(Buffer b) {
        return b.field.spans().stream().filter(s -> s.underline() && s.bg() != null).toList();
    }

    /** A Pontif file that does not compile says so on the status line and underlines where; fixed, both go. */
    @Test
    void aPontifProblemIsShownWhereItIsAndGoesWhenItIsFixed() throws Exception {
        String text = "function greet(n:Int):Int -> n + 1\n\ngree(41)\n";
        Buffer b = opened(Files.writeString(dir.resolve("app.ptf"), text));
        eventually("the problem", () -> !b.problem().isEmpty() && !problemSpans(b).isEmpty());
        dev.vexelray.gui.core.text.Span under = problemSpans(b).getFirst();
        assertEquals("gree", text.substring(under.start(), under.end()));
        eventually("the colours too", () -> b.field.spans().size() > problemSpans(b).size());

        b.field.caret(text.indexOf("(41)"));
        b.field.insert("t");
        eventually("the problem to go", () -> b.problem().isEmpty() && problemSpans(b).isEmpty());
        assertFalse(b.field.spans().isEmpty(), "the colours stay");
    }

    /** One check in flight per document: a burst of typing costs the check already out and one for where it ended. */
    @Test
    void aBurstOfTypingIsCheckedOnceItHasEnded() throws Exception {
        List<Messages.Check> checks = new CopyOnWriteArrayList<>();
        shell.bus().subscribe(Messages.CHECK_TOPIC, checks::add);
        String text = "function greet(n:Int):Int -> n + 1\n\ngreet(41)\n";
        Buffer b = opened(Files.writeString(dir.resolve("app.ptf"), text));
        eventually("the first check", () -> checks.size() == 1);
        eventually("its answer", () -> b.problem().isEmpty() && !b.field.spans().isEmpty());
        // Twenty keystrokes between two frames: the view hears them all in the next one.
        for (int i = 0; i < 20; i++) {
            b.field.caret(b.field.text().length());
            b.field.insert("#" + i + "\n");
        }
        String typed = b.field.text();
        eventually("the text as typed to be checked", () -> !checks.isEmpty() && checks.getLast().text().equals(typed));
        assertTrue(checks.size() <= 3, "the first, then at most one out and one for the end: " + checks.size());
        eventually("no problem in comments", () -> b.problem().isEmpty());
    }

    /** Only what CheckText checks is checked. */
    @Test
    void aTextFileIsNeverChecked() throws Exception {
        List<Messages.Check> checks = new CopyOnWriteArrayList<>();
        shell.bus().subscribe(Messages.CHECK_TOPIC, checks::add);
        Buffer b = opened(Files.writeString(dir.resolve("notes.txt"), "main ( nonExistentFn(1) )"));
        b.field.insert("x");
        VexelApplication.frame(shell);
        VexelApplication.frame(shell);
        assertEquals(List.of(), checks);
        assertEquals("", b.problem());
    }

    // ------------------------------------------------------------------ Pontif, go to definition

    private static final String BOOKS = "module my.books\nexports @.{total}\n\nfunction total(n:Int):Int -> n + 1\n";

    /** Ctrl+Enter with the caret at the {@code nth} occurrence of {@code word} in {@code b}. */
    private void goTo(Buffer b, String word, int nth) {
        int at = -1;
        for (int i = 0; i <= nth; i++) {
            at = b.field.text().indexOf(word, at + 1);
        }
        b.field.caret(at);
        tell(Intent.Command.GO_TO_DEFINITION);
    }

    @Test
    void ctrlEnterGoesToADeclarationInTheSameFile() throws Exception {
        String text = "function greet(n:Int):Int -> n + 1\n\ngreet(41)\n";
        Buffer b = opened(Files.writeString(dir.resolve("app.ptf"), text));
        goTo(b, "greet", 1);
        int end = text.indexOf("greet") + "greet".length();
        eventually("the declaration selected", () -> b.field.caret() == end);
    }

    @Test
    void ctrlEnterOpensTheFileBesideThatDeclaresAName() throws Exception {
        Path books = Files.writeString(dir.resolve("books.ptf"), BOOKS);
        Buffer app = opened(Files.writeString(dir.resolve("app.ptf"), "requires my.books.{total}\n\ntotal(41)\n"));
        goTo(app, "total", 1);
        int end = BOOKS.indexOf("function total") + "function total".length();
        eventually("books in front, at total", () -> ws.front() != null
                && ws.front().path.equals(books.toAbsolutePath().normalize()) && ws.front().field.caret() == end);
    }

    @Test
    void ctrlEnterImportsANameAndCtrlZTakesItOut() throws Exception {
        Files.writeString(dir.resolve("books.ptf"), BOOKS);
        Buffer b = opened(Files.writeString(dir.resolve("app.ptf"), "total(41)\n"));
        // The caret move just before Ctrl+Enter is itself a change with a version of its own (as is every colouring
        // and check that lands meanwhile), so this also holds that an answer is matched by its text, not the version.
        goTo(b, "total", 0);
        eventually("the requires added", () -> b.field.text().equals("requires my.books.{total}\ntotal(41)\n"));
        assertEquals("requires my.books.{total}\n".length(), b.field.caret(), "the caret stays on the name");
        b.field.history().undo();
        eventually("taken out again", () -> b.field.text().equals("total(41)\n"));
    }

    @Test
    void ctrlEnterOnABuiltInOpensItsSourceToBeRead() throws Exception {
        Buffer b = opened(Files.writeString(dir.resolve("app.ptf"), "requires pontif.core.{Stream}\n\n1\n"));
        goTo(b, "Stream", 0);
        Path builtins = dir.resolve("home").resolve("pontif-builtins").toAbsolutePath().normalize();
        eventually("pontif.core in front", () -> ws.front() != null && ws.front().path.startsWith(builtins));
        Buffer core = ws.front();
        assertEquals("pontif.core.ptf", core.name());
        assertTrue(core.readOnly(), "a built-in's source is read, not edited");
        assertFalse(b.readOnly());
        int caret = core.field.caret();
        assertEquals("Stream", core.field.text().substring(caret - "Stream".length(), caret));
    }
}
