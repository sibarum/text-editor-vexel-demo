package dev.vexelray.demo.editor;

/**
 * The ids of the commands a menu line names as well as the keymap binds ({@code View.commands}): a tab menu line that
 * names one says that command's chord as the person has it now, rebinds and all, so no chord is written twice. An id
 * is what the person's changes are stored under, so it is the part to keep stable.
 */
final class CommandIds {

    /** Save the front document. */
    static final String SAVE = "file.save";

    /** Close the front document. */
    static final String CLOSE = "tab.close";

    /** A Markdown document as formatted, or as its source. */
    static final String FORMATTED = "view.formatted";

    private CommandIds() {
    }
}
