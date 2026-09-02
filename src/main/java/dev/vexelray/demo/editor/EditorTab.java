package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.widget.TextField;

import java.nio.file.Path;

/** One open document: its widgets, its highlighter, and what saving must know about its file. */
final class EditorTab {
    final TextField editor;
    final Highlighter highlighter;
    /**
     * The Concordance links on this document — what Ctrl+click follows. Beside the highlighter because it is
     * the same kind of thing: a second opinion about the text, computed off the GUI thread and attached to the
     * field. It differs in what it is derived from — the highlighter reads the characters, this reads an index
     * of the project they are part of — and so in when it is recomputed: see {@link SymbolLinks}.
     */
    final SymbolLinks links;
    final Node body;
    Path file;
    /** Line-ending convention of the file, restored on save. New files save with {@code \n}. */
    boolean crlf;
    /**
     * The text as last loaded or saved — what {@link #dirty()} compares against.
     *
     * <p>A snapshot rather than a flag set from {@code onChange}, because change handlers run on worker
     * threads: a flag would race with the programmatic {@code editor.text(...)} that loading a file does, and
     * lose. Comparing on demand cannot race with anything, and it answers the question more honestly —
     * typing something and then undoing it back leaves the document clean, which is what it is.
     */
    String savedText;

    EditorTab(TextField editor, Highlighter highlighter, SymbolLinks links, Node body, String savedText) {
        this.editor = editor;
        this.highlighter = highlighter;
        this.links = links;
        this.body = body;
        this.savedText = savedText;
    }

    String title() {
        return file != null ? file.getFileName().toString() : TextEditorApp.UNTITLED;
    }

    /**
     * Declare {@code text} to be what is on disk: the snapshot {@link #dirty()} compares against, and the
     * position the field's undo history calls saved.
     *
     * <p>Both, because they answer different questions. The snapshot answers the editor's — whether the
     * bytes differ from the file — and is deliberately not a flag, for the reason {@link #savedText} gives.
     * {@code History.mark()} answers the one the field and anything subscribed to its
     * {@code history().status()} asks, and it is a position rather than a flag for a related reason: undoing
     * back past a save is dirty again, and redoing up to it is clean again. Both have to be set: a dirty dot
     * or a menu item wired to the history reads dirty from the first keystroke on if only the snapshot is.
     *
     * <p>Harmless where the text was replaced with {@code editor.text(...)} rather than typed: that clears
     * the history, so this marks the empty position it already stands at.
     */
    void savedAs(String text) {
        savedText = text;
        editor.history().mark();
    }

    /** Whether this document has edits that are not on disk. */
    boolean dirty() {
        return !editor.text().equals(savedText);
    }

    /** This document's name for a message about losing it. */
    String describe() {
        return file != null ? file.getFileName().toString() : TextEditorApp.UNTITLED + " (never saved)";
    }
}
