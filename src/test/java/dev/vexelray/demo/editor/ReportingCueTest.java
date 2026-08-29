package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.krono.KronoGui;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the editor does to say that something happened: the status line's arrival, the wash over a saved
 * document, and the pulse on the tab a file landed in.
 *
 * <p><b>The part worth a test is not the animation.</b> Whether a ring looks right is not something an assertion
 * can hold, and the curves are the framework's. What is this application's, and what breaks quietly, is
 * <em>which node gets marked</em> — every one of these resolves a node from an index or a path, against
 * structures that another thread is entitled to change, and a mark on the wrong tab is worse than no mark at
 * all: it is the editor pointing confidently at the wrong file.
 *
 * <p>The other half is that they end. A cue paints into a node's overlay and takes it back off when its ramp
 * finishes, so a cue that is never settled is a node left permanently decorated. {@code Cues.active()} is zero
 * at rest, and these drive the clock far enough forward to insist on it.
 *
 * <p>No frames and no window, like {@link TabBookkeepingTest}: a {@link Gui} whose handlers run on the calling
 * thread, and a clock ticked by hand rather than by a frame loop.
 */
class ReportingCueTest {

    /** Longer than the longest cue this application plays, so one of these settles anything in flight. */
    private static final Dur PAST_THE_END = Dur.ms(500);

    private record Harness(Gui gui, KronoGui krono, Workspace ws) implements AutoCloseable {
        static Harness open() {
            Gui gui = new Gui(Atchung.create(), Runnable::run);
            KronoGui krono = KronoGui.attach(gui);
            return new Harness(gui, krono, new Workspace(gui, krono));
        }

        /**
         * Run every ramp in flight to its end. Twice, and it has to be: {@code KronoGui.ramp} defers its
         * {@code done} to the frame after the one carrying 1, so that the end value is on screen before anything
         * tears down — which means a single tick past the duration leaves the settle still owing.
         */
        void settle() {
            krono.tick(PAST_THE_END);
            krono.tick(PAST_THE_END);
        }

        @Override
        public void close() {
            krono.close();
            gui.close();
        }
    }

    private static EditorTab openFile(Workspace ws, String name) {
        return ws.newTab("contents of " + name, Path.of(name), false);
    }

    @Test
    void anArrivalMarksThatTabsOwnHeaderAndNoOther() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            EditorTab a = openFile(ws, "A.txt");
            EditorTab b = openFile(ws, "B.txt");

            ws.arrived(a);

            assertTrue(ws.cues.isPlaying(ws.tabs.header(ws.open.indexOf(a))),
                    "the header of the tab that received the file is the one marked");
            assertFalse(ws.cues.isPlaying(ws.tabs.header(ws.open.indexOf(b))),
                    "and the tab that received nothing is left alone");
        }
    }

    /**
     * The stale-index case, which is the whole reason {@code arrived} resolves under the bar's lock rather than
     * being handed a number. A tab can go between the request being made and this running — the header menu's
     * own <b>Close</b> does exactly that, from a handler lane, without passing through the request queue.
     */
    @Test
    void arrivalOnATabThatHasSinceBeenClosedMarksNothing() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            openFile(ws, "A.txt");
            EditorTab gone = openFile(ws, "B.txt");
            ws.tabs.remove(ws.open.indexOf(gone));

            ws.arrived(gone);

            assertEquals(0, ws.cues.active(),
                    "a document that is no longer open has no header to mark, and nothing else is marked in "
                            + "its place");
        }
    }

    /** {@code active()} is often null — nothing is open — and every caller hands it straight over. */
    @Test
    void arrivalOnNothingIsQuiet() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            ws.arrived(null);
            assertEquals(0, ws.cues.active());
        }
    }

    /** A save marks the page that was written, which is not necessarily the page on screen. */
    @Test
    void aSaveMarksTheDocumentThatWasWrittenRatherThanTheSelectedOne() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            EditorTab background = openFile(ws, "A.txt");
            EditorTab selected = openFile(ws, "B.txt");

            ws.saved(background);

            assertTrue(ws.cues.isPlaying(background.body), "the saved document is the one that flashes");
            assertFalse(ws.cues.isPlaying(selected.body),
                    "a save-all writes tabs nobody is looking at, and must not flash the one they are");
        }
    }

    /**
     * Nothing stays decorated. A cue owns a node's overlay for its duration and hands it back; a settle that
     * never came would leave a tab header permanently ringed, and no frame after that could put it right.
     */
    @Test
    void everyMarkClearsItselfWhenItsRampRunsOut() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();
            EditorTab tab = openFile(ws, "A.txt");

            ws.arrived(tab);
            ws.saved(tab);
            ws.warn("Save failed: no");
            assertTrue(ws.cues.active() > 0, "something is in flight to begin with");

            h.settle();

            assertEquals(0, ws.cues.active(), "and the class is at rest afterwards");
        }
    }

    /**
     * Reporting twice in a row is the ordinary case — a save-all says something per document — and the second
     * report must not be left fighting the first for the status line. Supersession is by identity, so the
     * loser's samples and its settle both become no-ops rather than snapping the winner to its end state.
     */
    @Test
    void aSecondReportDuringTheFirstSettlesCleanly() {
        try (Harness h = Harness.open()) {
            Workspace ws = h.ws();

            ws.say("Saved one");
            h.krono().tick(Dur.ms(40));   // mid-fade, deliberately
            ws.warn("Save failed: two");

            h.settle();

            assertEquals(0, ws.cues.active(), "the ring the failure played is over");
        }
    }
}
