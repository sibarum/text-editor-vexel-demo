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

    /** The navigator's heading. Its name is the folder's. */
    static final String FOLDER = "folder";

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
