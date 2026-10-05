package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Tabs;

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
    private final AtomicLong ids = new AtomicLong();
    private final Consumer<Buffer.Position> caret;

    private final Object lock = new Object();
    /** Parallel to the bar's tabs. Guarded by {@link #lock}. */
    private final List<Buffer> buffers = new ArrayList<>();
    /** By id, for the paths that must not take {@link #lock}. */
    private final Map<Long, Buffer> byId = new ConcurrentHashMap<>();
    private final Map<Long, Node> headers = new ConcurrentHashMap<>();

    private volatile boolean wrap = true;

    Workspace(Gui gui, Motion motion, Model model, Consumer<Buffer.Position> caret) {
        this.gui = gui;
        this.motion = motion;
        this.accent = gui.theme().color(Role.ACCENT);
        this.model = model;
        this.caret = caret;
        this.tabs = new Tabs(gui).closable(false).transition(Tabs.slide(motion.change));
        tabs.node().width(Length.FILL).height(Length.FILL);
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
        return tabs.node();
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
                });
        Buffer replaced = null;
        synchronized (lock) {
            Buffer in = frontLocked();
            if (in != null && in.pristine() && buffers.size() == 1) {
                replaced = in;
            }
            tabs.add(new Doc.Entry(id, path, false, buffer.language()).title(), buffer.field.node());
            int index = buffers.size();
            buffers.add(buffer);
            byId.put(id, buffer);
            headers.put(id, tabs.header(index));
            tabs.select(index);
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
        headers.remove(id);
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
        }
    }
}
