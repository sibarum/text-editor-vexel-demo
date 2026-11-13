package dev.vexelray.demo.editor;

/**
 * The ids of the commands a menu line names as well as the keymap binds ({@code View.commands}): a tab menu line that
 * names one says that command's chord as the person has it now, rebinds and all, so no chord is written twice. An id
 * is what the person's changes are stored under, so it is the part to keep stable.
 */
final class CommandIds {

    /** Something new in the folder shown: a file, a Maven project, or a module of the project around it. */
    static final String NEW_FILE = "new.file";
    static final String NEW_PROJECT = "new.project";
    static final String NEW_MODULE = "new.module";

    /** Concordance's index of the project the folder shown is in. */
    static final String NEW_INDEX = "new.index";

    /** Save the front document. */
    static final String SAVE = "file.save";

    /** Close the front document. */
    static final String CLOSE = "tab.close";

    /** A Markdown document as formatted, or as its source. */
    static final String FORMATTED = "view.formatted";

    /** Word wrap on or off, in every document. */
    static final String WRAP = "view.wrap";

    /** The navigator shown or hidden. */
    static final String NAVIGATOR = "view.navigator";

    /** Symbols: the project's declarations, found by name. */
    static final String SYMBOLS = "go.symbol";

    /** Zoom in, out, or back to where it started. */
    static final String ZOOM_IN = "view.zoom-in";
    static final String ZOOM_OUT = "view.zoom-out";
    static final String ZOOM_RESET = "view.zoom-reset";

    private CommandIds() {
    }
}
