package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages.Relocate;
import dev.vexelray.gui.core.edit.Change;
import sibarum.atchung.Sender;

import java.nio.file.Path;

/**
 * A move among the scratches, in the window's history: applied, it asks {@code MoveScratch} to move what is at
 * {@code at} back to {@code back}, and returns the change that moves it forward again. The history's ruled pattern
 * (vexelray-gui's {@code ValueWidgetUndoTest}, and Vexplore's {@code Journaled}): its changes send messages, and the
 * disk is the job's, which refuses a move the files have since made impossible and says so.
 *
 * @param at   where the scratch is now
 * @param back where applying this puts it
 */
record Moving(Path at, Path back, Sender<Relocate> relocate) implements Change {

    @Override
    public Change apply() {
        relocate.send(new Relocate(at, back, false));
        return new Moving(back, at, relocate);
    }
}
