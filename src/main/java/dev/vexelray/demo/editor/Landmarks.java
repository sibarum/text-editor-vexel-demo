package dev.vexelray.demo.editor;

/**
 * Every name an automation script may write down, in one place.
 *
 * <p>A ref is minted per run; a landmark still means something tomorrow. These are a published contract, so
 * renaming one breaks a script somebody else wrote, and the compiler should be the thing that notices.
 *
 * <p>Where it can, a landmark carries state in its accessible name: {@link #FOLDER}'s text is the folder's name,
 * so {@code await folder src} is a real wait for the navigator to have been pointed somewhere.
 */
final class Landmarks {

    /** The tab bar and its pages. */
    static final String TABS = "tabs";

    /** The navigator's tree. */
    static final String TREE = "navigator";

    /** The navigator's heading: a button that switches between Edit and Open. Its name is the folder's. */
    static final String FOLDER = "folder";

    /** Open's tree of folders, where a click makes one the root. */
    static final String PICKER = "navigator.roots";

    /** Open's breadcrumb over its folders: every folder above the top one, each a button that lists from there. */
    static final String ANCESTRY = "navigator.ancestry";

    /** What stands in the editor's place in Open: the folder under the pointer, surveyed. */
    static final String PREVIEW = "preview";

    /** The preview's blocks, for a folder with no build to describe: its files as cubes on its folders' plates. */
    static final String BLOCKS = "preview.blocks";

    /** Open's recent roots, one button each. */
    static final String RECENT = "navigator.recent";

    /** Open's button that makes the scratches the root; shown once there is at least one scratch. */
    static final String OPEN_SCRATCHES = "navigator.open-scratches";

    /** The scratches laid over the navigator (Alt+N): their tree, where a file opens in a tab. */
    static final String SCRATCHES = "navigator.scratches";

    /** The New scratch button over the scratches' tree. */
    static final String NEW_SCRATCH = "navigator.new-scratch";

    /** The name field Rename opens over the scratches' tree: Enter renames, Escape puts it away. */
    static final String RENAME_SCRATCH = "navigator.rename-scratch";

    /** The status line, and its four slots. The slot keys are landmarks too, so a script can read each. */
    static final String STATUS = "status";
    static final String STATUS_MESSAGE = "status.message";
    /** The declaration the caret is in — {@code Outer › method} — in a Java document; empty otherwise. */
    static final String STATUS_SCOPE = "status.scope";
    static final String STATUS_LANGUAGE = "status.language";
    static final String STATUS_POSITION = "status.position";

    /** The Run button over the tabs, which opens the menu of what can be run here. */
    static final String RUN = "run";

    /** The Formatted button beside Run, which shows a Markdown document formatted in place of its source. */
    static final String FORMATTED = "formatted";

    /** The console window's tab bar and its pages: one run each. The console is {@code window console}. */
    static final String CONSOLE_TABS = "console.tabs";

    /** The console's settings button, which says when finished runs close. */
    static final String CONSOLE_SETTINGS = "console.settings";

    private Landmarks() {
    }
}
