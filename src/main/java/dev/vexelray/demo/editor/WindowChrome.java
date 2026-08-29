package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.NativeWindow;

/**
 * The two things every window this application opens has to do for itself when the OS hands it over, and the
 * one thing it has to do when the OS takes it away.
 *
 * <p>Neither is interesting, and that is the reason they are here rather than written out per window. Each
 * copy was five lines that had to name the same settings key three times, and the copies had already drifted:
 * one watched its {@code Gui} so the zoom came back, the other did not, and nothing on either said whether
 * that was a decision. Stated as a parameter, the difference is at least visible at both call sites.
 *
 * <p>Not the main window's, which genuinely differs: {@code GuiApp} is handed its bounds in the config it is
 * constructed from, so it is already in the right place by the time anyone could put it there.
 */
final class WindowChrome {

    private WindowChrome() {
    }

    /**
     * A window has just been created: point {@code bar} at it, put it back where it was left, and start
     * following it.
     *
     * <p>Restoring is an either/or rather than both. A window that was left maximized has bounds recorded
     * from before it was, and writing those in first would show the window at its old size for the frame
     * before the maximize lands.
     *
     * @param zoomed the tree whose zoom should be remembered along with the placement, or null to remember
     *               the placement only — Ctrl+= is the same kind of decision as dragging a window bigger, so
     *               a window that passes null is choosing to forget one of the two
     */
    static void created(WindowMemory memory, String key, TitleBar bar, NativeWindow window,
                        int defaultW, int defaultH, Gui zoomed) {
        bar.controls(WindowControls.of(window));
        if (memory.maximized(key)) {
            window.maximize();
        } else {
            memory.restoreBounds(key, window, defaultW, defaultH);
        }
        if (zoomed == null) {
            memory.watch(key, window);
        } else {
            memory.watch(key, window, zoomed);
        }
    }

    /**
     * The window is gone; whatever owns it is not.
     *
     * <p>Stop reading placement off a window that is being destroyed — what was recorded last stands — and
     * hand the bar back to nothing, because the buttons commanded that window and the bar outlives it. Both
     * windows here are shown again later out of the same object, so this is a handover rather than a
     * teardown.
     */
    static void closed(WindowMemory memory, String key, TitleBar bar) {
        memory.forget(key);
        bar.controls(WindowControls.NONE);
    }
}
