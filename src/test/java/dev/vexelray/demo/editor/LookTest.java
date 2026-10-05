package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Hex;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette, held to what a dark theme has to be true of.
 *
 * <p>Worth having from the first day rather than the day a colour goes wrong, because a palette is derived:
 * one anchor moved by a tenth changes every surface and every shade of text at once, and nothing on screen
 * says which change did it. These are the checks that would have caught it.
 *
 * <p>When this application gets a real design, the useful version of this test is the one that <b>pins the gap
 * between the design and the construction</b> -- assert each authored colour against what the palette derives,
 * with the difference written down. A gap that is measured is a known quantity; a gap that is absorbed is a
 * surprise waiting for a review.
 */
class LookTest {

    @Test
    void theInkReadsAgainstThePage() {
        Color page = Look.THEME.color(Role.PAGE);
        Color ink = Look.THEME.color(Role.INK);
        assertTrue(luminance(ink) - luminance(page) > 0.5f,
                "primary text should be far lighter than the page it sits on");
    }

    @Test
    void surfacesClimbAwayFromThePage() {
        float page = luminance(Look.THEME.color(Role.PAGE));
        float panel = luminance(Look.THEME.color(Role.PANEL));
        float raised = luminance(Look.THEME.color(Role.RAISED));
        assertTrue(panel > page, "a panel should sit above the page");
        assertTrue(raised > panel, "a raised surface should sit above a panel");
    }

    @Test
    void textFadesInSteps() {
        float ink = luminance(Look.THEME.color(Role.INK));
        float dim = luminance(Look.THEME.color(Role.DIM));
        float faint = luminance(Look.THEME.color(Role.FAINT));
        assertTrue(ink > dim, "dim text should be quieter than primary text");
        assertTrue(dim > faint, "faint text should be quieter than dim text");
    }

    @Test
    void theAccentIsNotTheInk() {
        assertNotEquals(Look.THEME.color(Role.INK), Look.THEME.color(Role.ACCENT),
                "the accent should be a colour, not the text shade");
    }

    /**
     * Each colour the design shows, against what the construction derives for it. The tolerance is an Oklab
     * distance; 0.02 is about where two flat swatches side by side stop looking the same. Glyph colours are
     * sampled at a glyph's brightest pixel, so they were measured a little dark and get the looser bound.
     */
    @Test
    void theConstructionLandsOnTheDesign() {
        assertNear("#07080c", Look.THEME.color(Role.PAGE), 0.01, "page");
        assertNear("#0e1218", Look.THEME.color(Look.CARD), 0.01, "card");
        assertNear("#141c25", Look.THEME.color(Role.PANEL), 0.01, "current line, card edge");
        assertNear("#17262c", Look.THEME.color(Role.SELECTION), 0.015, "selected row");
        assertNear("#313a47", Look.THEME.color(Role.GRIP), 0.02, "scrollbar thumb");
        assertNear("#eaf0f5", Look.THEME.color(Role.INK), 0.01, "heading ink");
        assertNear("#9fa6b3", Look.THEME.color(Role.DIM), 0.02, "a label");
        assertNear("#63d5e1", Look.THEME.color(Role.ACCENT), 0.01, "accent");
    }

    private static void assertNear(String hex, Color derived, double tolerance, String what) {
        Oklab want = Oklab.of(Hex.parse(hex).orElseThrow());
        Oklab got = Oklab.of(derived);
        double d = Math.sqrt(Math.pow(want.l() - got.l(), 2) + Math.pow(want.a() - got.a(), 2)
                + Math.pow(want.b() - got.b(), 2));
        assertTrue(d <= tolerance, what + ": design " + hex + " is " + String.format("%.4f", d)
                + " from what the palette derives");
    }

    /** Rough perceived brightness -- enough to order two shades, which is all these assertions need. */
    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }
}
