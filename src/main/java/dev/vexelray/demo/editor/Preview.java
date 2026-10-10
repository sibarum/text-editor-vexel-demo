package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What stands where the editor does while the navigator is in Open: a picture of the folder under the pointer, so
 * that which folder is the project's root can be seen rather than guessed.
 *
 * <p>Its name and path come at once; the rest is a {@link Survey}, run off the frame loop on a thread of its own and
 * abandoned the moment the pointer moves to another folder. It is drawn as it goes, a few times a second, so a large
 * build is seen being read, its numbers climbing and its bars growing, rather than waited on behind a blank.
 *
 * <p>The picture is drawn, not written: the build's own name and how it is made up, a bar of the languages in it,
 * the counts of each kind of declaration, the modules and annotations as bars against each other. Rows are built
 * once, as many as are ever shown, and filled or hidden: nothing is added to or removed from the tree as the
 * pointer moves.
 */
final class Preview {

    private static final int MODULES = 10;
    private static final int LANGUAGES = 6;
    private static final int ANNOTATIONS = 8;
    private static final Length BAR = Length.dp(8);
    /** A tile's number: smaller than the name's figure, so five tiles still hold four digits each in a narrow window. */
    private static final Length TILE_FIGURE = Length.rem(1.5f);
    private static final Length NAME_COLUMN = Length.rem(11);
    private static final Length COUNT_COLUMN = Length.rem(3.5f);

    private final Gui gui;
    private final Theme theme;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "editor-survey");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicLong generation = new AtomicLong();
    private final Map<Path, Survey> seen = new ConcurrentHashMap<>();
    private volatile Path showing;

    private final Node root;
    private final Node name;
    private final Node path;
    private final Node body;
    private final Node build;
    private final Node buildKind;
    private final Node modulesChip;
    private final Node partOfChip;
    private final Node repoChip;
    private final Node figures;
    private final Node[] figureValues = new Node[5];
    private final Node languageBar;
    private final Node[] languageSegments = new Node[LANGUAGES + 1];
    private final Node languageLegend;
    private final Node[] legendEntries = new Node[LANGUAGES];
    private final Node[] legendSwatches = new Node[LANGUAGES];
    private final Node[] legendNames = new Node[LANGUAGES];
    private final Node modulesSection;
    private final Bars modules;
    private final Node annotationsSection;
    private final Bars annotations;
    private final Node empty;

    Preview(Gui gui) {
        this.gui = gui;
        this.theme = gui.theme();

        name = gui.text("").font(Type.UI).textSize(Type.FIGURE).textColor(theme.color(Role.INK)).wordWrap(false);
        path = gui.text("").font(Type.MONO).textSize(Type.SMALL).textColor(theme.color(Role.FAINT)).wordWrap(false);

        buildKind = chip(Type.MONO, Look.PICKING_RIM);
        modulesChip = chip(Type.UI, Look.PICKING_RIM);
        partOfChip = chip(Type.UI, Look.RIM);
        repoChip = chip(Type.UI, Look.RIM);
        build = row().gap(Type.GAP).alignItems(AlignItems.CENTER).clip(true)
                .children(buildKind, modulesChip, partOfChip, repoChip);

        String[] captions = {"java files", "classes", "records", "interfaces", "enums"};
        figures = row().gap(Type.GAP).width(Length.FILL).clip(true);
        for (int i = 0; i < captions.length; i++) {
            figureValues[i] = gui.text("0").font(Type.MONO).textSize(TILE_FIGURE)
                    .textColor(theme.color(i == 0 ? Role.INK : Role.ACCENT)).wordWrap(false);
            Node caption = gui.text(captions[i]).font(Type.UI).textSize(Type.SMALL)
                    .textColor(theme.color(Role.FAINT)).wordWrap(false);
            // Each line in a cell that clips, so a tile narrower than its caption cuts it on one line rather than
            // wrapping it into a second line the tile has no room for.
            figures.append(column().width(Length.grow(1)).padding(Type.WIDE)
                    .background(theme.color(Look.TILE)).corner(Type.CORNER).clip(true)
                    .children(row().width(Length.FILL).clip(true).children(figureValues[i]),
                            row().width(Length.FILL).clip(true).children(caption)));
        }

        languageBar = row().width(Length.FILL).height(Length.dp(14)).corner(Length.dp(4)).clip(true)
                .scroll(false, false).background(theme.color(Look.TILE));
        for (int i = 0; i <= LANGUAGES; i++) {
            languageSegments[i] = gui.box().height(Length.FILL).width(Length.percent(0))
                    .background(languageColor(i)).visible(false);
            languageBar.append(languageSegments[i]);
        }
        languageLegend = row().gap(Type.WIDE).alignItems(AlignItems.CENTER).clip(true);
        for (int i = 0; i < LANGUAGES; i++) {
            legendSwatches[i] = gui.box().width(Length.dp(10)).height(Length.dp(10)).corner(Length.dp(2))
                    .background(languageColor(i));
            legendNames[i] = gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(theme.color(Role.DIM))
                    .wordWrap(false);
            legendEntries[i] = row().gap(Type.TIGHT).alignItems(AlignItems.CENTER).visible(false)
                    .children(legendSwatches[i], legendNames[i]);
            languageLegend.append(legendEntries[i]);
        }

        modules = new Bars(MODULES, Type.UI, Look.PICKING_RIM);
        modulesSection = section("modules", modules.node);
        annotations = new Bars(ANNOTATIONS, Type.MONO, Role.ACCENT);
        annotationsSection = section("annotations", annotations.node);

        // Not a sentence about what to do: an empty folder simply looks empty, a dash where the numbers would be.
        empty = gui.text("—").font(Type.UI).textSize(Type.FIGURE).textColor(theme.color(Role.FAINT)).visible(false);

        body = column().width(Length.FILL).gap(Type.EDGE).alignItems(AlignItems.STRETCH)
                .children(build, figures, column().gap(Type.GAP).alignItems(AlignItems.STRETCH)
                                .children(languageBar, languageLegend),
                        modulesSection, annotationsSection, empty);

        root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .padding(Type.EDGE)
                .gap(Type.WIDE)
                .alignItems(AlignItems.STRETCH)
                .background(theme.color(Look.PICKING))
                .corner(Type.CORNER)
                .border(Type.RULE, theme.color(Look.PICKING_RIM))
                .scroll(false, true)
                .clip(true)
                .visible(false)
                .children(name, path, body);
        gui.landmark(Landmarks.PREVIEW, root);
    }

    Node node() {
        return root;
    }

    /** The folder pictured, or null. */
    Path showing() {
        return showing;
    }

    /** Forget what was surveyed: a new visit to Open looks at the disk again. */
    void forget() {
        seen.clear();
    }

    /** Picture {@code folder}: its name now, the rest when the survey lands. Null is ignored. */
    void show(Path folder) {
        if (folder == null) {
            return;
        }
        Path dir = folder.toAbsolutePath().normalize();
        if (dir.equals(showing)) {
            return;
        }
        showing = dir;
        long mine = generation.incrementAndGet();
        Path fileName = dir.getFileName();
        name.text(fileName == null ? dir.toString() : fileName.toString());
        path.text(dir.toString());
        Survey known = seen.get(dir);
        if (known != null) {
            draw(known);
            return;
        }
        blank();
        worker.execute(() -> {
            if (generation.get() != mine) {
                return;
            }
            Survey s = Survey.of(dir, () -> generation.get() == mine, partial -> {
                if (generation.get() == mine) {
                    draw(partial);
                }
            });
            if (s == null) {
                return;
            }
            seen.put(dir, s);
            if (generation.get() == mine) {
                draw(s);
            }
        });
    }

    /**
     * Nothing but the name, for the moment before the first counts arrive: another folder's numbers under this one's
     * name would be a picture of the wrong thing.
     */
    private void blank() {
        for (Node n : new Node[] {buildKind, modulesChip, partOfChip, repoChip, figures, languageBar,
                languageLegend, modulesSection, annotationsSection, empty}) {
            n.visible(false);
        }
    }

    /**
     * Write {@code s} into the rows: the survey so far while it runs, so the numbers climb and the bars grow as the
     * folder is read, and then the whole of it. On the survey's thread; node writes are drained by the frame.
     */
    private void draw(Survey s) {
        buildKind.visible(s.maven());
        if (s.maven()) {
            buildKind.text((s.aggregator() ? "pom" : s.packaging()) + "  " + s.artifact());
        }
        modulesChip.visible(!s.modules().isEmpty());
        modulesChip.text(s.modules().size() + (s.modules().size() == 1 ? " module" : " modules"));
        partOfChip.visible(s.partOf() != null);
        partOfChip.text("in " + Objects.requireNonNullElse(s.partOf(), ""));
        repoChip.visible(s.repository());
        repoChip.text("git");

        int java = s.javaFiles();
        figures.visible(java > 0);
        int[] values = {java, s.classes(), s.records(), s.interfaces(), s.enums()};
        for (int i = 0; i < values.length; i++) {
            figureValues[i].text((i == 0 && s.truncated() ? "≥" : "") + values[i]);
        }

        List<Survey.Count> langs = s.languages();
        int total = langs.stream().mapToInt(Survey.Count::count).sum();
        languageBar.visible(total > 0);
        languageLegend.visible(total > 0);
        int other = total;
        for (int i = 0; i < LANGUAGES; i++) {
            boolean on = i < langs.size();
            int count = on ? langs.get(i).count() : 0;
            other -= count;
            languageSegments[i].visible(on).width(Length.percent(total == 0 ? 0 : 100f * count / total));
            legendEntries[i].visible(on);
            if (on) {
                legendNames[i].text(langs.get(i).name() + " " + count);
            }
        }
        languageSegments[LANGUAGES].visible(other > 0)
                .width(Length.percent(total == 0 ? 0 : 100f * Math.max(other, 0) / total));

        modulesSection.visible(!s.modules().isEmpty());
        modules.fill(s.modules().stream().map(m -> new Survey.Count(m.name(), m.javaFiles())).toList());
        annotationsSection.visible(!s.annotations().isEmpty());
        annotations.fill(s.annotations().stream().map(a -> new Survey.Count("@" + a.name(), a.count())).toList());

        empty.visible(total == 0 && !s.maven());
    }

    /**
     * A row that never scrolls. Every container scrolls on overflow by default, and one long name inside a section
     * would otherwise grow that section a scrollbar of its own: the card scrolls up and down as a whole and nothing
     * in it scrolls sideways, so what does not fit is cut off at the card's edge instead.
     */
    private Node row() {
        return gui.row().scroll(false, false);
    }

    /** A column that never scrolls, for the same reason as {@link #row()}. */
    private Node column() {
        return gui.column().scroll(false, false);
    }

    private Node chip(int face, Role edge) {
        return gui.text("").font(face).textSize(Type.SMALL).textColor(theme.color(Role.INK))
                .padding(Length.dp(3), Type.GAP).corner(Length.dp(10))
                .border(Type.RULE, theme.color(edge)).wordWrap(false).visible(false);
    }

    private Node section(String title, Node content) {
        Node heading = gui.text(title).font(Type.UI).textSize(Type.SMALL).textColor(theme.color(Role.FAINT));
        return column().gap(Type.TIGHT).alignItems(AlignItems.STRETCH).visible(false)
                .children(heading, content);
    }

    /**
     * The colour of the {@code i}th language: the accent's lightness and chroma, its hue turned a step per
     * language, so the bar reads as one family; the last, everything else, is a neutral.
     */
    private Color languageColor(int i) {
        if (i >= LANGUAGES) {
            return theme.color(Look.RIM);
        }
        Oklab accent = Look.PALETTE.accent();
        return Oklab.polar(0.74 - 0.04 * (i % 2), 0.10, accent.hueDegrees() + 52.0 * i).toColor();
    }

    /** Rows of a name, a bar against the largest, and a number: as many rows as are ever shown, filled or hidden. */
    private final class Bars {

        final Node node;
        private final Node[] rows;
        private final Node[] names;
        private final Node[] fills;
        private final Node[] counts;

        Bars(int size, int face, Role ink) {
            node = column().gap(Type.TIGHT).alignItems(AlignItems.STRETCH);
            rows = new Node[size];
            names = new Node[size];
            fills = new Node[size];
            counts = new Node[size];
            for (int i = 0; i < size; i++) {
                names[i] = gui.text("").font(face).textSize(Type.LABEL).textColor(theme.color(Role.INK))
                        .wordWrap(false);
                // The name in a cell of its own width that clips, as the tree does its labels: a text node is as
                // wide as its text, so sizing the text itself would not stop a long module name pushing the row.
                Node nameCell = row().width(NAME_COLUMN).alignItems(AlignItems.CENTER).clip(true)
                        .children(names[i]);
                fills[i] = gui.box().height(Length.FILL).width(Length.percent(0)).corner(Length.dp(3))
                        .background(theme.color(ink));
                Node track = row().width(Length.grow(1)).height(BAR).corner(Length.dp(3))
                        .background(theme.color(Look.TILE)).scroll(false, false).children(fills[i]);
                counts[i] = gui.text("").font(Type.MONO).textSize(Type.SMALL).textColor(theme.color(Role.DIM))
                        .wordWrap(false).width(COUNT_COLUMN);
                rows[i] = row().gap(Type.GAP).alignItems(AlignItems.CENTER).visible(false)
                        .children(nameCell, track, counts[i]);
                node.append(rows[i]);
            }
        }

        void fill(List<Survey.Count> items) {
            int max = items.stream().mapToInt(Survey.Count::count).max().orElse(0);
            for (int i = 0; i < rows.length; i++) {
                boolean on = i < items.size();
                rows[i].visible(on);
                if (on) {
                    Survey.Count c = items.get(i);
                    names[i].text(c.name());
                    fills[i].width(Length.percent(max == 0 ? 0 : Math.max(2f, 100f * c.count() / max)));
                    counts[i].text(String.valueOf(c.count()));
                }
            }
        }
    }
}
