package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * A look per window. Three {@code Gui} trees, three {@link Theme}s — and, because a palette is nine numbers
 * rather than a table of colours, two of the three are the framework's own look with an angle changed.
 *
 * <p><b>Why the windows differ at all.</b> They are different machines. The editor is the application; the file
 * tree is a drawer you pull out of it; the terminal is a session that outlives both and takes typed commands. A
 * glance at the taskbar should say which is which before any text is read, and hue is the cheapest thing a
 * glance resolves.
 *
 * <p><b>What a shift is allowed to touch.</b> {@link #tinted} rotates the <em>neutral family</em> — page, ink and
 * the depth colour, the three anchors both ladders and every shadow derive from — and leaves the chromatic
 * anchors alone. That keeps a shifted window's accent, its filled buttons and its danger red exactly where the
 * framework put them: a selection should read as a selection in every window, and a warm-grey drawer with a blue
 * selection is a normal-looking drawer, whereas rotating the accent with the greys turns a shift into a reskin.
 * {@link #PHOSPHOR} is the exception, and it is an exception on purpose — see its note.
 *
 * <p>Nothing here names a colour. Every value is an angle, a lightness or a chroma handed to
 * {@link Oklab#polar}, which is the same way {@code Palette.DARK} is authored.
 */
public final class Palettes {

    /**
     * A warm drawer: the same ladders and the same accent, swung round to the amber side of neutral — 163&deg;
     * from the &minus;93&deg; the framework's blue-greys sit at, which is far enough that no one has to be told
     * the windows are different.
     */
    private static final double MANILA = 70;

    /**
     * P1 phosphor, measured off the green a 5250 actually glowed: hue 145&deg;, and a chroma at full intensity
     * (0.25) that no grey ladder would ever carry. That is the tell that this one is not a shift of the others.
     */
    private static final double PHOSPHOR_HUE = 145;

    private Palettes() {
    }

    /** The editor: the framework's own look, unshifted. The other two read as departures from it. */
    public static final Theme EDITOR = Theme.DARK;

    /** The file tree, warmed. Same relationships, same accent, 163&deg; round the wheel. */
    public static final Theme FILES =
            Theme.of(tinted(Palette.DARK, MANILA, 1.0), Shading.ON_DARK, true, true);

    /**
     * The terminal: a green monochrome CRT, and the one palette here that is not {@code Palette.DARK} wearing a
     * different angle.
     *
     * <p>A phosphor screen has <b>one</b> colour. There is no accent to contrast with the ink and no red to warn
     * in, because the tube can only make the beam brighter or dimmer — so every chromatic anchor is the same
     * green at a different lightness, and the whole vocabulary collapses onto the ink ladder. That is the point:
     * roles that were separate decisions in a colour theme are allowed to converge here, and nothing had to be
     * forked for them to.
     *
     * <p>{@code depth} is the phosphor rather than a shadow colour, which is what turns {@code Node.elevation}
     * from a drop shadow into a bloom — the halo a bright glyph throws on the glass around it. One anchor, and
     * every raised thing in the window glows instead of casting.
     */
    public static final Theme PHOSPHOR = Theme.of(
            new Palette(
                    // The unlit tube: not black, because glass in a lit room never is.
                    Oklab.polar(0.150, 0.020, PHOSPHOR_HUE),
                    // A short surface step. A CRT has no panels, cards or elevation — only more or less beam.
                    0.028,
                    Oklab.polar(0.860, 0.255, PHOSPHOR_HUE),
                    0.300,
                    // Accent, action and danger: the same phosphor, hotter and cooler. See the note above.
                    Oklab.polar(0.900, 0.230, PHOSPHOR_HUE),
                    Oklab.polar(0.560, 0.190, PHOSPHOR_HUE),
                    Oklab.polar(0.620, 0.200, PHOSPHOR_HUE),
                    // Depth is the glow.
                    Oklab.polar(0.860, 0.255, PHOSPHOR_HUE),
                    0.42),
            // The beam responds harder than a painted surface does: hover blooms, press drops the gun.
            Shading.of(0.055, -0.045),
            true,
            false);

    // ------------------------------------------------------------------ app roles

    /**
     * Blown-out phosphor — brighter than {@link Role#INK}, which the ink ladder cannot reach because it only
     * fades <em>towards</em> the page. What bold, and every colour a monochrome screen cannot draw, becomes.
     *
     * <p>An application naming a role the framework never heard of is the whole reason {@code Role} is a function
     * and not an enum: this is one lambda, and it themes with everything else.
     */
    public static final Role HOT = p -> p.ink().atLightness(Math.min(1.0, p.ink().l() + 0.09)).toColor();

    /** The bezel the tube is set into — below the page, which is the direction {@code surface} runs backwards. */
    public static final Role BEZEL = p -> p.surface(-2);

    // ------------------------------------------------------------------ derivation

    /**
     * {@code base} with its neutral family rotated to {@code hueDegrees} and its tint scaled by
     * {@code chromaScale}. Lightness is untouched at every anchor, so the shifted palette keeps the original's
     * contrast exactly — the ladders land on the same lightnesses, every role keeps the perceptual step it had,
     * and the only thing that moved is which way the greys lean.
     */
    public static Palette tinted(Palette base, double hueDegrees, double chromaScale) {
        return new Palette(
                rehue(base.page(), hueDegrees, chromaScale),
                base.step(),
                rehue(base.ink(), hueDegrees, chromaScale),
                base.fade(),
                base.accent(),
                base.action(),
                base.danger(),
                rehue(base.depth(), hueDegrees, chromaScale),
                base.shadowAlpha());
    }

    /** One anchor, swung to a new hue at the same lightness. */
    private static Oklab rehue(Oklab c, double hueDegrees, double chromaScale) {
        return Oklab.polar(c.l(), c.chroma() * chromaScale, hueDegrees);
    }
}
