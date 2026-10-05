package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.SplitPane;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The window: the title bar, the navigator beside the tabs, and a status line under both.
 *
 * <p>Holds the handles and nothing else. {@link #show} takes a whole {@link Doc} and writes everything derived
 * from it — the window title, the tab headers, the status line — so a label cannot disagree with the session.
 * It is called on the committing thread, a worker, which is the framework's idiom: a prop written off the GUI
 * thread is applied by the next drain.
 */
final class Ui {

    /** The navigator's width on a first run. */
    private static final Length NAVIGATOR = Length.rem(16);

    private final TitleBar titleBar;
    private final StatusBar status;
    private final Workspace workspace;
    private final Navigator navigator;
    private final SplitPane split;
    private final Motion motion;
    private final Color danger;

    private volatile boolean navigatorShown = true;
    private volatile float navigatorDp;

    Ui(Gui gui, Motion motion, Model model, TitleBar titleBar) {
        this.titleBar = titleBar;
        this.motion = motion;
        this.danger = gui.theme().color(Role.DANGER);

        status = new StatusBar(gui)
                .slot(Landmarks.STATUS_MESSAGE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_LANGUAGE, StatusBar.Side.RIGHT, "")
                .slot(Landmarks.STATUS_POSITION, StatusBar.Side.RIGHT, "Ln 1, Col 1")
                .minWidth(Landmarks.STATUS_POSITION, Length.rem(7));
        gui.landmark(Landmarks.STATUS, status.node());
        for (String slot : new String[] {Landmarks.STATUS_MESSAGE, Landmarks.STATUS_LANGUAGE, Landmarks.STATUS_POSITION}) {
            gui.landmark(slot, status.slot(slot));
        }

        workspace = new Workspace(gui, motion, model,
                p -> status.text(Landmarks.STATUS_POSITION, "Ln " + p.line() + ", Col " + p.column()));
        navigator = new Navigator(gui, motion);

        split = new SplitPane(gui, SplitPane.Orientation.SIDE_BY_SIDE, navigator.node(), workspace.node())
                .minFirst(Length.ZERO)
                .size(NAVIGATOR);
        split.node().width(Length.FILL).height(Length.grow(1f));
        navigatorDp = split.sizeDp();
        split.onResize(dp -> {
            if (dp > 1f) {
                navigatorDp = dp;
            }
        });

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), split.node(), status.node());
    }

    /** Draw the eye to the status line: something was refused or failed, and the message there says what. */
    void alert() {
        motion.cues.play(status.node(), Cue.ring(danger, 2));
    }

    Workspace workspace() {
        return workspace;
    }

    Navigator navigator() {
        return navigator;
    }

    /** Show or hide the navigator. Hidden is the split dragged shut, so the width it had comes back with it. */
    void showNavigator(boolean show) {
        if (show == navigatorShown) {
            return;
        }
        navigatorShown = show;
        split.size(show ? Length.dp(Math.max(navigatorDp, 120f)) : Length.ZERO);
    }

    void toggleNavigator() {
        showNavigator(!navigatorShown);
    }

    /** Write everything derived from the session. One method taking the whole value, so no two fields can disagree. */
    void show(Doc doc) {
        Doc.Entry front = doc.front();
        titleBar.title(front == null ? TextEditor.TITLE : front.title() + " — " + TextEditor.TITLE);
        status.text(Landmarks.STATUS_MESSAGE, doc.status());
        status.text(Landmarks.STATUS_LANGUAGE, front == null ? "" : front.language());
        workspace.retitle(doc);
    }
}
