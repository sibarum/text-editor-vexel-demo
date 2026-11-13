package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.demo.editor.core.Grammars;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both looks, each held to what a look has to be true of, whichever way its page faces.
 *
 * <p>Worth having from the first day rather than the day a colour goes wrong, because a palette is derived:
 * one anchor moved by a tenth changes every surface and every shade of text at once, and nothing on screen
 * says which change did it. These are the checks that would have caught it.
 *
 * <p>Every check is written as a <b>distance from the page</b>, not as "lighter" or "darker", so one test holds the
 * dark look and the light one alike. Contrast is WCAG's ratio: 4.5 for body text, 3 for what only has to be seen.
 * The code sits on the card, not the page, so its colours are held against the card, which in both looks is the
 * harder of the two.
 */
class LookTest {

    static Stream<Theme> looks() {
        return Stream.of(Look.DARK, Look.LIGHT);
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theInkReadsAgainstThePage(Theme look) {
        assertReads(look, Role.INK, Role.PAGE, 7.0, "primary text");
        assertReads(look, Role.DIM, Role.PAGE, 4.5, "dim text");
        assertReads(look, Role.INK, Look.CARD, 7.0, "primary text on a card");
        assertReads(look, Role.DIM, Look.CARD, 4.5, "a tab's label on its card");
        assertReads(look, Role.INK, Look.SELECTED, 7.0, "a selected row's name");
    }

    /** Faint text: a line number, a folder part, the empty-state hint. */
    @ParameterizedTest
    @MethodSource("looks")
    void faintTextIsStillSeen(Theme look) {
        assertReads(look, Role.FAINT, Role.PAGE, 3.0, "faint text on the page");
        assertReads(look, Role.FAINT, Look.CARD, 3.0, "faint text on a card");
    }

    /** The accent is the caret, a link and Open's rim; in the light look the git colours sit at its lightness. */
    @ParameterizedTest
    @MethodSource("looks")
    void theAccentAndTheGitColoursRead(Theme look) {
        assertReads(look, Role.ACCENT, Look.CARD, 4.5, "the accent, as a link and a figure");
        assertReads(look, Role.CAUTION, Look.CARD, 4.5, "a modified file's name");
        assertReads(look, Role.SUCCESS, Look.CARD, 4.5, "an added file's name");
        assertReads(look, Role.DANGER, Look.CARD, look == Look.DARK ? 3.0 : 4.5, "a conflicted file's name");
    }

    /** The application's own roles, each read against what it is drawn on. */
    @ParameterizedTest
    @MethodSource("looks")
    void everyRoleOfOurOwnReadsWhereItIsDrawn(Theme look) {
        // The dark rings are the design's, quiet on purpose (Look.FOCUS): 2.7:1 and 2.8:1. The light ones are held to 3.
        assertReads(look, Look.FOCUS, Look.CARD, look == Look.DARK ? 2.5 : 3.0, "the focus ring");
        assertReads(look, Look.SEARCHING_RIM, Look.SEARCHING, 4.5, "Symbols' header on its card");
        assertReads(look, Look.SEARCH_FOCUS, Look.SEARCHING, look == Look.DARK ? 2.5 : 3.0, "Symbols' focus ring");
        assertReads(look, Role.INK, Look.SEARCH_SELECTED, 7.0, "a selected declaration");
        assertReads(look, Role.DIM, Look.SEARCHING, 4.5, "a declaration's detail");
        assertReads(look, Look.PICKING_RIM, Look.PICKING, 3.0, "Open's rim on its card");
        assertReads(look, Role.INK, Look.PICK_HOVER, 7.0, "a folder under the pointer in Open");
        assertReads(look, Role.ACCENT, Look.TILE, 3.0, "a figure on its tile, large text");
        // Open's chip, pressed: the accent over the wash, over the card steeped for Open.
        Color chip = over(look.color(Role.HIGHLIGHT), look.color(Look.PICKING));
        double ratio = contrast(look.color(Role.ACCENT), chip);
        assertTrue(ratio >= 3.0, "the pressed chip's label is " + String.format("%.2f", ratio) + ":1 in the "
                + name(look) + " look; it needs 3:1");
        for (int i = 0; i < Look.LANGUAGES; i++) {
            assertReads(look, Look.language(i), Look.TILE, 3.0, "language " + i + "'s segment on its track");
        }
    }

    /** Every code colour reads as text on the card the document sits on, in both looks. */
    @ParameterizedTest
    @MethodSource("looks")
    void everyCodeColourReadsOnTheCard(Theme look) {
        for (Grammars.Colour rule : Look.CODE) {
            double ratio = contrast(rule.paint().in(look), look.color(Look.CARD));
            assertTrue(ratio >= 4.5, rule.scope() + " is " + String.format("%.2f", ratio) + ":1 on the card in the "
                    + name(look) + " look; it needs 4.5:1");
        }
    }

    /** The code's colours are roles, so marked text follows a change of look; and each has a light answer of its own. */
    @Test
    void theCodeColoursFollowTheLook() {
        List<Grammars.Colour> code = Look.CODE;
        for (Grammars.Colour rule : code) {
            assertInstanceOf(Role.class, rule.paint(), rule.scope() + " should be a role, not a fixed colour");
            assertNotEquals(rule.paint().in(Look.DARK), rule.paint().in(Look.LIGHT),
                    rule.scope() + " has an answer in each look");
        }
    }

    /**
     * The sixteen ANSI colours a build or a test run writes in, on the console's card in the light look: each read as
     * text, since programs write in every one. Not held in the dark look, which is Campbell's as it always was
     * ({@link #theDarkConsoleIsAsItWas}).
     */
    @Test
    void everyAnsiColourReadsInTheLightConsole() {
        Color card = Look.LIGHT.color(Look.CARD);
        assertEquals(card, Look.TERMINAL.background().in(Look.LIGHT), "the console's ground is the card");
        for (int n = 0; n < 16; n++) {
            double ratio = contrast(Look.TERMINAL.ansi(n).in(Look.LIGHT), card);
            assertTrue(ratio >= 4.5, "ANSI colour " + n + " is " + String.format("%.2f", ratio)
                    + ":1 on the light console's card; it needs 4.5:1");
        }
        assertTrue(contrast(Look.TERMINAL.foreground().in(Look.LIGHT), card) >= 7.0, "the console's default text");
    }

    /** The dark console is the one it always was: the card, the ink, and Campbell's sixteen. */
    @Test
    void theDarkConsoleIsAsItWas() {
        assertEquals(Look.DARK.color(Look.CARD), Look.TERMINAL.background().in(Look.DARK));
        assertEquals(Look.DARK.color(Role.INK), Look.TERMINAL.foreground().in(Look.DARK));
        for (int n = 0; n < 16; n++) {
            assertEquals(Color.rgb(dev.sibarum.suite.tty.Terminal.Palette.xterm(n)), Look.TERMINAL.ansi(n).in(Look.DARK),
                    "ANSI colour " + n + " in the dark look");
        }
    }

    /** The dark look is the design's, measured: switching looks must never have moved it. */
    @Test
    void theDarkLookIsTheDesigns() {
        assertEquals(Color.rgb(0x8aa9f0), keyword().in(Look.DARK), "a keyword is the design's blue");
        assertEquals(Look.DARK_PALETTE, Look.DARK.palette());
    }

    @ParameterizedTest
    @MethodSource("looks")
    void surfacesStepAwayFromThePage(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float card = Math.abs(luminance(look.color(Look.CARD)) - page);
        float rim = Math.abs(luminance(look.color(Look.RIM)) - page);
        float selected = Math.abs(luminance(look.color(Look.SELECTED)) - page);
        assertTrue(card > 0, "a card should stand off the page");
        assertTrue(rim > card, "a card's rim should stand further off the page than the card");
        assertTrue(selected > rim, "a selected row should stand further off than the rim");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void textFadesInSteps(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float ink = Math.abs(luminance(look.color(Role.INK)) - page);
        float dim = Math.abs(luminance(look.color(Role.DIM)) - page);
        float faint = Math.abs(luminance(look.color(Role.FAINT)) - page);
        assertTrue(ink > dim, "dim text should be quieter than primary text");
        assertTrue(dim > faint, "faint text should be quieter than dim text");
    }

    @ParameterizedTest
    @MethodSource("looks")
    void theAccentIsNotTheInk(Theme look) {
        assertNotEquals(look.color(Role.INK), look.color(Role.ACCENT),
                "the accent should be a colour, not the text shade");
    }

    /** The two looks are two: the dark one's ink is above its page, the light one's below. */
    @ParameterizedTest
    @MethodSource("looks")
    void theLooksFaceOppositeWays(Theme look) {
        float page = luminance(look.color(Role.PAGE));
        float ink = luminance(look.color(Role.INK));
        assertTrue(look == Look.DARK ? ink > page : ink < page, "the dark look's ink is above its page, the light one's below");
        assertNotEquals(Look.DARK.color(Look.SEARCHING_RIM), Look.LIGHT.color(Look.SEARCHING_RIM),
                "Symbols' violet has an answer in each look");
    }

    private static Paint keyword() {
        for (Grammars.Colour rule : Look.CODE) {
            if (rule.scope().equals("keyword")) {
                return rule.paint();
            }
        }
        throw new AssertionError("no keyword rule");
    }

    private static void assertReads(Theme look, Role role, Role ground, double least, String what) {
        double ratio = contrast(look.color(role), look.color(ground));
        assertTrue(ratio >= least, what + " is " + String.format("%.2f", ratio) + ":1 in the " + name(look)
                + " look; it needs " + least + ":1");
    }

    private static String name(Theme look) {
        return look == Look.DARK ? "dark" : "light";
    }

    /** {@code top}, at its own opacity, over the opaque {@code under}. */
    private static Color over(Color top, Color under) {
        float a = top.a();
        return new Color(top.r() * a + under.r() * (1 - a), top.g() * a + under.g() * (1 - a),
                top.b() * a + under.b() * (1 - a), 1f);
    }

    /** WCAG's contrast ratio between two colours. */
    private static double contrast(Color a, Color b) {
        double la = relative(a);
        double lb = relative(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG's relative luminance, from sRGB. */
    private static double relative(Color c) {
        return 0.2126 * linear(c.r()) + 0.7152 * linear(c.g()) + 0.0722 * linear(c.b());
    }

    private static double linear(float channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /** Rough perceived brightness -- enough to order two shades, which is all the ordering checks need. */
    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }
}
