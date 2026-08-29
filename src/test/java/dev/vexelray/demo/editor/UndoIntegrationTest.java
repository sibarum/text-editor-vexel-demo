package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.krono.KronoGui;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the editor has to get right now that the fields under its tabs have an undo history.
 *
 * <p>The stacks, the coalescing and the saved position are the framework's ({@code History}), and Ctrl+Z is
 * wired inside {@code TextField}: none of that is this application's to test. What is this application's is the
 * seam — that a document loaded over a tab cannot be undone back into the previous file, that saving moves the
 * history's idea of "saved" to where the file now is, and that undoing across a save makes the tab dirty again
 * so the close gate still asks. Each of those is a call this editor either makes or does not.
 *
 * <p>Undo is driven through {@code history()} rather than by synthesising Ctrl+Z, because the key binding is the
 * widget's and already covered there; what is in question here is the state either side of it.
 */
class UndoIntegrationTest {

    private record Harness(Gui gui, KronoGui krono, TextEditorApp.Workspace ws) implements AutoCloseable {
        static Harness open() {
            Gui gui = new Gui(Atchung.create(), Runnable::run);
            KronoGui krono = KronoGui.attach(gui);
            return new Harness(gui, krono, new TextEditorApp.Workspace(gui, krono));
        }

        @Override
        public void close() {
            krono.close();
            gui.close();
        }
    }

    /** A tab arrives clean, and its history has nothing in it to undo the file it was opened with. */
    @Test
    void anOpenedFileIsCleanAndCannotBeUndoneAway() {
        try (Harness h = Harness.open()) {
            TextEditorApp.EditorTab tab = h.ws().newTab("on disk", Path.of("a.txt"), false);

            assertFalse(tab.dirty(), "a file just opened has no edits");
            assertFalse(tab.editor.history().canUndo(), "and nothing to undo: loading is not an edit");
            assertTrue(tab.editor.history().clean(), "the history agrees the document is saved");
        }
    }

    /** Typing is undoable and moves both answers to dirty; undoing back returns the document, and the answer. */
    @Test
    void typingIsUndoableAndUndoingBackIsCleanAgain() {
        try (Harness h = Harness.open()) {
            TextEditorApp.EditorTab tab = h.ws().newTab("on disk", Path.of("a.txt"), false);
            tab.editor.insert("!");

            assertTrue(tab.dirty(), "an edit that is not on disk");
            assertTrue(tab.editor.history().canUndo());
            assertFalse(tab.editor.history().clean());

            assertTrue(tab.editor.history().undo());
            assertEquals("on disk", tab.editor.text(), "the document is back");
            assertFalse(tab.dirty(), "and so is the file it was clean against");
            assertTrue(tab.editor.history().clean());
            assertTrue(tab.editor.history().canRedo(), "the edit is still there to be put back");
        }
    }

    /**
     * Saving marks the history where the file now is — so the next edit is dirty from that point, and undoing
     * across the save is dirty again rather than clean because the text once matched.
     */
    @Test
    void savingMovesTheSavedPositionAndUndoingPastItIsDirty() {
        try (Harness h = Harness.open()) {
            TextEditorApp.EditorTab tab = h.ws().newTab("first", Path.of("a.txt"), false);
            tab.editor.insert(" second");
            tab.savedAs(tab.editor.text());   // what write() does once the bytes have landed

            assertFalse(tab.dirty(), "the tab is clean against what was just written");
            assertTrue(tab.editor.history().clean(), "and the history stands at the saved position");
            assertTrue(tab.editor.history().canUndo(), "saving does not throw the edits away");

            assertTrue(tab.editor.history().undo());
            assertEquals("first", tab.editor.text());
            assertTrue(tab.dirty(), "undone back past the save, the document differs from the file again");
            assertFalse(tab.editor.history().clean());

            assertTrue(tab.editor.history().redo());
            assertFalse(tab.dirty(), "redone up to the save, it matches the file again");
            assertTrue(tab.editor.history().clean());
        }
    }

    /** The gate that asks about unsaved work sees an undone document as saved, and a redone one as not. */
    @Test
    void theUnsavedListFollowsUndo() {
        try (Harness h = Harness.open()) {
            TextEditorApp.EditorTab tab = h.ws().newTab("on disk", Path.of("a.txt"), false);
            tab.editor.insert("edited");
            assertEquals(1, h.ws().unsaved().size(), "one document has work that is not on disk");

            assertTrue(tab.editor.history().undo());
            assertTrue(h.ws().unsaved().isEmpty(), "undone away, there is nothing to lose by closing");

            assertTrue(tab.editor.history().redo());
            assertEquals(1, h.ws().unsaved().size(), "put back, there is again");
        }
    }

    /**
     * Closing the last tab resets it rather than removing it. The reset replaces the text, which clears the
     * history — so Ctrl+Z on the empty tab that remains cannot bring the closed document back.
     */
    @Test
    void closingTheLastTabLeavesNothingToUndo() {
        try (Harness h = Harness.open()) {
            TextEditorApp.EditorTab tab = h.ws().newTab("on disk", Path.of("a.txt"), false);
            tab.editor.insert(" edited");
            h.ws().tabs.remove(0);   // the welcome tab, so the edited one is the last

            h.ws().closeActive();

            TextEditorApp.EditorTab left = h.ws().active();
            assertEquals("", left.editor.text());
            assertFalse(left.editor.history().canUndo(), "a closed document is not undone back into view");
            assertFalse(left.dirty());
        }
    }
}
