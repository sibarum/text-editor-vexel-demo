package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Add;
import dev.vexelray.demo.editor.core.Messages.AddTab;
import dev.vexelray.demo.editor.core.Messages.Answer;
import dev.vexelray.demo.editor.core.Messages.Ask;
import dev.vexelray.demo.editor.core.Messages.AskWhere;
import dev.vexelray.demo.editor.core.Messages.Choice;
import dev.vexelray.demo.editor.core.Messages.CloseTab;
import dev.vexelray.demo.editor.core.Messages.Closing;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.demo.editor.core.Messages.Dropping;
import dev.vexelray.demo.editor.core.Messages.Edited;
import dev.vexelray.demo.editor.core.Messages.FileRead;
import dev.vexelray.demo.editor.core.Messages.Flush;
import dev.vexelray.demo.editor.core.Messages.FrontTab;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.NewFile;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.Placed;
import dev.vexelray.demo.editor.core.Messages.Purpose;
import dev.vexelray.demo.editor.core.Messages.Read;
import dev.vexelray.demo.editor.core.Messages.Relist;
import dev.vexelray.demo.editor.core.Messages.Relocated;
import dev.vexelray.demo.editor.core.Messages.Retitle;
import dev.vexelray.demo.editor.core.Messages.Save;
import dev.vexelray.demo.editor.core.Messages.SaveAs;
import dev.vexelray.demo.editor.core.Messages.SaveFailed;
import dev.vexelray.demo.editor.core.Messages.Saved;
import dev.vexelray.demo.editor.core.Messages.ScratchMade;
import dev.vexelray.demo.editor.core.Messages.Settled;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.demo.editor.core.Messages.Template;
import dev.vexelray.demo.editor.core.Messages.Why;
import dev.vexelray.demo.editor.core.Messages.Write;
import dev.vexelray.demo.editor.core.Messages.Written;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller, alone: no window, no lanes, no jobs. Each test hands the {@link Editor} a message the way its lane
 * would, one at a time on this thread, and reads what it sent back. The jobs' replies are handed in by hand, which is
 * what makes the interesting orders (a write that fails, a dialog cancelled, a root's tabs coming back one at a time)
 * as easy to test as the common one.
 */
class EditorTest {

    @TempDir
    Path dir;

    private Settings settings;
    private Editor editor;
    /** Everything the editor published, in order. */
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        Atchung bus = Atchung.create();
        settings = Settings.at(dir.resolve("settings.properties"));
        editor = new Editor(bus, settings, Launch.parse(new String[0], "test", Set.of()),
                new Scratches(dir.resolve("scratches")));
        for (Topic<?> t : List.of(Messages.SHOW_FOLDER_TOPIC, Messages.RECENT_TOPIC, Messages.ADD_TAB_TOPIC,
                Messages.FRONT_TAB_TOPIC, Messages.CLOSE_TAB_TOPIC, Messages.RETITLE_TOPIC, Messages.CAPTION_TOPIC,
                Messages.STATUS_TOPIC, Messages.ASK_TOPIC, Messages.TELL_TOPIC, Messages.ASK_WHERE_TOPIC,
                Messages.FLUSH_TOPIC, Messages.SAVE_FAILED_TOPIC, Messages.SAVED_TOPIC, Messages.MOVED_TOPIC,
                Messages.REVERT_TOPIC, Messages.SETTLED_TOPIC, Messages.SCRATCH_SHOWN_TOPIC, Messages.RELIST_TOPIC,
                Messages.PLAIN_TOPIC, Messages.CHOOSE_ROOT_TOPIC, Messages.READ_TOPIC, Messages.WRITE_TOPIC,
                Messages.FIND_TOPIC, Messages.MAKE_SCRATCH_TOPIC, Messages.NAME_SCRATCH_TOPIC,
                Messages.DROP_SCRATCH_TOPIC, Messages.MAKE_TOPIC)) {
            listen(bus, t);
        }
    }

    private <T> void listen(Atchung bus, Topic<T> topic) {
        bus.subscribe(topic, sent::add);
    }

    /** What was sent of one kind, in order, and forget everything sent so far. */
    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    private Path file(String name) {
        return dir.resolve(name).toAbsolutePath().normalize();
    }

    /** The Untitled tab a window starts with, as the view asks for it; its id. */
    private long untitled() {
        editor.newFile(new NewFile());
        return took(AddTab.class).getFirst().id();
    }

    /** Open {@code name} as the reader would answer it, and return the new tab's id. */
    private long opened(String name, String text) {
        Path file = file(name);
        editor.open(new Open(file, true));
        assertEquals(List.of(new Read(file, Why.OPEN, true, Messages.NO_TAB)), took(Read.class));
        editor.read(new FileRead(file, Why.OPEN, true, Messages.NO_TAB, text, false, "", ""));
        List<AddTab> added = took(AddTab.class);
        assertEquals(1, added.size());
        return added.getFirst().id();
    }

    @Test
    void aFileTakesThePlaceOfTheEmptyUntitledTabAWindowStartsWith() {
        long blank = untitled();
        Path file = file("a.txt");
        editor.open(new Open(file, true));
        editor.read(new FileRead(file, Why.OPEN, true, Messages.NO_TAB, "hello", false, "", ""));
        AddTab tab = took(AddTab.class).getFirst();
        assertEquals(blank, tab.replace(), "the empty Untitled one is named to go");
        assertEquals("hello", tab.text());

        // Typed into, it is no longer empty, and stays.
        long a = untitled();
        editor.edited(new Edited(a, true));
        Path other = file("b.txt");
        editor.open(new Open(other, true));
        editor.read(new FileRead(other, Why.OPEN, true, Messages.NO_TAB, "", false, "", ""));
        assertEquals(Messages.NO_TAB, took(AddTab.class).getFirst().replace());
    }

    @Test
    void openingAnOpenFileFrontsItRatherThanReadingItAgain() {
        long a = opened("a.txt", "a");
        opened("b.txt", "b");
        editor.open(new Open(file("a.txt"), true));
        assertEquals(List.of(new FrontTab(a, true)), took(FrontTab.class));
        assertTrue(took(Read.class).isEmpty());
    }

    @Test
    void closingTheLastTabLeavesAnEmptyOne() {
        long a = opened("a.txt", "a");
        editor.closing(new Closing(1, 1, a, false, Purpose.TABS, null));
        assertEquals(List.of(new CloseTab(a)), sent.stream().filter(CloseTab.class::isInstance).toList());
        AddTab blank = took(AddTab.class).getFirst();
        assertEquals(null, blank.path());
        assertEquals("", blank.library());
    }

    @Test
    void anUntitledTabWithUnsavedWorkCanBeKeptAsAScratchAndAFileCannot() {
        long blank = untitled();
        editor.closing(new Closing(1, 1, blank, true, Purpose.TABS, null));
        assertEquals("Keep as scratch", took(Ask.class).getFirst().extra());

        long a = opened("a.txt", "a");
        editor.closing(new Closing(2, 1, a, true, Purpose.TABS, null));
        Ask ask = took(Ask.class).getFirst();
        assertEquals("", ask.extra());
        editor.answer(new Answer(ask.question(), Choice.OTHER));
        assertEquals(List.of(new CloseTab(a)), took(CloseTab.class), "Don't save closes it");
    }

    @Test
    void savingAnUntitledTabAsksWhereAndCancellingLeavesItUnsaved() {
        long blank = untitled();
        editor.save(new Save(blank, "text", true));
        AskWhere where = took(AskWhere.class).getFirst();
        assertEquals("untitled.txt", where.name());
        editor.placed(new Placed(where.question(), null));
        assertEquals(List.of(new SaveFailed(blank)), took(SaveFailed.class));

        editor.save(new Save(blank, "text", true));
        AskWhere again = took(AskWhere.class).getFirst();
        Path target = file("kept.txt");
        editor.placed(new Placed(again.question(), target));
        Write write = took(Write.class).getFirst();
        assertEquals(new Write(blank, target, "text", System.lineSeparator().equals("\r\n")), write);

        editor.written(new Written(blank, target, ""));
        assertTrue(sent.contains(new Saved(blank, target)));
        assertTrue(sent.contains(new Retitle(blank, "kept.txt")), "the tab is named for its file now");
        assertTrue(sent.contains(new Relist(null)), "the navigator lists the file it has never seen");
    }

    @Test
    void saveAsWritesWhereThePersonPicksWithTheFilesOwnLineEndings() {
        Path file = file("crlf.txt");
        editor.open(new Open(file, true));
        editor.read(new FileRead(file, Why.OPEN, true, Messages.NO_TAB, "x", true, "", ""));
        long a = took(AddTab.class).getFirst().id();
        editor.saveAs(new SaveAs(a));
        AskWhere where = took(AskWhere.class).getFirst();
        assertEquals("crlf.txt", where.name());
        assertEquals(dir.toAbsolutePath().normalize(), where.dir());
        Path copy = file("copy.txt");
        editor.placed(new Placed(where.question(), copy));
        assertEquals(List.of(new Flush(a)), took(Flush.class));
        editor.save(new Save(a, "x", true));
        assertEquals(List.of(new Write(a, copy, "x", true)), took(Write.class));
        editor.written(new Written(a, copy, ""));
        assertTrue(sent.contains(new Relist(null)), "the navigator lists the copy");
    }

    @Test
    void aWriteThatFailsLeavesTheTabUnsavedAndKeepsTheWindowOpen() {
        long a = opened("a.txt", "a");
        editor.closing(new Closing(7, 1, a, true, Purpose.QUIT, null));
        Ask ask = took(Ask.class).getFirst();
        assertEquals("Quit with unsaved changes?", ask.title());
        editor.answer(new Answer(ask.question(), Choice.CONFIRM));
        assertEquals(List.of(new Flush(a)), took(Flush.class));
        editor.save(new Save(a, "a!", true));
        Write w = took(Write.class).getFirst();
        editor.written(new Written(a, w.path(), "disk full"));
        assertTrue(sent.contains(new SaveFailed(a)));
        assertTrue(sent.contains(new Settled(7, Purpose.QUIT, false)), "a quit that waited on it stays");
    }

    @Test
    void aRunSavesOnlyTheFilesWithUnsavedWorkThenGoes() {
        long a = opened("a.txt", "a");
        long blank = untitled();
        editor.closing(new Closing(3, 2, a, true, Purpose.RUN, null));
        editor.closing(new Closing(3, 2, blank, true, Purpose.RUN, null));
        assertEquals(List.of(new Flush(a)), took(Flush.class), "an Untitled document is not what runs");
        editor.save(new Save(a, "a!", true));
        Write w = took(Write.class).getFirst();
        editor.written(new Written(a, w.path(), ""));
        assertTrue(sent.contains(new Settled(3, Purpose.RUN, true)));
    }

    @Test
    void stagingSavesTheTabsItCoversFirstAndAFailedSaveStagesNothing() {
        long a = opened("a.txt", "a");
        editor.closing(new Closing(5, 1, a, true, Purpose.STAGE, null));
        assertEquals(List.of(new Flush(a)), took(Flush.class));
        editor.save(new Save(a, "a!", true));
        Write w = took(Write.class).getFirst();
        editor.written(new Written(a, w.path(), ""));
        assertTrue(sent.contains(new Settled(5, Purpose.STAGE, true)));
        sent.clear();

        editor.edited(new Edited(a, true));
        editor.closing(new Closing(6, 1, a, true, Purpose.STAGE, null));
        took(Flush.class);
        editor.save(new Save(a, "a!!", true));
        editor.written(new Written(a, took(Write.class).getFirst().path(), "the disk is full"));
        assertTrue(sent.contains(new Settled(6, Purpose.STAGE, false)));

        editor.closing(new Closing(7, 1, Messages.NO_TAB, false, Purpose.STAGE, null));
        assertTrue(sent.contains(new Settled(7, Purpose.STAGE, true)), "nothing open: nothing to save first");
    }

    @Test
    void switchingRootsPutsTheTabsAwayAndSwitchingBackBringsThemBackOneAtATime() {
        Path one = file("one");
        Path two = file("two");
        untitled();
        editor.closing(new Closing(1, 1, 1, false, Purpose.ROOT, one));
        assertEquals(List.of(new ShowFolder(one)), took(ShowFolder.class));
        long a = opened("one/a.txt", "a");

        editor.closing(new Closing(2, 1, a, false, Purpose.ROOT, two));
        assertTrue(sent.contains(new ShowFolder(two)));
        assertTrue(sent.contains(new CloseTab(a)));
        sent.clear();

        editor.closing(new Closing(3, 1, a + 1, false, Purpose.ROOT, one));
        Read back = took(Read.class).getFirst();
        assertEquals(new Read(file("one/a.txt"), Why.REOPEN, false, Messages.NO_TAB), back);
        editor.read(new FileRead(back.path(), Why.REOPEN, false, Messages.NO_TAB, "a", false, "", ""));
        assertEquals(file("one/a.txt"), took(AddTab.class).getFirst().path());
    }

    @Test
    void addRefusesWhatIsNotANameAndOpensAFileOnceItIsMade() {
        editor.add(new Add(dir, Made.FILE, Template.TEXT, "  ", null));
        assertTrue(took(Status.class).getFirst().alert());
        editor.add(new Add(dir, Made.FILE, Template.TEXT, "a/b.txt", null));
        assertTrue(took(Add.class).isEmpty(), "a name, not a path");

        editor.add(new Add(dir, Made.FILE, Template.TEXT, " notes.txt ", null));
        assertEquals(List.of(new Add(dir, Made.FILE, Template.TEXT, "notes.txt", null)), took(Add.class));
        Path made = file("notes.txt");
        editor.made(new Created(made, Made.FILE, "", ""));
        assertTrue(sent.contains(new Relist(made)));
        assertTrue(sent.contains(new Read(made, Why.MADE, true, Messages.NO_TAB)));
        editor.read(new FileRead(made, Why.MADE, true, Messages.NO_TAB, "", false, "", ""));
        assertEquals("Made notes.txt", took(Status.class).getLast().message(), "not \"Opened\": what happened is that it was made");
    }

    @Test
    void aScratchMadeAndClosedEmptyGoesAndOneSavedOrFiledAwayStays() {
        Path made = file("scratches/one.md");
        editor.scratchMade(new ScratchMade(made, ""));
        long s = took(AddTab.class).getFirst().id();
        editor.closing(new Closing(1, 1, s, false, Purpose.TABS, null));
        assertTrue(sent.contains(new Dropping(made)));
        sent.clear();

        Path saved = file("scratches/two.md");
        editor.scratchMade(new ScratchMade(saved, ""));
        long t = took(AddTab.class).getFirst().id();
        editor.written(new Written(t, saved, ""));
        editor.closing(new Closing(2, 1, t, false, Purpose.TABS, null));
        assertTrue(took(Dropping.class).isEmpty(), "saved, even empty: kept");

        Path filed = file("scratches/three.md");
        editor.scratchMade(new ScratchMade(filed, ""));
        long u = took(AddTab.class).getFirst().id();
        Path moved = file("scratches/ideas/three.md");
        editor.relocated(new Relocated(filed, moved));
        editor.closing(new Closing(3, 1, u, false, Purpose.TABS, null));
        assertTrue(took(Dropping.class).isEmpty(), "filed away is meant to be kept");
    }
}
