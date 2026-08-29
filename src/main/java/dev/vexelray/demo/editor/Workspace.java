package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Ramp;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.TextField;
import sibarum.kronometer.anim.Ease;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The window's content: a {@link Tabs} panel of {@link EditorTab}s over a status line. The parallel
 * {@code open} list and the widget's tab order are kept in lockstep — {@code open.get(i)} is the document
 * on tab {@code i}, and every index this class computes assumes it.
 *
 * <p>Most structural changes are asked for here and serviced on the GUI thread, having been posted to the
 * frame loop by {@link FileActions}. One is not: the tab bar puts a <b>Close</b> item on every header's
 * context menu itself, which removes a tab straight from the handler lane without being posted at all. That
 * arrives at {@link #tabRemoved}, and it is why the list is guarded rather than merely thread-confined —
 * the lock is the bar's own monitor, because the invariant being protected spans both structures and a
 * second lock taken in the other order would be a deadlock waiting for a right click during a frame.
 *
 * <p><b>What may be called while holding that monitor.</b> Some of it reaches well past the two structures
 * being guarded: {@link #closeActive} empties a document under the lock, which sets text on the field, marks
 * its history, re-picks its grammar and writes the status line. That is safe on one property, which is worth
 * stating because nothing about it is local — none of those blocks on another thread. {@code Gui.async} is a
 * plain submit and the ramps only schedule, so the monitor is never held across a wait for work that might
 * itself want it. A right-click builds its menu on a handler thread and asks {@link #at}, so anything that
 * did block here would deadlock against exactly that.
 */
final class Workspace {
    final Gui gui;
    final Tabs tabs;
    final Node status;
    final TitleBar titleBar;
    final List<EditorTab> open = new ArrayList<>();

    /**
     * Timing for the status line's arrival, or null for no motion at all — the reduced-motion path, and what
     * a clock that is never ticked has to be given rather than a ramp that would deliver its 0 and stop.
     * That is not hypothetical: {@code ramp} delivers 0 before any time passes, so a status line faded in by
     * a stalled clock would be a status line at zero opacity holding the message nobody can read.
     */
    private final Ramp arrival;

    /**
     * One-shot feedback on this window's nodes. {@link Cues#none()} where there is no clock, which is the
     * honest collapse — a cue exists only in the middle, so "instantly" means "not at all".
     *
     * <p>Package-private, like {@link #tabs} and {@link #open} beside it and for the same reason: a cue is
     * played and then forgotten, so the only way a test can ask whether the right node was marked is to ask
     * this. {@link Cues#active()} is also the one number that says the class is at rest.
     */
    final Cues cues;

    /**
     * The longer timing {@link #warn} plays its ring on. Null alongside a {@link Cues#none()}, which plays
     * nothing whatever ramp it is handed — so the two travel together and neither has to check the other.
     */
    private final Ramp alert;

    /**
     * Which {@link #arrive} owns the status line. A second message during the first one's fade supersedes
     * it rather than fighting it for the node: two ramps writing one opacity every frame is a race settled
     * by whichever landed last, and the loser's settle would then snap the winner's fade to its end a
     * duration later. This is the same rule {@link Cues} keeps for overlays, kept here for the two
     * properties a cue is not allowed to touch.
     *
     * <p>Atomic rather than a plain int because it is the identity in that rule and not merely a counter,
     * even though every {@code say} here arrives on the GUI thread — everything that reports enqueues.
     */
    private final java.util.concurrent.atomic.AtomicInteger saying =
            new java.util.concurrent.atomic.AtomicInteger();

    Workspace(Gui gui, KronoGui krono) {
        this.gui = gui;
        this.tabs = new Tabs(gui);
        this.arrival = krono == null ? null
                : (progress, done) -> krono.ramp(TextEditorApp.TRANSITION, Ease.LINEAR, progress, done);
        this.cues = krono == null ? Cues.none()
                : new Cues((progress, done) -> krono.ramp(TextEditorApp.CUE, Ease.LINEAR, progress, done));
        this.alert = krono == null ? null
                : (progress, done) -> krono.ramp(TextEditorApp.ALERT, Ease.LINEAR, progress, done);
        // Changing tabs dissolves, with the arriving document travelling a short way in from the side the
        // selection moved toward. Tabs supplies the motion -- opacity over both pages and a translate on the
        // arriving one, the outgoing page floated over it so nothing reflows for the duration -- and
        // Kronometer supplies the time; the seam between them is a DoubleConsumer and a Runnable, so this is
        // the only line where the two meet, and leaving it out gives back the instant switch.
        //
        // Slide rather than a bare crossfade because these are documents in a bar that has an order, and a
        // displacement is what makes the content agree with that order instead of the headers merely
        // asserting it. A dissolve alone has nothing in it that moves, which is what reads as mechanical.
        //
        // LINEAR, and it has to be: Tabs already eases the displacement out-cubic and holds the fade
        // underneath it linear. Easing this ramp as well eases the fade twice, which spends most of the
        // duration at an opacity indistinguishable from the end -- a delay and then a jump. A test checking
        // only the endpoints cannot see the difference, because the endpoints are right either way.
        //
        // Harmless under --capture, which never ticks the clock: a panel with one tab has nothing to fade
        // from, and the first tab is selected before there is a second.
        this.tabs.transition(Tabs.slide(
                (progress, done) -> krono.ramp(TextEditorApp.TRANSITION, Ease.LINEAR, progress, done)));
        // Before the welcome tab is added, so no tab can be removed without this being in place: the bar's
        // own Close item is a removal this class never calls for, and a removal it does not see leaves
        // `open` one document longer than the bar for the rest of the session.
        this.tabs.onRemove(this::tabRemoved);
        // AUTO, not a fixed line: this line also reports what was opened or saved, and a long path wraps.
        // A fixed height clips the second line outside the padding instead of making room for it.
        this.status = gui.text("Ctrl+O open - Ctrl+Shift+O folder - Ctrl+` terminal - Ctrl+S save - "
                        + "Ctrl+N new - Ctrl+W close - Ctrl+Z undo")
                .width(Length.FILL).height(Length.AUTO)
                .textSize(Length.rem(0.875f)).textColor(gui.theme().color(Role.DIM))
                .align(dev.vexelray.text.TextLayout.HAlign.LEFT, dev.vexelray.text.TextLayout.VAlign.MIDDLE)
                .scroll(false, false);

        Node root = gui.column().width(Length.FILL).height(Length.grow(1))
                .padding(TextEditorApp.GUTTER).gap(Length.rem(0.625f))
                .children(tabs.node(), status);
        // The same gutter, said to the window manager: everything outside the page and below the bar is a
        // grip. The bar is not — it declares itself caption, and a declared region keeps the system's own
        // thin band, so this buys the three dead edges without costing the fourth its drag.
        gui.resizeBorder(TextEditorApp.GUTTER);
        // The window's own title bar: ordinary widgets, plus the declarations that tell the window manager
        // which pixels are caption. Bound to the real window in main(); here it commands
        // WindowControls.NONE, which is what --capture draws.
        this.titleBar = new TitleBar(gui, WindowControls.NONE, "Text Editor");
        gui.root().background(gui.theme().color(Role.PAGE)).children(titleBar.node(), root);

        newTab(TextEditorApp.WELCOME, null, false);
    }

    /**
     * Report something that happened, on the status line.
     *
     * <p><b>Why this is a method and not {@code status.text(...)}.</b> A line of text replaced in place is
     * the one kind of change a person looking somewhere else cannot notice: nothing moves, nothing appears,
     * and the only evidence is a sentence that was not there a moment ago in a strip that always has a
     * sentence in it. Saving the same file twice writes an identical string and shows nothing at all. So the
     * change gets motion of its own, and every report here goes through this or {@link #warn}.
     */
    void say(String message) {
        arrive(message);
    }

    /**
     * Report something that did <em>not</em> happen — a refusal or a failure.
     *
     * <p>The same arrival as {@link #say}, plus a ring: {@code Cue.ring} pulses twice rather than once,
     * which is what distinguishes a refusal from an acknowledgement at a glance, and it is an outline rather
     * than a tint, so it does not lie over the sentence it is drawing attention to. That is the whole reason
     * this is a ring and the save acknowledgement is a wash — a wash over the status line would cover the
     * explanation at exactly the moment it asked to be read.
     *
     * <p>{@link TextEditorApp#ALERT} rather than the house cue length, for the reason stated there.
     */
    void warn(String message) {
        arrive(message);
        cues.play(status, Cue.ring(gui.theme().color(Role.DANGER)), alert);
    }

    /**
     * Acknowledge that {@code tab} has just been written to disk: a wash over the page that was saved.
     *
     * <p>Ctrl+S is repeated most and read least. It already reported itself on the status line, but by the
     * time a sentence has been read the keystroke it answered has been forgotten; a tint over the document
     * says <em>this one, now</em> without being read at all.
     *
     * <p>{@link Role#HIGHLIGHT} rather than a bare accent, and this one matters: a wash takes the colour's
     * own alpha as its peak, so an opaque colour would blank the document it is confirming, for a frame, in
     * the middle of the confirmation. HIGHLIGHT is the accent already at the alpha a wash wants.
     *
     * <p>Played on the tab's page rather than the window, so a save-all flashes each document as it lands.
     * Pages that are not on screen paint into an overlay nobody is looking at and clear it again.
     */
    void saved(EditorTab tab) {
        cues.play(tab.body, Cue.wash(gui.theme().color(Role.HIGHLIGHT)));
    }

    /**
     * Mark {@code tab}'s header as having just received a file: a single pulse of the accent around it.
     *
     * <p><b>The header, not the page.</b> The page already slides in under {@code Tabs.slide}, so a mark
     * there would be motion over motion saying the same thing. The header is the half of a tab that does
     * <em>not</em> move, and the half still on screen for every document this did not select — which is the
     * case worth having: {@code ls | where ext == "java" | first 3 | edit} opens three tabs and can select
     * only the last.
     *
     * <p><b>One pulse, where {@link #warn} takes two.</b> {@code Cue.ring}'s repeat reads as insistence,
     * which is right for a refusal and wrong for a document turning up where it was asked to be. Same cue
     * and same colour, one difference, so the two never have to be told apart by reading the status line.
     *
     * <p>Null is an ordinary answer here: the caller is often handing over {@link #active()}, which is null
     * when there is nothing open. So is a tab that has been closed between the request and this call, which
     * the bar's own Close item makes possible without passing through the queue — the index is resolved
     * under the same lock every other index here is, and a tab that has gone is simply not marked.
     */
    void arrived(EditorTab tab) {
        if (tab == null) {
            return;
        }
        Node header;
        synchronized (tabs) {
            int i = open.indexOf(tab);
            if (i < 0) {
                return;
            }
            header = tabs.header(i);
        }
        // Outside the lock: playing a cue is the bar's business only in that the node is one of its, and
        // Cues is safe from any thread. Nothing here needs the two structures to agree any more.
        cues.play(header, Cue.ring(gui.theme().color(Role.ACCENT), 1));
    }

    /**
     * Put {@code message} on the status line and bring it in: a fade with a short rise under it.
     *
     * <p><b>One ramp, two curves, and it has to be.</b> The fade is taken linear because opacity has nowhere
     * to arrive at — the eye reads it about as it is given, and easing it spends most of the duration at a
     * value indistinguishable from the end. The rise is a distance being covered and is eased into its
     * stop, which is what reads as weight rather than as a slide that was switched off. So the ease is
     * applied inside the sample rather than by the ramp: that is the same division {@code Tabs.slide} makes
     * between its own travel and the fade underneath it, and the reason the ramp driving both is LINEAR.
     */
    private void arrive(String message) {
        status.text(message);
        if (arrival == null) {
            return;
        }
        int mine = saying.incrementAndGet();
        arrival.run(
                t -> {
                    if (saying.get() != mine) {
                        return;   // superseded: a newer message owns the line and will settle it
                    }
                    status.opacity((float) t);
                    status.translate(0f, TextEditorApp.STATUS_RISE_EM * (1f - Ease.OUT_CUBIC.at((float) t)));
                },
                () -> {
                    if (saying.get() == mine) {
                        status.opacity(1f);
                        status.translate(0f, 0f);
                    }
                });
    }

    /** Open a new tab holding {@code content}, select it, and return it. */
    EditorTab newTab(String content, Path file, boolean crlf) {
        TextField editor = new TextField(gui, content).multiline(true).wordWrap(true).lineNumbers(true);
        editor.node().width(Length.FILL).height(Length.FILL);
        // Square shoulders and no border on top, so the page meets the tab bar seamlessly — the framework
        // strokes borders as one ring (no per-side control), so the seam-free look means no stroke at all;
        // lit + elevation keep the card reading as a panel without it.
        Node body = gui.column().width(Length.FILL).height(Length.FILL)
                .background(gui.theme().color(Role.PANEL)).corner(Length.ZERO, Length.rem(0.75f))
                .lit(gui.theme().lit()).elevation(Length.rem(1))
                .padding(Length.dp(12))
                .children(editor.node());
        EditorTab tab = new EditorTab(editor, new Highlighter(gui, editor), body, content);
        tab.file = file;
        tab.crlf = crlf;
        synchronized (tabs) {
            open.add(tab);
            tabs.add(tab.title(), body);
            tabs.select(open.size() - 1);
        }
        tab.highlighter.language(tab.title());
        return tab;
    }

    EditorTab active() {
        synchronized (tabs) {
            int i = tabs.selected();
            return i >= 0 && i < open.size() ? open.get(i) : null;
        }
    }

    /** Every open document with edits that are not on disk, in tab order. */
    List<EditorTab> unsaved() {
        List<EditorTab> out = new ArrayList<>();
        synchronized (tabs) {
            for (EditorTab tab : open) {
                if (tab.dirty()) {
                    out.add(tab);
                }
            }
        }
        return out;
    }

    /** Bring the tab holding {@code file} to the front; false if no tab holds it. */
    boolean showFile(Path file) {
        synchronized (tabs) {
            for (int i = 0; i < open.size(); i++) {
                if (file.equals(open.get(i).file)) {
                    tabs.select(i);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Bring {@code tab} to the front, if it is still open. Resolving the index and using it under one lock
     * rather than handing one out: an index is only true of the moment it was taken, and a close between
     * the two would make this select a different document — or, for a tab that has since gone, tab 0,
     * which is what {@link Tabs#select} clamps a -1 to.
     */
    void show(EditorTab tab) {
        synchronized (tabs) {
            int i = open.indexOf(tab);
            if (i >= 0) {
                tabs.select(i);
            }
        }
    }

    /**
     * The document on tab {@code index}, or null if there is no such tab.
     *
     * <p>What a header's context menu is built from. That menu is built on a worker thread at the moment of
     * the click, off an index the bar resolved a moment earlier, so it asks under the same lock everything
     * else does rather than trusting the number it was handed — a tab removed in between answers null here,
     * and an item aimed at a document that is no longer open is an item that comes out greyed.
     */
    EditorTab at(int index) {
        synchronized (tabs) {
            return index >= 0 && index < open.size() ? open.get(index) : null;
        }
    }

    /** True when nothing is open at all — see {@link FileActions#perFrame()}, which is what fixes it. */
    boolean empty() {
        synchronized (tabs) {
            return open.isEmpty();
        }
    }

    /** Re-label the active tab and re-pick its grammar — after an open-into or a save-as. */
    void retitleActive() {
        EditorTab tab;
        synchronized (tabs) {
            tab = active();
        }
        if (tab != null) {
            retitle(tab);
        }
    }

    /**
     * Re-label {@code tab}'s own header and re-pick its grammar, wherever it sits in the bar.
     *
     * <p>Separate from {@link #retitleActive} because saving is not always about the tab in front. A
     * <b>Save all</b> walks every unsaved document, and one that already has a path is written without
     * ever being selected — so the retitle that follows the write has to name the tab that was written
     * rather than whichever one happens to be showing.
     *
     * <p>A tab that has left the bar is simply not retitled: the index is resolved under the same lock
     * every other index here is, and the bar's own Close item can remove one at any point.
     */
    void retitle(EditorTab tab) {
        synchronized (tabs) {
            int i = open.indexOf(tab);
            if (i < 0) {
                return;
            }
            tabs.title(i, tab.title());
        }
        tab.highlighter.language(tab.title());
    }

    /** Close the active tab. The last tab is not removed but reset to an empty untitled document. */
    void closeActive() {
        synchronized (tabs) {
            EditorTab tab = active();
            if (tab == null) {
                return;
            }
            if (open.size() == 1) {
                tab.file = null;
                tab.crlf = false;
                tab.editor.text("");
                tab.savedAs("");
                retitleActive();
                say("Closed - one empty tab remains");
                return;
            }
            // Removing from the bar is the whole action: `open` shrinks in tabRemoved, which the bar calls
            // back into. Doing it here as well would drop two documents for one close.
            tabs.remove(tabs.selected());
        }
    }

    /**
     * Close every tab — <b>Close all</b> on a header's context menu. The last one is emptied rather than
     * removed, exactly as {@link #closeActive} leaves it, so the editor is never without a document to type
     * into and {@link FileActions#perFrame()} never has to put the floor back.
     *
     * <p>Back to front, for two reasons. Removing from the end never shifts an index this loop has still to
     * use; and {@link Tabs#remove} reselects after every removal, so front to back would walk the selection
     * through each surviving document on the way out. Neither costs a transition — a removal reselects from
     * "nothing selected", which is the one path {@code Tabs} does not animate.
     *
     * <p>One lock for the whole sweep, not one per tab: {@code open} and the bar disagree in between, and a
     * thread that read {@link #active()} halfway through this would be told about a document that is on its
     * way out.
     */
    void closeAll() {
        synchronized (tabs) {
            for (int i = open.size() - 1; i > 0; i--) {
                tabs.remove(i);
            }
            // Whatever survives is tab 0, which is where the selection has ended up — so this is the same
            // call Ctrl+W on a last tab makes, and the emptying rule is stated in exactly one place.
            closeActive();
        }
    }

    /**
     * A tab has left the bar — by {@link #closeActive}, or by <b>Close</b> on the header's context menu,
     * which is the bar's own and reaches it without passing through this class at all. This is the single
     * place {@code open} shrinks, so both routes cost exactly one document.
     *
     * <p>Called from inside {@code Tabs.remove}, on whichever thread asked for it, with both of the bar's
     * lists already shrunk and the selection not yet moved. Holding the bar's monitor for the list edit is
     * the point: a document removed a moment before or after its tab is a window in which {@code active()}
     * answers with the wrong file.
     */
    private void tabRemoved(int index) {
        EditorTab tab;
        synchronized (tabs) {
            if (index < 0 || index >= open.size()) {
                return;
            }
            tab = open.remove(index);
        }
        // The page left the tree with the tab, so these came back dead rather than dormant. Closing them
        // releases what the removal did not: the highlighter's pending tokenize and the field's own state.
        tab.highlighter.close();
        tab.editor.close();
    }

    /**
     * Release every open document. Closing a tab one at a time already does this in {@link #tabRemoved};
     * this is the whole workspace going away at once.
     *
     * <p>It has to exist because the editor can be shut without its documents being closed first. Under
     * MainFrame the editor is a window the shell opens and closes while the process carries on, so a
     * workspace dropped with ten tabs in it dropped ten highlighters — each holding a generation counter and
     * reachable from its field's {@code onChange} — and ten fields, every time.
     *
     * <p>Emptied rather than merely walked, so closing twice is not closing every document twice.
     */
    void close() {
        List<EditorTab> closing;
        synchronized (tabs) {
            closing = new ArrayList<>(open);
            open.clear();
        }
        for (EditorTab tab : closing) {
            tab.highlighter.close();
            tab.editor.close();
        }
    }

    /** Select the next ({@code +1}) or previous ({@code -1}) tab, wrapping around the ends. */
    void cycle(int direction) {
        int n = tabs.count();
        if (n > 1) {
            tabs.select(((tabs.selected() + direction) % n + n) % n);
        }
    }
}
