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

import java.nio.file.Path;

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
    /** What the window is open on, beside the application's name in the title bar. */
    private final Node project;

    private volatile boolean navigatorShown = true;
    private volatile float navigatorDp;

    Ui(Gui gui, Motion motion, Model model, TitleBar titleBar) {
        this.titleBar = titleBar;
        this.motion = motion;
        this.danger = gui.theme().color(Role.DANGER);

        // The bar's caption is the application's name, and what it is open on stands beside it after a rule. The
        // caption's own ink is the bar's (DIM, at the bar's size), so the design's bright name is not reachable.
        titleBar.title(TextEditor.TITLE);
        project = gui.text("")
                .font(Type.UI)
                .textSize(Type.LABEL)
                .textColor(gui.theme().color(Role.DIM))
                .wordWrap(false);
        titleBar.addLeading(gui.box().width(Type.RULE).height(Length.dp(14)).background(gui.theme().color(Look.RIM)))
                .addLeading(project);
        // The design's bar is the page, not chrome: the cards are what stand off it.
        titleBar.node().background(gui.theme().color(Role.PAGE));

        status = new StatusBar(gui)
                .slot(Landmarks.STATUS_MESSAGE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_SCOPE, StatusBar.Side.RIGHT, "")
                .slot(Landmarks.STATUS_LANGUAGE, StatusBar.Side.RIGHT, "")
                .slot(Landmarks.STATUS_POSITION, StatusBar.Side.RIGHT, "Ln 1, Col 1")
                .minWidth(Landmarks.STATUS_POSITION, Length.rem(7));
        status.slot(Landmarks.STATUS_LANGUAGE).textColor(gui.theme().color(Role.INK));
        gui.landmark(Landmarks.STATUS, status.node());
        for (String slot : new String[] {Landmarks.STATUS_MESSAGE, Landmarks.STATUS_SCOPE, Landmarks.STATUS_LANGUAGE,
                Landmarks.STATUS_POSITION}) {
            gui.landmark(slot, status.slot(slot));
        }

        navigator = new Navigator(gui, motion);
        workspace = new Workspace(gui, motion, model,
                p -> {
                    status.text(Landmarks.STATUS_SCOPE, p.scope());
                    status.text(Landmarks.STATUS_POSITION, "Ln " + p.line() + ", Col " + p.column());
                },
                navigator::reveal);

        split = new SplitPane(gui, SplitPane.Orientation.SIDE_BY_SIDE, navigator.node(), workspace.node())
                .minFirst(Length.ZERO)
                // The gap between the two cards is the divider: all of it is the target, a hairline is painted
                // down its middle, and the line fades to the accent on hover and holds it for the whole drag.
                .gutter(Type.GAP)
                .line(Length.dp(2))
                .motion(motion.change)
                .size(NAVIGATOR);
        split.node().width(Length.FILL).height(Length.FILL);
        navigatorDp = split.sizeDp();
        split.onResize(dp -> {
            if (dp > 1f) {
                navigatorDp = dp;
            }
        });

        // Two cards on the page, with the divider as the gap between them.
        Node body = gui.box()
                .width(Length.FILL).height(Length.grow(1f))
                .padding(Length.ZERO, Type.TIGHT)
                .scroll(false, false)
                .children(split.node());

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body, status.node());
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
        Path folder = doc.folder();
        Path name = folder == null ? null : folder.getFileName();
        project.text(folder != null ? String.valueOf(name == null ? folder : name)
                : front == null ? "" : front.title());
        status.text(Landmarks.STATUS_MESSAGE, doc.status());
        status.text(Landmarks.STATUS_LANGUAGE, front == null ? "" : front.language());
        workspace.retitle(doc);
    }
}
