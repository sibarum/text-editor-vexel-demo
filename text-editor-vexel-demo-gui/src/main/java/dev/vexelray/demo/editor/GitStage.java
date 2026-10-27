package dev.vexelray.demo.editor;

import dev.sibarum.suite.git.Diff;
import dev.vexelray.demo.editor.core.Messages.GitDiff;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.SelectionModel;
import sibarum.tactroller.api.Key;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * What stands where the editor does while a row of Git mode is picked: that file's diff, its old and new line numbers,
 * a {@code +} or {@code −}, and the line, added lines on green and removed on red, each hunk under its own header.
 *
 * <p>It shows what it is given ({@link GitDiff}, git's text, read here with {@code Diff.parse}) and keeps nothing else.
 * A line, Enter on it or a double click, opens the file there: the line itself, or for a removed line the place it
 * was taken from. Close, or Escape, puts the stage away and the editor comes back as it was.
 *
 * <p>Rows are a {@link ListView}, so a diff of a whole generated file costs a screenful of rows.
 */
final class GitStage {

    /** A row's height, in rem: the code face at its size, and a little air. */
    private static final float ROW_REM = 1.375f;

    /**
     * One row: a hunk's header, or one of its lines.
     *
     * @param index where it is in the diff, so two equal lines are two rows
     * @param kind  what it is; null for a hunk's header
     * @param old   its number on the old side; 0 if it has none
     * @param neu   its number on the new side; 0 if it has none
     * @param at    the line of the file it stands for: its own on the new side, or for a removed line the new side's
     *              line where it was; for a header, its hunk's first
     * @param text  the line, or the header
     */
    record Row(int index, Diff.Kind kind, int old, int neu, int at, String text) {

        boolean header() {
            return kind == null;
        }

        String glyph() {
            return kind == Diff.Kind.ADDED ? "+" : kind == Diff.Kind.REMOVED ? "−" : "";
        }
    }

    private final Gui gui;
    private final Node root;
    private final Node name;
    private final Node about;
    private final Node note;
    private final Button open;
    private final ListView<Row> lines;

    /** The file on show, absolute; null while there is none. */
    private volatile Path file;
    /** Whether the file is there to open: a deleted file is not. */
    private volatile boolean openable;
    private volatile BiConsumer<Path, Integer> openAt = (p, line) -> { };
    private volatile Runnable closed = () -> { };

    GitStage(Gui gui) {
        this.gui = gui;
        name = gui.text("").font(Type.UI).textSize(Type.HEADING).textColor(gui.theme().color(Role.INK))
                .wordWrap(false).clip(true);
        about = gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(false).clip(true);
        open = new Button(gui, "Open file").kind(Button.Kind.GHOST).onPress(() -> openAt(1));
        Button close = new Button(gui, "Close").kind(Button.Kind.GHOST).onPress(() -> closed.run());
        gui.landmark(Landmarks.DIFF_OPEN, open.node());
        gui.landmark(Landmarks.DIFF_CLOSE, close.node());
        gui.landmark(Landmarks.DIFF_FILE, name);
        Node names = gui.column().width(Length.grow(1)).alignItems(AlignItems.STRETCH).clip(true)
                .children(name, about);
        Node head = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .padding(Type.TIGHT, Type.WIDE)
                .children(names, open.node(), close.node());

        note = gui.text("").font(Type.UI).textSize(Type.LABEL).textColor(gui.theme().color(Role.DIM))
                .padding(Type.WIDE, Type.WIDE).wordWrap(false).visible(false);
        lines = new ListView<>(gui, ROW_REM, this::row, SelectionModel.single());
        lines.node().width(Length.FILL).height(Length.grow(1)).background(gui.theme().color(Role.NONE));
        Look.ring(gui, lines.node());
        lines.onActivate(r -> openAt(r.at()));
        gui.landmark(Landmarks.DIFF, lines.node());
        gui.claim(lines.node(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, () -> closed.run());

        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .alignItems(AlignItems.STRETCH)
                .background(gui.theme().color(Look.CARD))
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Look.RIM))
                .visible(false)
                .children(head, gui.box().height(Type.RULE).background(gui.theme().color(Look.RIM)), note,
                        lines.node());
        gui.landmark(Landmarks.STAGE, root);
    }

    private Node row(Gui g, Row r) {
        if (r.header()) {
            return g.row().width(Length.FILL).height(Length.FILL).alignItems(AlignItems.CENTER)
                    .padding(Length.ZERO, Type.WIDE)
                    .background(g.theme().color(Look.HUNK))
                    .children(g.text(r.text()).font(Type.MONO).textSize(Type.SMALL)
                            .textColor(g.theme().color(Role.DIM)).wordWrap(false));
        }
        Role ground = r.kind() == Diff.Kind.ADDED ? Look.ADDED_LINE
                : r.kind() == Diff.Kind.REMOVED ? Look.REMOVED_LINE : Role.NONE;
        Role mark = r.kind() == Diff.Kind.ADDED ? Role.SUCCESS : Role.DANGER;
        return g.row().width(Length.FILL).height(Length.FILL).alignItems(AlignItems.CENTER)
                .background(g.theme().color(ground))
                .children(number(g, r.old()), number(g, r.neu()),
                        g.text(r.glyph()).font(Type.MONO).textSize(Type.CODE).textColor(g.theme().color(mark))
                                .width(Length.rem(1.5f)).wordWrap(false),
                        g.text(r.text()).font(Type.MONO).textSize(Type.CODE).textColor(g.theme().color(Role.INK))
                                .wordWrap(false).width(Length.grow(1)).clip(true));
    }

    private static Node number(Gui g, int n) {
        return g.text(n > 0 ? Integer.toString(n) : "").font(Type.MONO).textSize(Type.SMALL)
                .textColor(g.theme().color(Role.FAINT)).width(Length.rem(3.25f)).wordWrap(false)
                .padding(Length.ZERO, Type.TIGHT);
    }

    Node node() {
        return root;
    }

    /** Be asked to open a file at a 1-based line. Runs on a worker. */
    void onOpenAt(BiConsumer<Path, Integer> handler) {
        this.openAt = handler == null ? (p, line) -> { } : handler;
    }

    /** Be told the stage was closed: Close, or Escape. Runs on a worker. */
    void onClose(Runnable handler) {
        this.closed = handler == null ? () -> { } : handler;
    }

    private void openAt(int line) {
        Path f = file;
        if (f != null && openable) {
            openAt.accept(f, Math.max(1, line));
        }
    }

    /**
     * Show a diff, as git wrote it, for {@code d.path()} on {@code d.side()}. {@code there} is whether the file is on
     * the disk to be opened, which the row it was picked from knows: a deleted file is not.
     */
    void show(GitDiff d, boolean there) {
        Diff diff = Diff.parse(d.text());
        String path = d.path();
        String own = path.endsWith("/") ? path : path.substring(path.lastIndexOf('/') + 1);
        int slash = path.lastIndexOf('/', path.length() - 2);
        String folder = slash < 0 ? "" : path.substring(0, slash) + " · ";
        file = d.root().resolve(path);
        List<Row> rows = rows(diff);
        openable = there && !path.endsWith("/");
        name.text(own);
        about.text(folder + side(d.side()) + counts(diff));
        open.enabled(openable);
        String says = diff.binary() ? "A binary file: there are no lines to show."
                : rows.isEmpty() ? "No changes on this side." : "";
        note.text(says);
        note.visible(!says.isEmpty());
        lines.node().visible(says.isEmpty());
        lines.items(rows);
    }

    /** Put the keyboard on the lines, so the arrows walk them and Escape closes. */
    void focus() {
        gui.focus(lines.node());
    }

    private static String side(Diff.Side side) {
        return switch (side) {
            case UNSTAGED -> "unstaged";
            case STAGED -> "staged";
            case UNTRACKED -> "untracked";
        };
    }

    private static String counts(Diff diff) {
        StringBuilder s = new StringBuilder();
        if (diff.added() > 0) {
            s.append("  +").append(diff.added());
        }
        if (diff.removed() > 0) {
            s.append("  −").append(diff.removed());
        }
        return s.toString();
    }

    /** A diff as the stage lists it: each hunk's header, then its lines, each knowing the file's line it stands for. */
    static List<Row> rows(Diff diff) {
        List<Row> rows = new ArrayList<>();
        for (Diff.Hunk h : diff.hunks()) {
            rows.add(new Row(rows.size(), null, 0, 0, h.newStart(), h.header()));
            // Where the new side has got to: a removed line stands for the line that now follows it.
            int next = h.newStart();
            for (Diff.Line l : h.lines()) {
                int at = l.kind() == Diff.Kind.REMOVED ? next : l.neu();
                rows.add(new Row(rows.size(), l.kind(), l.old(), l.neu(), at, l.text()));
                if (l.kind() != Diff.Kind.REMOVED) {
                    next = l.neu() + 1;
                }
            }
        }
        return rows;
    }
}
