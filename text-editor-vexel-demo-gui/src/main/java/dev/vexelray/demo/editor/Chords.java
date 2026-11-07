package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.input.Shortcut;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * The chords a menu line says as well as the keyboard binds: one spelling for both, so a line never shows a chord the
 * keyboard does not have. Bound in {@code View.shortcuts}, said by the tab menu ({@code Workspace}).
 */
final class Chords {

    /** Save the front document. */
    static final Shortcut SAVE = Shortcut.of(Key.S, Modifier.CONTROL);

    /** Close the front document. */
    static final Shortcut CLOSE = Shortcut.of(Key.W, Modifier.CONTROL);

    /** A Markdown document as formatted, or as its source. */
    static final Shortcut FORMATTED = Shortcut.of(Key.V, Modifier.CONTROL, Modifier.SHIFT);

    private Chords() {
    }
}
