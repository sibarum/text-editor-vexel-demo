package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Grammars;
import dev.vexelray.demo.editor.core.Markdown;
import dev.vexelray.demo.editor.core.Messages.Format;
import dev.vexelray.demo.editor.core.Messages.Formatted;
import dev.vexelray.demo.editor.core.Messages.Mark;
import dev.vexelray.demo.editor.core.Messages.Marked;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.Whitespace;
import dev.vexelray.gui.widget.TextField;

import java.nio.file.Path;
import java.util.List;

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
    /** Where the file is: changed only when the editor says it moved ({@link #moved}). */
    Path path;
    final TextField field;

    private final Gui gui;
    /** What the tab shows: the field, or for Markdown the formatted view in its place. */
    private final Node page;
    /** Built only for Markdown, which is all that has a formatted view, the first time the file is; null before. */
    private FormattedView formatted;
    /** Whether the formatted view is shown in place of the field. */
    private boolean showingFormatted;
    /** The text the formatted view was last asked for. */
    private String formattedText;
    /** The TextMate scope of the file's format, or null for plain text. */
    private String scope;
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
        this.gui = gui;
        this.path = path;
        this.scope = Grammars.scopeFor(String.valueOf(path.getFileName()));
        // A document: its past is its own, so Ctrl+Z in one file never reaches into another, and its save mark
        // says whether this file is saved rather than whether the window is. Named, so its messages say which.
        // Multiline is also what gives it Ctrl+F. Whitespace dotted where it means something or hides a mistake:
        // indentation, what trails a line, runs of blanks; and a ¬ at each line's end.
        this.field = new TextField(gui, "").name("buffer-" + id).history(new History()).multiline(true)
                .lineNumbers(true).wordWrap(true).whitespace(Whitespace.BOUNDARY).lineEnds(true);
        field.node().width(Length.FILL).height(Length.FILL).font(scope != null ? Type.MONO : Type.UI)
                .textSize(Type.CODE)
                // The text sits on the card, not in a well of its own: the card is the document's edge. The border
                // keeps its width in the card's colour, as FormattedView's does, so switching views moves nothing.
                .background(gui.theme().color(Look.CARD))
                .border(Length.rem(0.1f), gui.theme().color(Look.CARD));
        this.formatted = Markdown.handles(name()) ? new FormattedView(gui) : null;
        this.page = gui.column().width(Length.FILL).height(Length.FILL).children(field.node());
        if (formatted != null) {
            page.append(formatted.node());
        }

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

    /** What the tab shows: the field, or the formatted view in its place. */
    Node node() {
        return page;
    }

    /** What has the keyboard when this document is in front: the field, or the formatted view in its place. */
    Node keyboard() {
        return showingFormatted ? formatted.node() : field.node();
    }

    /** Whether this document has a formatted view: whether it is Markdown, by its name. */
    boolean formattable() {
        return formatted != null && Markdown.handles(name());
    }

    /**
     * The file is at {@code to} now, moved or renamed: the document stays as it is, unsaved work and all, and takes the
     * format the new name says, its face and its colours with it. What to ask {@code MarkText}, or null when it is plain
     * text now, whose colours are taken off.
     */
    Mark moved(Path to) {
        path = to;
        scope = Grammars.scopeFor(name());
        field.node().font(scope != null ? Type.MONO : Type.UI);
        if (showingFormatted && !Markdown.handles(name())) {
            showFormatted(false);
        }
        if (formatted == null && Markdown.handles(name())) {
            formatted = new FormattedView(gui);
            formatted.node().visible(false);
            page.append(formatted.node());
        }
        marked = null;
        if (scope == null) {
            field.setSpans(List.of());
            return null;
        }
        marked = field.text();
        return new Mark(address(), marked, version, scope);
    }

    boolean showingFormatted() {
        return showingFormatted;
    }

    /** The formatted view, or null when this is not Markdown. */
    FormattedView formatted() {
        return formatted;
    }

    /**
     * Show the document formatted, or as its source: what to ask {@code FormatText} for, or null when going back to
     * the source. The view keeps showing the last blocks it had until the answer lands, which on a switch back on is
     * the right ones unless the text changed in between.
     */
    Format showFormatted(boolean on) {
        showingFormatted = on;
        field.node().visible(!on);
        formatted.node().visible(on);
        if (!on) {
            return null;
        }
        formattedText = field.text();
        return new Format(address(), formattedText, version);
    }

    /**
     * The field changed under the formatted view (nothing is typed while it is shown, but a revert from disk still
     * changes it): what to ask {@code FormatText} for again, or null when the source is what is shown or only the
     * colours changed.
     */
    Format reformat(String now) {
        if (!showingFormatted || now.equals(formattedText)) {
            return null;
        }
        formattedText = now;
        return new Format(address(), now, version);
    }

    /** {@code FormatText}'s answer: shown only if the field still says the text it was read from. */
    void formatted(Formatted m) {
        if (formatted != null && m.version() == version) {
            formatted.show(m.blocks());
        }
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

    /** The file's format, for the status line: "Java", or "Plain text". */
    String language() {
        return Grammars.nameOf(scope);
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
        if (formatted != null) {
            formatted.close();
        }
    }
}
