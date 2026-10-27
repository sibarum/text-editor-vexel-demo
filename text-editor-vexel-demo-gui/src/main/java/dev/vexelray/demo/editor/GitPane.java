package dev.vexelray.demo.editor;

import dev.sibarum.suite.git.Numstat;
import dev.sibarum.suite.git.Status;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitTree;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ListView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Git mode's card: the branch, then what is changed and not staged, then what is staged, each path once per side it
 * has changed on, with its letter and how many lines it adds and removes.
 *
 * <p>It shows what it is given and keeps nothing else. The {@code Repository} decides when git is asked; what comes
 * back arrives as git's own text ({@link GitTree}), read here by the parsers that read it there. Rows are
 * {@link ListView}s, so a repository with thousands of untracked files costs a screenful of rows.
 *
 * <p>Read-only for now: a row opens its file, and staging, the commit and the diff are later slices of
 * {@code docs/git-mode.md}.
 */
final class GitPane {

    /** A row's height, in rem: two lines of the UI face would not fit the name and its folder side by side. */
    private static final float ROW_REM = 1.75f;

    /**
     * One row: a path on one side.
     *
     * @param letter what happened to it on this side: M, A, D, R, U for untracked, ! for a conflict
     * @param lines  how many lines it adds and removes on this side; null if not counted
     */
    record Row(String path, String from, char letter, Numstat.Lines lines, boolean staged) {

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

    /** The repository's top, which every row's path is relative to; null before the first status. */
    private volatile Path top;
    /** Whether the first list has anything in it, for where the keyboard goes. */
    private volatile boolean anyUnstaged;
    private volatile Consumer<Path> openFile = p -> { };
    private volatile Runnable refresh = () -> { };

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
        stagedEmpty = empty("Nothing staged");
        staged = list();
        gui.landmark(Landmarks.GIT_STAGED, staged.node());

        root = gui.column()
                .width(Length.FILL).height(Length.grow(1))
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .visible(false)
                .children(head, gui.box().height(Type.RULE).background(gui.theme().color(Look.RIM)),
                        unstagedLabel, unstagedEmpty, unstaged.node(),
                        stagedLabel, stagedEmpty, staged.node());
        show(List.of(), List.of());
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
        ListView<Row> list = new ListView<>(gui, ROW_REM, this::row);
        list.node().width(Length.FILL).height(Length.grow(1)).background(gui.theme().color(Role.NONE));
        Look.ring(gui, list.node());
        // A row is a file to open, as a row of the root's tree is; a deleted one has nothing to open.
        list.onActivate(this::open);
        list.onRowClicked(this::open);
        return list;
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
        return g.row().width(Length.FILL).height(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .padding(Length.ZERO, Type.TIGHT)
                .children(letter, names, counts);
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

    /** Be asked to ask git again: Refresh. Runs on a worker. */
    void onRefresh(Runnable handler) {
        this.refresh = handler == null ? () -> { } : handler;
    }

    /** Where the root stands: the branch and its distance, and what it tracks. */
    void head(GitHead h) {
        branch.text(h.chip());
        upstream.text(h.upstream().isEmpty() ? "no upstream" : "tracking " + h.upstream());
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
        unstaged.items(notStaged);
        staged.items(isStaged);
        anyUnstaged = !notStaged.isEmpty();
    }

    /** Put the keyboard in the first list with something in it. */
    void focus() {
        Node first = anyUnstaged ? unstaged.node() : staged.node();
        gui.focus(first);
    }
}
