package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.widget.Breadcrumb;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Subscription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One open document: its field, its highlighter, and where it lives on disk.
 *
 * <p>The text is the field's own {@code State<Document>}; this class adds only what a file needs on top of a
 * field â€” a path, the line-ending convention to write back, and the question of whether it is saved, which the
 * field's undo history already answers ({@link dev.vexelray.gui.core.edit.History#mark()}).
 */
final class Buffer implements AutoCloseable {

    /** Where the caret is, 1-based, for the status line. */
    record Position(int line, int column) {
    }

    final long id;
    final TextField field;
    /** What the tab shows: where the file is, over the field. */
    final Node page;
    private final Breadcrumb<Path> crumbs;
    private final Highlighter highlighter;
    private final Subscription dirtyWatch;
    private final Subscription caretWatch;
    private final Consumer<Boolean> dirty;

    private volatile Path path;
    private volatile boolean crlf;
    private volatile boolean unsavedWrite;
    /** The text the highlighter was last asked about, so a span-only change does not ask again. */
    private volatile String highlighted;
    /** The path and folder the breadcrumb was last drawn for, so an unchanged one is not rebuilt. */
    private volatile List<Path> placed;

    /**
     * @param dirty told whether the document differs from what is on disk, each time that changes
     * @param caret told where the caret is, each time it moves
     */
    Buffer(Gui gui, long id, Path path, TextFile.Loaded content, boolean wrap,
           Consumer<Boolean> dirty, Consumer<Position> caret, Consumer<Path> reveal) {
        this.id = id;
        this.path = path;
        this.crlf = content.crlf();
        this.dirty = dirty;
        this.field = new TextField(gui, "")
                .multiline(true)
                .lineNumbers(true)
                .wordWrap(wrap);
        // The field sits on the card rather than in a well of its own. Its border is the field's to repaint on
        // every change of focus, so the accent ring round the document stays (framework-notes FN-14).
        field.node().width(Length.FILL).height(Length.FILL).textSize(Type.CODE)
                .background(gui.theme().color(Look.CARD));
        this.crumbs = new Breadcrumb<Path>(gui, p -> {
            Path name = p.getFileName();
            return name == null ? p.toString() : name.toString();
        }).maxSegments(10).onNavigate(reveal);
        crumbs.node().padding(Length.ZERO, Type.TIGHT);
        this.page = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .background(gui.theme().color(Look.CARD))
                .children(crumbs.node(), field.node());
        this.highlighter = new Highlighter(gui, field);

        field.text(content.text());
        field.caret(0);
        field.history().mark();
        highlighted = field.text();

        // onChange is one slot, not a list: a second call replaces the first. So this class owns it, and anything
        // else that wants to hear about edits asks here. It also fires when only the spans changed â€” which the
        // highlighter's own result does â€” so it re-highlights only when the text did (framework-notes FN-8).
        field.onChange(text -> {
            if (!text.equals(highlighted)) {
                highlighted = text;
                highlighter.refresh();
            }
        });
        this.dirtyWatch = field.history().status().onCommitLatest(s -> dirty.accept(unsavedWrite || !s.value().clean()));
        this.caretWatch = field.document().onCommitLatest(d -> caret.accept(position(d.value())));
        highlighter.language(name());
    }

    Path path() {
        return path;
    }

    boolean crlf() {
        return crlf;
    }

    /** The file name, or null for a document that has never been saved. */
    String name() {
        Path p = path;
        return p == null ? null : String.valueOf(p.getFileName());
    }

    /** What the highlighter calls this document. */
    String language() {
        return highlighter.language();
    }

    /** Whether the document is unsaved work: what the user would lose by closing it. */
    boolean dirty() {
        return unsavedWrite || !field.history().clean();
    }

    /** Empty, never saved and never touched â€” a tab a file being opened may simply take over. */
    boolean pristine() {
        return path == null && !dirty() && field.text().isEmpty();
    }

    /**
     * What the document says now, encoded the way its file was â€” and the history marked clean at that point.
     *
     * <p>Marked here, before the write, not after it: typing that lands while the bytes are on their way to disk
     * is not in them, so it has to stay unsaved, and {@code History.mark()} marks whatever the history is at the
     * moment it is called. If the write then fails, {@link #saveFailed} puts the document back to unsaved.
     */
    byte[] takeForSave() {
        byte[] bytes = TextFile.encode(field.text(), crlf);
        unsavedWrite = false;
        field.history().mark();
        return bytes;
    }

    /** The write {@link #takeForSave} was for did not land: the document is unsaved, whatever the mark says. */
    void saveFailed() {
        unsavedWrite = true;
        dirty.accept(true);
    }

    /** The write landed at {@code target}, which is now this document's file. */
    void savedAs(Path target) {
        this.path = target;
        highlighter.language(name());
    }

    /**
     * Point the breadcrumb at where this file is: from the navigator's folder down when the file is inside it, the
     * folder itself left out, and from the root of the drive otherwise. Nothing for a file never saved.
     */
    void place(Path folder) {
        Path file = path;
        List<Path> key = java.util.Arrays.asList(file, folder);
        if (key.equals(placed)) {
            return;
        }
        placed = key;
        crumbs.path(chain(file, folder));
    }

    static List<Path> chain(Path file, Path folder) {
        if (file == null) {
            return List.of();
        }
        Path abs = file.toAbsolutePath().normalize();
        Path base = folder == null ? null : folder.toAbsolutePath().normalize();
        boolean inside = base != null && abs.startsWith(base) && !abs.equals(base);
        Path at = inside ? base : abs.getRoot();
        List<Path> chain = new ArrayList<>();
        if (!inside) {
            chain.add(at);
        }
        for (Path part : at.relativize(abs)) {
            at = at.resolve(part);
            chain.add(at);
        }
        return chain;
    }

    void wordWrap(boolean wrap) {
        field.wordWrap(wrap);
    }

    /** Where the caret is in {@code doc}: one pass over the text before it. */
    static Position position(Document doc) {
        String text = doc.text();
        int caret = Math.min(doc.caret(), text.length());
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < caret; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, caret - lineStart + 1);
    }

    @Override
    public void close() {
        highlighter.close();
        dirtyWatch.close();
        caretWatch.close();
        field.close();
    }
}
