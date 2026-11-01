package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.SplitPane;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window: the framework's title bar, the navigator beside the open files, and a status line under both.
 *
 * <h2>It holds no state</h2>
 *
 * <p>Every label here says what the editor last told the view, and the view tells it ({@link #caption},
 * {@link #status}). Nothing is remembered here to be shown later, which is the rule worth keeping as this grows: a
 * label that remembers a value can disagree with the session, and a header that says a file is saved when it is not
 * is the application lying.
 *
 * <p>Built in the {@code TREE} phase, before there is a window, and needing none: that is what lets a test build the
 * real tree headless. Written to from the view's lane, which is the framework's own idiom: a prop written off the GUI
 * thread is queued and applied by the next drain.
 *
 * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the screenshot
 * instrument in it means the same thing in every window on the desk; this application places the node and
 * supplies every colour in it through {@link Look}.
 */
final class Ui {

    /** The navigator's width on a first run. Type-relative, so it grows with the zoom like the text in it. */
    private static final Length NAVIGATOR = Length.rem(16);

    private final TitleBar titleBar;
    private final StatusBar status;
    private final Workspace workspace;
    private final Navigator navigator;
    private final Preview preview;
    private final SplitPane split;
    /** Set only by {@link #showNavigator}, from the view, on the GUI thread. */
    private boolean navigatorShown = true;
    /** Set only by {@link #picturing}: whether Open's picture stands where the editor does. */
    private boolean picturing;
    private final Motion motion;
    private final Color danger;

    Ui(Gui gui, Motion motion, TitleBar titleBar) {
        this.titleBar = titleBar;
        this.motion = motion;
        this.danger = gui.theme().color(Role.DANGER);

        status = new StatusBar(gui)
                .slot(Landmarks.STATUS_FILE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_MESSAGE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_LANGUAGE, StatusBar.Side.RIGHT, "")
                .slot(Landmarks.STATUS_POSITION, StatusBar.Side.RIGHT, "")
                .minWidth(Landmarks.STATUS_POSITION, Length.rem(7));
        gui.landmark(Landmarks.STATUS, status.node());
        for (String slot : new String[] {Landmarks.STATUS_FILE, Landmarks.STATUS_MESSAGE, Landmarks.STATUS_LANGUAGE,
                Landmarks.STATUS_POSITION}) {
            gui.landmark(slot, status.slot(slot));
        }

        workspace = new Workspace(gui, motion);
        navigator = new Navigator(gui, motion);

        // In Open the editor steps aside for a picture of the folder being considered: the two share the place
        // beside the navigator, one showing at a time, and the tabs keep everything they had while hidden.
        preview = new Preview(gui);
        Node stage = gui.column().width(Length.FILL).height(Length.FILL).scroll(false, false)
                .alignItems(AlignItems.STRETCH)
                .children(workspace.node(), preview.node());

        split = new SplitPane(gui, SplitPane.Orientation.SIDE_BY_SIDE, navigator.node(), stage)
                .size(NAVIGATOR);
        split.node().width(Length.FILL).height(Length.grow(1f));

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), split.node(), status.node());
    }

    Workspace workspace() {
        return workspace;
    }

    Navigator navigator() {
        return navigator;
    }

    Preview preview() {
        return preview;
    }

    /** Whether Open's picture stands where the editor does. */
    boolean picturing() {
        return picturing;
    }

    /** Put Open's picture where the editor stands, or the editor back. */
    void picturing(boolean value) {
        picturing = value;
        workspace.node().visible(!value);
        preview.node().visible(value);
    }

    /**
     * Show or hide the navigator. Hidden is out of the layout, divider and all, so the editor has the whole window:
     * what a window on files alone starts as ({@code Plain}), and what Ctrl+B does. The split keeps the width it had,
     * so the navigator comes back as it was.
     */
    void showNavigator(boolean show) {
        navigatorShown = show;
        split.alone(show ? null : SplitPane.Pane.SECOND);
    }

    /** Whether the navigator is on screen: the split's own answer, which it does not give, kept here beside it. */
    boolean navigatorShown() {
        return navigatorShown;
    }

    /** The front file's title, dot and all, in the title bar and the status line; empty when nothing is open. */
    void caption(String file) {
        titleBar.title(file.isEmpty() ? TextEditor.TITLE : file + " — " + TextEditor.TITLE);
        status.text(Landmarks.STATUS_FILE, file);
        if (file.isEmpty()) {
            status.text(Landmarks.STATUS_LANGUAGE, "");
            status.text(Landmarks.STATUS_POSITION, "");
        }
    }

    /** The front file's format, on the status line. */
    void language(String name) {
        status.text(Landmarks.STATUS_LANGUAGE, name);
    }

    /** Where the caret is in the front file, on the status line. */
    void position(String where) {
        status.text(Landmarks.STATUS_POSITION, where);
    }

    /** Say something on the status line, and when {@code alert}, draw the eye to it: something was refused or failed. */
    void status(String message, boolean alert) {
        status.text(Landmarks.STATUS_MESSAGE, message);
        if (alert) {
            motion.cues.play(status.node(), Cue.ring(danger, 2));
        }
    }
}
