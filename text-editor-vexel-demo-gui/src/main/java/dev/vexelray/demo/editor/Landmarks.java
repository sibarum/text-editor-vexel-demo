package dev.vexelray.demo.editor;

/**
 * Every name an automation script may write down, in one place.
 *
 * <p><b>A ref is minted per run; a landmark still means something tomorrow.</b> That is the whole distinction
 * (vexelray-gui/docs/automation.md 5), and it is why these are constants rather than string literals scattered
 * through the view: a landmark is a published contract, so renaming one breaks a script somebody else wrote, and
 * the compiler should be the thing that notices.
 *
 * <p>Where you can, carry <em>state in the accessible name</em>. {@code await <landmark> <text>} is how a driver
 * waits for the application to be ready, and it needs no hook when readiness is declared somewhere the read-model
 * can already see. {@link #STATUS_FILE} works that way: its name is the front tab's title, dot and all, so
 * {@code await status.file notes.md} waits for a file to open and {@code await status.file •} for it to go
 * unsaved.
 */
final class Landmarks {

    /** The tab bar and its pages. */
    static final String TABS = "tabs";

    /** What the editor area shows when no file is open. */
    static final String EMPTY = "empty";

    /** The navigator's path bar: type a folder to show it, a file to open it, a new name to create it. */
    static final String PATH = "path";

    /** The navigator's tree. */
    static final String TREE = "navigator";

    /** The chip saying the folder shown, which switches the navigator to Open and back. */
    static final String FOLDER = "folder";

    /** Open's recent folders, its way up, and its tree of folders. */
    static final String RECENT = "recent";
    static final String ANCESTRY = "ancestry";
    static final String PICKER = "picker";

    /** Open's button that shows the scratches' folder; there once there is at least one scratch. */
    static final String OPEN_SCRATCHES = "open-scratches";

    /** The scratches' tree, laid over the navigator's by Alt+N, and its New scratch button. */
    static final String SCRATCHES = "scratches";
    static final String NEW_SCRATCH = "new-scratch";

    /** The name field Rename… puts above the scratches' tree. */
    static final String RENAME_SCRATCH = "rename-scratch";

    /** Open's picture of a folder, where the editor stands, and its list of the folder's files. */
    static final String PREVIEW = "preview";
    static final String CONTENTS = "preview.files";

    /** The status line, and its five slots. */
    static final String STATUS = "status";
    static final String STATUS_FILE = "status.file";
    static final String STATUS_MESSAGE = "status.message";
    /** What is wrong with the file in front, as checking it found (Pontif); empty when nothing is. */
    static final String STATUS_PROBLEM = "status.problem";
    static final String STATUS_LANGUAGE = "status.language";
    static final String STATUS_POSITION = "status.position";

    /** The application's New menu in the title bar ({@code TitleBar.menuLandmark}); its items are {@code menu.<label>}. */
    static final String NEW_MENU = "titlebar-menu.new";

    /** The application's View menu in the title bar ({@code TitleBar.menuLandmark}); its items are {@code menu.<label>}. */
    static final String VIEW_MENU = "titlebar-menu.view";

    /** The application's Run menu in the title bar ({@code TitleBar.menuLandmark}); its items are {@code menu.<label>}. */
    static final String RUN_MENU = "titlebar-menu.run";

    /** The console window's tab bar, one tab per run ({@code ottermate --window console}). */
    static final String CONSOLE_TABS = "console.tabs";

    /** Run…'s window ({@code ottermate --window run}): its fields and its Run button. */
    static final String RUN_COMMAND = "run.command";
    static final String RUN_DIR = "run.dir";
    static final String RUN_ENVIRONMENT = "run.environment";
    static final String RUN_PATH = "run.path";
    static final String RUN_GO = "run.go";

    private Landmarks() {
    }
}
