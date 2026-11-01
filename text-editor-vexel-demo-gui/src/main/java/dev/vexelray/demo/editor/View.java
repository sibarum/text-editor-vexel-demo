package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.Intent.Command;
import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.AddTab;
import dev.vexelray.demo.editor.core.Messages.Caption;
import dev.vexelray.demo.editor.core.Messages.Close;
import dev.vexelray.demo.editor.core.Messages.CloseTabs;
import dev.vexelray.demo.editor.core.Messages.Edited;
import dev.vexelray.demo.editor.core.Messages.Flush;
import dev.vexelray.demo.editor.core.Messages.FrontTab;
import dev.vexelray.demo.editor.core.Messages.Fronted;
import dev.vexelray.demo.editor.core.Messages.Format;
import dev.vexelray.demo.editor.core.Messages.Formatted;
import dev.vexelray.demo.editor.core.Messages.Go;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Listing;
import dev.vexelray.demo.editor.core.Messages.Mark;
import dev.vexelray.demo.editor.core.Messages.Marked;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.Picture;
import dev.vexelray.demo.editor.core.Messages.Pictured;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Recent;
import dev.vexelray.demo.editor.core.Messages.Retitle;
import dev.vexelray.demo.editor.core.Messages.Save;
import dev.vexelray.demo.editor.core.Messages.SaveFailed;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.NodeEvent;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.widget.Breadcrumb;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TreeView;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The view: what the editor decided, shown, and what the person did, told. A {@code @View}, so it runs on the GUI
 * thread, in each frame's widget tick (threading.md, T7.4).
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Every decision is the {@code Editor}'s, in {@code -core}: whether a file is already open, what a typed path names,
 * whether closing needs a question. This turns the editor's messages into widgets ({@code AddTab} is a tab) and the
 * person's gestures into messages ({@code Open}, {@code Close}, {@code Save}). A question is not the view's: the editor
 * sends the framework an {@code Ask}, and the framework shows it.
 *
 * <h2>Everything it hears is a message, in the frame</h2>
 *
 * <p>Every {@code @Subscribe} here is drained on the GUI thread, after input is dispatched and before layout: what the
 * editor and the jobs send, and what the widgets say they did (a tab selected, a tree's row clicked or its menu chosen,
 * a field's text changed). So a click is heard, and what the view writes about it drawn, in the frame the click
 * arrived, and the widgets, the {@link Workspace} and the {@link Navigator} are only ever touched here, with no lock.
 * A widget's messages carry its address, and every widget of a kind shares a topic, so each handler first asks which
 * of the view's widgets a message is about.
 *
 * <h2>Gestures are declared, not handled</h2>
 *
 * <p>A chord, a menu item and a dialog's button each name the message they send ({@link Intent}, a
 * {@code Messages} record) through a {@link Sender}; the dispatcher sends it, and nothing of the application's runs at
 * the moment of the click (T7.5). What needs the view comes back to it as one of these handlers.
 *
 * <h2>What it sends is in its constructor</h2>
 *
 * <p>One {@link Sender} for each message it sends, and nothing else to send with: the wiring finds each one's topic by
 * its payload, beside the record in {@code Messages}, so the processor sees every message the view can send without
 * reading a line of its body (ROADMAP fork 5).
 *
 * <h2>Slow work is a job</h2>
 *
 * <p>Reading a folder for the tree and colouring a document are the jobs' ({@code ListFolder},
 * {@code MarkText}): the view asks and acts on the answer, because its time is the frame's.
 */
@dev.vexelray.framework.api.View
final class View {

    private final Gui gui;
    private final Ui ui;
    private final Workspace ws;
    private final Navigator nav;

    private final Sender<Open> open;
    private final Sender<Go> go;
    private final Sender<Fronted> fronted;
    private final Sender<Edited> edited;
    private final Sender<Close> close;
    private final Sender<Save> save;
    private final Sender<Listing> listing;
    private final Sender<Mark> mark;
    private final Sender<Format> format;
    private final Sender<TreeView.Children> children;
    private final Sender<Intent> intents;
    private final Sender<Picture> picture;

    /** The tab in front, as the editor was last told. */
    private long front = Messages.NO_TAB;
    /** The folder under the pointer in Open: a recent one, a row of its tree, or null for neither. */
    private Path recentHover;
    private Path treeHover;

    View(Gui gui, Ui ui, Sender<Open> open, Sender<Go> go, Sender<Fronted> fronted,
         Sender<Edited> edited, Sender<Close> close, Sender<Save> save,
         Sender<Listing> listing, Sender<Mark> mark, Sender<Format> format, Sender<TreeView.Children> children,
         Sender<Intent> intents, Sender<Picture> picture) {
        this.gui = gui;
        this.ui = ui;
        this.ws = ui.workspace();
        this.nav = ui.navigator();
        this.picture = picture;
        this.open = open;
        this.go = go;
        this.fronted = fronted;
        this.edited = edited;
        this.close = close;
        this.save = save;
        this.listing = listing;
        this.mark = mark;
        this.format = format;
        this.children = children;
        this.intents = intents;
        shortcuts();
        for (int i = 0; i < Picker.RECENT; i++) {
            int slot = i;
            gui.hear(nav.picker().recentCell(i), NodeEvent.STATE, state -> recentHovered(slot, state));
        }
    }

    /**
     * Every chord, as a declaration: the chord and the {@link Intent} it sends. Chords a text field leaves alone, so a
     * focused field never needs to outrank them. Zoom is here too: which chord zooms, or whether zooming exists at all,
     * is the application's to say; how far it goes is {@code Appearance.ZoomRange}, the framework's.
     */
    private void shortcuts() {
        chord(Shortcut.of(Key.S, Modifier.CONTROL), Command.SAVE_FRONT);
        chord(Shortcut.of(Key.W, Modifier.CONTROL), Command.CLOSE_FRONT);
        chord(Shortcut.of(Key.L, Modifier.CONTROL), Command.FOCUS_PATH);
        chord(Shortcut.of(Key.E, Modifier.CONTROL, Modifier.SHIFT), Command.FOCUS_TREE);
        chord(Shortcut.of(Key.V, Modifier.CONTROL, Modifier.SHIFT), Command.TOGGLE_FORMATTED);
        chord(Shortcut.of(Key.B, Modifier.CONTROL), Command.TOGGLE_NAVIGATOR);
        chord(Shortcut.of(Key.O, Modifier.CONTROL), Command.TOGGLE_OPEN);
        chord(Shortcut.of(Key.TAB, Modifier.CONTROL), Command.NEXT_TAB);
        chord(Shortcut.of(Key.TAB, Modifier.CONTROL, Modifier.SHIFT), Command.PREVIOUS_TAB);
        chord(Shortcut.of(Key.PAGE_DOWN, Modifier.CONTROL), Command.NEXT_TAB);
        chord(Shortcut.of(Key.PAGE_UP, Modifier.CONTROL), Command.PREVIOUS_TAB);
        chord(Shortcut.of(Key.EQUAL, Modifier.CONTROL), Command.ZOOM_IN);
        chord(Shortcut.of(Key.MINUS, Modifier.CONTROL), Command.ZOOM_OUT);
        chord(Shortcut.of(Key.DIGIT_0, Modifier.CONTROL), Command.ZOOM_RESET);
        chord(Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL), Command.ZOOM_IN);
        chord(Shortcut.of(Key.NUMPAD_SUBTRACT, Modifier.CONTROL), Command.ZOOM_OUT);
        chord(Shortcut.of(Key.NUMPAD_0, Modifier.CONTROL), Command.ZOOM_RESET);
    }

    private void chord(Shortcut shortcut, Command command) {
        gui.shortcut(shortcut, intents, new Intent(command));
    }

    @Subscribe
    public void intent(Intent intent) {
        intent.command().on(this);
    }

    // ------------------------------------------------------------------ what the editor says

    /** A folder to show: the navigator shows it, and comes back if it was hidden, which is how a plain window ends. */
    @Subscribe
    public void showFolder(ShowFolder m) {
        nav.show(m.path());
        ui.showNavigator(true);
        picture();
    }

    /** A window on files alone: the navigator out of the way until a folder is shown or Ctrl+B asks for it. */
    @Subscribe
    public void plain(Plain m) {
        ui.showNavigator(false);
    }

    /** The folders shown before, for Open's Recent. */
    @Subscribe
    public void recent(Recent m) {
        nav.picker().recent(m.folders());
    }

    @Subscribe
    public void addTab(AddTab m) {
        ws.add(m.id(), m.path(), m.text(), m.focus());
    }

    @Subscribe
    public void frontTab(FrontTab m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            ws.show(b, m.focus());
        }
    }

    @Subscribe
    public void closeTabs(CloseTabs m) {
        for (long id : m.tabs()) {
            ws.close(id);
        }
    }

    /** The last title per tab in the frame: a state topic, so a burst of them is never a backlog. */
    @Subscribe
    public void retitle(Retitle m) {
        ws.retitle(m.id(), m.title());
    }

    @Subscribe
    public void caption(Caption m) {
        ui.caption(m.file());
    }

    @Subscribe
    public void status(Status m) {
        ui.status(m.message(), m.alert());
    }

    @Subscribe
    public void flush(Flush m) {
        for (long id : m.tabs()) {
            Buffer b = ws.byId(id);
            if (b == null) {
                save.send(new Save(id, "", false));
            } else {
                save(b);
            }
        }
    }

    @Subscribe
    public void saveFailed(SaveFailed m) {
        Buffer b = ws.byId(m.id());
        if (b != null) {
            b.saveFailed();
            reportDirty(b);
        }
    }

    // ------------------------------------------------------------------ what the jobs answer

    @Subscribe
    public void listed(Listed m) {
        if (m.tree().equals(nav.treeAddress())) {
            children.send(nav.children(m));
        } else if (m.tree().equals(nav.picker().treeAddress())) {
            children.send(nav.picker().children(m));
        }
    }

    /** A folder's picture: drawn if it is still the one wanted, and the next asked for if the pointer has moved on. */
    @Subscribe
    public void pictured(Pictured m) {
        ask(ui.preview().landed(m));
    }

    @Subscribe
    public void marked(Marked m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            b.marked(m);
        }
    }

    @Subscribe
    public void formatted(Formatted m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            b.formatted(m);
        }
    }

    // ------------------------------------------------------------------ what the widgets say

    /**
     * The bar's selection moved: the editor hears which file is in front, and the status line its format and where
     * its caret is.
     */
    @Subscribe
    public void tabSelected(Tabs.Selected m) {
        Buffer b = m.tabs().equals(ws.address()) ? ws.at(m.index()) : null;
        if (b != null && b.id != front) {
            front = b.id;
            ui.language(b.language());
            ui.position(b.position());
            fronted.send(new Fronted(b.id));
        }
    }

    /** One of the tab menu's actions. */
    @Subscribe
    public void tabChosen(Tabs.Chosen m) {
        Buffer b = m.at().equals(ws.address()) && m.item() instanceof Integer i ? ws.at(i) : null;
        if (b == null) {
            return;
        }
        if (m.action().equals(Workspace.SAVE)) {
            save(b);
        } else if (m.action().equals(Workspace.CLOSE)) {
            close(List.of(b));
        } else if (m.action().equals(Workspace.CLOSE_OTHERS)) {
            List<Buffer> rest = new ArrayList<>(ws.all());
            rest.remove(b);
            close(rest);
        } else if (m.action().equals(Workspace.CLOSE_ALL)) {
            close(ws.all());
        } else if (m.action().equals(Workspace.REVEAL)) {
            if (nav.reveal(b.path)) {
                ui.showNavigator(true);
            } else {
                ui.status(b.name() + " is not inside the folder shown", false);
            }
        } else if (m.action().equals(Workspace.FORMAT)) {
            toggleFormatted(b);
        }
    }

    /** A document's text changed: whether it is now unsaved, and its colours. */
    @Subscribe
    public void fieldChanged(TextField.Changed m) {
        Buffer b = ws.byField(m.field());
        if (b == null) {
            return;
        }
        reportDirty(b);
        Mark wanted = b.changed(m.text(), m.version());
        if (wanted != null) {
            mark.send(wanted);
        }
        Format again = b.reformat(m.text());
        if (again != null) {
            format.send(again);
        }
    }

    @Subscribe
    public void fieldCaret(TextField.Caret m) {
        Buffer b = ws.byField(m.field());
        if (b != null) {
            b.caret(m.line(), m.column());
            if (b.id == front) {
                ui.position(b.position());
            }
        }
    }

    /** The path bar was submitted: what the text names is the editor's call. */
    @Subscribe
    public void fieldSubmitted(TextField.Submitted m) {
        if (m.field().equals(nav.pathAddress())) {
            go.send(new Go(nav.submitted(m.text())));
        }
    }

    /** The tree wants a level: ask {@code ListFolder}, or answer an empty top when no folder is shown yet. */
    @Subscribe
    public void treeWanted(TreeView.ChildrenWanted m) {
        if (m.tree().equals(nav.picker().treeAddress())) {
            // Open's top is the one folder it starts at, known already; the levels under it are a listing.
            Listing wanted = nav.picker().wanted(m.parent());
            if (wanted != null) {
                listing.send(wanted);
            } else {
                children.send(nav.picker().top());
            }
            return;
        }
        if (!m.tree().equals(nav.treeAddress())) {
            return;
        }
        Listing wanted = nav.wanted(m.parent());
        if (wanted != null) {
            listing.send(wanted);
        } else {
            children.send(nav.nothing());
        }
    }

    /** A file selected in the tree opens, and the keyboard stays in the tree, so walking it keeps walking. */
    @Subscribe
    public void treeSelected(TreeView.Selected m) {
        if (m.tree().equals(nav.treeAddress()) && m.item() instanceof Path p && !nav.isFolder(p)) {
            open.send(new Open(p, false));
        } else if (m.tree().equals(nav.picker().treeAddress())) {
            picture();
        }
    }

    /** The row under the pointer in Open's tree, or none: pictured. */
    @Subscribe
    public void treeHovered(TreeView.Hovered m) {
        if (m.tree().equals(nav.picker().treeAddress())) {
            treeHover = m.item() instanceof Path p ? p : null;
            picture();
        }
    }

    /** Enter on a folder opens it in place; in Open, it picks it. */
    @Subscribe
    public void treeActivated(TreeView.Activated m) {
        if (m.tree().equals(nav.treeAddress()) && m.item() instanceof Path p && nav.isFolder(p)) {
            nav.expand(p);
        } else if (m.tree().equals(nav.picker().treeAddress()) && m.item() instanceof Path p) {
            pick(p);
        }
    }

    /** A click on a folder in Open picks it. In the files a click is a selection, heard as one. */
    @Subscribe
    public void treeClicked(TreeView.Clicked m) {
        if (m.tree().equals(nav.picker().treeAddress()) && m.item() instanceof Path p) {
            pick(p);
        }
    }

    /** The chip pressed or released: Open, or the files. */
    @Subscribe
    public void chipToggled(Button.Toggled m) {
        if (m.button().equals(nav.chipAddress())) {
            open(m.pressed());
        }
    }

    /** A recent folder pressed in Open: picked. */
    @Subscribe
    public void buttonPressed(Button.Pressed m) {
        Path p = nav.picker().recentAt(m.button());
        if (p != null) {
            pick(p);
        }
    }

    /** One of Open's way up: its tree starts there instead. */
    @Subscribe
    public void ancestry(Breadcrumb.Navigated m) {
        if (m.breadcrumb().equals(nav.picker().ancestryAddress()) && m.item() instanceof Path p) {
            nav.widen(p);
            treeHover = null;   // a row of the tree just replaced
            claimEscape();
            picture();
        }
    }

    /** One of a row's menu actions. */
    @Subscribe
    public void treeChosen(TreeView.Chosen m) {
        if (m.at().equals(nav.picker().treeAddress()) && m.item() instanceof Path p) {
            if (m.action().equals(Navigator.SHOW)) {
                pick(p);
            } else if (m.action().equals(Navigator.COPY_PATH)) {
                gui.clipboard().set(p.toString());
            }
            return;
        }
        if (!m.at().equals(nav.treeAddress()) || !(m.item() instanceof Path p)) {
            return;
        }
        if (m.action().equals(Navigator.OPEN)) {
            open.send(new Open(p, true));
        } else if (m.action().equals(Navigator.SHOW)) {
            go.send(new Go(p.toString()));
        } else if (m.action().equals(Navigator.COPY_PATH)) {
            gui.clipboard().set(p.toString());
        } else if (m.action().equals(Navigator.NEW_FILE) || m.action().equals(Navigator.NEW_FOLDER)) {
            // A file's row names something beside it, in the folder it is in.
            Path in = nav.isFolder(p) ? p : p.getParent();
            boolean folder = m.action().equals(Navigator.NEW_FOLDER);
            nav.startNew(in, folder);
            ui.status(folder ? "Name the new folder, then Enter" : "Name the new file, then Enter", false);
        }
    }

    @Subscribe
    public void treeRevealed(TreeView.Revealed m) {
        if (m.tree().equals(nav.treeAddress())) {
            nav.revealed(m.item(), m.reached());
        }
    }

    // ------------------------------------------------------------------ what the person did, by chord

    void saveFront() {
        Buffer b = ws.front();
        if (b != null) {
            save(b);
        }
    }

    /** The text, taken now and marked saved now: what is written is what the document said when Save was asked for. */
    private void save(Buffer b) {
        save.send(new Save(b.id, b.takeForSave(), true));
        reportDirty(b);
    }

    void closeFront() {
        Buffer b = ws.front();
        if (b != null) {
            close(List.of(b));
        }
    }

    /** Ask the editor to close these tabs, in one message: whether any is unsaved is the editor's to know. */
    private void close(List<Buffer> buffers) {
        List<Long> ids = new ArrayList<>(buffers.size());
        for (Buffer b : buffers) {
            ids.add(b.id);
        }
        close.send(new Close(ids));
    }

    void toggleFormatted() {
        Buffer b = ws.front();
        if (b != null) {
            toggleFormatted(b);
        }
    }

    /** Show {@code b} formatted, or as its source, and say which on the status line. */
    private void toggleFormatted(Buffer b) {
        if (!b.formattable()) {
            ui.status("Only Markdown has a formatted view", false);
            return;
        }
        Format wanted = b.showFormatted(!b.showingFormatted());
        if (wanted != null) {
            format.send(wanted);
        }
        if (b == ws.front()) {
            gui.focus(b.keyboard());
        }
        ui.status(b.showingFormatted() ? "Formatted view" : "Source view", false);
    }

    void cycle(int delta) {
        ws.cycle(delta);
    }

    /** Ctrl+B: the navigator, or the whole window for the files; the keyboard goes where the eye is. */
    void toggleNavigator() {
        boolean show = !ui.navigatorShown();
        ui.showNavigator(show);
        if (show && nav.folder() == null) {
            open(true);   // no folder to list: what there is to do here is pick one
        } else if (show) {
            nav.focusTree();
        } else if (ws.front() != null) {
            gui.focus(ws.front().keyboard());
        }
        picture();
    }

    /** Ctrl+O: Open, or back to the files. */
    void toggleOpen() {
        open(!nav.opened() || !ui.navigatorShown());
    }

    /**
     * Put Open up, bringing the navigator back if it was hidden, or take it away and give the keyboard to the files.
     * Showing a folder takes it away too, without this: the navigator ends Open itself when it is shown one.
     */
    void open(boolean value) {
        if (value) {
            if (!nav.opened()) {
                ui.preview().forget();   // each visit looks at the disk again
                recentHover = null;
                treeHover = null;
            }
            ui.showNavigator(true);
            nav.open(true);
            claimEscape();
        } else if (nav.opened()) {
            nav.open(false);
            nav.focusTree();
        }
        picture();
    }

    /** The pointer on a recent folder in Open, or off it. */
    private void recentHovered(int slot, InteractionState state) {
        Path p = nav.picker().recentPath(slot);
        if (state != InteractionState.NORMAL) {
            recentHover = p;
        } else if (recentHover != null && recentHover.equals(p)) {
            recentHover = null;   // a late leave of one the pointer has since left clears nothing else
        }
        picture();
    }

    /**
     * While Open is up, its picture stands where the editor does: of the folder under the pointer, a recent one
     * before a row, and with the pointer on neither, of the selected row. Before the tree's walk has selected the
     * folder shown, that folder, which the walk will select; with none shown, the folder the tree starts at. Worked
     * out from all of them each time rather than from whichever message came last, since leaving one control and
     * entering the next arrive as two messages in no promised order.
     */
    private void picture() {
        boolean on = nav.opened() && ui.navigatorShown();
        ui.picturing(on);
        if (!on) {
            return;
        }
        Path selected = nav.picker().selected();
        Path folder = recentHover != null ? recentHover
                : treeHover != null ? treeHover
                : selected != null ? selected
                : nav.folder() != null ? nav.folder()
                : nav.picker().topFolder();
        ask(ui.preview().show(folder));
    }

    private void ask(Picture wanted) {
        if (wanted != null) {
            picture.send(wanted);
        }
    }

    /** Esc in Open's tree goes back to the files. Claimed on each tree, since Open builds one each time it starts. */
    private void claimEscape() {
        Node picker = nav.picker().treeNode();
        if (picker != null) {
            gui.claim(picker, Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, intents, new Intent(Command.LEAVE_OPEN));
        }
    }

    /** A folder picked in Open: the editor shows it, as if it were typed in the path bar, and that ends Open. */
    private void pick(Path folder) {
        go.send(new Go(folder.toString()));
    }

    /** The path bar and the tree are the navigator's, so asking for either brings it back if it was hidden. */
    void focusPath() {
        ui.showNavigator(true);
        nav.focusPath();
    }

    void focusTree() {
        ui.showNavigator(true);
        nav.focusTree();
    }

    /** In ({@code 1}), out ({@code -1}), or back to where it started ({@code 0}). */
    void zoom(int direction) {
        if (direction > 0) {
            gui.zoomIn();
        } else if (direction < 0) {
            gui.zoomOut();
        } else {
            gui.resetZoom();
        }
    }

    /** Tell the editor when a document went unsaved, or came back to what is on disk. */
    private void reportDirty(Buffer b) {
        if (b.dirtyChanged()) {
            edited.send(new Edited(b.id, b.dirty()));
        }
    }
}
