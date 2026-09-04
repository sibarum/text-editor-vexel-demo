package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;

/**
 * A look per window. Two {@code Gui} trees this file dresses, and — because a palette is nine numbers rather
 * than a table of colours — the second is the framework's own look with an angle changed.
 *
 * <p><b>Why the windows differ at all.</b> They are different machines. The editor is the application; the file
 * tree is a drawer you pull out of it. A glance at the taskbar should say which is which before any text is
 * read, and hue is the cheapest thing a glance resolves.
 *
 * <p>The third window this application shows — the MainFrame console — brings its own palette with it, in
 * {@code dev.mainframe.gui.console.Phosphor}. That is the right side of the line for it to be on: the console is
 * the same shell whether it opened out of this editor or out of nothing, and a window that had to be themed by
 * whoever embedded it would look different in every application that used it.
 *
 * <p><b>What a shift is allowed to touch.</b> {@link #tinted} rotates the <em>neutral family</em> — page, ink and
 * the depth colour, the three anchors both ladders and every shadow derive from — and leaves the chromatic
 * anchors alone. That keeps a shifted window's accent, its filled buttons and its danger red exactly where the
 * framework put them: a selection should read as a selection in every window, and a warm-grey drawer with a blue
 * selection is a normal-looking drawer, whereas rotating the accent with the greys turns a shift into a reskin.
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

    private Palettes() {
    }

    /** The editor: the framework's own look, unshifted. The file tree reads as a departure from it. */
    public static final Theme EDITOR = Theme.DARK;

    /** The file tree, warmed. Same relationships, same accent, 163&deg; round the wheel. */
    public static final Theme FILES =
            Theme.of(tinted(Palette.DARK, MANILA, 1.0), Shading.ON_DARK, Relief.STANDARD, true, true);

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
