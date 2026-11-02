package dev.vexelray.demo.editor.core;

import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Every message the application's parts send each other: what each one says, and the topic it travels on.
 *
 * <h2>Message passing is how the parts talk</h2>
 *
 * <p>The application is three kinds of part, and none of them holds another. The {@link Editor} decides; it owns the
 * session, and changes it only by running its own declared steps. The view, in the {@code -gui} module, shows; it
 * owns the widgets. The jobs ({@link ReadFile}, {@link WriteFile}, {@link FindPath}, {@link CreateFile},
 * {@link ReadSession}, {@link WriteSession}, {@link ListFolder}, {@link MarkText}, {@link SurveyFolder},
 * {@link MakeScratch}, {@link DropScratch}, {@link FindScratches}) touch the
 * disk or do slow work, on the job lane. Each sends a record on a topic and the others hear it on their own lane, so no state is shared
 * and nothing needs a lock.
 *
 * <p>A record here is what crossed, and it crosses as itself: the processor refuses a payload that is not deeply
 * immutable (T2.4). A list may cross when what it holds may, and the list itself must not change after it is sent,
 * which no type can show: so each record that holds one copies it in its constructor, and no sender can get that
 * wrong.
 *
 * <h2>Reading this file</h2>
 *
 * <p>Grouped by direction, each record beside its topic. A channel is its record's type: each topic is named by its
 * payload ({@code Topic.edge(Open.class)}), and every part refers to it by the type, a {@code @Subscribe} method's
 * parameter, a {@code Sender<Open>}, a job's {@code run}, a class literal in {@code @Hears}. There is no topic name
 * anywhere to misspell, and a record with no topic here is a compile error wherever it is heard or sent (T4.1).
 */
public final class Messages {

    private Messages() {
    }

    /** The id of no tab: nothing is in front. */
    public static final long NO_TAB = -1;

    // ------------------------------------------------------------------ the view asks the editor

    /** The path bar was submitted, or the navigator asked to show a folder: what the text names is the editor's call. */
    public static final Topic<Go> GO_TOPIC = Topic.edge(Go.class);

    public record Go(String text) {
    }

    /**
     * The path bar was submitted to make something its extension does not say: a project, a module, an archive or a
     * link, which a row's menu put the bar in mind of. {@code from} is the folder an archive holds or what a link
     * points at.
     */
    public static final Topic<Add> ADD_TOPIC = Topic.edge(Add.class);

    public record Add(String text, Made what, Optional<Path> from) {
    }

    /** A file was chosen in the navigator. {@code focus}: whether its tab takes the keyboard. */
    public static final Topic<Open> OPEN_TOPIC = Topic.edge(Open.class);

    public record Open(Path path, boolean focus) {
    }

    /** A different tab is in front. */
    public static final Topic<Fronted> FRONTED_TOPIC = Topic.edge(Fronted.class);

    public record Fronted(long id) {
    }

    /** A tab's text went unsaved, or came back to what is on disk. */
    public static final Topic<Edited> EDITED_TOPIC = Topic.edge(Edited.class);

    public record Edited(long id, boolean dirty) {
    }

    /**
     * Close these tabs: one, the others, or all of them. The editor asks first if any is unsaved. Whether one is
     * unsaved is the editor's to know, from the {@link Edited}s the view sent before this, which arrive first because
     * one sender's messages to one receiver keep their order.
     */
    public static final Topic<Close> CLOSE_TOPIC = Topic.edge(Close.class);

    public record Close(List<Long> tabs) {
        public Close {
            tabs = List.copyOf(tabs);
        }
    }

    /**
     * A tab's text, to be written: after Ctrl+S, or because the editor asked with {@link Flush}. The view has already
     * marked the text saved at this point, so typing that lands while it is written stays unsaved. {@code open} is
     * false when the tab had gone by the time it was asked for.
     */
    public static final Topic<Save> SAVE_TOPIC = Topic.edge(Save.class);

    public record Save(long id, String text, boolean open) {
    }

    /**
     * Ctrl+Shift+N: a new, empty scratch ({@link MakeScratch}), in {@code in} if that is among the scratches, else in
     * the scratches' own folder. The job answers the editor, which opens it.
     */
    public static final Topic<NewScratch> NEW_SCRATCH_TOPIC = Topic.edge(NewScratch.class);

    public record NewScratch(Optional<Path> in) {
    }

    // ------------------------------------------------------------------ the editor tells the view

    /**
     * A window on files alone ({@code vex notes.md}): the navigator out of the layout, so the editor has the whole
     * window, until a folder is shown.
     */
    public static final Topic<Plain> PLAIN_TOPIC = Topic.edge(Plain.class);

    public record Plain() {
    }

    /** The folders shown before the one shown now, newest first: what the navigator's Open offers as Recent. */
    public static final Topic<Recent> RECENT_TOPIC = Topic.edge(Recent.class);

    public record Recent(List<Path> folders) {
        public Recent {
            folders = List.copyOf(folders);
        }
    }

    /** Point the navigator at a folder, or list it again. */
    public static final Topic<ShowFolder> SHOW_FOLDER_TOPIC = Topic.edge(ShowFolder.class);

    public record ShowFolder(Path path) {
    }

    /** Open a tab for a file the editor has read, in front. */
    public static final Topic<AddTab> ADD_TAB_TOPIC = Topic.edge(AddTab.class);

    public record AddTab(long id, Path path, String text, boolean focus) {
    }

    /** Bring a tab that is already open to the front, and ring it so the eye finds it. */
    public static final Topic<FrontTab> FRONT_TAB_TOPIC = Topic.edge(FrontTab.class);

    public record FrontTab(long id, boolean focus) {
    }

    /**
     * Close these tabs; the editor has asked whatever had to be asked. One message for a whole closing, so closing a
     * hundred tabs is one delivery, not a hundred waiting in the view's mailbox.
     */
    public static final Topic<CloseTabs> CLOSE_TABS_TOPIC = Topic.edge(CloseTabs.class);

    public record CloseTabs(List<Long> tabs) {
        public CloseTabs {
            tabs = List.copyOf(tabs);
        }
    }

    /**
     * What a tab's header says: its file's name, with a bullet while it is unsaved. State, one per tab: only what a
     * tab says now matters, so the view is handed the last title per tab in a tick and never a backlog of them.
     */
    public static final Topic<Retitle> RETITLE_TOPIC = Topic.state(Retitle.class, Retitle::id);

    public record Retitle(long id, String title) {
    }

    /** The front tab's title, for the title bar and the status line; empty when nothing is open. */
    public static final Topic<Caption> CAPTION_TOPIC = Topic.edge(Caption.class);

    public record Caption(String file) {
    }

    /** Say something on the status line. {@code alert}: something was refused or failed, so draw the eye to it. */
    public static final Topic<Status> STATUS_TOPIC = Topic.edge(Status.class);

    public record Status(String message, boolean alert) {
    }

    /**
     * Send the text of each of these tabs back as a {@link Save}: the editor is saving them before closing or
     * quitting. One message however many are unsaved.
     */
    public static final Topic<Flush> FLUSH_TOPIC = Topic.edge(Flush.class);

    public record Flush(List<Long> tabs) {
        public Flush {
            tabs = List.copyOf(tabs);
        }
    }

    /** The write a {@link Save} asked for did not land, so the tab is unsaved whatever its history says. */
    public static final Topic<SaveFailed> SAVE_FAILED_TOPIC = Topic.edge(SaveFailed.class);

    public record SaveFailed(long id) {
    }

    // ------------------------------------------------------------------ the editor and the jobs

    /** Why a file is being read or a path looked at, carried through the job so its answer can be acted on. */
    public enum Why {
        /** Chosen in the navigator, or found already open. */
        OPEN,
        /** Typed into the path bar. */
        GO,
        /** Typed into the path bar ending in a separator: a folder, made if it is not there. */
        GO_FOLDER,
        /** The folder the last session showed. */
        RESTORE_FOLDER,
        /** A file the last session had open, or one the command line named: one at a time, in order. */
        RESTORE_FILE,
        /** A path on the command line, which may be a folder or a file. */
        ARGUMENT
    }

    /** Read a file and decide whether it can be opened ({@link ReadFile}). */
    public static final Topic<Read> READ_TOPIC = Topic.edge(Read.class);

    public record Read(Path path, Why why, boolean focus) {
    }

    /** What {@link Read} found: the text, or why not ({@code problem}, empty when there is none). */
    public static final Topic<FileRead> FILE_READ_TOPIC = Topic.edge(FileRead.class);

    public record FileRead(Path path, Why why, boolean focus, String text, boolean crlf, String notes, String problem) {
    }

    /** Write a file, with its own line endings ({@link WriteFile}). */
    public static final Topic<Write> WRITE_TOPIC = Topic.edge(Write.class);

    public record Write(long id, Path path, String text, boolean crlf) {
    }

    /** What {@link Write} did: {@code problem} is empty when it landed. */
    public static final Topic<Written> WRITTEN_TOPIC = Topic.edge(Written.class);

    public record Written(long id, Path path, String problem) {
    }

    /** What is at a path, as far as opening it goes ({@link FindPath}). */
    public enum Kind {
        FOLDER, FILE,
        /** Nothing, in a folder that is there: a name a file can be created under. */
        NEW,
        /** Nothing, and no folder to make it in. */
        NOWHERE
    }

    /** Look at what is at a path. */
    public static final Topic<Find> FIND_TOPIC = Topic.edge(Find.class);

    public record Find(Path path, Why why) {
    }

    public static final Topic<Found> FOUND_TOPIC = Topic.edge(Found.class);

    public record Found(Path path, Why why, Kind kind) {
    }

    /** What a new thing on the disk is. */
    public enum Made {
        FILE, FOLDER,
        /** A Maven project with nothing in it yet. */
        PROJECT,
        /** A Maven module of the project around it. */
        MODULE,
        /** A zip of a folder. */
        ARCHIVE,
        /** A symbolic link. */
        LINK
    }

    /**
     * Make {@code what} at {@code path} ({@link CreateFile}): a file from the template its name asks for, or one of the
     * others. {@code from} is the folder an archive holds or what a link points at, and empty for the rest.
     */
    public static final Topic<Create> CREATE_TOPIC = Topic.edge(Create.class);

    public record Create(Path path, Made what, Optional<Path> from) {
        public Create(Path path, Made what) {
            this(path, what, Optional.empty());
        }
    }

    /**
     * What {@link Create} did: for a file, the text it was made with and its line endings, which are the platform's
     * unless the template wants its own. {@code note}: anything worth saying about what was made, as "214 files".
     */
    public static final Topic<Created> CREATED_TOPIC = Topic.edge(Created.class);

    public record Created(Path path, Made what, String text, boolean crlf, String note, String problem) {
    }

    /**
     * What {@link NewScratch} made: the new, empty scratch, with the line endings a save gives it, or why not
     * ({@code problem}, and {@code path} the scratches' folder).
     */
    public static final Topic<ScratchMade> SCRATCH_MADE_TOPIC = Topic.edge(ScratchMade.class);

    public record ScratchMade(Path path, boolean crlf, String problem) {
    }

    /** A scratch this run made, closed with nothing ever written to it: it goes again ({@link DropScratch}). */
    public static final Topic<Dropping> DROPPING_TOPIC = Topic.edge(Dropping.class);

    public record Dropping(Path path) {
    }

    /** Read back what the last session remembered, and what the command line named ({@link ReadSession}). */
    public static final Topic<Recall> RECALL_TOPIC = Topic.edge(Recall.class);

    public record Recall() {
    }

    /**
     * What there is to bring back, every path absolute and normalised: the folder shown, the files open in tab order,
     * the one in front, the recent folders that are still there, the command line's paths, and the folder the
     * application started in, for when there is none.
     */
    public static final Topic<Recalled> RECALLED_TOPIC = Topic.edge(Recalled.class);

    public record Recalled(Optional<Path> folder, List<Path> files, Optional<Path> front, List<Path> recent,
                           List<Path> arguments, Path started) {
        public Recalled {
            files = List.copyOf(files);
            recent = List.copyOf(recent);
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * Keep the session for next time ({@link WriteSession}): the folder shown, the files open, the one in front, the
     * folders shown before. A value, so the editor tells whether it changed with {@code equals}.
     */
    public static final Topic<Remember> REMEMBER_TOPIC = Topic.edge(Remember.class);

    public record Remember(Optional<Path> folder, List<Path> files, Optional<Path> front, List<Path> recent) {
        public Remember {
            files = List.copyOf(files);
            recent = List.copyOf(recent);
        }
    }

    // ------------------------------------------------------------------ the view and its jobs

    /** List one level of the navigator's tree ({@link ListFolder}): the folder shown's, or a folder in it. */
    public static final Topic<Listing> LISTING_TOPIC = Topic.edge(Listing.class);

    /**
     * {@code roots}: the level is the tree's top, the folder shown, rather than a folder opened in it.
     * {@code foldersOnly}: Open's tree, which lists the folders a folder can be picked from and no files.
     */
    public record Listing(String tree, Path dir, boolean roots, boolean foldersOnly) {
    }

    /**
     * One entry of a level: where it is, what its row says, and whether it opens. {@code inProject}: a Maven project is
     * at or above it, so what is added in it is a module of that project rather than a project of its own.
     */
    public record Item(Path path, String name, boolean folder, boolean inProject) {
    }

    public static final Topic<Listed> LISTED_TOPIC = Topic.edge(Listed.class);

    public record Listed(String tree, Path dir, boolean roots, List<Item> items) {
        public Listed {
            items = List.copyOf(items);
        }
    }

    /** Whether there is a scratch, for Open's Scratches button ({@link FindScratches}): asked each time Open comes up. */
    public static final Topic<ScratchesWanted> SCRATCHES_WANTED_TOPIC = Topic.edge(ScratchesWanted.class);

    public record ScratchesWanted() {
    }

    public static final Topic<ScratchesFound> SCRATCHES_FOUND_TOPIC = Topic.edge(ScratchesFound.class);

    public record ScratchesFound(boolean any) {
    }

    /** Picture a folder for Open ({@link SurveyFolder}): the one under the pointer, or selected. */
    public static final Topic<Picture> PICTURE_TOPIC = Topic.edge(Picture.class);

    public record Picture(Path folder) {
    }

    /** What {@link Picture} found: the survey, and the folder's files when it has no build to describe. */
    public static final Topic<Pictured> PICTURED_TOPIC = Topic.edge(Pictured.class);

    public record Pictured(Path folder, Survey survey, Optional<Contents> contents) {
    }

    /**
     * Colour a document ({@link MarkText}): its text as of {@code version}, in the grammar for {@code scope}
     * ({@link Grammars#scopeFor}). The colours are the {@link Grammars}' own, so the request is only what changes.
     */
    public static final Topic<Mark> MARK_TOPIC = Topic.edge(Mark.class);

    public record Mark(String field, String text, long version, String scope) {
    }

    public static final Topic<Marked> MARKED_TOPIC = Topic.edge(Marked.class);

    public record Marked(String field, long version, List<dev.vexelray.gui.core.text.Span> spans) {
        public Marked {
            spans = List.copyOf(spans);
        }
    }

    /**
     * Read a Markdown document as blocks for its formatted view ({@link FormatText}): its text as of {@code version}.
     * Asked when the view is switched on, and again when the text changes under it.
     */
    public static final Topic<Format> FORMAT_TOPIC = Topic.edge(Format.class);

    public record Format(String field, String text, long version) {
    }

    public static final Topic<Formatted> FORMATTED_TOPIC = Topic.edge(Formatted.class);

    public record Formatted(String field, long version, List<Markdown.Block> blocks) {
        public Formatted {
            blocks = List.copyOf(blocks);
        }
    }
}
