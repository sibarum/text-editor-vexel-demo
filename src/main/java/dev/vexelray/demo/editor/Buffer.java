package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Subscription;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * One open document: its field, its highlighter, and where it lives on disk.
 *
 * <p>The text is the field's own {@code State<Document>}; this class adds only what a file needs on top of a
 * field — a path, the line-ending convention to write back, and the question of whether it is saved, which the
 * field's undo history already answers ({@link dev.vexelray.gui.core.edit.History#mark()}).
 */
final class Buffer implements AutoCloseable {

    /** Where the caret is, 1-based, for the status line. */
    record Position(int line, int column) {
    }

    final long id;
    final TextField field;
    private final Highlighter highlighter;
    private final Subscription dirtyWatch;
    private final Subscription caretWatch;
    private final Consumer<Boolean> dirty;

    private volatile Path path;
    private volatile boolean crlf;
    private volatile boolean unsavedWrite;
    /** The text the highlighter was last asked about, so a span-only change does not ask again. */
    private volatile String highlighted;

    /**
     * @param dirty told whether the document differs from what is on disk, each time that changes
     * @param caret told where the caret is, each time it moves
     */
    Buffer(Gui gui, long id, Path path, TextFile.Loaded content, boolean wrap,
           Consumer<Boolean> dirty, Consumer<Position> caret) {
        this.id = id;
        this.path = path;
        this.crlf = content.crlf();
        this.dirty = dirty;
        this.field = new TextField(gui, "")
                .multiline(true)
                .lineNumbers(true)
                .wordWrap(wrap);
        field.node().width(Length.FILL).height(Length.FILL).textSize(Type.CODE);
        this.highlighter = new Highlighter(gui, field);

        field.text(content.text());
        field.caret(0);
        field.history().mark();
        highlighted = field.text();

        // onChange is one slot, not a list: a second call replaces the first. So this class owns it, and anything
        // else that wants to hear about edits asks here. It also fires when only the spans changed — which the
        // highlighter's own result does — so it re-highlights only when the text did (framework-notes FN-8).
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

    /** Empty, never saved and never touched — a tab a file being opened may simply take over. */
    boolean pristine() {
        return path == null && !dirty() && field.text().isEmpty();
    }

    /**
     * What the document says now, encoded the way its file was — and the history marked clean at that point.
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
