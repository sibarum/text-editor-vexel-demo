package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.demo.editor.core.TextFile;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.text.TextLayout;
import sibarum.concordance.index.Source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The open documents: a {@link Tabs} bar, one {@link Buffer} per tab, and the bookkeeping that keeps the two
 * in step.
 *
 * <h2>Who decides</h2>
 *
 * <p>What is open is the editor's ({@code Editor}, in {@code -core}): a tab is added, fronted and closed here when its
 * message reaches the {@link View}, under the id the editor gave it. What this keeps is the widgets: the order of the
 * bar and the buffers in it, which change together under {@link #lock}, and only there. A buffer's own news (it went
 * unsaved, the caret moved) is reported by id through {@link #onEdited} and the caret callback, never through an index
 * into the bar, which a close landing in between turns into the neighbour's.
 *
 * <p>The bar never removes a tab on its own: {@link Tabs#closable} is off, because the bar's own Close cannot be
 * stopped and a tab with unsaved work has to be asked about first. Closing is the editor's, which asks, and then the
 * view calls {@link #close}.
 */
final class Workspace {

    private final Gui gui;
    private final Motion motion;
    private final Color accent;
    private final Tabs tabs;
    /** The Run button over the tabs; what it offers is the {@link View}'s. */
    private final IconButton run;
    /** The Formatted button beside it, which says and changes how the document in front is shown. */
    private final IconButton formatted;
    private final Node card;
    private final Consumer<Buffer.Position> caret;
    /** The tab in front as the bar last said, for the caret's callback, which must not take {@link #lock}. */
    private volatile long frontId = -1;
    private volatile BiConsumer<Long, Boolean> edited = (id, dirty) -> { };
    private volatile Consumer<Buffer> fronted = b -> { };

    private final Object lock = new Object();
    /** Parallel to the bar's tabs. Guarded by {@link #lock}. */
    private final List<Buffer> buffers = new ArrayList<>();
    /** By id, for the paths that must not take {@link #lock}. */
    private final Map<Long, Buffer> byId = new ConcurrentHashMap<>();
    private final Map<Long, Node> headers = new ConcurrentHashMap<>();
    /** Each header's accent mark, which the skin shows under the selected one. */
    private final Map<Node, Node> marks = new ConcurrentHashMap<>();
    /**
     * Guards the mark's slide, and nothing else: the skin runs under the bar's lock and the slide's steps on the
     * clock, so this is taken inside both and takes nothing itself.
     */
    private final Object markLock = new Object();
    /** Guarded by {@link #markLock}: the header the mark is going to, and the slide taking it there. */
    private Node markTarget;
    private long slide;
    /**
     * Guarded by {@link #markLock}: the header whose mark is actually drawn, how far from home, and where that was
     * on screen — kept apart from the target because a slide can be overtaken before it has drawn anything, and
     * the next one has to start from what is on screen, not from where the last one meant to go.
     */
    private Node shownOn;
    private float shownOffsetPx;
    private float shownX = Float.NaN;

    private volatile boolean wrap = true;

    /**
     * @param caret told where the caret is in the document in front
     */
    Workspace(Gui gui, Motion motion, Consumer<Buffer.Position> caret) {
        this.gui = gui;
        this.motion = motion;
        this.accent = gui.theme().color(Role.ACCENT);
        this.caret = caret;
        this.tabs = new Tabs(gui).closable(false).transition(Tabs.slide(motion.change)).skin(this::paintHeader);
        tabs.node().width(Length.FILL).height(Length.FILL);
        // The bar and the pages are both CHROME already, which is this design's card; the card itself is the
        // rounded edge round the two.
        // Over the tabs, a row of commands that act on the document in front and its project: Run, for now.
        this.run = new IconButton(gui, Icon.PLAY, "Run").tip("Run");
        gui.landmark(Landmarks.RUN, run.node());
        // Lit while the document in front is shown formatted, and only there at all when it is Markdown.
        this.formatted = new IconButton(gui, Icon.FONTS, "Formatted").tip("Formatted (Ctrl+Shift+V)")
                .toggle(true);
        formatted.node().visible(false);
        gui.landmark(Landmarks.FORMATTED, formatted.node());
        Node tools = gui.row()
                .width(Length.FILL)
                .padding(Length.dp(2), Type.TIGHT)
                .alignItems(AlignItems.CENTER)
                .children(run.node(), formatted.node());
        this.card = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .background(gui.theme().color(Look.CARD))
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Look.RIM))
                .clip(true)
                .children(tools, tabs.node());
        gui.landmark(Landmarks.TABS, tabs.node());
        // Delivered on a worker, after the fact: resolve what is in front now rather than trusting the index,
        // which a close between the click and this may have moved.
        tabs.onSelect(i -> {
            Buffer b = front();
            if (b != null) {
                frontId = b.id;
                caret.accept(b.position());
                fronted.accept(b);
            }
            syncFormatted();
        });
    }

    /** Be told that tab {@code id}'s text went unsaved, or came back to what is saved. Any thread. */
    void onEdited(BiConsumer<Long, Boolean> handler) {
        this.edited = handler == null ? (id, dirty) -> { } : handler;
    }

    /** Be told which buffer is in front after the bar's selection moved. Delivered on a worker, after the fact. */
    void onFronted(Consumer<Buffer> handler) {
        this.fronted = handler == null ? b -> { } : handler;
    }

    IconButton formattedButton() {
        return formatted;
    }

    /**
     * Show the document in front formatted, or as its source if it is formatted now. Returns what happened, for the
     * status line.
     */
    String toggleFormatted() {
        Buffer b = front();
        if (b == null) {
            return null;
        }
        if (!b.formattable()) {
            return "Only Markdown has a formatted view";
        }
        b.showFormatted(!b.showingFormatted());
        syncFormatted();
        return b.showingFormatted() ? "Formatted view" : "Source view";
    }

    /** Make the Formatted button say how the document in front is shown, and show it only when that is Markdown. */
    void syncFormatted() {
        Buffer b = front();
        formatted.on(b != null && b.showingFormatted());
        formatted.node().visible(b != null && b.formattable());
    }

    Node node() {
        return card;
    }

    IconButton runButton() {
        return run;
    }

    Tabs tabs() {
        return tabs;
    }

    /**
     * Open {@code content} in a new tab in front, as tab {@code id}: a file at {@code path}, a library's source
     * ({@code library}, read-only), or neither for an Untitled document. The new document takes the keyboard only if
     * {@code focus}: a file opened by walking the navigator with the arrow keys must leave the keyboard in the
     * navigator, or the walk stops at the first file.
     */
    Buffer add(long id, Path path, TextFile.Loaded content, boolean focus, Source library) {
        // Both of these are document listeners, so neither may take the lock (see the class note): the caret asks
        // which document the bar last put in front rather than asking the bar.
        Buffer buffer = new Buffer(gui, id, path, content, wrap,
                dirty -> edited.accept(id, dirty),
                position -> {
                    if (frontId == id) {
                        caret.accept(position);
                    }
                },
                library);
        String title = buffer.name() == null ? "Untitled" : buffer.name();
        synchronized (lock) {
            tabs.add(title, buffer.page);
            int index = buffers.size();
            // The bar painted the header before the mark existed, and selecting a tab that is already selected
            // repaints nothing, so the mark is moved here as well as by the skin.
            Node header = tabs.header(index);
            marks.put(header, mark(header));
            buffers.add(buffer);
            byId.put(id, buffer);
            headers.put(id, header);
            tabs.select(index);
            frontId = id;
            if (tabs.selected() == index) {
                slideMarkTo(header);
            }
        }
        if (focus) {
            gui.focus(buffer.focusTarget());
        }
        caret.accept(buffer.position());
        syncFormatted();
        return buffer;
    }

    /**
     * Close tab {@code id} without asking: the editor has already asked. When the last tab goes, the editor opens an
     * Untitled one, as every editor does.
     */
    void close(long id) {
        Buffer gone = null;
        synchronized (lock) {
            for (int i = 0; i < buffers.size(); i++) {
                if (buffers.get(i).id == id) {
                    gone = buffers.remove(i);
                    tabs.remove(i);
                    break;
                }
            }
        }
        if (gone == null) {
            return;
        }
        byId.remove(id);
        Node header = headers.remove(id);
        if (header != null) {
            marks.remove(header);
        }
        gone.close();
    }

    /** The document in front, or null. */
    Buffer front() {
        synchronized (lock) {
            return frontLocked();
        }
    }

    private Buffer frontLocked() {
        int i = tabs.selected();
        return i >= 0 && i < buffers.size() ? buffers.get(i) : null;
    }

    /** The open document for {@code file}, or null. */
    Buffer find(Path file) {
        Path want = file.toAbsolutePath().normalize();
        for (Buffer b : all()) {
            Path p = b.path();
            if (p != null && p.toAbsolutePath().normalize().equals(want)) {
                return b;
            }
        }
        return null;
    }

    /** The open document showing library source {@code source}, or null. */
    Buffer findLibrary(Source source) {
        for (Buffer b : all()) {
            if (source.equals(b.library())) {
                return b;
            }
        }
        return null;
    }

    Buffer byId(long id) {
        return byId.get(id);
    }

    /** Every open document, in tab order — a snapshot. */
    List<Buffer> all() {
        synchronized (lock) {
            return List.copyOf(buffers);
        }
    }

    /** Bring {@code buffer}'s tab to the front and give it the keyboard. */
    void show(Buffer buffer) {
        show(buffer, true);
    }

    void show(Buffer buffer, boolean focus) {
        synchronized (lock) {
            int i = buffers.indexOf(buffer);
            if (i >= 0) {
                tabs.select(i);
            }
        }
        // Asked to open what is already open: ring the tab it is in, so the answer to "where did it go" is visible.
        Node header = headers.get(buffer.id);
        if (header != null) {
            motion.cues.play(header, Cue.ring(accent, 1));
        }
        if (focus) {
            gui.focus(buffer.focusTarget());
        }
    }

    /** Move {@code delta} tabs along the bar, wrapping at the ends. */
    void cycle(int delta) {
        Buffer next;
        synchronized (lock) {
            int n = buffers.size();
            if (n < 2) {
                return;
            }
            int i = Math.floorMod(tabs.selected() + delta, n);
            tabs.select(i);
            next = buffers.get(i);
        }
        gui.focus(next.focusTarget());
    }

    /** Whether documents are word-wrapped. */
    boolean wrap() {
        return wrap;
    }

    /** Word wrap, for every document open now and opened later. */
    void wordWrap(boolean on) {
        wrap = on;
        for (Buffer b : all()) {
            b.wordWrap(on);
        }
    }

    /**
     * Write what the editor says tab {@code id} is called. It takes no lock: the header is found by its document's id,
     * and one that has gone is skipped.
     */
    void retitle(long id, String title) {
        Node header = headers.get(id);
        if (header != null) {
            header.text(title);
        }
    }

    /**
     * A tab as the design draws it: a label on the card, with no silhouette of its own. Idle labels are dim and
     * the selected one is ink, with the accent mark under it.
     */
    private void paintHeader(Node header, boolean selected, InteractionState state) {
        header.padding(Length.dp(6), Length.dp(14))
                .textSize(Type.LABEL)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .corner(Type.CORNER)
                .background(gui.theme().color(selected || state == InteractionState.NORMAL ? Role.NONE : Role.PANEL))
                .textColor(gui.theme().color(selected ? Role.INK : Role.DIM))
                .lit(false)
                .elevation(Length.ZERO);
        if (selected) {
            slideMarkTo(header);
        }
    }

    /**
     * Move the accent mark to {@code header}, sliding it there from wherever it is now.
     *
     * <p>There is one mark on show at a time, but it is drawn by whichever header is selected: each header owns
     * a mark as a floating child, and the slide is the arriving header's mark drawn displaced back to where the
     * last one was, then eased home. So the mark is always laid out by the header it belongs to and nothing has
     * to follow the bar's geometry when tabs open, close or resize — only the displacement is computed, and only
     * while it is moving. It is read from the arriving header's layout on every step rather than once, because a
     * header that was added this instant has no layout until the next frame; until it has, the old mark stays
     * where it was.
     *
     * <p>Interrupted, a slide starts again from where the mark is drawn now, not from where it was going.
     */
    private void slideMarkTo(Node header) {
        float fromX;
        long mine;
        synchronized (markLock) {
            if (header == markTarget || !marks.containsKey(header)) {
                return;
            }
            markTarget = header;
            mine = ++slide;
            NodeLayout was = shownOn == null ? NodeLayout.ABSENT : shownOn.layout();
            fromX = was.present() ? was.rect().x() + shownOffsetPx : shownX;
            if (Float.isNaN(fromX)) {
                // Nowhere to come from — the first tab of the session. The mark arrives with its tab.
                drawMark(header, 0f);
                return;
            }
        }
        motion.arrival.run(p -> {
            synchronized (markLock) {
                if (slide != mine) {
                    return;   // a later selection owns the mark now, and starts from wherever this one drew it
                }
                NodeLayout to = header.layout();
                if (!to.present()) {
                    return;
                }
                float t = (float) Math.max(0d, Math.min(1d, p));
                drawMark(header, (fromX - to.rect().x()) * (1f - t));
            }
        }, () -> { });
    }

    /** Draw {@code header}'s mark {@code offsetPx} from home, and take down whichever mark was drawn before it. */
    private void drawMark(Node header, float offsetPx) {
        Node mark = marks.get(header);
        if (mark == null) {
            return;
        }
        Node before = shownOn == null || shownOn == header ? null : marks.get(shownOn);
        if (before != null) {
            before.visible(false);
        }
        float emPx = gui.rootEmPx() * gui.zoom().value() * gui.dpi().value();
        mark.translate(offsetPx / emPx, 0f).visible(true);
        shownOn = header;
        shownOffsetPx = offsetPx;
        NodeLayout at = header.layout();
        if (at.present()) {
            shownX = at.rect().x() + offsetPx;
        }
    }

    /**
     * The accent mark under a header: a short bar, floated to the header's foot. A header is the bar's text node,
     * and a text node may carry floating children (as a field carries its find bar), so the mark is the header's
     * own child rather than something the bar has to know about. Asking for a y past the bottom puts it on the
     * bottom edge, because a float is clamped inside its parent.
     */
    private Node mark(Node header) {
        Node mark = gui.box()
                .width(Length.rem(1.25f)).height(Length.dp(2))
                .corner(Length.dp(1))
                .background(accent)
                .floatAt(Length.dp(14), Length.rem(4))
                .visible(false);
        header.append(mark);
        return mark;
    }
}
