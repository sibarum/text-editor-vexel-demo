package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.demo.editor.core.Grammars;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.FocusRing;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

import java.util.List;

/**
 * The two looks, dark and light, as anchors. The dark one is measured from the October 2026 design; the light one is
 * authored in the same family. The person chooses between them in the app menu (Theme, then Light or Dark), and the
 * framework remembers the choice and re-themes every window with it.
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
 * {@link #theme} rather than absorbed: a selected row is tinted with the accent, a scrollbar thumb is quiet, every line
 * is one step above the card it bounds, a well (a field, a tree set into a card) is cut down to the page rather than
 * below it, and the focus ring is a quiet neutral grey rather than the accent, since colour here says which mode the
 * navigator is in.
 *
 * <h2>Two looks, each authored</h2>
 *
 * <p>The light look is not the dark one reflected. A mirror of the dark page (L 0.135) lands at 0.87, a grey, where a
 * light page wants 0.975; the teal accent at 0.81 is near-invisible on white and has to come down to about half; and a
 * shadow that is depth on a dark page is a smudge on a light one. The framework derives neither look, on purpose.
 *
 * <p>Most of this application's roles are built from the palette ({@code p.surface(n)}, {@code p.accent()}), so they
 * follow the look by construction: on the light page {@code surface(n)} steps <em>down</em>, so a card is a step
 * darker than the page and its rim a step darker still, which is the same relation the dark look has. A role that
 * names a fixed lightness (Symbols' violet, the focus rings, the code's colours) has an answer in each look instead,
 * chosen by {@link #light}.
 *
 * <h2>Write the role, never the colour</h2>
 *
 * <p>A node that holds a {@link Role} is repainted in the new look, and fades to it; a node handed
 * {@code gui.theme().color(role)} keeps the colour it was given, forever. So everything in this application writes
 * roles, the code's colours included ({@link #CODE}).
 */
final class Look {

    // ---------------------------------------------------------------- the dark look

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

    static final Palette DARK_PALETTE =
            new Palette(PAGE, STEP, INK, FADE, ACCENT, ACTION, DANGER, DEPTH, SHADOW_ALPHA);

    // ---------------------------------------------------------------- the light look

    /** A near-white page at the dark page's hue, so the two read as one family; 0.975 rather than white, to rest the eye. */
    private static final Oklab LIGHT_PAGE = Oklab.polar(0.9750, 0.0060, -95.0);

    /** Primary text: a deep slate at the ink's own cool hue rather than black, which is harsh on a near-white page. */
    private static final Oklab LIGHT_INK = Oklab.polar(0.2500, 0.0180, -117.2);

    /**
     * The teal, brought down from 0.81 to 0.51 so the caret, a link, Open's rim and the git colours derived from it
     * (Role.CAUTION, Role.SUCCESS sit at its lightness) read on the page at 4.5:1 or better.
     */
    private static final Oklab LIGHT_ACCENT = Oklab.polar(0.5100, 0.1000, -153.0);

    /** A filled control's fill: the accent's hue, a little deeper than it, so the white label it gets has room. */
    private static final Oklab LIGHT_ACTION = Oklab.polar(0.4600, 0.0900, -153.0);

    /** Danger: darker and a little stronger than the dark look's, to read as text on white as well as carry a label. */
    private static final Oklab LIGHT_DANGER = Oklab.polar(0.5200, 0.1700, 22.0);

    /** Shadows at the page's hue, lighter than the dark look's: on a light page depth is a hint, not a void. */
    private static final Oklab LIGHT_DEPTH = Oklab.polar(0.2000, 0.0080, -95.0);

    /**
     * A smaller step than the dark look's 0.044: the code sits on the card, and a card a whole 0.044 down from the page
     * is a grey that costs every code colour contrast. 0.025 still parts card, rim and selected row at a glance.
     */
    private static final double LIGHT_STEP = 0.025;

    /** A little faster than the dark look's fade, which on a light page leaves the label too close to the ink. */
    private static final double LIGHT_FADE = 0.300;

    /** A shadow at about a third of the dark look's strength. */
    private static final double LIGHT_SHADOW_ALPHA = 0.18;

    static final Palette LIGHT_PALETTE = new Palette(LIGHT_PAGE, LIGHT_STEP, LIGHT_INK, LIGHT_FADE, LIGHT_ACCENT,
            LIGHT_ACTION, LIGHT_DANGER, LIGHT_DEPTH, LIGHT_SHADOW_ALPHA);

    /** Whether {@code p} is the light look's palette: how a role of our own picks its answer. */
    static boolean light(Palette p) {
        return p.equals(LIGHT_PALETTE);
    }

    // ---------------------------------------------------------------- roles of our own

    /** A card: the navigator, and the tabs with their document. The framework's CHROME, named for what it is here. */
    static final Role CARD = Role.CHROME;

    /** The hairline round a card, and every other line in this design: one step above the card it bounds. */
    static final Role RIM = p -> p.surface(2);

    /** The selected tab's header: two steps up, neutral, so the accent mark under it is the only colour there. */
    static final Role TAB = p -> p.surface(2);

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
     * The ring round whatever has the keyboard: a neutral grey, the page's hue at no chroma, four tenths of the way
     * from the page to the ink. Not teal: the accent's hue steeps the navigator in Open, and violet is Symbols', so a
     * coloured ring read as a mode the window was not in (found 2026-11-13). Not loud either: the full accent round a
     * whole card pulled the eye off the text (found in 0.1). Symbols rings its own controls in its violet
     * ({@link #SEARCH_FOCUS}), since there the mode's colour is the right thing to say. In the light look it goes
     * half way, since four tenths of the way to the ink is under 3:1 against a light card.
     */
    static final Role FOCUS = p -> {
        double way = light(p) ? 0.50 : 0.40;
        double l = p.page().l() + way * (p.ink().l() - p.page().l());
        return Oklab.polar(l, 0.0, p.page().hueDegrees()).toColor();
    };

    /**
     * The wash over selected text and under a pressed chip. The framework's own, the accent at 0.35, in the dark look;
     * in the light one the accent at 0.20, since the light accent is dark and at 0.35 its wash is so deep that the
     * pressed chip's teal label (the folder's name, in Open) fell under 3:1 on it.
     */
    static final Role WASH = p -> light(p) ? p.accent().toColor(0.20f) : Role.HIGHLIGHT.of(p);

    /**
     * Flat: lit surfaces and letterpress off, since the design's cards stand off the page by colour and edge alone.
     * Selection, grip and line are overridden by identity, which reaches every widget that names them.
     */
    static final Theme DARK = theme(DARK_PALETTE, Shading.ON_DARK);

    /** The same decisions on the light page: flat, with hover and press shading that darkens rather than lightens. */
    static final Theme LIGHT = theme(LIGHT_PALETTE, Shading.ON_LIGHT);

    private static Theme theme(Palette palette, Shading shading) {
        return Theme.of(palette, shading, Relief.STANDARD, false, false,
                        FocusRing.STANDARD.color(FOCUS).width(Length.rem(0.1f)))
                .map(Role.SELECTION, SELECTED)
                .map(Role.GRIP, THUMB)
                .map(Role.LINE, RIM)
                .map(Role.WELL, Role.PAGE)
                .map(Role.HIGHLIGHT, WASH);
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

    /**
     * Symbols' hue, a violet: the one mode that is not the accent's, so the navigator searching reads as neither editing
     * (neutral) nor Open (teal) at a glance. Far round the wheel from the teal accent ({@code -153}) and the red of
     * danger ({@code 22}), and clear of the code's blue keywords, which sit near {@code 265}.
     */
    private static final double SEARCH_HUE = 318.0;

    /** The navigator in Symbols: the panel's level steeped in violet, as {@link #PICKING} is in teal for Open. */
    static final Role SEARCHING = p -> {
        Oklab level = Oklab.of(p.surface(2));
        return Oklab.polar(level.l(), 0.050, SEARCH_HUE).toColor();
    };

    /** Symbols' violet at full voice on the dark page. */
    private static final Color DARK_SEARCHING_RIM = Oklab.polar(0.74, 0.13, SEARCH_HUE).toColor();

    /** The same violet brought down to 0.50, where it reads as Symbols' header text on its light, violet card. */
    private static final Color LIGHT_SEARCHING_RIM = Oklab.polar(0.50, 0.13, SEARCH_HUE).toColor();

    /** The hairline round the navigator in Symbols, its header and its icon: the violet at full voice. */
    static final Role SEARCHING_RIM = p -> light(p) ? LIGHT_SEARCHING_RIM : DARK_SEARCHING_RIM;

    /**
     * The ring round Symbols' field or list while it has the keyboard: built as {@link #FOCUS} is, but in violet and a
     * little louder, so it reads on the violet card. The theme's teal ring there said Open, in a mode that is not.
     */
    static final Role SEARCH_FOCUS = p -> {
        double way = light(p) ? 0.55 : 0.45;
        double l = p.page().l() + way * (p.ink().l() - p.page().l());
        return Oklab.polar(l, 0.080, SEARCH_HUE).toColor();
    };

    /** A selected declaration in Symbols: two steps above {@link #SEARCHING}, in its hue, where elsewhere it is teal. */
    static final Role SEARCH_SELECTED = p -> {
        Oklab level = Oklab.of(p.surface(4));
        return Oklab.polar(level.l(), 0.060, SEARCH_HUE).toColor();
    };

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

    /** How many languages Open's picture colours by name; the rest are one neutral ({@link #language}). */
    static final int LANGUAGES = 6;

    /**
     * The languages' colours in Open's picture: the accent's hue turned a step per language, so the bar reads as one
     * family, alternating a little in lightness; the last, everything else, is the rim's neutral. On the dark page at
     * 0.74 and 0.70, chroma 0.10; on the light one at 0.56 and 0.52 and a little stronger, so a segment stands off
     * the light tile at 3:1.
     * Roles held once, so a re-pointed segment writes an equal paint.
     */
    private static final Role[] LANGUAGE = new Role[LANGUAGES + 1];

    static {
        for (int i = 0; i < LANGUAGES; i++) {
            double turn = 52.0 * i;
            Color dark = Oklab.polar(0.74 - 0.04 * (i % 2), 0.10, ACCENT.hueDegrees() + turn).toColor();
            Color light = Oklab.polar(0.56 - 0.04 * (i % 2), 0.12, LIGHT_ACCENT.hueDegrees() + turn).toColor();
            LANGUAGE[i] = p -> light(p) ? light : dark;
        }
        LANGUAGE[LANGUAGES] = RIM;
    }

    /** The {@code i}th language's colour, {@code i} from 0 to {@link #LANGUAGES}, the last being everything else. */
    static Role language(int i) {
        return LANGUAGE[Math.min(i, LANGUAGES)];
    }

    /**
     * The code colours: a TextMate scope prefix and its colour in each look, the first rule naming a token's most
     * specific scope winning ({@link Grammars}). Each is a role, so the spans a document was marked with follow the
     * person's choice with nothing re-marked.
     *
     * <p>The dark set is the design's, measured where it shows them -- keywords (#8aa9f0), types (#7dd3e6), numbers
     * (#e5a477), comments (#7f8994), plain names and punctuation -- and the rest moved to sit with those. Comments are
     * italic in the design; a span carries no style, so they are not. Carried from the 0.1 editor.
     *
     * <p>The light set is authored, hue for hue: each dark colour taken down until it reads at 4.5:1 on the light card
     * the code sits on (LookTest holds it there), keeping its hue so a keyword is the same blue in both looks. Comment
     * and punctuation stay the quietest, as they are in the dark set.
     */
    static final List<Grammars.Colour> CODE = List.of(
            code("comment", 0x7f8994, 0x5d6672),
            code("string", 0xa6d189, 0x3b7520),
            code("constant.numeric", 0xe5a477, 0x9c4e12),
            code("constant.language", 0xe5a477, 0x9c4e12),
            // CSS keeps its interesting values here: #fff, auto, print, and the rest of the keyword values.
            code("constant.other", 0xe5a477, 0x9c4e12),
            code("support.constant", 0xe5a477, 0x9c4e12),
            code("keyword", 0x8aa9f0, 0x2f55c0),
            code("storage", 0x8aa9f0, 0x2f55c0),
            code("entity.name.function", 0xe3c08d, 0x805400),
            code("entity.name.type", 0x7dd3e6, 0x0a6b7d),
            code("support.type", 0x7dd3e6, 0x0a6b7d),
            code("support.class", 0x7dd3e6, 0x0a6b7d),
            code("variable.parameter", 0xe8d4b0, 0x765232),
            // Shell and PowerShell are mostly variables, and an uncoloured $name reads as prose. This sits a step off
            // the default ink rather than shouting, and it tints plain identifiers in JS and Java too.
            code("variable.other", 0xc8d3e2, 0x34475e),
            code("entity.name.section", 0x8aa9f0, 0x2f55c0),
            code("entity.name.tag", 0xf7768e, 0xb52a48),
            code("entity.other.attribute-name", 0x7dcfff, 0x1c66a8),
            code("support.function", 0x7dd3e6, 0x0a6b7d),
            code("markup.heading", 0x8aa9f0, 0x2f55c0),
            code("markup.bold", 0xe3c08d, 0x805400),
            code("markup.italic", 0x8aa9f0, 0x2f55c0),
            code("markup.inline.raw", 0x7dd3e6, 0x0a6b7d),
            code("markup.fenced_code", 0x7dd3e6, 0x0a6b7d),
            code("markup.underline.link", 0x73daca, 0x0d7062),
            code("markup.quote", 0x7f8994, 0x5d6672),
            // A diff without red and green is not a diff. These three carry .diff files, and Markdown's ```diff
            // fences with them.
            code("markup.inserted", 0xa6d189, 0x3b7520),
            code("markup.deleted", 0xf7768e, 0xb52a48),
            code("markup.changed", 0xe3c08d, 0x805400),
            // The ---/+++/@@ furniture around the hunks, which the grammar scopes as meta, not markup.
            code("meta.diff", 0x8aa9f0, 0x2f55c0),
            code("punctuation", 0x8b95a1, 0x5a6470));

    /** One rule: {@code scope} in {@code dark} on the dark page and {@code light} on the light one. */
    private static Grammars.Colour code(String scope, int dark, int light) {
        Color d = Color.rgb(dark);
        Color l = Color.rgb(light);
        Role role = p -> light(p) ? l : d;
        return new Grammars.Colour(scope, role);
    }

    private Look() {
    }
}
