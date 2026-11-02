package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.AddTab;
import dev.vexelray.demo.editor.core.Messages.Caption;
import dev.vexelray.demo.editor.core.Messages.Close;
import dev.vexelray.demo.editor.core.Messages.CloseTabs;
import dev.vexelray.demo.editor.core.Messages.Create;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.demo.editor.core.Messages.Dropping;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.Edited;
import dev.vexelray.demo.editor.core.Messages.FileRead;
import dev.vexelray.demo.editor.core.Messages.Find;
import dev.vexelray.demo.editor.core.Messages.Flush;
import dev.vexelray.demo.editor.core.Messages.Found;
import dev.vexelray.demo.editor.core.Messages.FrontTab;
import dev.vexelray.demo.editor.core.Messages.Go;
import dev.vexelray.demo.editor.core.Messages.Kind;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Read;
import dev.vexelray.demo.editor.core.Messages.Recall;
import dev.vexelray.demo.editor.core.Messages.Recalled;
import dev.vexelray.demo.editor.core.Messages.Recent;
import dev.vexelray.demo.editor.core.Messages.Remember;
import dev.vexelray.demo.editor.core.Messages.Retitle;
import dev.vexelray.demo.editor.core.Messages.Save;
import dev.vexelray.demo.editor.core.Messages.SaveFailed;
import dev.vexelray.demo.editor.core.Messages.ScratchMade;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.demo.editor.core.Messages.Why;
import dev.vexelray.demo.editor.core.Messages.Write;
import dev.vexelray.demo.editor.core.Messages.Written;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowOpened;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;
import sibarum.atchung.Inbox;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller, alone: no window and no jobs. The {@link Editor} owns an inbox on a tick of this test's, as it will
 * own one on the framework's, and each test publishes what the view or a job would and runs the tick, then reads what
 * the editor sent. The jobs' replies are published by hand, which is what makes the interesting orders (a write that
 * fails, an answer to a question already answered) as easy to test as the common one.
 *
 * <p>The close tests are the witness of {@code docs/plans/chains.md}: closing a document with unsaved work is one
 * chain, from the message that asks to its end, whichever way the person answers.
 */
class EditorTest {

    @TempDir
    Path dir;

    private Fatal previous;
    private Atchung bus;
    private Tick tick;
    private Editor editor;
    /** Everything the editor sent, in order. */
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        previous = Atchung.fatal();
        Atchung.onFatal(Fatal.THROW);
        bus = Atchung.create();
        tick = bus.tick(null);
        editor = new Editor(tick.inbox("editor"));
        Inbox heard = tick.inbox("heard");
        for (Topic<?> t : List.of(Messages.SHOW_FOLDER_TOPIC, Messages.ADD_TAB_TOPIC, Messages.FRONT_TAB_TOPIC,
                Messages.CLOSE_TABS_TOPIC, Messages.RETITLE_TOPIC, Messages.CAPTION_TOPIC, Messages.STATUS_TOPIC,
                Ask.TOPIC, Messages.FLUSH_TOPIC, Messages.SAVE_FAILED_TOPIC, CloseAnswer.TOPIC,
                Messages.READ_TOPIC, Messages.WRITE_TOPIC, Messages.FIND_TOPIC, Messages.CREATE_TOPIC,
                Messages.RECALL_TOPIC, Messages.REMEMBER_TOPIC, Messages.PLAIN_TOPIC, Messages.RECENT_TOPIC,
                Messages.DROPPING_TOPIC)) {
            listen(heard, t);
        }
    }

    @AfterEach
    void restoreTheFatalPolicy() {
        Atchung.onFatal(previous);
    }

    private <T> void listen(Inbox heard, Topic<T> topic) {
        heard.subscribe(topic, sent::add, 256);
    }

    /** Publish {@code message} as its sender would, and run the tick until nothing is left to deliver. */
    private <T> void tell(Topic<T> topic, T message) {
        bus.publish(topic, message);
        tick.run(System.nanoTime() + 60_000_000_000L);
    }

    /** What was sent of one kind, in order, and forget everything sent so far. */
    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    /** What ReadSession would answer, with no recent folders and started in {@code dir}. */
    private Recalled recalled(Optional<Path> folder, List<Path> files, Optional<Path> front, List<Path> arguments) {
        return new Recalled(folder, files, front, List.of(), arguments, dir);
    }

    private Path file(String name) {
        return dir.resolve(name).toAbsolutePath().normalize();
    }

    /** Open {@code name} as the reader would answer it, and return the new tab's id. */
    private long opened(String name, String text) {
        Path file = file(name);
        tell(Messages.OPEN_TOPIC, new Open(file, true));
        assertEquals(List.of(new Read(file, Why.OPEN, true)), took(Read.class));
        tell(Messages.FILE_READ_TOPIC, new FileRead(file, Why.OPEN, true, text, false, "", ""));
        List<AddTab> added = took(AddTab.class);
        assertEquals(1, added.size());
        return added.getFirst().id();
    }

    /** Open {@code name} and type into it. */
    private long unsaved(String name) {
        long id = opened(name, name);
        tell(Messages.EDITED_TOPIC, new Edited(id, true));
        sent.clear();
        return id;
    }

    // ------------------------------------------------------------------ opening

    @Test
    void openingReadsTheFileThenAddsATabInFront() {
        Path file = file("a.txt");
        tell(Messages.OPEN_TOPIC, new Open(file, true));
        assertEquals(List.of(new Read(file, Why.OPEN, true)), took(Read.class));
        tell(Messages.FILE_READ_TOPIC, new FileRead(file, Why.OPEN, true, "hello", false, "", ""));
        assertTrue(sent.contains(new AddTab(1, file, "hello", true)));
        assertTrue(sent.contains(new Caption("a.txt")));
        assertTrue(sent.contains(new Status("Opened a.txt", false)));
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanReadingItAgain() {
        long a = opened("a.txt", "a");
        opened("b.txt", "b");
        tell(Messages.OPEN_TOPIC, new Open(dir.resolve("a.txt"), true));
        assertEquals(List.of(new FrontTab(a, true)), took(FrontTab.class));
        assertTrue(took(Read.class).isEmpty());
    }

    @Test
    void aSecondClickWhileTheFirstIsBeingReadReadsNothingMore() {
        Path file = file("a.txt");
        tell(Messages.OPEN_TOPIC, new Open(file, true));
        tell(Messages.OPEN_TOPIC, new Open(file, true));
        assertEquals(1, took(Read.class).size());
    }

    @Test
    void aFileThatCannotBeOpenedIsRefusedOnTheStatusLine() {
        Path blob = file("blob.bin");
        tell(Messages.OPEN_TOPIC, new Open(blob, true));
        tell(Messages.FILE_READ_TOPIC, new FileRead(blob, Why.OPEN, true, "", false, "", "it is binary"));
        assertTrue(sent.contains(new Status("Could not open blob.bin: it is binary", true)));
        assertTrue(took(AddTab.class).isEmpty());
        tell(Messages.OPEN_TOPIC, new Open(blob, true));
        assertEquals(1, took(Read.class).size(), "a refusal does not leave the file marked as being read");
    }

    @Test
    void goingUnsavedRetitlesTheTabAndTheCaption() {
        long a = opened("a.txt", "a");
        tell(Messages.EDITED_TOPIC, new Edited(a, true));
        assertTrue(sent.contains(new Retitle(a, "• a.txt")));
        assertTrue(sent.contains(new Caption("• a.txt")));
    }

    @Test
    void thePathBarCreatesANameThatIsNotThereYetInTheFolderShown() {
        tell(Messages.RECALLED_TOPIC, recalled(Optional.of(dir), List.of(), Optional.empty(), List.of()));
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.RESTORE_FOLDER, Kind.FOLDER));
        sent.clear();
        tell(Messages.GO_TOPIC, new Go("new.txt"));
        Path wanted = file("new.txt");
        assertEquals(List.of(new Find(wanted, Why.GO)), took(Find.class));
        tell(Messages.FOUND_TOPIC, new Found(wanted, Why.GO, Kind.NEW));
        assertEquals(List.of(new Create(wanted, Made.FILE)), took(Create.class));
        tell(Messages.CREATED_TOPIC, new Created(wanted, Made.FILE, "", true, "", ""));
        assertTrue(sent.contains(new ShowFolder(dir)), "the folder is listed again");
        List<AddTab> added = took(AddTab.class);
        assertEquals(1, added.size());
        tell(Messages.SAVE_TOPIC, new Save(added.getFirst().id(), "x", true));
        assertTrue(took(Write.class).getFirst().crlf(), "a new file has the line endings its job said");
    }

    @Test
    void aNameEndingInASeparatorIsAFolderToMake() {
        tell(Messages.RECALLED_TOPIC, recalled(Optional.of(dir), List.of(), Optional.empty(), List.of()));
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.RESTORE_FOLDER, Kind.FOLDER));
        sent.clear();
        tell(Messages.GO_TOPIC, new Go("made" + java.io.File.separator));
        Path wanted = file("made");
        assertEquals(List.of(new Find(wanted, Why.GO_FOLDER)), took(Find.class));
        tell(Messages.FOUND_TOPIC, new Found(wanted, Why.GO_FOLDER, Kind.NEW));
        assertEquals(List.of(new Create(wanted, Made.FOLDER)), took(Create.class));
        tell(Messages.CREATED_TOPIC, new Created(wanted, Made.FOLDER, "", true, "", ""));
        assertTrue(sent.contains(new ShowFolder(dir)), "the folder is listed again");
        assertTrue(sent.contains(new Status("Created folder made", false)));
        assertTrue(took(AddTab.class).isEmpty(), "a folder opens no tab");
    }

    @Test
    void aFolderAskedForWhereAFileIsIsRefused() {
        Path there = file("a.txt");
        tell(Messages.FOUND_TOPIC, new Found(there, Why.GO_FOLDER, Kind.FILE));
        assertTrue(sent.contains(new Status("A file is there, not a folder: a.txt", true)));
        assertTrue(took(Read.class).isEmpty(), "the file is not opened");
    }

    // ------------------------------------------------------------------ closing: the witness

    @Test
    void closingASavedTabClosesItWithoutAsking() {
        long a = opened("a.txt", "a");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        assertEquals(List.of(new CloseTabs(List.of(a))), sent.stream().filter(CloseTabs.class::isInstance).toList());
        assertTrue(took(Ask.class).isEmpty());
        assertTrue(editor.state().closings().isEmpty(), "nothing is left waiting");
    }

    @Test
    void closingAnUnsavedTabAsksAndNothingClosesWhileThePersonDecides() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        Ask ask = took(Ask.class).getFirst();
        assertEquals("Unsaved changes", ask.title());
        assertEquals("Save the changes to a.txt before closing it?", ask.message());
        assertEquals(List.of("Save", "Don't save", "Cancel"), ask.buttons(), "default first, cancel last");
        assertEquals(1, editor.state().closings().size());
        assertTrue(editor.state().doc().entry(a) != null, "the tab is still open");
    }

    @Test
    void dontSaveClosesWithoutWriting() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        tell(Answer.TOPIC, new Answer(took(Ask.class).getFirst().id(), 1));
        assertTrue(sent.contains(new CloseTabs(List.of(a))));
        assertTrue(took(Write.class).isEmpty(), "Don't save does not save");
        assertTrue(editor.state().closings().isEmpty());
    }

    @Test
    void saveWritesThenClosesOnceTheWriteHasLanded() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        tell(Answer.TOPIC, new Answer(took(Ask.class).getFirst().id(), 0));
        assertEquals(List.of(new Flush(List.of(a))), took(Flush.class));
        tell(Messages.SAVE_TOPIC, new Save(a, "typed", true));
        Write w = took(Write.class).getFirst();
        assertEquals("typed", w.text());
        assertTrue(editor.state().doc().entry(a) != null, "nothing closes before the write lands");
        tell(Messages.WRITTEN_TOPIC, new Written(a, w.path(), ""));
        assertTrue(sent.contains(new CloseTabs(List.of(a))));
        assertTrue(editor.state().closings().isEmpty());
    }

    @Test
    void cancelClosesNothingAndForgetsTheQuestion() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        long question = took(Ask.class).getFirst().id();
        tell(Answer.TOPIC, new Answer(question, 2));
        assertTrue(took(CloseTabs.class).isEmpty());
        assertTrue(editor.state().closings().isEmpty());
        tell(Answer.TOPIC, new Answer(question, 1));
        assertTrue(took(CloseTabs.class).isEmpty(), "an answer to a question no longer asked does nothing");
    }

    @Test
    void severalTabsAreOneMessageAndOneQuestionAboutTheUnsavedOnes() {
        long a = opened("a.txt", "a");
        long b = unsaved("b.txt");
        long c = unsaved("c.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a, b, c)));
        List<Ask> asked = took(Ask.class);
        assertEquals(1, asked.size());
        assertEquals("2 files have unsaved changes: b.txt, c.txt.", asked.getFirst().message());
        tell(Answer.TOPIC, new Answer(asked.getFirst().id(), 1));
        assertEquals(List.of(new CloseTabs(List.of(a, b, c))), took(CloseTabs.class));
    }

    @Test
    void aSecondAnswerWhileTheSavesAreUnderWayChangesNothing() {
        long a = unsaved("a.txt");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(a)));
        long question = took(Ask.class).getFirst().id();
        tell(Answer.TOPIC, new Answer(question, 0));
        tell(Answer.TOPIC, new Answer(question, 1));
        assertTrue(took(CloseTabs.class).isEmpty(), "the run waits on the write, not on another answer");
    }

    // ------------------------------------------------------------------ scratches

    /** A scratch as {@code MakeScratch} would answer it: opened, and its tab's id returned. */
    private long scratch(String name) {
        Path made = file(name);
        tell(Messages.SCRATCH_MADE_TOPIC, new ScratchMade(made, false, ""));
        List<AddTab> added = took(AddTab.class);
        assertEquals(List.of(new AddTab(added.getFirst().id(), made, "", true)), added);
        return added.getFirst().id();
    }

    @Test
    void aNewScratchOpensEmptyInFrontAndIsSaid() {
        Path made = file("2026-11-01-1432.md");
        tell(Messages.SCRATCH_MADE_TOPIC, new ScratchMade(made, false, ""));
        assertTrue(sent.contains(new AddTab(1, made, "", true)));
        assertTrue(sent.contains(new Status("New scratch 2026-11-01-1432.md", false)));
        assertEquals(java.util.Set.of(made), editor.state().fresh());
    }

    @Test
    void aScratchThatCouldNotBeMadeIsRefused() {
        tell(Messages.SCRATCH_MADE_TOPIC, new ScratchMade(dir, false, "denied"));
        assertTrue(sent.contains(new Status("Could not make a scratch: denied", true)));
        assertTrue(took(AddTab.class).isEmpty());
    }

    @Test
    void aNewScratchInTheFolderShownListsItAgain() {
        tell(Messages.RECALLED_TOPIC, recalled(Optional.empty(), List.of(), Optional.empty(), List.of()));
        sent.clear();
        tell(Messages.SCRATCH_MADE_TOPIC, new ScratchMade(file("t.md"), false, ""));
        assertEquals(List.of(new ShowFolder(dir)), took(ShowFolder.class));
    }

    @Test
    void aFreshScratchClosedGoesAgainAndAnotherFileDoesNot() {
        long s = scratch("s.md");
        long a = opened("a.txt", "a");
        tell(Messages.CLOSE_TOPIC, new Close(List.of(s, a)));
        assertEquals(List.of(new Dropping(file("s.md"))), took(Dropping.class));
        assertTrue(editor.state().fresh().isEmpty());
    }

    @Test
    void aScratchWrittenToIsKeptWhenClosed() {
        long s = scratch("s.md");
        tell(Messages.SAVE_TOPIC, new Save(s, "note", true));
        tell(Messages.WRITTEN_TOPIC, new Written(s, took(Write.class).getFirst().path(), ""));
        tell(Messages.CLOSE_TOPIC, new Close(List.of(s)));
        assertTrue(took(Dropping.class).isEmpty());
    }

    @Test
    void quittingTakesFreshScratchesAway() {
        scratch("s.md");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 5));
        assertEquals(List.of(new Dropping(file("s.md"))), sent.stream().filter(Dropping.class::isInstance).toList());
        assertEquals(List.of(new CloseAnswer(5, true)), took(CloseAnswer.class));
    }

    // ------------------------------------------------------------------ quitting: the same chain, another way in

    @Test
    void quittingWithNothingUnsavedProceedsWithoutAsking() {
        opened("a.txt", "a");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 7));
        assertEquals(List.of(new CloseAnswer(7, true)), sent.stream().filter(CloseAnswer.class::isInstance).toList());
        assertTrue(took(Ask.class).isEmpty());
    }

    @Test
    void saveAllThenQuitProceedsOnceEveryWriteHasLanded() {
        long a = unsaved("a.txt");
        long c = unsaved("c.txt");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 9));
        Ask ask = took(Ask.class).getFirst();
        assertEquals("Quit with unsaved changes?", ask.title());
        tell(Answer.TOPIC, new Answer(ask.id(), 0));
        assertEquals(List.of(new Flush(List.of(a, c))), took(Flush.class));

        tell(Messages.SAVE_TOPIC, new Save(a, "1a", true));
        tell(Messages.SAVE_TOPIC, new Save(c, "2c", true));
        List<Write> writes = took(Write.class);
        assertEquals(List.of("1a", "2c"), writes.stream().map(Write::text).toList());
        tell(Messages.WRITTEN_TOPIC, new Written(a, writes.get(0).path(), ""));
        assertTrue(took(CloseAnswer.class).isEmpty(), "one of two has landed");
        tell(Messages.WRITTEN_TOPIC, new Written(c, writes.get(1).path(), ""));
        assertEquals(List.of(new CloseAnswer(9, true)), took(CloseAnswer.class));
        assertTrue(editor.state().doc().entry(a) != null, "a quit closes the window, not the tabs");
    }

    @Test
    void aWriteThatFailsCancelsTheQuitAndSaysWhy() {
        long a = unsaved("a.txt");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 3));
        tell(Answer.TOPIC, new Answer(took(Ask.class).getFirst().id(), 0));
        tell(Messages.SAVE_TOPIC, new Save(a, "x", true));
        Write w = took(Write.class).getFirst();
        tell(Messages.WRITTEN_TOPIC, new Written(a, w.path(), "disk full"));
        assertTrue(sent.contains(new SaveFailed(a)));
        assertTrue(sent.contains(new Status("Could not save a.txt: disk full", true)));
        assertEquals(List.of(new CloseAnswer(3, false)), took(CloseAnswer.class));
        assertTrue(editor.state().closings().isEmpty());
    }

    @Test
    void cancellingAQuitKeepsTheWindow() {
        unsaved("a.txt");
        tell(CloseRequested.TOPIC, new CloseRequested(CloseRequested.MAIN, 4));
        tell(Answer.TOPIC, new Answer(took(Ask.class).getFirst().id(), 2));
        assertEquals(List.of(new CloseAnswer(4, false)), took(CloseAnswer.class));
    }

    @Test
    void anotherWindowClosesWithoutAskingWhateverIsUnsaved() {
        unsaved("a.txt");
        tell(CloseRequested.TOPIC, new CloseRequested("about", 5));
        assertEquals(List.of(new CloseAnswer(5, true)), took(CloseAnswer.class));
        assertTrue(took(Ask.class).isEmpty(), "closing the About window asked about the editor's files");
        assertTrue(editor.state().closings().isEmpty());
    }

    // ------------------------------------------------------------------ the session

    /** The tabs come back as they were: one file asked for at a time, the next when the last is answered. */
    @Test
    void theSessionComesBackInOrderAndIsRememberedOnlyAfterwards() {
        Path a = file("a.txt");
        Path b = file("b.txt");
        tell(WindowOpened.TOPIC, new WindowOpened("about"));
        assertTrue(took(Recall.class).isEmpty(), "another window opening brought the session back");
        tell(WindowOpened.TOPIC, new WindowOpened(CloseRequested.MAIN));
        assertEquals(List.of(new Recall()), took(Recall.class));

        tell(Messages.RECALLED_TOPIC, recalled(Optional.of(dir), List.of(a, b), Optional.of(a), List.of()));
        assertEquals(List.of(new Find(dir, Why.RESTORE_FOLDER)), took(Find.class));
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.RESTORE_FOLDER, Kind.FOLDER));
        assertEquals(List.of(new Read(a, Why.RESTORE_FILE, true)), took(Read.class), "only the first file");
        tell(Messages.FILE_READ_TOPIC, new FileRead(a, Why.RESTORE_FILE, true, "a", false, "", ""));
        assertFalse(sent.stream().anyMatch(Remember.class::isInstance), "nothing is remembered mid-restore");
        assertEquals(List.of(new Read(b, Why.RESTORE_FILE, true)), took(Read.class));
        tell(Messages.FILE_READ_TOPIC, new FileRead(b, Why.RESTORE_FILE, true, "b", false, "", ""));
        assertEquals(1, sent.stream().filter(FrontTab.class::isInstance).count(),
                "the remembered front comes back to the front");
        assertEquals(List.of(new Remember(Optional.of(dir), List.of(a, b), Optional.of(a), List.of())),
                took(Remember.class));
    }

    @Test
    void anUnchangedSessionIsNotRememberedTwice() {
        tell(Messages.RECALLED_TOPIC, recalled(Optional.empty(), List.of(), Optional.empty(), List.of()));
        assertEquals(1, took(Remember.class).size());
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.GO, Kind.FOLDER));
        assertTrue(took(Remember.class).isEmpty(), "the same folder again is the same session");
    }

    /** The recent folders come back with the session, the view hears them, and leaving a folder adds it. */
    @Test
    void recentFoldersAreBroughtBackToldAndRemembered() {
        Path old = dir.resolve("old");
        Path other = dir.resolve("other");
        tell(Messages.RECALLED_TOPIC,
                new Recalled(Optional.of(dir), List.of(), Optional.empty(), List.of(old), List.of(), dir));
        assertEquals(List.of(new Recent(List.of(old))), took(Recent.class));
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.RESTORE_FOLDER, Kind.FOLDER));
        assertTrue(took(Recent.class).isEmpty(), "the first folder shown leaves nothing new behind");

        tell(Messages.FOUND_TOPIC, new Found(other, Why.GO, Kind.FOLDER));
        assertEquals(List.of(new Recent(List.of(dir, old))), sent.stream().filter(Recent.class::isInstance).toList());
        assertEquals(List.of(new Remember(Optional.of(other), List.of(), Optional.empty(), List.of(dir, old))),
                took(Remember.class));
    }

    /**
     * A window the command line named a file for is about that file: plain, said before anything is looked at; the last
     * session is not brought back; and nothing it does is remembered, not even a folder shown in it later.
     */
    @Test
    void aWindowOnFilesAloneIsPlainAndKeepsNoSession() {
        Path a = file("a.txt");
        Path b = file("b.txt");
        tell(Messages.RECALLED_TOPIC, recalled(Optional.of(dir), List.of(b), Optional.of(b), List.of(a)));
        assertEquals(List.of(new Recent(List.of()), new Plain(), new Find(a, Why.ARGUMENT)), List.copyOf(sent),
                "plain first, then only the argument: no folder, no remembered files");
        sent.clear();
        tell(Messages.FOUND_TOPIC, new Found(a, Why.ARGUMENT, Kind.FILE));
        assertEquals(List.of(new Read(a, Why.RESTORE_FILE, true)), took(Read.class));
        tell(Messages.FILE_READ_TOPIC, new FileRead(a, Why.RESTORE_FILE, true, "a", false, "", ""));
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.GO, Kind.FOLDER));
        assertEquals(1, sent.stream().filter(ShowFolder.class::isInstance).count(), "a folder still shows");
        assertTrue(took(Remember.class).isEmpty(), "nothing is remembered");
    }

    /** A folder among the arguments is shown, which is what brings a plain window's navigator back. */
    @Test
    void aFolderArgumentIsShown() {
        tell(Messages.RECALLED_TOPIC, recalled(Optional.empty(), List.of(), Optional.empty(), List.of(dir)));
        assertEquals(List.of(new Find(dir, Why.ARGUMENT)), took(Find.class));
        tell(Messages.FOUND_TOPIC, new Found(dir, Why.ARGUMENT, Kind.FOLDER));
        assertEquals(List.of(new ShowFolder(dir)), took(ShowFolder.class));
    }
}
