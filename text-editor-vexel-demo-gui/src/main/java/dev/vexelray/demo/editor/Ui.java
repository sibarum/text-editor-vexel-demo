package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.demo.editor.core.Messages.Offer;
import dev.vexelray.demo.editor.core.Messages.RunThis;
import dev.vexelray.demo.editor.core.NavigateText;
import dev.vexelray.demo.editor.core.Scratches;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.SplitPane;
import dev.vexelray.gui.widget.StatusBar;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.framework.core.WindowCommand;
import sibarum.atchung.Sender;

import java.util.List;

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

    Ui(Gui gui, Motion motion, TitleBar titleBar, Scratches scratches) {
        this.titleBar = titleBar;
        this.motion = motion;
        this.danger = gui.theme().color(Role.DANGER);

        status = new StatusBar(gui)
                .slot(Landmarks.STATUS_FILE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_MESSAGE, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_PROBLEM, StatusBar.Side.LEFT, "")
                .slot(Landmarks.STATUS_LANGUAGE, StatusBar.Side.RIGHT, "")
                .slot(Landmarks.STATUS_POSITION, StatusBar.Side.RIGHT, "")
                .minWidth(Landmarks.STATUS_POSITION, Length.rem(7));
        gui.landmark(Landmarks.STATUS, status.node());
        for (String slot : new String[] {Landmarks.STATUS_FILE, Landmarks.STATUS_MESSAGE, Landmarks.STATUS_PROBLEM,
                Landmarks.STATUS_LANGUAGE,
                Landmarks.STATUS_POSITION}) {
            gui.landmark(slot, status.slot(slot));
        }

        workspace = new Workspace(gui, motion, NavigateText.builtins(scratches));
        navigator = new Navigator(gui, motion, scratches);

        // In Open the editor steps aside for a picture of the folder being considered: the two share the place
        // beside the navigator, one showing at a time, and the tabs keep everything they had while hidden.
        preview = new Preview(gui);
        Node stage = gui.column().width(Length.FILL).height(Length.FILL).scroll(false, false)
                .alignItems(AlignItems.STRETCH)
                .children(workspace.node(), preview.node());

        // The gap between the two cards is the divider: all of it the drag target, a hairline down its middle that
        // fades to the accent under the pointer and holds it for the whole drag.
        split = new SplitPane(gui, SplitPane.Orientation.SIDE_BY_SIDE, navigator.node(), stage)
                .gutter(Type.SEAM)
                .line(Type.SEAM_LINE)
                .motion(motion.change)
                .size(NAVIGATOR);
        split.node().width(Length.FILL).height(Length.FILL);

        // Two cards on the page. The title bar and the status line are the page too, not chrome: the cards are
        // what stand off it.
        titleBar.node().background(gui.theme().color(Role.PAGE));
        // The menus are there from the first frame, in this order; the view declares what is on them.
        titleBar.menu(NEW, "New", Menu.NONE);
        titleBar.menu(VIEW, "View", Menu.NONE);
        titleBar.menu(RUN, "Run", Menu.NONE);
        status.node().background(gui.theme().color(Role.PAGE));
        status.slot(Landmarks.STATUS_LANGUAGE).textColor(gui.theme().color(Role.INK));
        status.slot(Landmarks.STATUS_PROBLEM).textColor(danger);
        Node body = gui.box()
                .width(Length.FILL).height(Length.grow(1f))
                .padding(Length.ZERO, Type.GAP)
                .scroll(false, false)
                .children(split.node());

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), body, status.node());
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

    /** The New menu's name in the title bar: its landmark is {@link Landmarks#NEW_MENU}. */
    static final String NEW = "new";

    /** The New menu, declared again when the folder shown changes: {@link #newMenuOf}. */
    void newMenu(boolean folder, boolean inProject, Sender<Intent> intents) {
        titleBar.menu(NEW, "New", newMenuOf(folder, inProject, intents));
    }

    /**
     * What the New menu holds for the folder shown: a file, then a project, or where a project is around the folder a
     * module of it instead, never both, as a row's menu does. Each names its thing in the path bar, so with no folder
     * shown each is there but off. Under a rule, an index of the project, which is on only inside one. Each line sends
     * the same {@link Intent} its command does.
     */
    static Menu newMenuOf(boolean folder, boolean inProject, Sender<Intent> intents) {
        Menu.Builder menu = Menu.builder()
                .item("File…", folder, intents, new Intent(Intent.Command.NEW_FILE)).command(CommandIds.NEW_FILE);
        if (inProject) {
            menu.item("Submodule…", folder, intents, new Intent(Intent.Command.NEW_MODULE))
                    .command(CommandIds.NEW_MODULE);
        } else {
            menu.item("Project…", folder, intents, new Intent(Intent.Command.NEW_PROJECT))
                    .command(CommandIds.NEW_PROJECT);
        }
        return menu.separator()
                .item("Index this project", folder && inProject, intents, new Intent(Intent.Command.NEW_INDEX))
                .command(CommandIds.NEW_INDEX)
                .build();
    }

    /** The View menu's name in the title bar: its landmark is {@link Landmarks#VIEW_MENU}. */
    static final String VIEW = "view";

    /**
     * The View menu, declared again when what it says changes: word wrap first, a line ticked while documents wrap,
     * then the navigator and the formatted view, then zoom. Each line names its command, so it shows the chord as the
     * person has it bound. Word wrap's line sets the setting to the other way from its tick rather than flipping it,
     * so it does what it said when it was drawn; the rest send the same {@link Intent} as their chord.
     */
    void viewMenu(boolean wrapping, Sender<Intent> intents) {
        titleBar.menu(VIEW, "View", Menu.builder()
                .item("Word wrap", intents,
                        new Intent(wrapping ? Intent.Command.WRAP_OFF : Intent.Command.WRAP_ON))
                .toggle(wrapping).command(CommandIds.WRAP)
                .separator()
                .item("Show or hide the navigator", intents, new Intent(Intent.Command.TOGGLE_NAVIGATOR))
                .command(CommandIds.NAVIGATOR)
                .item("Formatted or source", intents, new Intent(Intent.Command.TOGGLE_FORMATTED))
                .command(CommandIds.FORMATTED)
                .separator()
                .item("Zoom in", intents, new Intent(Intent.Command.ZOOM_IN)).command(CommandIds.ZOOM_IN)
                .item("Zoom out", intents, new Intent(Intent.Command.ZOOM_OUT)).command(CommandIds.ZOOM_OUT)
                .item("Reset zoom", intents, new Intent(Intent.Command.ZOOM_RESET)).command(CommandIds.ZOOM_RESET)
                .build());
    }

    /** The Run menu's name in the title bar: its landmark is {@link Landmarks#RUN_MENU}. */
    static final String RUN = "run";

    /**
     * The Run menu, declared again: Run… first, which brings up its window, then each offer a line that sends it to be
     * run, a rule before each group, in the order found.
     */
    void runMenu(List<Offer> offers, Sender<RunThis> run, Sender<WindowCommand> windows) {
        Menu.Builder menu = Menu.builder().item("Run…", windows, WindowCommand.show(RunDialog.KEY));
        int group = -1;
        for (Offer o : offers) {
            if (o.group() != group) {
                menu.separator();
                group = o.group();
            }
            menu.item(o.label(), run, new RunThis(o));
        }
        titleBar.menu(RUN, "Run", menu.build());
    }

    /** Drop the Run menu, or put it away: F5. */
    void openRunMenu() {
        titleBar.open(RUN);
    }

    /** The front file's title, dot and all, in the title bar and the status line; empty when nothing is open. */
    void caption(String file) {
        titleBar.title(file.isEmpty() ? TextEditor.TITLE : file + " — " + TextEditor.TITLE);
        status.text(Landmarks.STATUS_FILE, file);
        if (file.isEmpty()) {
            status.text(Landmarks.STATUS_LANGUAGE, "");
            status.text(Landmarks.STATUS_POSITION, "");
            status.text(Landmarks.STATUS_PROBLEM, "");
        }
    }

    /** The front file's format, on the status line. */
    void language(String name) {
        status.text(Landmarks.STATUS_LANGUAGE, name);
    }

    /** What is wrong with the front file, on the status line in the danger colour; empty for nothing. */
    void problem(String message) {
        status.text(Landmarks.STATUS_PROBLEM, message);
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
