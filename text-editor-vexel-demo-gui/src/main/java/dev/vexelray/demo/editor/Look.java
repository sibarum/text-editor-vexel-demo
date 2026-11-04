package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.demo.editor.core.Grammars;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.FocusRing;
import dev.vexelray.gui.core.style.IconEffects;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

import java.util.List;

/**
 * The palette, as anchors, measured from the October 2026 design.
 *
 * <h2>A palette is a construction, not a list of colours</h2>
 *
 * <p>{@link Palette} takes a handful of anchors and derives the rest: surfaces are an even ladder from the page
 * at a fixed lightness step, and text is the ink blended towards the page at a fixed rate. Every surface in the
 * application stays in step with every other one for free, and a design change is a number here.
 *
 * <h2>The design is six decisions</h2>
 *
 * <p>Carried from the 0.1 editor, which measured them from the design's mockup in Oklab: a near-black page, cards one
 * step up from it, a ladder of 0.044 that lands on the current-line, selected-row and edge colours, a cool neutral ink
 * fading 0.284 a step, and one teal accent. The hex each was measured from is in its comment.
 *
 * <p>Five things in the design are not where the framework's roles put them, and are overridden by name in
 * {@link #THEME} rather than absorbed: a selected row is tinted with the accent, a scrollbar thumb is quiet, every line
 * is one step above the card it bounds, a well (a field, a tree set into a card) is cut down to the page rather than
 * below it, and the focus ring is a quiet teal rather than the full accent.
 */
final class Look {

    // ---------------------------------------------------------------- anchors

    /** The page: the window behind the cards, the title bar and the status line. Measured {@code #07080c}. */
    private static final Oklab PAGE = Oklab.polar(0.1351, 0.0100, -95.0);

    /** Primary text: a heading, the selected tab. Measured {@code #eaf0f5}. */
    private static final Oklab INK = Oklab.polar(0.9520, 0.0093, -117.2);

    /** The one chromatic decision: the active tab's mark, the caret, the divider. Measured {@code #63d5e1}. */
    private static final Oklab ACCENT = Oklab.polar(0.8100, 0.1040, -153.0);

    /** The fill of a filled control: the accent's hue, dark enough that the ink reads on it. Derived, not measured. */
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

    // ---------------------------------------------------------------- roles of our own

    /** A card: the navigator, and the tabs with their document. The framework's CHROME, named for what it is here. */
    static final Role CARD = Role.CHROME;

    /** The hairline round a card, and every other line in this design: one step above the card it bounds. */
    static final Role RIM = p -> p.surface(2);

    /** The selected tab's header: two steps up, neutral, so the accent mark under it is the only colour there. */
    static final Role TAB = p -> p.surface(2);

    /**
     * A header at rest: {@link #TAB}'s colour with none of it showing, so selecting or leaving a header fades its
     * background rather than cutting it (a fade from transparent black would change it in one frame).
     */
    static final Role TAB_REST = p -> Color.withAlpha(p.surface(2), 0f);

    /**
     * A selected row: three steps up, carrying a little of the accent's hue. The framework's SELECTION is a neutral
     * level 4, which in this design reads as a grey bar; the design's row is measured at chroma 0.023 on a teal hue.
     */
    static final Role SELECTED = p -> {
        Oklab level = Oklab.of(p.surface(3));
        return Oklab.polar(level.l(), 0.024, p.accent().hueDegrees()).toColor();
    };

    /** A scrollbar thumb: level 5 ({@code #313a47}), where the framework's GRIP is a loud level 10. */
    static final Role THUMB = p -> p.surface(5);

    /**
     * The ring round whatever has the keyboard: the accent's hue at under half its chroma, three tenths of the way
     * from the page to the ink. The full accent round a whole card pulled the eye off the text (found in 0.1).
     */
    static final Role FOCUS = p -> {
        double l = p.page().l() + 0.30 * (p.ink().l() - p.page().l());
        return Oklab.polar(l, 0.0400, p.accent().hueDegrees()).toColor();
    };

    /**
     * Flat: lit surfaces and letterpress off, since the design's cards stand off the page by colour and edge alone.
     * Selection, grip and line are overridden by identity, which reaches every widget that names them.
     */
    static final Theme THEME = theme(PALETTE);

    private static Theme theme(Palette palette) {
        Theme base = Theme.of(palette, Shading.ON_DARK, Relief.STANDARD, false, false,
                FocusRing.STANDARD.color(FOCUS).width(Length.rem(0.1f)));
        return new Theme() {
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
            public FocusRing focusRing() {
                return base.focusRing();
            }

            @Override
            public IconEffects icons() {
                return base.icons();
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
                if (role == Role.WELL) {
                    return Role.PAGE;
                }
                return role;
            }
        };
    }

    /**
     * The navigator in Open: the panel's level, steeped in the accent's hue, so picking a folder reads as somewhere
     * else at a glance. Carried from the 0.1 editor.
     */
    static final Role PICKING = p -> {
        Oklab level = Oklab.of(p.surface(2));
        return Oklab.polar(level.l(), 0.030, p.accent().hueDegrees()).toColor();
    };

    /** The hairline round the navigator in Open: the accent itself. */
    static final Role PICKING_RIM = p -> p.accent().toColor();

    /** A tile or a bar's track in Open's picture: a step above {@link #PICKING}, in the same hue. */
    static final Role TILE = p -> {
        Oklab level = Oklab.of(p.surface(3));
        return Oklab.polar(level.l(), 0.034, p.accent().hueDegrees()).toColor();
    };

    /**
     * A folder under the pointer in Open: well above the card and strongly in the accent's hue, where a hovered row
     * elsewhere is a quiet neutral. A click there changes the whole window, and the row says so by how it lights.
     */
    static final Role PICK_HOVER = p -> {
        Oklab level = Oklab.of(p.surface(5));
        return Oklab.polar(level.l(), 0.070, p.accent().hueDegrees()).toColor();
    };

    /**
     * The code colours: a TextMate scope prefix and its colour, the first rule naming a token's most specific scope
     * winning ({@link Grammars}). The design's, measured where it shows them -- keywords (#8aa9f0), types (#7dd3e6),
     * numbers (#e5a477), comments (#7f8994), plain names and punctuation -- and the rest moved to sit with those.
     * Comments are italic in the design; a span carries no style, so they are not. Carried from the 0.1 editor.
     */
    static final List<Grammars.Colour> CODE = List.of(
            code("comment", 0x7f8994),
            code("string", 0xa6d189),
            code("constant.numeric", 0xe5a477),
            code("constant.language", 0xe5a477),
            // CSS keeps its interesting values here: #fff, auto, print, and the rest of the keyword values.
            code("constant.other", 0xe5a477),
            code("support.constant", 0xe5a477),
            code("keyword", 0x8aa9f0),
            code("storage", 0x8aa9f0),
            code("entity.name.function", 0xe3c08d),
            code("entity.name.type", 0x7dd3e6),
            code("support.type", 0x7dd3e6),
            code("support.class", 0x7dd3e6),
            code("variable.parameter", 0xe8d4b0),
            // Shell and PowerShell are mostly variables, and an uncoloured $name reads as prose. This sits a step off
            // the default ink rather than shouting, and it tints plain identifiers in JS and Java too.
            code("variable.other", 0xc8d3e2),
            code("entity.name.section", 0x8aa9f0),
            code("entity.name.tag", 0xf7768e),
            code("entity.other.attribute-name", 0x7dcfff),
            code("support.function", 0x7dd3e6),
            code("markup.heading", 0x8aa9f0),
            code("markup.bold", 0xe3c08d),
            code("markup.italic", 0x8aa9f0),
            code("markup.inline.raw", 0x7dd3e6),
            code("markup.fenced_code", 0x7dd3e6),
            code("markup.underline.link", 0x73daca),
            code("markup.quote", 0x7f8994),
            // A diff without red and green is not a diff. These three carry .diff files, and Markdown's ```diff
            // fences with them.
            code("markup.inserted", 0xa6d189),
            code("markup.deleted", 0xf7768e),
            code("markup.changed", 0xe3c08d),
            // The ---/+++/@@ furniture around the hunks, which the grammar scopes as meta, not markup.
            code("meta.diff", 0x8aa9f0),
            code("punctuation", 0x8b95a1));

    private static Grammars.Colour code(String scope, int rgb) {
        return new Grammars.Colour(scope, Color.rgb(rgb));
    }

    private Look() {
    }
}
