package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Grammars;
import dev.vexelray.demo.editor.core.Messages.Mark;
import dev.vexelray.demo.editor.core.Messages.Marked;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TextField;

import java.nio.file.Path;

/**
 * One open file: its {@link TextField}, its colours, and what a save needs to know. The {@link View}'s, on the GUI
 * thread, so it holds plain fields and no locks.
 *
 * <p>The text is the field's own. Whether it is saved is the field's undo history's answer ({@code History.mark()}
 * and {@code clean()}): the history already knows which state was last saved, so this keeps no flag of its own for
 * that beyond the one case the history cannot express, a save that did not land.
 *
 * <h2>It hears its field, through the view</h2>
 *
 * <p>The field says what it did on its own topics, addressed by its name ({@code TextField.CHANGED},
 * {@code TextField.CARET}), and the view hands each to the buffer it is about. Nothing calls back into this: it is
 * told, and it answers with what to send, so the view does the sending.
 *
 * <h2>Colours, off the GUI thread</h2>
 *
 * <p>A file in a format {@link Grammars} knows gets its colours after every edit. That is a pass over the whole text,
 * so it is a job ({@code MarkText}): {@link #changed} says what to ask it, and {@link #marked} applies the answer only
 * if the field still says the text it was computed for, because colours worked out for one text and attached to
 * another are on the wrong characters. Such a file is code, and is shown in the mono face; anything else is plain text,
 * which reads better in the proportional one.
 */
final class Buffer implements AutoCloseable {

    final long id;
    final Path path;
    final TextField field;

    /** The TextMate scope of the file's format, or null for plain text. */
    private final String scope;
    /** The version of the field's last change, which a colouring has to match. */
    private long version = -1;
    /** The text the colours were last asked for, so a change that only moved the colours asks nothing. */
    private String marked;
    /** A save took the bytes and then failed, so the document is unsaved whatever the history's mark says. */
    private boolean unsavedWrite;
    /** Whether the editor was last told this is unsaved. */
    private boolean said;
    /** Where the caret is, 1-based, for the status line when this comes to the front. */
    private int line = 1;
    private int column = 1;

    Buffer(Gui gui, long id, Path path, String initial) {
        this.id = id;
        this.path = path;
        this.scope = Grammars.scopeFor(String.valueOf(path.getFileName()));
        // A document: its past is its own, so Ctrl+Z in one file never reaches into another, and its save mark
        // says whether this file is saved rather than whether the window is. Named, so its messages say which.
        this.field = new TextField(gui, "").name("buffer-" + id).history(new History()).multiline(true)
                .lineNumbers(true).wordWrap(true);
        field.node().width(Length.FILL).height(Length.FILL).font(scope != null ? Type.MONO : Type.UI)
                .textSize(Type.CODE);

        // Content this field has no past with, so text() rather than replace(): the history starts here, and the
        // mark says this is what is on disk.
        field.text(initial);
        field.caret(0);
        field.history().mark();
    }

    /** The field's address, which its messages carry. */
    String address() {
        return field.address();
    }

    String name() {
        return String.valueOf(path.getFileName());
    }

    /** The field changed: what to ask {@code MarkText}, or null when the colours are current or it is plain text. */
    Mark changed(String now, long at) {
        version = at;
        if (scope == null || now.equals(marked)) {
            return null;
        }
        marked = now;
        return new Mark(address(), now, at, scope);
    }

    /** {@code MarkText}'s answer: applied only if the field still says the text it was worked out for. */
    void marked(Marked m) {
        if (m.version() == version && !m.spans().equals(field.spans())) {
            field.setSpans(m.spans());
        }
    }

    /** The caret moved. */
    void caret(int atLine, int atColumn) {
        line = atLine;
        column = atColumn;
    }

    /** Where the caret is, for the status line. */
    String position() {
        return "Ln " + line + ", Col " + column;
    }

    /** Whether closing this would lose work. */
    boolean dirty() {
        return unsavedWrite || !field.history().clean();
    }

    /** Whether {@link #dirty} differs from what the editor was last told; it is remembered as told. */
    boolean dirtyChanged() {
        boolean now = dirty();
        if (now == said) {
            return false;
        }
        said = now;
        return true;
    }

    /**
     * The text to write, and the history marked clean at this point, before the write and not after it. Typing that
     * lands while the text is on its way to disk is not in it, so it has to stay unsaved, and {@code mark()} marks
     * whatever the history is when it is called. {@link #saveFailed} undoes the claim if the write does not land. The
     * line endings are the editor's to put back, since it knows how the file had them.
     */
    String takeForSave() {
        unsavedWrite = false;
        field.history().mark();
        return field.text();
    }

    /** The write {@link #takeForSave} was for did not land. */
    void saveFailed() {
        unsavedWrite = true;
    }

    @Override
    public void close() {
        field.close();
    }
}
