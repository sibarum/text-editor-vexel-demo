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

    /** The name field Add opens over the root's tree: Enter makes what is being added, Escape puts it away. */
    static final String NAME = "navigator.name";

    /**
     * The chip beside the root's name, inside a repository: the branch and its distance ({@code main ↑2}). Pressed,
     * the navigator is in Git mode (Ctrl+Shift+G); released, back in Edit.
     */
    static final String GIT = "navigator.git";

    /** Git mode's branch, at the top of its card. */
    static final String GIT_BRANCH = "git.branch";

    /** Git mode's Refresh, which asks git again. */
    static final String GIT_REFRESH = "git.refresh";

    /**
     * Git mode's list of what is changed and not staged, untracked and conflicted. A row picked shows its diff on the
     * stage; Enter or a double click opens its file.
     */
    static final String GIT_UNSTAGED = "git.unstaged";

    /** Git mode's list of what is staged, picked and opened as {@link #GIT_UNSTAGED}'s rows are. */
    static final String GIT_STAGED = "git.staged";

    /** Git mode's Stage all, beside Unstaged's heading: everything under the root, its tabs saved first. */
    static final String GIT_STAGE_ALL = "git.stage-all";

    /** Git mode's Unstage all, beside Staged's heading. */
    static final String GIT_UNSTAGE_ALL = "git.unstage-all";

    /** Git mode's commit message, below Staged: Ctrl+Enter in it commits. */
    static final String GIT_MESSAGE = "git.message";

    /** How long the message's first line is, {@code 12 / 72}: past 72 it is shown as too long, and still allowed. */
    static final String GIT_COUNT = "git.count";

    /** Git mode's Commit: enabled with something staged and a message. */
    static final String GIT_COMMIT = "git.commit";

    /** The diff's Stage or Unstage: the row picked, moved to the other side. */
    static final String DIFF_STAGE = "stage.stage";

    /** What stands where the editor does while a Git mode row is picked: its diff. */
    static final String STAGE = "stage";

    /** The stage's file name, over the diff. */
    static final String DIFF_FILE = "stage.file";

    /** The diff's lines: Enter or a double click on one opens the file there; Escape closes the stage. */
    static final String DIFF = "stage.diff";

    /** The stage's Open file, which opens it at its first line. */
    static final String DIFF_OPEN = "stage.open";

    /** The stage's Close: the editor comes back. */
    static final String DIFF_CLOSE = "stage.close";

    /** What stands where the editor does in Git mode while no row is picked: the history and the branches. */
    static final String HISTORY = "history";

    /** The history's changesets, newest first, from {@code HEAD}. */
    static final String HISTORY_LOG = "history.log";

    /** Every branch, local then remote, with how far each stands from what it tracks. */
    static final String HISTORY_BRANCHES = "history.branches";

    /** The history's Close: the editor comes back, in Git mode still. */
    static final String HISTORY_CLOSE = "history.close";

    /** Git mode's History, beside Refresh: the history comes back where the editor stands. */
    static final String GIT_HISTORY = "git.history";

    /** The status line, and its five slots. The slot keys are landmarks too, so a script can read each. */
    static final String STATUS = "status";
    /** Where the root stands in git — {@code main ↑2 · 3 changed} — inside a repository; empty otherwise. */
    static final String STATUS_GIT = "status.git";
    static final String STATUS_MESSAGE = "status.message";
    /** The declaration the caret is in — {@code Outer › method} — in a Java document; empty otherwise. */
    static final String STATUS_SCOPE = "status.scope";
    static final String STATUS_LANGUAGE = "status.language";
    static final String STATUS_POSITION = "status.position";

    /** The Run button over the tabs, which opens the menu of what can be run here. */
    static final String RUN = "run";

    /** The Formatted button beside Run, which shows a Markdown document formatted in place of its source. */
    static final String FORMATTED = "formatted";

    /** The preferences button in the main window's title bar: theme, word wrap, indexing, and where they are kept. */
    static final String PREFERENCES = "preferences";

    /** The console window's tab bar and its pages: one run each. The console is {@code window console}. */
    static final String CONSOLE_TABS = "console.tabs";

    /** The console's settings button, which says when finished runs close. */
    static final String CONSOLE_SETTINGS = "console.settings";

    private Landmarks() {
    }
}
