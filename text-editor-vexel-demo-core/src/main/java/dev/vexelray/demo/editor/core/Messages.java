package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Diff;
import sibarum.atchung.Topic;

import java.nio.file.Path;

/**
 * Every message the editor's parts send each other: what each one says, and the topic it travels on.
 *
 * <h2>Message passing is how the parts talk</h2>
 *
 * <p>The editor is three kinds of part, and none of them holds another. The {@link Editor} decides; it runs on a lane
 * of its own and owns the session. The {@link Repository} decides what git is asked and when; it too has a lane of
 * its own. The view, in {@code -gui}, shows; it runs on a lane of its own and owns the widgets. The jobs
 * ({@link ReadFile}, {@link WriteFile}, {@link FindPath}, {@link MakeScratch}, {@link NameScratch},
 * {@link DropScratch}, {@link Make}, {@link ReadStatus}) touch the disk or run git, on the offload lane. Each sends a record on a topic and the
 * others hear it on their own thread, so no state is shared and nothing needs a lock.
 *
 * <p>A record here is what crossed, and it crosses as itself: the processor refuses a payload that is not deeply
 * immutable (T2.4). That is why nothing here holds a list. A list cannot cross until the framework can copy one, so
 * "close these five tabs" is five {@link Closing} messages that say how many make the set, the restored session is
 * one {@link Read} per file, and the recent roots are three fields.
 *
 * <h2>Reading this file</h2>
 *
 * <p>Grouped by direction, each record beside the name of its topic. A topic name is a string the processor checks
 * against every sender and receiver it can see (T4.1, T4.4): two topics with one spelling and two types are a compile
 * error. The {@code Topic} constants are what a sender publishes on.
 */
public final class Messages {

    private Messages() {
    }

    /** The id of no tab: nothing is in front, a quit with nothing open, or an add that replaces nothing. */
    public static final long NO_TAB = -1;

    // ------------------------------------------------------------------ the view asks the editor

    /** A file was chosen: in the navigator, the open dialog, or as a declaration gone to. Its tab takes the keyboard if {@code focus}. */
    public static final String OPEN = "editor.open";
    public static final Topic<Open> OPEN_TOPIC = Topic.of(OPEN, Open.class);

    public record Open(Path path, boolean focus) {
    }

    /** Ctrl+N: a new Untitled document. */
    public static final String NEW_FILE = "editor.new-file";
    public static final Topic<NewFile> NEW_FILE_TOPIC = Topic.of(NEW_FILE, NewFile.class);

    public record NewFile() {
    }

    /** A library's source, read by the view to go to a declaration in it: a read-only tab under {@code name}. */
    public static final String OPEN_TEXT = "editor.open-text";
    public static final Topic<OpenText> OPEN_TEXT_TOPIC = Topic.of(OPEN_TEXT, OpenText.class);

    /** {@code ticket} comes back on the {@link AddTab}, so the view can tell which of its own requests it answers. */
    public record OpenText(long ticket, String name, String text, boolean crlf) {
    }

    /** A different tab is in front. */
    public static final String FRONTED = "editor.fronted";
    public static final Topic<Fronted> FRONTED_TOPIC = Topic.of(FRONTED, Fronted.class);

    public record Fronted(long id) {
    }

    /** A tab's text went unsaved, or came back to what is on disk. */
    public static final String EDITED = "editor.edited";
    public static final Topic<Edited> EDITED_TOPIC = Topic.of(EDITED, Edited.class);

    public record Edited(long id, boolean dirty) {
    }

    /** An Untitled tab the editor asked to be put away if still empty ({@link AddTab#replace}) was. */
    public static final String DROPPED = "editor.dropped";
    public static final Topic<Dropped> DROPPED_TOPIC = Topic.of(DROPPED, Dropped.class);

    public record Dropped(long id) {
    }

    /** What a set of {@link Closing} messages is for. */
    public enum Purpose {
        /** Closing tabs: one, the others, or all of them. */
        TABS,
        /** The window is being asked to close; the batch is the close gate's ticket. */
        QUIT,
        /** About to go somewhere a root is picked: unsaved work is saved or put back first, or the way is not taken. */
        SETTLE,
        /** The root is changing to {@link Closing#folder}: the tabs are put away as the old root's. */
        ROOT,
        /** Something is about to run: every file with unsaved work is saved first, so what runs is what is shown. */
        RUN,
        /**
         * Something is about to be staged: the tabs it covers are saved first, as for {@link #RUN}, since git stages
         * what is on the disk and the person means what is on the screen.
         */
        STAGE
    }

    /**
     * One tab of a set: being closed, quit, settled, switched away from or saved before a run. A set is {@code of}
     * messages with one {@code batch}; they may arrive in any order, and the editor decides once it has them all.
     * {@code dirty} is the view's word on the tab at the moment it was asked, because an {@link Edited} sent just before
     * may still be on its way, and a tab must never be closed unasked because the news that it changed arrived second.
     * A set with no tab in it is one message whose {@code id} is {@link #NO_TAB}. {@code folder} is the new root, for
     * {@link Purpose#ROOT}, and null otherwise.
     */
    public static final String CLOSING = "editor.closing";
    public static final Topic<Closing> CLOSING_TOPIC = Topic.of(CLOSING, Closing.class);

    public record Closing(long batch, int of, long id, boolean dirty, Purpose purpose, Path folder) {
    }

    /**
     * A tab's text, to be written: after Ctrl+S, or because the editor asked with {@link Flush}. The view has already
     * marked the text saved at this point, so typing that lands while it is written stays unsaved. {@code open} is
     * false when the tab had gone by the time it was asked for.
     */
    public static final String SAVE = "editor.save";
    public static final Topic<Save> SAVE_TOPIC = Topic.of(SAVE, Save.class);

    public record Save(long id, String text, boolean open) {
    }

    /** Ctrl+Shift+S: save a tab somewhere the person picks. */
    public static final String SAVE_AS = "editor.save-as";
    public static final Topic<SaveAs> SAVE_AS_TOPIC = Topic.of(SAVE_AS, SaveAs.class);

    public record SaveAs(long id) {
    }

    /** How a question was answered: its first button, its second, its third, or cancel. */
    public enum Choice {
        CONFIRM, OTHER, EXTRA, CANCEL
    }

    /** The answer to an {@link Ask}. */
    public static final String ANSWER = "editor.answer";
    public static final Topic<Answer> ANSWER_TOPIC = Topic.of(ANSWER, Answer.class);

    public record Answer(long question, Choice choice) {
    }

    /** Where a file is to be saved, the answer to an {@link AskWhere}; null if the dialog was cancelled. */
    public static final String PLACED = "editor.placed";
    public static final Topic<Placed> PLACED_TOPIC = Topic.of(PLACED, Placed.class);

    public record Placed(long question, Path path) {
    }

    /** The window exists: bring back last time's session and open what the command line named. */
    public static final String BEGIN = "editor.begin";
    public static final Topic<Begin> BEGIN_TOPIC = Topic.of(BEGIN, Begin.class);

    public record Begin() {
    }

    /** A scratch or a folder of them was moved or renamed in the navigator: every tab open on it follows. */
    public static final String RELOCATED = "editor.relocated";
    public static final Topic<Relocated> RELOCATED_TOPIC = Topic.of(RELOCATED, Relocated.class);

    public record Relocated(Path from, Path to) {
    }

    /** Ctrl+Shift+N, or the scratches' own New: a new scratch in {@code folder}, or in the scratches for null. */
    public static final String NEW_SCRATCH = "editor.new-scratch";
    public static final Topic<NewScratch> NEW_SCRATCH_TOPIC = Topic.of(NEW_SCRATCH, NewScratch.class);

    public record NewScratch(Path folder) {
    }

    /** What the navigator's Add makes. */
    public enum Made {
        FOLDER, FILE, SYMLINK, PROJECT, SUBMODULE, ARCHIVE
    }

    /** What a new file starts as: its extension, and what is in it before anything is typed. */
    public enum Template {
        BLANK, TEXT, JAVA, MARKDOWN, POWERSHELL, BASH, ZSH
    }

    /**
     * The navigator's Add, named: make {@code what} called {@code name} in {@code folder}. {@code template} says what a
     * file starts as (null for anything else), and {@code target} what a link points at (null for anything else).
     */
    public static final String ADD = "editor.add";
    public static final Topic<Add> ADD_TOPIC = Topic.of(ADD, Add.class);

    public record Add(Path folder, Made what, Template template, String name, Path target) {
    }

    // ------------------------------------------------------------------ the editor tells the view

    /** Point the navigator at a folder, which is the root now: its preferences, its index, its tree. */
    public static final String SHOW_FOLDER = "view.show-folder";
    public static final Topic<ShowFolder> SHOW_FOLDER_TOPIC = Topic.of(SHOW_FOLDER, ShowFolder.class);

    public record ShowFolder(Path path) {
    }

    /** The roots Open offers as recent, newest first, the root itself and the scratches left out; null where there are fewer. */
    public static final String RECENT = "view.recent";
    public static final Topic<Recent> RECENT_TOPIC = Topic.of(RECENT, Recent.class);

    public record Recent(Path first, Path second, Path third) {
    }

    /**
     * Open a tab, in front. {@code path} is null for an Untitled document or a library's source, which then has a
     * {@code library} name ("" otherwise). {@code replace} is an Untitled tab to put away if it is still empty, or
     * {@link #NO_TAB}, and {@code ticket} the view's own {@link OpenText}, or 0.
     */
    public static final String ADD_TAB = "view.add-tab";
    public static final Topic<AddTab> ADD_TAB_TOPIC = Topic.of(ADD_TAB, AddTab.class);

    public record AddTab(long id, Path path, String text, boolean crlf, boolean focus, long replace, String library,
                         long ticket) {
    }

    /** Bring a tab that is already open to the front, and ring it so the eye finds it. */
    public static final String FRONT_TAB = "view.front-tab";
    public static final Topic<FrontTab> FRONT_TAB_TOPIC = Topic.of(FRONT_TAB, FrontTab.class);

    public record FrontTab(long id, boolean focus) {
    }

    /** Close a tab; the editor has asked whatever had to be asked. */
    public static final String CLOSE_TAB = "view.close-tab";
    public static final Topic<CloseTab> CLOSE_TAB_TOPIC = Topic.of(CLOSE_TAB, CloseTab.class);

    public record CloseTab(long id) {
    }

    /** What a tab's header says: its file's name, with a bullet while it is unsaved. */
    public static final String RETITLE = "view.retitle";
    public static final Topic<Retitle> RETITLE_TOPIC = Topic.of(RETITLE, Retitle.class);

    public record Retitle(long id, String title) {
    }

    /** What the title bar says the window is open on: the root's name, else the tab in front's title. */
    public static final String CAPTION = "view.caption";
    public static final Topic<Caption> CAPTION_TOPIC = Topic.of(CAPTION, Caption.class);

    public record Caption(String text) {
    }

    /** Say something on the status line. {@code alert}: something was refused or failed, so draw the eye to it. */
    public static final String STATUS = "view.status";
    public static final Topic<Status> STATUS_TOPIC = Topic.of(STATUS, Status.class);

    public record Status(String message, boolean alert) {
    }

    /**
     * Ask the person: up to three buttons and Cancel. {@code other} and {@code extra} are empty for no such button. The
     * answer comes back as an {@link Answer} with this question.
     */
    public static final String ASK = "view.ask";
    public static final Topic<Ask> ASK_TOPIC = Topic.of(ASK, Ask.class);

    public record Ask(long question, String title, String message, String confirm, String other, String extra) {
    }

    /** Tell the person something that went wrong, with one button to say it was read. */
    public static final String TELL = "view.tell";
    public static final Topic<Tell> TELL_TOPIC = Topic.of(TELL, Tell.class);

    public record Tell(String title, String message) {
    }

    /** Ask where to save, starting in {@code dir} with {@code name}: the answer is a {@link Placed}. */
    public static final String ASK_WHERE = "view.ask-where";
    public static final Topic<AskWhere> ASK_WHERE_TOPIC = Topic.of(ASK_WHERE, AskWhere.class);

    public record AskWhere(long question, Path dir, String name) {
    }

    /** Send the text of a tab back as a {@link Save}: the editor is saving it before closing, quitting or running. */
    public static final String FLUSH = "view.flush";
    public static final Topic<Flush> FLUSH_TOPIC = Topic.of(FLUSH, Flush.class);

    public record Flush(long id) {
    }

    /** The write a {@link Save} asked for did not land, so the tab is unsaved whatever its history says. */
    public static final String SAVE_FAILED = "view.save-failed";
    public static final Topic<SaveFailed> SAVE_FAILED_TOPIC = Topic.of(SAVE_FAILED, SaveFailed.class);

    public record SaveFailed(long id) {
    }

    /** A tab was written to {@code path}, which is where it lives now: a Save As, or a first save. */
    public static final String SAVED = "view.saved";
    public static final Topic<Saved> SAVED_TOPIC = Topic.of(SAVED, Saved.class);

    public record Saved(long id, Path path) {
    }

    /** A tab's file moved, and the tab with it; unsaved work stays unsaved. */
    public static final String MOVED = "view.moved";
    public static final Topic<Moved> MOVED_TOPIC = Topic.of(MOVED, Moved.class);

    public record Moved(long id, Path path) {
    }

    /** Put a tab back as its file is on disk: its unsaved work was thrown away. */
    public static final String REVERT = "view.revert";
    public static final Topic<Revert> REVERT_TOPIC = Topic.of(REVERT, Revert.class);

    public record Revert(long id, String text, boolean crlf) {
    }

    /** What the editor decided for a set of {@link Closing}s that the view is waiting on: go on, or stay. */
    public static final String SETTLED = "view.settled";
    public static final Topic<Settled> SETTLED_TOPIC = Topic.of(SETTLED, Settled.class);

    public record Settled(long batch, Purpose purpose, boolean go) {
    }

    /** A new scratch: show it in the scratches tree, if the scratches are what the navigator is showing. */
    public static final String SCRATCH_SHOWN = "view.scratch-shown";
    public static final Topic<ScratchShown> SCRATCH_SHOWN_TOPIC = Topic.of(SCRATCH_SHOWN, ScratchShown.class);

    public record ScratchShown(Path path) {
    }

    /** Something was made or written the navigator has not listed: list it, and unfold the way to {@code reveal} if not null. */
    public static final String RELIST = "view.relist";
    public static final Topic<Relist> RELIST_TOPIC = Topic.of(RELIST, Relist.class);

    public record Relist(Path reveal) {
    }

    /** A window opened on files alone: the tabs, and no navigator beside them. */
    public static final String PLAIN = "view.plain";
    public static final Topic<Plain> PLAIN_TOPIC = Topic.of(PLAIN, Plain.class);

    public record Plain() {
    }

    /** A window with nothing to show: put the navigator in Open, where a root is picked. */
    public static final String CHOOSE_ROOT = "view.choose-root";
    public static final Topic<ChooseRoot> CHOOSE_ROOT_TOPIC = Topic.of(CHOOSE_ROOT, ChooseRoot.class);

    public record ChooseRoot() {
    }

    // ------------------------------------------------------------------ the editor and the jobs

    /** Why a file is being read or a path looked at, carried through the job so its answer can be acted on. */
    public enum Why {
        /** Chosen: in the navigator, a dialog, or a declaration gone to. */
        OPEN,
        /** The folder the last session showed. */
        RESTORE_FOLDER,
        /** A file the last session had open, or one the command line named: one at a time, in order. */
        RESTORE_FILE,
        /** A path on the command line, which may be a folder or a file. */
        ARGUMENT,
        /** A root the navigator's Open offered last time, still to be found there. */
        RECENT,
        /** A tab a root had when it was left, coming back with it: one at a time, in order, not taking the keyboard. */
        REOPEN,
        /** A file the navigator's Add has just made, opened to be written in: what the status line says is that it was made. */
        MADE,
        /** A tab's unsaved work thrown away: its file read again, to put it back. */
        REVERT
    }

    /** Read a file and decide whether it can be opened ({@link ReadFile}). {@code id} is the tab to revert, or {@link #NO_TAB}. */
    public static final String READ = "file.read";
    public static final Topic<Read> READ_TOPIC = Topic.of(READ, Read.class);

    public record Read(Path path, Why why, boolean focus, long id) {
    }

    /** What {@link Read} found: the text, or why not ({@code problem}, empty when there is none). */
    public static final String FILE_READ = "file.read.done";

    public record FileRead(Path path, Why why, boolean focus, long id, String text, boolean crlf, String notes,
                           String problem) {
    }

    /** Write a file, with its own line endings ({@link WriteFile}). */
    public static final String WRITE = "file.write";
    public static final Topic<Write> WRITE_TOPIC = Topic.of(WRITE, Write.class);

    public record Write(long id, Path path, String text, boolean crlf) {
    }

    /** What {@link Write} did: {@code problem} is empty when it landed. */
    public static final String WRITTEN = "file.written";

    public record Written(long id, Path path, String problem) {
    }

    /** What is at a path, as far as opening it goes ({@link FindPath}). */
    public enum Kind {
        FOLDER, FILE, NOTHING
    }

    /** Look at what is at a path. {@code index} tells several of one {@code why} apart: a recent root's place. */
    public static final String FIND = "path.find";
    public static final Topic<Find> FIND_TOPIC = Topic.of(FIND, Find.class);

    public record Find(Path path, Why why, int index) {
    }

    public static final String FOUND = "path.found";

    public record Found(Path path, Why why, int index, Kind kind) {
    }

    /** Make an empty scratch in {@code folder}, or in the scratches for null ({@link MakeScratch}). */
    public static final String MAKE_SCRATCH = "scratch.make";
    public static final Topic<NewScratch> MAKE_SCRATCH_TOPIC = Topic.of(MAKE_SCRATCH, NewScratch.class);

    public static final String SCRATCH_MADE = "scratch.made";

    public record ScratchMade(Path path, String problem) {
    }

    /** A free scratch name to save tab {@code id} under: Keep as scratch ({@link NameScratch}). */
    public static final String NAME_SCRATCH = "scratch.name";
    public static final Topic<ScratchFor> NAME_SCRATCH_TOPIC = Topic.of(NAME_SCRATCH, ScratchFor.class);

    public record ScratchFor(long id) {
    }

    public static final String SCRATCH_NAMED = "scratch.named";

    public record ScratchNamed(long id, Path path, String problem) {
    }

    /** A scratch made and closed with nothing written to it: it goes again ({@link DropScratch}). */
    public static final String DROP_SCRATCH = "scratch.drop";
    public static final Topic<Dropping> DROP_SCRATCH_TOPIC = Topic.of(DROP_SCRATCH, Dropping.class);

    public record Dropping(Path path) {
    }

    /** Make what an {@link Add} named ({@link Make}). */
    public static final String MAKE = "path.make";
    public static final Topic<Add> MAKE_TOPIC = Topic.of(MAKE, Add.class);

    /** What {@link Make} made: {@code path}, and anything worth saying about it ({@code note}), or why not. */
    public static final String MADE = "path.made";

    public record Created(Path path, Made what, String note, String problem) {
    }

    // ------------------------------------------------------------------ the repository, and git

    /**
     * Where the root stands in git, for the status line: the branch, how far from its upstream, and how much is
     * changed. {@link #NOT_A_REPOSITORY} when the root is not in one, git is not installed, or there is no root.
     *
     * @param label    what the status line says: {@code main ↑2 · 3 changed}; empty outside a repository
     * @param chip     what the navigator's way into Git mode says: {@code main ↑2}, the branch and its distance
     * @param branch   the branch checked out; the short commit when {@code HEAD} is detached
     * @param upstream the branch it tracks, {@code origin/main}; empty if none
     * @param staged   paths with something staged
     * @param unstaged paths changed and not staged, untracked or conflicted; an untracked folder counts once
     */
    public static final String GIT_HEAD = "view.git-head";
    public static final Topic<GitHead> GIT_HEAD_TOPIC = Topic.of(GIT_HEAD, GitHead.class);

    public record GitHead(boolean repository, String label, String chip, String branch, String upstream, int ahead,
                          int behind, int staged, int unstaged) {
    }

    public static final GitHead NOT_A_REPOSITORY = new GitHead(false, "", "", "", "", 0, 0, 0, 0);

    /**
     * Every changed path, for Git mode's lists. A list cannot cross (FN-24), and a message per path could fill the
     * view's mailbox, which ends the application, in a repository with thousands of changes. But the whole status is
     * one value, and it crosses as the text git wrote: the view reads it with the same parsers that read it here.
     *
     * @param root          the repository's top directory, which every path in the texts is relative to
     * @param status        {@code git status --porcelain=v2 -z}, as {@code Status.parse} reads it
     * @param unstagedLines lines added and removed per path not staged, untracked files counted too, as
     *                      {@code Numstat.parse} reads it
     * @param stagedLines   lines added and removed per path staged, as {@code Numstat.parse} reads it
     */
    public static final String GIT_TREE = "view.git-tree";
    public static final Topic<GitTree> GIT_TREE_TOPIC = Topic.of(GIT_TREE, GitTree.class);

    public record GitTree(Path root, String status, String unstagedLines, String stagedLines) {
    }

    // ------------------------------------------------------------------ the view asks the repository

    /** Git mode was entered ({@code on}) or left: while it is up, git is watched for changes made outside the editor. */
    public static final String GIT_WATCH = "git.watch";
    public static final Topic<GitWatch> GIT_WATCH_TOPIC = Topic.of(GIT_WATCH, GitWatch.class);

    public record GitWatch(boolean on) {
    }

    /** Git mode's Refresh: ask git again now. */
    public static final String GIT_REFRESH = "git.refresh";
    public static final Topic<GitRefresh> GIT_REFRESH_TOPIC = Topic.of(GIT_REFRESH, GitRefresh.class);

    public record GitRefresh() {
    }

    /**
     * Time to look whether git changed, every {@code Repository.WATCH} while Git mode is up. A sample: a tick missed
     * is the next one's to catch.
     */
    public static final String GIT_TICK = "git.tick";
    public static final Topic<GitTick> GIT_TICK_TOPIC = Topic.of(GIT_TICK, GitTick.class);

    public record GitTick() {
    }

    // ------------------------------------------------------------------ the repository and its jobs

    /**
     * Ask git where {@code folder} stands ({@link ReadStatus}). {@code generation} comes back on the answer, so the
     * {@link Repository} can tell an answer about a root it has since left from one about the root it has.
     */
    public static final String GIT_STATUS = "git.status";
    public static final Topic<GitAsk> GIT_STATUS_TOPIC = Topic.of(GIT_STATUS, GitAsk.class);

    public record GitAsk(long generation, Path folder) {
    }

    /**
     * What {@link ReadStatus} found: {@code repository} false, and the rest empty, outside one. {@code branch} is the
     * branch's name, or the short commit when {@code HEAD} is detached. The counts are of what is under the folder
     * asked about; {@code changed} counts each path once, though one staged and changed again is in both lists.
     * {@code root} and the three texts are what a {@link GitTree} carries; {@code gitDir} and {@code stamp} are what
     * the next {@link GitStampAsk} asks about and compares with.
     */
    public static final String GIT_STATUS_READ = "git.status-read";

    public record GitRead(long generation, boolean repository, String branch, String upstream, int ahead, int behind,
                          int staged, int unstaged, int changed, Path root, Path gitDir, String stamp,
                          String status, String unstagedLines, String stagedLines) {
    }

    /** Look at the files git itself changes ({@link ReadStamp}), to see whether it is worth asking git again. */
    public static final String GIT_STAMP = "git.stamp";
    public static final Topic<GitStampAsk> GIT_STAMP_TOPIC = Topic.of(GIT_STAMP, GitStampAsk.class);

    public record GitStampAsk(long generation, Path gitDir) {
    }

    /** What {@link ReadStamp} saw: equal to the last answer's {@code stamp} if git has done nothing since. */
    public static final String GIT_STAMPED = "git.stamped";

    public record GitStamp(long generation, String stamp) {
    }

    /** One file's diff, for the stage ({@link ReadDiff}). {@code ticket} tells the answer to the latest ask apart. */
    public static final String GIT_DIFF = "git.diff";
    public static final Topic<DiffAsk> GIT_DIFF_TOPIC = Topic.of(GIT_DIFF, DiffAsk.class);

    public record DiffAsk(long ticket, Path root, String path, Diff.Side side) {
    }

    /** What {@link ReadDiff} found: the diff as git wrote it, empty for no difference or a git that failed. */
    public static final String GIT_DIFF_READ = "git.diff-read";

    public record DiffRead(long ticket, String path, Diff.Side side, String text) {
    }

    // ------------------------------------------------------------------ diffs, between the view and the repository

    /**
     * Show {@code path}'s diff on {@code side} where the editor stands: a row of Git mode's lists was picked. A null
     * {@code path} is the diff put away, so it is no longer asked for again when the status changes.
     */
    public static final String SHOW_DIFF = "git.show-diff";
    public static final Topic<ShowDiff> SHOW_DIFF_TOPIC = Topic.of(SHOW_DIFF, ShowDiff.class);

    public record ShowDiff(String path, Diff.Side side) {
    }

    /**
     * A diff to show: {@code path} on {@code side}, relative to {@code root}, as git wrote it, read by the view with
     * {@code Diff.parse}. Sent for each {@link ShowDiff}, and again whenever the status changes while it is shown.
     */
    public static final String GIT_DIFF_SHOWN = "view.git-diff";
    public static final Topic<GitDiff> GIT_DIFF_SHOWN_TOPIC = Topic.of(GIT_DIFF_SHOWN, GitDiff.class);

    public record GitDiff(Path root, String path, Diff.Side side, String text) {
    }

    // ------------------------------------------------------------------ staging

    /**
     * Stage ({@code on}) or unstage {@code path}, relative to the repository's top; a null {@code path} is everything
     * under the root. The view sends it once the tabs it covers are saved ({@link Purpose#STAGE}).
     */
    public static final String STAGE = "git.stage";
    public static final Topic<Stage> STAGE_TOPIC = Topic.of(STAGE, Stage.class);

    public record Stage(String path, boolean on) {
    }

    /**
     * Commit what is staged, with {@code message} as it was typed: Git mode's Commit. The {@code Repository} decides
     * whether it can go ({@code Repository.committable}) and answers with {@link GitCommitted} either way.
     */
    public static final String COMMIT = "git.commit";
    public static final Topic<CommitStaged> COMMIT_TOPIC = Topic.of(COMMIT, CommitStaged.class);

    public record CommitStaged(String message) {
    }

    /**
     * How a commit went, for the message box: {@code ok}, it is emptied; not, it keeps what was typed, and the status
     * line says why. {@code subject} is the commit's first line.
     */
    public static final String GIT_COMMITTED = "view.git-committed";
    public static final Topic<GitCommitted> GIT_COMMITTED_TOPIC = Topic.of(GIT_COMMITTED, GitCommitted.class);

    public record GitCommitted(boolean ok, String subject) {
    }

    /** A write {@link RunGit} makes. */
    public enum GitOp {
        STAGE, UNSTAGE, COMMIT
    }

    /**
     * Run a write ({@link RunGit}) in {@code dir}: {@code path} relative to it, or {@code "."} for everything under it;
     * a commit has no path, and has its {@code message}. One at a time, so two never meet at git's
     * {@code index.lock}.
     */
    public static final String GIT_RUN = "git.run";
    public static final Topic<GitRun> GIT_RUN_TOPIC = Topic.of(GIT_RUN, GitRun.class);

    public record GitRun(long generation, Path dir, GitOp op, String path, String message) {

        /** A stage or an unstage, which has no message. */
        public GitRun(long generation, Path dir, GitOp op, String path) {
            this(generation, dir, op, path, null);
        }
    }

    /**
     * How a write went: {@code ok}, and if not, what git said ({@code error}). {@code command} is the command line, for
     * whatever shows the user what git did.
     */
    public static final String GIT_RAN = "git.ran";

    public record GitRan(long generation, GitOp op, String path, boolean ok, String command, String error) {
    }
}
