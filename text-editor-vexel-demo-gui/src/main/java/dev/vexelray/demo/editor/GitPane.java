package dev.vexelray.demo.editor;

import dev.sibarum.suite.git.Numstat;
import dev.sibarum.suite.git.Status;
import dev.vexelray.demo.editor.core.Messages.GitCommitted;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitTree;
import dev.vexelray.demo.editor.core.Repository;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.SelectionModel;
import dev.vexelray.gui.widget.TextField;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Git mode's card: the branch, then what is changed and not staged, then what is staged, each path once per side it
 * has changed on, with its letter and how many lines it adds and removes.
 *
 * <p>It shows what it is given and keeps nothing else. The {@code Repository} decides when git is asked; what comes
 * back arrives as git's own text ({@link GitTree}), read here by the parsers that read it there. Rows are
 * {@link ListView}s, so a repository with thousands of untracked files costs a screenful of rows.
 *
 * <p>A row picked shows its diff where the editor stands ({@link GitStage}); Enter or a double click opens its file.
 * Each row's + or − stages or unstages it. Below the lists is the commit's message, with how long its first line is,
 * and Commit; whether a commit can go is the {@code Repository}'s to say ({@link Repository#committable}), asked here
 * only to enable the button.
 */
final class GitPane {

    /** A row's height, in rem: two lines of the UI face would not fit the name and its folder side by side. */
    private static final float ROW_REM = 1.75f;
    /** How long a commit's first line should be, at most: what {@code git log --oneline} and most tools show whole. */
    static final int SUBJECT = 72;

    /**
     * One row: a path on one side.
     *
     * @param letter what happened to it on this side: M, A, D, R, U for untracked, ! for a conflict
     * @param lines  how many lines it adds and removes on this side; null if not counted
     */
    record Row(String path, String from, char letter, Numstat.Lines lines, boolean staged) {

        /**
         * The same path once it has moved to the other side: staged, a new file is added; unstaged, an added file is
         * untracked again. Which letter git gives it is the next status's to say; this is for the diff meanwhile.
         */
        Row across() {
            char to = staged ? (letter == 'A' ? 'U' : letter) : (letter == 'U' ? 'A' : letter);
            return new Row(path, from, to, lines, !staged);
        }

        /** The name, without its folder; a whole untracked folder keeps its slash. */
        String name() {
            String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            String own = p.substring(p.lastIndexOf('/') + 1);
            return path.endsWith("/") ? own + "/" : own;
        }

        /** The folder it is in, relative to the repository's top; empty at the top. */
        String folder() {
            String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int slash = p.lastIndexOf('/');
            return slash < 0 ? "" : p.substring(0, slash);
        }

        /** {@code +4 −1}, the parts there are; {@code bin} for a binary file; empty if not counted. */
        String counts() {
            if (lines == null) {
                return "";
            }
            if (lines.binary()) {
                return "bin";
            }
            StringBuilder s = new StringBuilder();
            if (lines.added() > 0) {
                s.append('+').append(lines.added());
            }
            if (lines.removed() > 0) {
                if (!s.isEmpty()) {
                    s.append(' ');
                }
                s.append('−').append(lines.removed());
            }
            return s.toString();
        }
    }

    private final Gui gui;
    private final Node root;
    private final Node branch;
    private final Node upstream;
    private final Node unstagedLabel;
    private final Node stagedLabel;
    private final Node unstagedEmpty;
    private final Node stagedEmpty;
    private final ListView<Row> unstaged;
    private final ListView<Row> staged;
    private final Button stageAll;
    private final Button unstageAll;
    /** Asked to stage ({@code true}) or unstage a row, or everything for a null row. */
    private volatile BiConsumer<Row, Boolean> staging = (r, on) -> { };
    private final TextField message;
    private final Node count;
    private final Button commit;
    /** Asked to commit what is staged, with the message as typed. */
    private volatile Consumer<String> committing = m -> { };
    /** How many paths are staged, from the last head. */
    private volatile int stagedCount;
    /** Whether a commit is out: Commit waits for its answer, so it is never pressed twice for one. */
    private volatile boolean out;

    /** The repository's top, which every row's path is relative to; null before the first status. */
    private volatile Path top;
    /** Whether the first list has anything in it, for where the keyboard goes. */
    private volatile boolean anyUnstaged;
    private volatile Consumer<Path> openFile = p -> { };
    private volatile Runnable refresh = () -> { };
    private volatile Consumer<Row> pick = r -> { };
    /** The row picked, by path and side, so a refreshed list picks it again; null while none is. */
    private volatile Row picked;

    GitPane(Gui gui) {
        this.gui = gui;
        branch = gui.text("").font(Type.UI).textSize(Type.HEADING).textColor(gui.theme().color(Role.INK))
                .wordWrap(false).clip(true);
        upstream = gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(false).clip(true);
        Button again = new Button(gui, "Refresh").kind(Button.Kind.GHOST).onPress(() -> refresh.run());
        gui.landmark(Landmarks.GIT_REFRESH, again.node());
        Node names = gui.column().width(Length.grow(1)).alignItems(AlignItems.STRETCH).clip(true)
                .children(branch, upstream);
        Node head = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .padding(Length.ZERO, Type.TIGHT)
                .children(names, again.node());
        gui.landmark(Landmarks.GIT_BRANCH, branch);

        unstagedLabel = label();
        unstagedEmpty = empty("Working tree clean");
        unstaged = list();
        gui.landmark(Landmarks.GIT_UNSTAGED, unstaged.node());
        stagedLabel = label();
        stagedEmpty = empty("Nothing staged. Press + on a file to stage it.");
        staged = list();
        gui.landmark(Landmarks.GIT_STAGED, staged.node());
        // One row is picked across both lists: picking in one lets go of the other's.
        unstaged.selection().onChange(set -> picked(set, staged));
        staged.selection().onChange(set -> picked(set, unstaged));
        stageAll = new Button(gui, "Stage all").kind(Button.Kind.GHOST).onPress(() -> staging.accept(null, true));
        unstageAll = new Button(gui, "Unstage all").kind(Button.Kind.GHOST)
                .onPress(() -> staging.accept(null, false));
        gui.landmark(Landmarks.GIT_STAGE_ALL, stageAll.node());
        gui.landmark(Landmarks.GIT_UNSTAGE_ALL, unstageAll.node());

        // The message: several lines, with a past of its own, and not wrapped, so its first line is a line on screen
        // too. Ctrl+Enter commits from it, as Enter cannot: Enter is the next line.
        message = new TextField(gui, "").history(new History()).multiline(true).wordWrap(false)
                .onChange(text -> typed());
        message.node().width(Length.FILL).height(Length.rem(5)).font(Type.UI).textSize(Type.LABEL);
        gui.claim(message.node(), Shortcut.of(Key.ENTER, Modifier.CONTROL), ClaimScope.FOCUSED, this::commit);
        gui.claim(message.node(), Shortcut.of(Key.NUMPAD_ENTER, Modifier.CONTROL), ClaimScope.FOCUSED, this::commit);
        gui.landmark(Landmarks.GIT_MESSAGE, message.node());
        count = gui.text("").font(Type.MONO).textSize(Type.SMALL).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(false).width(Length.grow(1)).padding(Length.ZERO, Type.TIGHT);
        gui.landmark(Landmarks.GIT_COUNT, count);
        commit = new Button(gui, "Commit").kind(Button.Kind.PRIMARY).onPress(this::commit);
        gui.landmark(Landmarks.GIT_COMMIT, commit.node());
        Node commitRow = gui.row().width(Length.FILL).alignItems(AlignItems.CENTER).gap(Type.TIGHT)
                .children(count, commit.node());

        root = gui.column()
                .width(Length.FILL).height(Length.grow(1))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .visible(false)
                .children(head, gui.box().height(Type.RULE).background(gui.theme().color(Look.RIM)),
                        heading(unstagedLabel, stageAll), unstagedEmpty, unstaged.node(),
                        heading(stagedLabel, unstageAll), stagedEmpty, staged.node(),
                        gui.box().height(Type.RULE).background(gui.theme().color(Look.RIM)),
                        message.node(), commitRow);
        show(List.of(), List.of());
        typed();
    }

    /** The message changed, or what is staged did: the count says how long the first line is, and Commit whether. */
    private void typed() {
        String text = message.text();
        int n = firstLine(text);
        count.text(n + " / " + SUBJECT);
        count.textColor(gui.theme().color(n > SUBJECT ? Role.CAUTION : Role.FAINT));
        commit.enabled(!out && Repository.committable(stagedCount, text));
    }

    /** How long a message's first line is, in characters as a person counts them. */
    static int firstLine(String message) {
        int end = message.indexOf('\n');
        String line = end < 0 ? message : message.substring(0, end);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line.codePointCount(0, line.length());
    }

    /** Commit, or Ctrl+Enter in the message: sent if it can go, and Commit waits for how it went. */
    private void commit() {
        String text = message.text();
        if (out || !Repository.committable(stagedCount, text)) {
            return;
        }
        out = true;
        typed();
        committing.accept(text);
    }

    /** How a commit went: made, the message is emptied; not, it is left as typed, and the status line says why. */
    void committed(GitCommitted c) {
        out = false;
        if (c.ok()) {
            message.text("");
            message.history().clear();
        }
        typed();
    }

    /** Be asked to commit what is staged, with the message as typed. Runs on whichever thread pressed it. */
    void onCommit(Consumer<String> handler) {
        this.committing = handler == null ? m -> { } : handler;
    }

    /** A list's heading: its name and count, and the button that moves all of it to the other side. */
    private Node heading(Node label, Button all) {
        label.width(Length.grow(1));
        return gui.row().width(Length.FILL).alignItems(AlignItems.CENTER).gap(Type.TIGHT)
                .children(label, all.node());
    }

    private Node label() {
        return gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.FAINT))
                .padding(Length.ZERO, Type.TIGHT).wordWrap(false);
    }

    private Node empty(String says) {
        return gui.text(says).font(Type.UI).textSize(Type.LABEL).textColor(gui.theme().color(Role.DIM))
                .padding(Type.TIGHT, Type.WIDE).wordWrap(false);
    }

    private ListView<Row> list() {
        ListView<Row> list = new ListView<>(gui, ROW_REM, this::row, SelectionModel.single());
        list.node().width(Length.FILL).height(Length.grow(1)).background(gui.theme().color(Role.NONE));
        Look.ring(gui, list.node());
        // Picked, by a click or the arrows, a row's diff stands where the editor does; Enter or a double click
        // opens the file itself, and a deleted one has nothing to open.
        list.onActivate(this::open);
        return list;
    }

    /** A list's selection moved. One row picked is its diff asked for; the same row picked again asks nothing. */
    private void picked(java.util.Set<Row> set, ListView<Row> other) {
        if (set.size() != 1) {
            return;   // let go of because the other list was picked in, or emptied by a refresh: the stage stays
        }
        Row r = set.iterator().next();
        if (same(r, picked)) {
            return;
        }
        picked = r;
        other.selection().clear();
        pick.accept(r);
    }

    private static boolean same(Row a, Row b) {
        return a != null && b != null && a.path().equals(b.path()) && a.staged() == b.staged();
    }

    /** Let go of the row picked: the stage was closed. */
    void unpick() {
        picked = null;
        unstaged.selection().clear();
        staged.selection().clear();
    }

    /** Be told a row was picked, to show its diff. Runs on whichever thread moved the selection. */
    void onPick(Consumer<Row> handler) {
        this.pick = handler == null ? r -> { } : handler;
    }

    private void open(Row r) {
        Path t = top;
        if (t != null && r.letter() != 'D' && !r.path().endsWith("/")) {
            openFile.accept(t.resolve(r.path()));
        }
    }

    private Node row(Gui g, Row r) {
        Node letter = g.text(String.valueOf(r.letter())).font(Type.MONO).textSize(Type.LABEL).bold()
                .textColor(g.theme().color(color(r.letter()))).width(Length.rem(1)).wordWrap(false);
        Node name = g.text(r.name()).font(Type.UI).textSize(Type.LABEL).textColor(g.theme().color(Role.INK))
                .wordWrap(false);
        Node folder = g.text(r.folder()).font(Type.UI).textSize(Type.SMALL).textColor(g.theme().color(Role.FAINT))
                .wordWrap(false);
        Node names = g.row().width(Length.grow(1)).gap(Type.TIGHT).alignItems(AlignItems.CENTER).clip(true)
                .children(name, folder);
        Node counts = g.text(r.counts()).font(Type.MONO).textSize(Type.SMALL)
                .textColor(g.theme().color(Role.DIM)).wordWrap(false);
        // + to stage, − to unstage. A conflict is resolved at a prompt: staging it would mark it resolved, markers
        // and all, so it has no button here.
        IconButton move = new IconButton(g, r.staged() ? Icon.MINUS : Icon.PLUS, r.staged() ? "Unstage" : "Stage")
                .size(Length.rem(1.5f), Length.rem(0.875f))
                .tip(r.staged() ? "Unstage" : r.letter() == '!' ? "Resolve the conflict at a prompt" : "Stage")
                .enabled(r.letter() != '!')
                .onPress(() -> move(r));
        return g.row().width(Length.FILL).height(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .padding(Length.ZERO, Type.TIGHT)
                .children(letter, names, counts, move.node());
    }

    /**
     * A row's + or −: staged or unstaged. When it is the row picked, the pick goes with it to the other side, so the
     * diff shows what is now there rather than an emptied one.
     */
    private void move(Row r) {
        if (same(r, picked)) {
            Row moved = r.across();
            picked = moved;
            pick.accept(moved);
        }
        staging.accept(r, !r.staged());
    }

    /** The row picked, staged if it is not, unstaged if it is: the diff's Stage or Unstage. */
    void movePicked() {
        Row r = picked;
        if (r != null && r.letter() != '!') {
            move(r);
        }
    }

    /** Be asked to stage ({@code true}) or unstage a row, or everything for a null row. Runs on a worker. */
    void onStage(BiConsumer<Row, Boolean> handler) {
        this.staging = handler == null ? (r, on) -> { } : handler;
    }

    /** What colour a letter is: changed, added, gone, moved, new to git, or stuck in a merge. */
    static Role color(char letter) {
        return switch (letter) {
            case 'A' -> Role.SUCCESS;
            case 'D', '!' -> Role.DANGER;
            case 'R', 'C' -> Role.ACCENT;
            case 'U' -> Role.FAINT;
            default -> Role.CAUTION;
        };
    }

    Node node() {
        return root;
    }

    /** Be asked to open a file a row stands for. Runs on a worker. */
    void onOpenFile(Consumer<Path> handler) {
        this.openFile = handler == null ? p -> { } : handler;
    }

    /** The repository's top, which every row's path is relative to; null before the first status or outside one. */
    Path top() {
        return top;
    }

    /** Be asked to ask git again: Refresh. Runs on a worker. */
    void onRefresh(Runnable handler) {
        this.refresh = handler == null ? () -> { } : handler;
    }

    /** Where the root stands: the branch and its distance, and what it tracks. */
    void head(GitHead h) {
        branch.text(h.chip());
        upstream.text(h.upstream().isEmpty() ? "no upstream" : "tracking " + h.upstream());
        stagedCount = h.staged();
        typed();
    }

    /** Every changed path, as git wrote it: read, sorted into the two sides, and shown. */
    void tree(GitTree t) {
        top = t.root();
        Status s = Status.parse(t.root(), t.status());
        List<Row>[] sides = rows(s, Numstat.parse(t.unstagedLines()), Numstat.parse(t.stagedLines()));
        show(sides[0], sides[1]);
    }

    /** Nothing to show: no repository. */
    void clear() {
        top = null;
        show(List.of(), List.of());
    }

    /** A status as Git mode lists it: what is not staged, then what is, each with its own side's letter and lines. */
    @SuppressWarnings("unchecked")
    static List<Row>[] rows(Status s, Numstat unstagedLines, Numstat stagedLines) {
        List<Row> unstaged = new ArrayList<>();
        List<Row> staged = new ArrayList<>();
        for (Status.Change c : s.changes()) {
            if (c.unstaged()) {
                char letter = c.state() == Status.State.CONFLICTED ? '!'
                        : c.state() == Status.State.UNTRACKED ? 'U' : c.tree();
                unstaged.add(new Row(c.path(), c.from(), letter, unstagedLines.at(c.path()), false));
            }
            if (c.staged()) {
                staged.add(new Row(c.path(), c.from(), c.index(), stagedLines.at(c.path()), true));
            }
        }
        return new List[] {unstaged, staged};
    }

    private void show(List<Row> notStaged, List<Row> isStaged) {
        unstagedLabel.text("Unstaged  " + notStaged.size());
        stagedLabel.text("Staged  " + isStaged.size());
        unstagedEmpty.visible(notStaged.isEmpty());
        stagedEmpty.visible(isStaged.isEmpty());
        unstaged.node().visible(!notStaged.isEmpty());
        staged.node().visible(!isStaged.isEmpty());
        stageAll.node().visible(!notStaged.isEmpty());
        unstageAll.node().visible(!isStaged.isEmpty());
        unstaged.items(notStaged);
        staged.items(isStaged);
        anyUnstaged = !notStaged.isEmpty();
        // The rows are new, counts and all: the one picked is picked again, which asks nothing since it is the same.
        // Gone from its side and on the other, it moved there (a stage made at a prompt, or one of ours that failed
        // and left it where it was), and the pick and its diff follow it.
        Row was = picked;
        if (was != null) {
            Row here = find(was.staged() ? isStaged : notStaged, was.path());
            if (here != null) {
                (was.staged() ? staged : unstaged).selection().at(here);
            } else {
                Row there = find(was.staged() ? notStaged : isStaged, was.path());
                if (there != null) {
                    picked = there;
                    (there.staged() ? staged : unstaged).selection().at(there);
                    pick.accept(there);
                }
            }
        }
    }

    private static Row find(List<Row> rows, String path) {
        for (Row r : rows) {
            if (r.path().equals(path)) {
                return r;
            }
        }
        return null;
    }

    /** Put the keyboard in the first list with something in it. */
    void focus() {
        Node first = anyUnstaged ? unstaged.node() : staged.node();
        gui.focus(first);
    }
}
