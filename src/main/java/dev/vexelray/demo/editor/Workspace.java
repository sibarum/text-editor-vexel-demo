package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.text.TextLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The open documents: a {@link Tabs} bar, one {@link Buffer} per tab, and the bookkeeping that keeps the two
 * in step.
 *
 * <h2>Two rules, and the deadlock they prevent</h2>
 *
 * <p>The tab order and the buffer list change together under {@link #lock}, and only there. <b>Nothing commits to
 * the {@link Model} while holding it</b>, and <b>nothing reached from a model listener takes it</b>. The reason is
 * in atchung: {@code onCommitLatest} serialises delivery by making a committing thread wait for any delivery
 * already running on another thread. A commit made under this lock, racing a listener that wanted this lock,
 * would be two threads each waiting for the other, with nothing thrown.
 *
 * <p>So the listener's half — retitling a header when a document goes dirty — goes by id through a concurrent
 * map to the header node the buffer was given, never through an index into the bar.
 *
 * <p>The bar never removes a tab on its own: {@link Tabs#closable} is off, because the bar's own Close cannot be
 * stopped and a tab with unsaved work has to be asked about first. Closing is {@link Actions}', which asks, and
 * then calls {@link #close}.
 */
final class Workspace {

    private final Gui gui;
    private final Model model;
    private final Motion motion;
    private final Color accent;
    private final Tabs tabs;
    private final Node card;
    private final Consumer<Path> reveal;
    private final AtomicLong ids = new AtomicLong();
    private final Consumer<Buffer.Position> caret;

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
     * @param caret  told where the caret is in the document in front
     * @param reveal asked to show a folder a breadcrumb was clicked on
     */
    Workspace(Gui gui, Motion motion, Model model, Consumer<Buffer.Position> caret, Consumer<Path> reveal) {
        this.gui = gui;
        this.motion = motion;
        this.accent = gui.theme().color(Role.ACCENT);
        this.model = model;
        this.caret = caret;
        this.reveal = reveal;
        this.tabs = new Tabs(gui).closable(false).transition(Tabs.slide(motion.change)).skin(this::paintHeader);
        tabs.node().width(Length.FILL).height(Length.FILL);
        // The bar and the pages are both CHROME already, which is this design's card; the card itself is the
        // rounded edge round the two.
        this.card = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .background(gui.theme().color(Look.CARD))
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Look.RIM))
                .clip(true)
                .children(tabs.node());
        gui.landmark(Landmarks.TABS, tabs.node());
        // Delivered on a worker, after the fact: resolve what is in front now rather than trusting the index,
        // which a close between the click and this may have moved.
        tabs.onSelect(i -> {
            Buffer b = front();
            if (b != null) {
                model.front(b.id);
                caret.accept(Buffer.position(b.field.document().value()));
            }
        });
    }

    Node node() {
        return card;
    }

    Tabs tabs() {
        return tabs;
    }

    /** Open {@code content} in a new tab in front, taking over a pristine Untitled tab if that is what is there. */
    Buffer add(Path path, TextFile.Loaded content) {
        return add(path, content, true);
    }

    /**
     * As {@link #add(Path, TextFile.Loaded)}, and give the new document the keyboard only if {@code focus}: a file
     * opened by walking the navigator with the arrow keys must leave the keyboard in the navigator, or the walk
     * stops at the first file.
     */
    Buffer add(Path path, TextFile.Loaded content, boolean focus) {
        long id = ids.incrementAndGet();
        // Both of these are document listeners, so neither may take the lock (see the class note): the caret asks
        // the model which document is in front rather than asking the bar.
        Buffer buffer = new Buffer(gui, id, path, content, wrap,
                dirty -> model.dirty(id, dirty),
                position -> {
                    if (model.doc().active() == id) {
                        caret.accept(position);
                    }
                },
                reveal);
        buffer.place(model.doc().folder());
        Buffer replaced = null;
        synchronized (lock) {
            Buffer in = frontLocked();
            if (in != null && in.pristine() && buffers.size() == 1) {
                replaced = in;
            }
            tabs.add(new Doc.Entry(id, path, false, buffer.language()).title(), buffer.page);
            int index = buffers.size();
            // The bar painted the header before the mark existed, and selecting a tab that is already selected
            // repaints nothing, so the mark is moved here as well as by the skin.
            Node header = tabs.header(index);
            marks.put(header, mark(header));
            buffers.add(buffer);
            byId.put(id, buffer);
            headers.put(id, header);
            tabs.select(index);
            if (tabs.selected() == index) {
                slideMarkTo(header);
            }
        }
        model.opened(new Doc.Entry(id, path, false, buffer.language()));
        if (replaced != null) {
            close(replaced.id);
        }
        if (focus) {
            gui.focus(buffer.field.node());
        }
        return buffer;
    }

    /** A new empty document, in front. */
    Buffer untitled() {
        return add(null, new TextFile.Loaded("", System.lineSeparator().equals("\r\n"), List.of()));
    }

    /**
     * Close tab {@code id} without asking — {@link Actions} has already asked. The workspace never leaves itself
     * empty: closing the last tab opens an Untitled one, as every editor does.
     */
    void close(long id) {
        Buffer gone = null;
        boolean empty;
        synchronized (lock) {
            for (int i = 0; i < buffers.size(); i++) {
                if (buffers.get(i).id == id) {
                    gone = buffers.remove(i);
                    tabs.remove(i);
                    break;
                }
            }
            empty = buffers.isEmpty();
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
        model.closed(id);
        if (empty) {
            untitled();
        }
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
            gui.focus(buffer.field.node());
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
        gui.focus(next.field.node());
    }

    /** Word wrap, for every document open now and opened later. */
    boolean toggleWrap() {
        boolean next = !wrap;
        wrap = next;
        for (Buffer b : all()) {
            b.wordWrap(next);
        }
        return next;
    }

    /**
     * Write what the session says onto the headers. Called from the model's listener, so it takes no lock: each
     * header is found by its document's id, and one that has gone is skipped.
     */
    void retitle(Doc doc) {
        for (Doc.Entry e : doc.tabs()) {
            Node header = headers.get(e.id());
            if (header != null) {
                header.text(e.title());
            }
            Buffer b = byId.get(e.id());
            if (b != null) {
                b.place(doc.folder());
            }
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
