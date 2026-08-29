package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.krono.KronoGui;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one invariant the whole {@link Workspace} rests on: {@code open.get(i)} is the document on
 * tab {@code i}. Every command the editor has resolves a tab index against that list — which file Ctrl+S writes,
 * which tab a click in the file tree brings forward, which document a title belongs to — so the moment the two
 * disagree by one the editor keeps drawing perfectly and acts on the wrong file, or on none.
 *
 * <p>It got away once. The tab bar puts a <b>Close</b> item on every header's context menu itself and wires it
 * straight to {@code Tabs.remove}, so that route never passed through {@code Workspace} at all: the bar lost a
 * tab, {@code open} kept the document, and from then on every index was off. Right-click-close a few tabs,
 * press Ctrl+N, and the visible tab and the tab commands acted on were different objects — the file tree opened
 * the wrong tab, a file's name appeared over an empty page, and Ctrl+S did nothing at all with nothing to say.
 * The tests below are that sequence, and the ones that make the general case hold.
 *
 * <p>No frames and no window: these drive the workspace by the calls the shortcuts and the file tree make, on
 * a {@link Gui} whose handlers run on the calling thread, so what is under test is the bookkeeping rather than
 * anything about rendering.
 */
class TabBookkeepingTest {

    /** The workspace plus the two things it is built from, closed together. */
    private record Harness(Gui gui, KronoGui krono, Workspace ws) implements AutoCloseable {
        static Harness open() {
            Gui gui = new Gui(Atchung.create(), Runnable::run);
            KronoGui krono = KronoGui.attach(gui);
            return new Harness(gui, krono, new Workspace(gui, krono));
        }

        @Override
        public void close() {
            krono.close();
            gui.close();
        }
    }

    /** Open {@code name} in a tab, the way loading a file does. */
    private static void openFile(Workspace ws, String name) {
        ws.newTab("contents of " + name, Path.of(name), false);
    }

    /** Close tab {@code index} the way the header's context menu does: straight at the bar. */
    private static void closeFromHeaderMenu(Workspace ws, int index) {
        ws.tabs.remove(index);
    }

    /** The list and the bar are the same length, and the visible tab is the one commands will act on. */
    private static void assertConsistent(Workspace ws) {
        assertEquals(ws.tabs.count(), ws.open.size(), "a document per tab, no more and no fewer");
        if (ws.tabs.count() > 0) {
            assertSame(ws.open.get(ws.tabs.selected()), ws.active(),
                    "the tab that is showing is the tab active() answers with");
        }
    }

    @Test
    void closingFromTheHeaderMenuDropsTheDocumentTooAndNoOther() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            openFile(ws, "A.txt");
            openFile(ws, "B.txt");
            openFile(ws, "C.txt");
            assertEquals(4, ws.open.size(), "the welcome tab and three files");

            closeFromHeaderMenu(ws, 2);   // B.txt

            assertConsistent(ws);
            assertEquals(3, ws.open.size());
            assertFalse(ws.showFile(Path.of("B.txt")), "B is gone from the list, not just from the bar");
            assertTrue(ws.showFile(Path.of("C.txt")), "and C is still reachable");
            assertEquals("C.txt", ws.active().title(), "by the tab it is actually on");
        }
    }

    /**
     * The reported sequence, start to finish: several files open, all of them closed from the header menu,
     * Ctrl+N for a fresh document, then a click in the file tree. Before the bar reported its own removals
     * every one of these steps compounded the last.
     */
    @Test
    void closingEveryTabFromTheMenuThenStartingAgainLeavesNothingBehind() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            openFile(ws, "A.txt");
            openFile(ws, "B.txt");
            openFile(ws, "C.txt");

            while (ws.tabs.count() > 0) {
                closeFromHeaderMenu(ws, 0);
            }
            assertTrue(ws.empty(), "closing every tab left no documents either");

            // Ctrl+N. The drain loop puts the floor back on its own, but the shortcut is what happens here.
            ws.newTab("", null, false);

            assertConsistent(ws);
            assertEquals(1, ws.open.size(), "one new document, not one on top of four dead ones");
            assertEquals(0, ws.tabs.selected());
            // The symptom this is really about: the tab on screen and the tab Ctrl+S would write were two
            // different objects, so saving reported nothing and typing went somewhere nothing could see.
            assertSame(ws.open.get(0), ws.active());

            // A click in the file tree on a file that was closed: it opens, rather than selecting some other tab
            // on the strength of a stale entry that still claimed to hold it.
            assertFalse(ws.showFile(Path.of("A.txt")), "no tab still claims a file that was closed");
        }
    }

    /** Ctrl+W and the menu are the same removal now, so a session mixing them stays consistent throughout. */
    @Test
    void theTwoWaysToCloseAgreeWithEachOther() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            openFile(ws, "A.txt");
            openFile(ws, "B.txt");
            openFile(ws, "C.txt");
            openFile(ws, "D.txt");

            closeFromHeaderMenu(ws, 0);   // the welcome tab
            assertConsistent(ws);
            ws.closeActive();             // Ctrl+W
            assertConsistent(ws);
            closeFromHeaderMenu(ws, ws.tabs.selected());
            assertConsistent(ws);
            ws.closeActive();
            assertConsistent(ws);

            assertEquals(1, ws.open.size(), "four closes over five tabs leave one");
        }
    }

    /** Ctrl+W on the last tab empties it rather than removing it — the editor is never left with no document. */
    @Test
    void ctrlWOnTheLastTabLeavesAnEmptyOne() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            openFile(ws, "A.txt");
            ws.closeActive();   // A.txt goes
            ws.closeActive();   // the welcome tab is emptied, not removed

            assertConsistent(ws);
            assertEquals(1, ws.open.size());
            assertEquals("", ws.active().editor.text(), "reset to an empty untitled document");
            assertFalse(ws.active().dirty(), "and not counted as unsaved work");
        }
    }

    /**
     * <b>Close all</b> is the bulk version of the removal this whole file is about, so it is the one command
     * most able to leave the two lists a different length: it removes many tabs in a row without a frame in
     * between, and each removal moves the selection under the next one.
     */
    @Test
    void closeAllLeavesOneEmptyTabAndNoDocumentsBehind() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            openFile(ws, "A.txt");
            openFile(ws, "B.txt");
            openFile(ws, "C.txt");
            assertEquals(4, ws.open.size(), "the welcome tab and three files");

            ws.closeAll();

            assertConsistent(ws);
            assertEquals(1, ws.open.size(), "four tabs in, one out");
            assertEquals("", ws.active().editor.text(), "and it is an empty untitled document");
            assertNull(ws.active().file);
            assertFalse(ws.active().dirty(), "not counted as unsaved work");
            assertFalse(ws.showFile(Path.of("B.txt")), "no tab still claims a file that was closed");
        }
    }

    /** The selection walks backwards over every tab on the way out; it must arrive somewhere real. */
    @Test
    void closeAllFromAnyStartingSelectionEndsOnTheTabThatSurvives() {
        for (int start = 0; start < 4; start++) {
            try (Harness h = Harness.open()) {
                Workspace ws = h.ws();
                openFile(ws, "A.txt");
                openFile(ws, "B.txt");
                openFile(ws, "C.txt");
                ws.tabs.select(start);

                ws.closeAll();

                assertConsistent(ws);
                assertEquals(0, ws.tabs.selected(), "selection " + start + " ended up on the one tab left");
                assertEquals(1, ws.open.size());
            }
        }
    }

    /** Asked for when there is nothing to close, it is the same reset Ctrl+W does — not an editor with no tabs. */
    @Test
    void closeAllOnASingleTabEmptiesItRatherThanRemovingIt() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            ws.active().editor.text("typed but never saved");

            ws.closeAll();

            assertConsistent(ws);
            assertEquals(1, ws.open.size(), "the editor is never left with no document");
            assertEquals("", ws.active().editor.text());
        }
    }

    /** And it agrees with the other two routes, so a session mixing all three stays consistent. */
    @Test
    void closeAllAgreesWithTheOtherTwoWaysToClose() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            openFile(ws, "A.txt");
            openFile(ws, "B.txt");
            openFile(ws, "C.txt");

            closeFromHeaderMenu(ws, 0);   // the welcome tab
            assertConsistent(ws);
            ws.closeActive();             // Ctrl+W
            assertConsistent(ws);
            ws.closeAll();
            assertConsistent(ws);

            // Everything still works afterwards: the list the commands resolve against is the bar's own.
            openFile(ws, "D.txt");
            assertConsistent(ws);
            assertTrue(ws.showFile(Path.of("D.txt")));
            assertSame(ws.open.get(ws.tabs.selected()), ws.active());
        }
    }

    /** Removing tabs must not confuse which documents have edits — unsaved() is what the quit gate asks. */
    @Test
    void unsavedWorkIsStillAttributedToTheRightDocument() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            openFile(ws, "A.txt");
            openFile(ws, "B.txt");
            openFile(ws, "C.txt");
            ws.open.get(3).editor.text("edited C");

            closeFromHeaderMenu(ws, 1);   // A.txt, ahead of the edited one

            assertConsistent(ws);
            assertEquals(1, ws.unsaved().size(), "one document has changes that are not on disk");
            assertEquals("C.txt", ws.unsaved().get(0).title(), "and it is the one that was edited");
        }
    }
}
