package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.ListView;

import java.nio.file.Path;
import java.util.List;

/**
 * The Welcome window: what the IDE opens on when it is started with nothing named, while the main window stays hidden
 * ({@code @VexelApp(startsHidden)}). It lists the folders worked in lately, the last session's first, each its name
 * over where it is, then the git repositories found in the home folder as they are found ({@code ScanRepos}); Enter
 * or a double-click opens one in the main window. Closing it with nothing chosen quits.
 *
 * <p>A {@code ListView}, which gives the arrows, Enter and a double-click with nothing written for them, as MainFrame's
 * Open window does. It holds no state of its own: the rows are the editor's {@code Welcome}, put in as they come. The
 * {@link WelcomeView}'s, on the GUI thread.
 *
 * <p>A window of its own rather than a satellite of the main one, with its own taskbar button: the main window is not on
 * screen to own it.
 */
final class Welcome {

    /** The window's key, which the editor names too ({@link Messages#WELCOME}). */
    static final String KEY = Messages.WELCOME;

    static final int W = 560;
    static final int H = 640;

    /** How many rows of Recent show before it scrolls, so Repositories keeps the rest of the window. */
    static final int RECENT_ROWS = 4;
    static final float ROW_REM = 3.25f;

    private final Gui gui;
    private final ListView<Path> list;
    private final ListView<Path> repos;
    private final Node note;

    Welcome(Window window) {
        this.gui = window.gui();
        list = folders(Landmarks.WELCOME_RECENT);
        list.node().width(Length.FILL).height(Length.rem(RECENT_ROWS * ROW_REM));
        repos = folders(Landmarks.WELCOME_REPOS);
        repos.node().width(Length.FILL).height(Length.grow(1f));
        note = gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(Role.DIM)
                .padding(Length.dp(4), Length.ZERO);
        gui.landmark(Landmarks.WELCOME_NOTE, note);

        Node card = gui.column().width(Length.FILL).height(Length.grow(1f))
                .padding(Type.EDGE)
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .background(Look.CARD)
                .corner(Type.CORNER)
                .border(Type.RULE, Look.RIM)
                .children(label("Recent"), list.node(),
                        label("Git repositories in your home folder"), repos.node(), note,
                        label("Enter or a double-click opens a folder."));

        window.titleBar().node().background(Role.PAGE);
        gui.root().direction(Direction.COLUMN)
                .background(Role.PAGE)
                .children(window.titleBar().node(),
                        gui.box().width(Length.FILL).height(Length.grow(1f)).padding(Length.ZERO, Type.GAP)
                                .scroll(false, false).children(card),
                        gui.box().width(Length.FILL).height(Type.GAP));
    }

    /** A list of folders, each its name over where it is, under {@code landmark}. */
    private ListView<Path> folders(String landmark) {
        ListView<Path> l = new ListView<Path>(gui, ROW_REM, (nodes, folder) -> nodes.column().children(
                nodes.text(name(folder)).font(Type.UI).textColor(Role.INK),
                nodes.text(folder.toString()).font(Type.UI).textSize(Type.SMALL).textColor(Role.DIM)))
                .name(landmark);
        gui.landmark(landmark, l.node());
        return l;
    }

    private Node label(String text) {
        return gui.text(text).font(Type.UI).textSize(Type.SMALL).textColor(Role.DIM)
                .padding(Length.dp(4), Length.ZERO);
    }

    /** A folder's own name, or the whole path for a root, which has none. */
    private static String name(Path folder) {
        Path name = folder.getFileName();
        return name == null ? folder.toString() : name.toString();
    }

    /** The folders to offer, the first selected, and the keyboard in the list. */
    void show(List<Path> recent) {
        list.items(recent);
        // As tall as its rows, up to RECENT_ROWS, so Repositories has the rest.
        list.node().height(Length.rem(Math.max(1, Math.min(recent.size(), RECENT_ROWS)) * ROW_REM));
        if (!recent.isEmpty()) {
            // Selected, so Enter alone opens the last session's folder and Down moves to the one after it.
            list.selection().at(recent.getFirst());
            list.reveal(recent.getFirst());
        }
        gui.focus(list.node());
    }

    /** The repositories found so far, and how the looking goes. What is selected stays selected. */
    void repos(List<Path> found, String says) {
        repos.items(found);
        note.text(says);
    }

    /** Whether {@code address} is one of Welcome's lists, whose rows open. */
    boolean opens(String address) {
        return address.equals(list.address()) || address.equals(repos.address());
    }

    /** The lists' addresses: for a test. */
    String recentAddress() {
        return list.address();
    }

    String reposAddress() {
        return repos.address();
    }

    /** This window's tree: for a test. */
    Gui gui() {
        return gui;
    }
}
