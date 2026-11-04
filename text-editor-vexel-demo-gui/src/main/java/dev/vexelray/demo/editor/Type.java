package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.layout.Length;

/**
 * The two faces, the type scale, and the gutters.
 *
 * <h2>Type is rem; gutters are dp</h2>
 *
 * <p>That is the framework's rule and it decides what zoom does. {@code rem} grows with the user's zoom because it
 * is proportional to text; {@code dp} does not, because tripling the frame around content you zoomed in to read
 * means seeing less of it. Getting this backwards is not a subtle bug -- it is an application whose zoom makes the
 * window emptier.
 *
 * <h2>The faces are the atlas's, not yours</h2>
 *
 * <p>The framework's text atlas is baked at build time from the fonts in {@code vexelray-text}, so face 0 and face
 * 1 are whatever it shipped -- a sans and a mono -- and so is which characters each has. The sans has General
 * Punctuation (the bullet an unsaved tab wears) and not Geometric Shapes: a character the atlas lacks draws as the
 * missing-glyph box, so check the charset in {@code vexelray-text}'s pom before reaching for a symbol.
 */
final class Type {

    /** Face 0: the sans. Labels, the path bar, the status line. */
    static final int UI = 0;

    /** Face 1: the mono. The text being edited, so columns line up. */
    static final int MONO = 1;

    // ------------------------------------------------------------------ type

    /** The text being edited. */
    static final Length CODE = Length.rem(0.875f);

    /** The path bar, a hint, the navigator's chrome. */
    static final Length SMALL = Length.rem(0.75f);

    /** A row of Open's picture: a module's or an annotation's name. */
    static final Length LABEL = Length.rem(0.8125f);

    /** A number worth looking at: the folder's name in Open's picture, the one thing on it that is the point. */
    static final Length FIGURE = Length.rem(2.0f);

    // --------------------------------------------------------------- gutters

    /** The outer inset of a region: Open's picture. */
    static final Length EDGE = Length.dp(22.4f);

    /** A panel's padding, and the gap between a quote's bar and its text. */
    static final Length WIDE = Length.dp(11.2f);

    /** A tight gap, and the navigator's padding. */
    static final Length TIGHT = Length.dp(5.6f);

    /** The standard gap between two things that belong together. */
    static final Length GAP = Length.dp(8.4f);

    /** A border. Not scaled by zoom; a hairline is a hairline. */
    static final Length RULE = Length.dp(1);

    /** The corner radius of a card: the navigator, the open files, Open's picture. */
    static final Length CORNER = Length.dp(10);

    /** The gap between two cards, which is also the divider's whole drag target. */
    static final Length SEAM = Length.dp(10);

    /** The divider's painted line, centred in the {@link #SEAM}: a hairline at rest, the accent while dragged. */
    static final Length SEAM_LINE = Length.dp(2);

    private Type() {
    }
}
