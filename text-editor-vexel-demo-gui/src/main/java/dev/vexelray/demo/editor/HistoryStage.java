package dev.vexelray.demo.editor;

import dev.sibarum.suite.git.Commit;
import dev.sibarum.suite.git.Log;
import dev.sibarum.suite.git.Ref;
import dev.sibarum.suite.git.Refs;
import dev.vexelray.demo.editor.core.Messages.GitHistory;
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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * What stands where the editor does in Git mode while no row is picked: the changesets, newest first from
 * {@code HEAD}, each with the refs at it, and beside them every branch with how far it stands from what it tracks.
 * A branch activated, Enter or a double click, is switched to; the changesets are only shown.
 *
 * <p>It shows what it is given ({@link GitHistory}, as {@code Log.text} and {@code Refs.text} wrote it, read here with
 * their {@code parse}) and keeps nothing else. Both are {@link ListView}s. Close, or Escape, brings the editor back;
 * Git mode's History brings this back.
 */
final class HistoryStage {

    /** A row's height, in rem: the subject in the UI face, with its refs beside it. */
    private static final float ROW_REM = 1.75f;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final Gui gui;
    private final Node root;
    private final Node about;
    private final Node empty;
    private final Node branchesLabel;
    private final Node changesetsLabel;
    private final ListView<Commit> log;
    private final ListView<Ref> branches;
    private volatile Runnable closed = () -> { };
    private volatile Consumer<String> switching = b -> { };
    /** The remote branches' names, from the last branches shown. */
    private volatile Set<String> remotes = Set.of();

    HistoryStage(Gui gui) {
        this.gui = gui;
        Node title = gui.text("History").font(Type.UI).textSize(Type.HEADING).textColor(gui.theme().color(Role.INK))
                .wordWrap(false);
        about = gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(false).clip(true);
        Button close = new Button(gui, "Close").kind(Button.Kind.GHOST).onPress(() -> closed.run());
        gui.landmark(Landmarks.HISTORY_CLOSE, close.node());
        Node names = gui.column().width(Length.grow(1)).alignItems(AlignItems.STRETCH).clip(true)
                .children(title, about);
        Node head = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .padding(Type.TIGHT, Type.WIDE)
                .children(names, close.node());

        changesetsLabel = label("Changesets");
        log = new ListView<>(gui, ROW_REM, this::commitRow, SelectionModel.single());
        log.node().width(Length.FILL).height(Length.grow(1)).background(gui.theme().color(Role.NONE));
        Look.ring(gui, log.node());
        gui.landmark(Landmarks.HISTORY_LOG, log.node());
        branchesLabel = label("Branches");
        branches = new ListView<>(gui, ROW_REM, this::branchRow, SelectionModel.single());
        branches.node().width(Length.FILL).height(Length.grow(1)).background(gui.theme().color(Role.NONE));
        Look.ring(gui, branches.node());
        gui.landmark(Landmarks.HISTORY_BRANCHES, branches.node());
        for (ListView<?> list : List.of(log, branches)) {
            gui.claim(list.node(), Shortcut.of(Key.ESCAPE), ClaimScope.FOCUSED, () -> closed.run());
        }
        // Enter or a double click on a branch switches to it; the one checked out is where it is already.
        branches.onActivate(r -> {
            if (!r.head()) {
                switching.accept(switchName(r));
            }
        });
        empty = gui.text("No commits yet.").font(Type.UI).textSize(Type.LABEL)
                .textColor(gui.theme().color(Role.DIM)).padding(Type.WIDE, Type.WIDE).wordWrap(false)
                .visible(false);

        Node changesets = gui.column().width(Length.grow(2)).height(Length.FILL).alignItems(AlignItems.STRETCH)
                .gap(Type.TIGHT).padding(Type.TIGHT, Length.ZERO)
                .children(changesetsLabel, empty, log.node());
        Node refs = gui.column().width(Length.grow(1)).height(Length.FILL).alignItems(AlignItems.STRETCH)
                .gap(Type.TIGHT).padding(Type.TIGHT, Length.ZERO)
                .children(branchesLabel, branches.node());
        Node body = gui.row().width(Length.FILL).height(Length.grow(1)).alignItems(AlignItems.STRETCH)
                .children(changesets, gui.box().width(Type.RULE).height(Length.FILL)
                        .background(gui.theme().color(Look.RIM)), refs);

        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .alignItems(AlignItems.STRETCH)
                .background(gui.theme().color(Look.CARD))
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Look.RIM))
                .visible(false)
                .children(head, gui.box().height(Type.RULE).background(gui.theme().color(Look.RIM)), body);
        gui.landmark(Landmarks.HISTORY, root);
    }

    private Node label(String says) {
        return gui.text(says).font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.FAINT))
                .padding(Length.ZERO, Type.WIDE).wordWrap(false);
    }

    /** A changeset: the refs at it, its subject, who and when, and its short hash. */
    private Node commitRow(Gui g, Commit c) {
        List<Node> parts = new ArrayList<>();
        for (String r : c.refs()) {
            parts.add(chip(g, r));
        }
        // A text keeps its own width; the row round it is what gives way, and cuts a long subject off.
        parts.add(g.row().width(Length.grow(1)).alignItems(AlignItems.CENTER).clip(true).scroll(false, false)
                .children(g.text(c.subject()).font(Type.UI).textSize(Type.LABEL)
                        .textColor(g.theme().color(c.head() ? Role.INK : Role.DIM)).wordWrap(false)));
        parts.add(g.row().width(Length.rem(7)).alignItems(AlignItems.CENTER).clip(true).scroll(false, false)
                .children(g.text(c.author()).font(Type.UI).textSize(Type.SMALL)
                        .textColor(g.theme().color(Role.FAINT)).wordWrap(false)));
        parts.add(g.text(ago(c.when(), Instant.now())).font(Type.UI).textSize(Type.SMALL)
                .textColor(g.theme().color(Role.FAINT)).wordWrap(false).width(Length.rem(5.5f)));
        parts.add(g.text(c.shortHash()).font(Type.MONO).textSize(Type.SMALL).textColor(g.theme().color(Role.FAINT))
                .wordWrap(false));
        return g.row().width(Length.FILL).height(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .padding(Length.ZERO, Type.WIDE)
                .children(parts.toArray(Node[]::new));
    }

    /** A ref at a changeset, outlined in what it is: where you are, a branch, a remote's branch, a tag. */
    private Node chip(Gui g, String ref) {
        Role role = chipRole(ref, remotes);
        return g.text(ref).font(Type.UI).textSize(Type.SMALL).textColor(g.theme().color(role)).wordWrap(false)
                .border(Type.RULE, g.theme().color(role)).corner(Type.CORNER).padding(Length.ZERO, Type.TIGHT);
    }

    /**
     * What colour a ref at a changeset is outlined in. Git names a local {@code feature/x} and a remote's
     * {@code origin/x} alike, so which are remote is the branch list's to say.
     */
    static Role chipRole(String ref, Set<String> remotes) {
        if (ref.equals("HEAD")) {
            return Role.ACCENT;
        }
        if (ref.startsWith("tag: ")) {
            return Role.CAUTION;
        }
        return remotes.contains(ref) ? Role.FAINT : Role.SUCCESS;
    }

    /** A branch: checked out or not, its name, how far from what it tracks, and the commit it is at. */
    private Node branchRow(Gui g, Ref r) {
        Node mark = g.text(r.head() ? "●" : "").font(Type.UI).textSize(Type.SMALL)
                .textColor(g.theme().color(Role.ACCENT)).width(Length.rem(1)).wordWrap(false);
        Node name = g.text(r.name()).font(Type.UI).textSize(Type.LABEL)
                .textColor(g.theme().color(r.remote() ? Role.FAINT : Role.INK)).wordWrap(false);
        if (r.head()) {
            name.bold();
        }
        Node names = g.row().width(Length.grow(1)).alignItems(AlignItems.CENTER).clip(true).scroll(false, false)
                .children(name);
        Node distance = g.text(r.distance()).font(Type.UI).textSize(Type.SMALL)
                .textColor(g.theme().color(r.gone() ? Role.DANGER : Role.CAUTION)).wordWrap(false);
        Node oid = g.text(r.oid()).font(Type.MONO).textSize(Type.SMALL).textColor(g.theme().color(Role.FAINT))
                .wordWrap(false);
        return g.row().width(Length.FILL).height(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .padding(Length.ZERO, Type.WIDE)
                .children(mark, names, distance, oid);
    }

    /** {@code just now}, {@code 5 min ago}, {@code 3 h ago}, {@code 2 d ago}, and past a fortnight the day. */
    static String ago(Instant when, Instant now) {
        Duration d = Duration.between(when, now);
        if (d.isNegative() || d.toMinutes() < 1) {
            return "just now";
        }
        if (d.toHours() < 1) {
            return d.toMinutes() + " min ago";
        }
        if (d.toDays() < 1) {
            return d.toHours() + " h ago";
        }
        if (d.toDays() < 14) {
            return d.toDays() + " d ago";
        }
        return DAY.format(when.atZone(ZoneId.systemDefault()));
    }

    Node node() {
        return root;
    }

    /**
     * What a branch is switched to by: its name, or a remote's without the remote, {@code origin/x} as {@code x}, which
     * git makes a local branch tracking it, or switches to the local one if there is one.
     */
    static String switchName(Ref r) {
        return r.remote() ? r.name().substring(r.name().indexOf('/') + 1) : r.name();
    }

    /** Be asked to switch to a branch, by the name git switches to it by. Runs on whichever thread activated it. */
    void onSwitch(Consumer<String> handler) {
        this.switching = handler == null ? b -> { } : handler;
    }

    /** Be told the history was closed: Close, or Escape. Runs on a worker. */
    void onClose(Runnable handler) {
        this.closed = handler == null ? () -> { } : handler;
    }

    /** Show the history and the branches as the repository sent them. */
    void show(GitHistory h) {
        List<Commit> commits = Log.parse(h.log());
        List<Ref> refs = Refs.parse(h.branches());
        long local = refs.stream().filter(r -> !r.remote()).count();
        // Before the rows are built, which colour their refs by it.
        remotes = refs.stream().filter(Ref::remote).map(Ref::name).collect(Collectors.toUnmodifiableSet());
        about.text(h.root().getFileName() + " · " + (commits.size() == 1 ? "1 changeset" : commits.size()
                + " changesets") + " · " + (local == 1 ? "1 branch" : local + " branches"));
        changesetsLabel.text("Changesets  " + commits.size());
        branchesLabel.text("Branches  " + refs.size() + "  ·  Enter or double-click switches");
        empty.visible(commits.isEmpty());
        log.node().visible(!commits.isEmpty());
        log.items(commits);
        branches.items(refs);
    }

    /** Put the keyboard on the changesets, so the arrows walk them and Escape closes. */
    void focus() {
        gui.focus(log.node());
    }
}
