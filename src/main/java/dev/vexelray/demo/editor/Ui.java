package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.SplitPane;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.Tooltip;

import java.nio.file.Path;
import java.util.function.Consumer;

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
    /** The hairline painted down the middle of the divider, the gap between the two cards. */
    private static final Length DIVIDER_LINE = Length.dp(2);

    private final TitleBar titleBar;
    private final StatusBar status;
    private final Workspace workspace;
    private final Navigator navigator;
    private final Preview preview;
    private final SplitPane split;
    private final Motion motion;
    private final Color danger;
    private final Scratches scratches;
    /** What the window is open on, beside the application's name in the title bar. */
    private final Node project;

    private volatile Consumer<ClickEvent> preferences;
    private volatile boolean navigatorShown = true;
    /** A window on files alone: the navigator and its divider gone rather than dragged shut. See {@link #plain()}. */
    private volatile boolean plain;
    private volatile float navigatorDp;

    Ui(Gui gui, Motion motion, Model model, TitleBar titleBar, Scratches scratches) {
        this.titleBar = titleBar;
        this.motion = motion;
        this.danger = gui.theme().color(Role.DANGER);
        this.scratches = scratches;

        // The bar's caption is the application's name, and what it is open on stands beside it after a rule. The
        // caption's own ink is the bar's (DIM, at the bar's size), so the design's bright name is not reachable.
        titleBar.title(TextEditor.TITLE);
        project = gui.text("")
                .font(Type.UI)
                .textSize(Type.LABEL)
                .textColor(gui.theme().color(Role.DIM))
                .wordWrap(false);
        titleBar.addLeading(gui.box().width(Type.RULE).height(Length.dp(14)).background(gui.theme().color(Look.RIM)))
                .addLeading(project)
                .addLeading(preferencesButton(gui));
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

        navigator = new Navigator(gui, motion, scratches);
        workspace = new Workspace(gui, motion, model, p -> {
            status.text(Landmarks.STATUS_SCOPE, p.scope());
            status.text(Landmarks.STATUS_POSITION, "Ln " + p.line() + ", Col " + p.column());
        });

        // In Open the editor steps aside for a picture of the folder being considered: the two share the place
        // beside the navigator, one showing at a time, and the tabs keep everything they had while hidden.
        preview = new Preview(gui);
        Node stage = gui.column().width(Length.FILL).height(Length.FILL).scroll(false, false)
                .alignItems(AlignItems.STRETCH)
                .children(workspace.node(), preview.node());
        navigator.onPreview(preview::show);
        navigator.onMode(m -> {
            boolean open = m == Navigator.Mode.OPEN;
            if (open) {
                preview.forget();
            }
            workspace.node().visible(!open);
            preview.node().visible(open);
        });

        split = new SplitPane(gui, SplitPane.Orientation.SIDE_BY_SIDE, navigator.node(), stage)
                .minFirst(Length.ZERO)
                // The gap between the two cards is the divider: all of it is the target, a hairline is painted
                // down its middle, and the line fades to the accent on hover and holds it for the whole drag.
                .gutter(Type.GAP)
                .line(DIVIDER_LINE)
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

    /**
     * The title bar's preferences button, as the console's settings button is drawn. What it opens is
     * {@link #onPreferences}'s: the menu is the actions', which know the root and what changing each one does.
     */
    private Node preferencesButton(Gui gui) {
        Node b = gui.row().role("preferences").width(Length.dp(36)).height(Length.dp(32))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.NONE)).scroll(false, false)
                .windowRegion(WindowRegion.INTERACTIVE)
                .children(IconView.of(gui, Icon.SETTINGS).size(Length.dp(16)).tint(Role.DIM).node());
        gui.onClick(b, (ClickEvent e) -> {
            Consumer<ClickEvent> open = preferences;
            if (open != null) {
                open.accept(e);
            }
        });
        gui.onState(b, state -> b.background(
                gui.theme().color(state == InteractionState.NORMAL ? Role.NONE : Role.RAISED)));
        new Tooltip(gui).attach(b, "Preferences");
        gui.landmark(Landmarks.PREFERENCES, b);
        return b;
    }

    /** What the preferences button does when clicked: present the menu at the click. */
    void onPreferences(Consumer<ClickEvent> open) {
        preferences = open;
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

    Preview preview() {
        return preview;
    }

    /**
     * Show or hide the navigator. Hidden is the split dragged shut, so the width it had comes back with it. Either way
     * the window stops being {@link #plain()}, which is how a folder arriving brings the navigator back.
     */
    synchronized void showNavigator(boolean show) {
        if (show == navigatorShown && !plain) {
            return;
        }
        if (plain) {
            plain = false;
            navigator.node().visible(true);
            split.gutter(Type.GAP).line(DIVIDER_LINE);
        }
        navigatorShown = show;
        split.size(show ? Length.dp(Math.max(navigatorDp, 120f)) : Length.ZERO);
    }

    synchronized void toggleNavigator() {
        showNavigator(!navigatorShown());
    }

    /**
     * A plain window: the tabs and nothing beside them, for a window opened on files alone ({@code vex notes.md}).
     * Not the split dragged shut, which leaves the gap between the cards and a divider to drag open again, but the
     * navigator out of the layout and the divider with no width, so the editor has the whole of the window.
     *
     * <p>The navigator's width is not touched, since only the end of a drag records one, so it comes back at the width
     * it had when {@link #showNavigator} ends this: a folder opened by any route, Ctrl+B, or the scratches.
     */
    synchronized void plain() {
        plain = true;
        navigatorShown = false;
        navigator.node().visible(false);
        split.size(Length.ZERO).gutter(Length.ZERO).line(Length.ZERO);
    }

    /** Whether the navigator is on screen: shown, and the window not {@link #plain()}. */
    boolean navigatorShown() {
        return navigatorShown && !plain;
    }

    /** Whether the window is {@link #plain()}, the navigator out of the layout altogether. */
    boolean isPlain() {
        return plain;
    }

    /** Write everything derived from the session. One method taking the whole value, so no two fields can disagree. */
    void show(Doc doc) {
        Doc.Entry front = doc.front();
        Path folder = doc.folder();
        Path name = folder == null ? null : folder.getFileName();
        String root = folder == null ? null
                : scratches.is(folder) ? Scratches.NAME : String.valueOf(name == null ? folder : name);
        project.text(root != null ? root
                : front == null ? "" : front.title());
        status.text(Landmarks.STATUS_MESSAGE, doc.status());
        status.text(Landmarks.STATUS_LANGUAGE, front == null ? "" : front.language());
        navigator.recent(doc.recent(), folder);
        workspace.retitle(doc);
    }
}
