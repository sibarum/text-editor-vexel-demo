package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.fileicons.FileIconView;
import dev.vexelray.gui.icons.IconView;

/**
 * A {@link Listing} on screen, as an archive lists its contents: a row per file, its icon, its path relative to the
 * folder with the folder part dimmed and the name in ink, a bar of its size against the largest file's, coloured by
 * its family as its icon is, and the size in figures.
 *
 * <p>Rows are built once, as many as are ever shown, and filled or hidden, as {@link Preview}'s are, so the window's
 * own motion carries a change of folder: rows arriving and leaving, bars growing and shrinking. Past
 * {@link #ROWS} files the rest are counted, not listed.
 */
final class ListingView {

    /** The most rows shown: enough to read a folder by, few enough that the pool stays small. */
    static final int ROWS = 120;
    /** A folder part longer than this is cut from the left, so the file's own name is what survives a narrow card. */
    private static final int DIR_CHARS = 44;
    private static final Length ICON = Length.dp(16);
    private static final Length TRACK = Length.rem(5);
    private static final Length BAR = Length.dp(6);
    private static final Length SIZE_COLUMN = Length.rem(3.75f);

    private final Node node;
    private final Node summary;
    private final Node more;
    private final Row[] rows = new Row[ROWS];
    private final boolean dark;

    ListingView(Gui gui) {
        Theme theme = gui.theme();
        this.dark = FileIconView.dark(theme);
        Node heading = gui.text("files").font(Type.UI).textSize(Type.SMALL).textColor(theme.color(Role.FAINT))
                .wordWrap(false);
        summary = gui.text("").font(Type.MONO).textSize(Type.SMALL).textColor(theme.color(Role.DIM)).wordWrap(false);
        Node head = gui.row().scroll(false, false).gap(Type.GAP).alignItems(AlignItems.CENTER)
                .children(heading, summary);
        Node list = gui.column().scroll(false, false).gap(Length.dp(2)).alignItems(AlignItems.STRETCH);
        for (int i = 0; i < ROWS; i++) {
            rows[i] = new Row(gui, theme);
            list.append(rows[i].node);
        }
        more = gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(theme.color(Role.FAINT)).wordWrap(false)
                .visible(false);
        node = gui.column().scroll(false, false).gap(Type.TIGHT).alignItems(AlignItems.STRETCH).visible(false)
                .children(head, list, more);
        gui.landmark(Landmarks.FILES, node);
    }

    Node node() {
        return node;
    }

    /** Show {@code l}, or nothing for null or a folder with no files. From any thread; drawn by the next frame. */
    void show(Listing l) {
        boolean on = l != null && l.files() > 0;
        node.visible(on);
        if (!on) {
            return;
        }
        int files = l.files();
        summary.text((l.truncated() ? "≥" : "") + files + (files == 1 ? " file" : " files") + "  ·  "
                + Listing.size(l.bytes()));
        long largest = l.largest();
        for (int i = 0; i < ROWS; i++) {
            if (i < files) {
                rows[i].fill(l.entries().get(i), largest);
            } else {
                rows[i].hide();
            }
        }
        int rest = files - ROWS;
        more.visible(rest > 0 || l.truncated());
        if (l.truncated()) {
            more.text("and more: the list stops at " + Listing.MAX_FILES + " files");
        } else if (rest > 0) {
            more.text("and " + rest + (rest == 1 ? " more file" : " more files"));
        }
    }

    /** {@code dir} with its trailing slash, cut from the left to {@link #DIR_CHARS} with an ellipsis. */
    static String folderPart(String dir) {
        if (dir.isEmpty()) {
            return "";
        }
        String d = dir + "/";
        return d.length() <= DIR_CHARS ? d : "…" + d.substring(d.length() - DIR_CHARS + 1);
    }

    /** One file's row, re-pointed per file. */
    private final class Row {

        final Node node;
        private final IconView icon;
        private final Node dir;
        private final Node name;
        private final Node fill;
        private final Node size;

        Row(Gui gui, Theme theme) {
            icon = IconView.of(gui, null).size(ICON);
            icon.node().hitInert(true);
            dir = gui.text("").font(Type.MONO).textSize(Type.SMALL).textColor(theme.color(Role.FAINT))
                    .wordWrap(false);
            name = gui.text("").font(Type.MONO).textSize(Type.SMALL).textColor(theme.color(Role.INK))
                    .wordWrap(false);
            // The path in a cell that takes what the row leaves and clips, as the tree does its labels: a text node
            // is as wide as its text, so a long path would otherwise push the bar off the card.
            Node path = gui.row().scroll(false, false).width(Length.grow(1)).alignItems(AlignItems.CENTER).clip(true)
                    .children(dir, name);
            // Coloured by what the row now stands for, so a re-pointed row takes its new colour at once.
            fill = gui.box().height(Length.FILL).width(Length.percent(0)).corner(Length.dp(3)).paintCuts(true);
            Node track = gui.row().scroll(false, false).width(TRACK).height(BAR).corner(Length.dp(3))
                    .background(theme.color(Look.TILE)).children(fill);
            size = gui.text("").font(Type.MONO).textSize(Type.SMALL).textColor(theme.color(Role.DIM))
                    .wordWrap(false).width(SIZE_COLUMN);
            node = gui.row().scroll(false, false).gap(Type.GAP).alignItems(AlignItems.CENTER).visible(false)
                    .children(icon.node(), path, track, size);
        }

        void fill(Listing.Entry e, long largest) {
            icon.icon(FolderSource.byName(e.name()));
            dir.text(folderPart(e.dir()));
            name.text(e.name());
            fill.background(e.family().hue(dark))
                    .width(Length.percent(largest == 0 ? 0 : Math.max(2f, 100f * e.bytes() / largest)));
            size.text(Listing.size(e.bytes()));
            node.visible(true);
        }

        void hide() {
            node.visible(false);
        }
    }
}
