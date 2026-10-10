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

    /** Open's recent roots, one button each. */
    static final String RECENT = "navigator.recent";

    /** The status line, and its four slots. The slot keys are landmarks too, so a script can read each. */
    static final String STATUS = "status";
    static final String STATUS_MESSAGE = "status.message";
    /** The declaration the caret is in — {@code Outer › method} — in a Java document; empty otherwise. */
    static final String STATUS_SCOPE = "status.scope";
    static final String STATUS_LANGUAGE = "status.language";
    static final String STATUS_POSITION = "status.position";

    private Landmarks() {
    }
}
