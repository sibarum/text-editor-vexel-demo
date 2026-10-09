package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * The palette, as anchors.
 *
 * <h2>A palette is a construction, not a list of colours</h2>
 *
 * <p>{@link Palette} takes a handful of anchors and derives the rest: surfaces are an even ladder from the page
 * at a fixed lightness step, and text is the ink blended towards the page at a fixed rate. That is the point --
 * every surface in the application stays in step with every other one for free, and a design change is a
 * number here rather than fifteen hex codes spread across the tree.
 *
 * <h2>Measured from the October 2026 design</h2>
 *
 * <p>Every anchor below was sampled from the design's mockup and converted to Oklab; the hex it was measured
 * from is in its comment, and {@code LookTest} pins each authored colour against what the construction gives.
 * The design turned out to be six decisions: a near-black page, cards one step up from it, a ladder of
 * 0.044 that lands on the current-line, selected-row and edge colours, a cool neutral ink fading
 * 0.284 a step, and one teal accent. Text sampled from glyphs is read at its brightest pixel, so the
 * thin faces (line numbers, dim labels) were measured a little dark and the anchors sit slightly above them.
 *
 * <p>Two things in the design are not where the framework's roles put them, and are overridden by name in
 * {@link #THEME} rather than absorbed: a selected row is tinted with the accent, and a scrollbar thumb is quiet.
 */
final class Look {

    // ---------------------------------------------------------------- anchors

    /** The page: the window behind the cards, the title bar and the status line. Measured {@code #07080c}. */
    private static final Oklab PAGE = Oklab.polar(0.1351, 0.0100, -95.0);

    /** Primary text: a heading, the selected tab. Measured {@code #eaf0f5}. */
    private static final Oklab INK = Oklab.polar(0.9520, 0.0093, -117.2);

    /** The one chromatic decision: the active tab's mark, the caret, a focus ring. Measured {@code #63d5e1}. */
    private static final Oklab ACCENT = Oklab.polar(0.8100, 0.1040, -153.0);

    /**
     * The fill of a filled control. The accent's hue, dark enough that {@code Palette.contrastTo} puts the ink on
     * it rather than the page -- the design has no filled control, so this is derived, not measured.
     */
    private static final Oklab ACTION = Oklab.polar(0.5000, 0.0900, -153.0);

    /** Destructive: the accent's chroma taken round to red, dark enough to carry a white label. */
    private static final Oklab DANGER = Oklab.polar(0.5600, 0.1500, 22.0);

    /** Shadows: near-black, at the page's hue rather than a neutral grey. */
    private static final Oklab DEPTH = Oklab.polar(0.0800, 0.0080, -95.0);

    /**
     * How far one surface is from the next. One step is a card ({@code #0e1218}), two the current line and the
     * cards' edges ({@code #141c25}), three a selected row ({@code #17262c}).
     */
    private static final double STEP = 0.044;

    /** How fast the ink fades towards the page: text(1) is a label ({@code #9fa6b3}), text(2) a line number. */
    private static final double FADE = 0.284;

    /** How dark a shadow is. */
    private static final double SHADOW_ALPHA = 0.60;

    static final Palette PALETTE =
            new Palette(PAGE, STEP, INK, FADE, ACCENT, ACTION, DANGER, DEPTH, SHADOW_ALPHA);

    // ------------------------------------------------------------ own roles

    /** A card: the navigator, and the tabs with their document. The framework's CHROME, named for what it is here. */
    static final Role CARD = Role.CHROME;

    /** The hairline round a card, and every other line in this design: one step above the card it bounds. */
    static final Role RIM = p -> p.surface(2);

    /**
     * A selected row: three steps up, carrying a little of the accent's hue. The framework's SELECTION is a
     * neutral level 4, which in this design reads as a grey bar; the design's row is measured at chroma 0.023 on
     * a teal hue.
     */
    static final Role SELECTED = p -> {
        Oklab level = Oklab.of(p.surface(3));
        return Oklab.polar(level.l(), 0.024, p.accent().hueDegrees()).toColor();
    };

    /**
     * The navigator's card in Open: the card's level a step up, steeped in the accent's hue, so picking a root reads
     * as somewhere else at a glance. Darker than {@link #SELECTED}, so the selected row still stands out on it.
     */
    static final Role PICKING = p -> {
        Oklab level = Oklab.of(p.surface(2));
        return Oklab.polar(level.l(), 0.030, p.accent().hueDegrees()).toColor();
    };

    /** The hairline round the card in Open: the accent itself, where Edit's is {@link #RIM}. */
    static final Role PICKING_RIM = p -> p.accent().toColor();

    /** A scrollbar thumb: level 5 ({@code #313a47}), where the framework's GRIP is a loud level 10. */
    static final Role THUMB = p -> p.surface(5);

    // ---------------------------------------------------------------- theme

    /**
     * Lit surfaces off, letterpress off: the design is flat. Selection, grip and line are overridden by identity --
     * {@link Theme}'s own advice for special-casing a role -- which reaches every widget that names them.
     */
    static final Theme THEME = new Theme() {
        private final Theme base = Theme.of(PALETTE, Shading.ON_DARK, Relief.STANDARD, false, false);

        @Override
        public Palette palette() {
            return base.palette();
        }

        @Override
        public Shading shading() {
            return base.shading();
        }

        @Override
        public Relief relief() {
            return base.relief();
        }

        @Override
        public boolean lit() {
            return base.lit();
        }

        @Override
        public boolean letterpress() {
            return base.letterpress();
        }

        @Override
        public Color color(Role role) {
            return base.color(mapped(role));
        }

        @Override
        public Color color(Role role, InteractionState state) {
            return base.color(mapped(role), state);
        }

        private Role mapped(Role role) {
            if (role == Role.SELECTION) {
                return SELECTED;
            }
            if (role == Role.GRIP) {
                return THUMB;
            }
            if (role == Role.LINE) {
                return RIM;
            }
            return role;
        }
    };

    private Look() {
    }
}
