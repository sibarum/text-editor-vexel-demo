package dev.vexelray.demo.editor.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.highlight.Colour;
import dev.vexelray.framework.highlight.Colours;
import dev.vexelray.framework.highlight.Highlighting;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.draw.Ink;
import org.junit.jupiter.api.Test;
import sibarum.pontif.tooling.PontifTokens;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pontif, coloured by its own scan rather than a grammar, as the framework's highlighting runs it once {@link Core}
 * provides the language. The bundled grammars are the framework's and tested there
 * ({@code vexelray-framework-highlight}'s {@code HighlightingTest}, where the rest of this file's old cases went).
 */
final class PontifTest {

    private static final Color COMMENT = Color.rgb(0x7f8994);
    private static final Color STRING = Color.rgb(0xa6d189);
    private static final Color CONSTANT = Color.rgb(0xe5a477);
    private static final Color KEYWORD = Color.rgb(0x8aa9f0);
    private static final Color TYPE = Color.rgb(0x7dd3e6);
    private static final Color NAME = Color.rgb(0xc8d3e2);

    /** The families these tests ask about, in the shape of the application's own table. */
    private static final Colours COLOURS = Colours.of(
            Colour.of("comment", COMMENT),
            Colour.of("string", STRING),
            Colour.of("constant", CONSTANT),
            Colour.of("keyword", KEYWORD),
            Colour.of("storage", KEYWORD),
            Colour.of("entity.name.type", TYPE),
            Colour.of("variable.other", NAME),
            Colour.of("punctuation", Color.rgb(0x8b95a1)));

    /** What {@code HighlightStarter} builds for the editor: the bundled languages and {@link Core#pontif}. */
    private static final Highlighting HIGHLIGHTING = Highlighting.of(COLOURS, List.of(new Core().pontif()));

    private static final String SAMPLE = """
            module ledger
            # an account never goes negative
            struct Account(balance:[Int:@>=0])
            let greeting = "hello"
            method Account.deposit(n:Int):Account -> match n { _ -> Account(balance + n) }
            main ( if true then 42 else 'x' )
            """;

    /** The colour of the span covering exactly {@code needle}, or null if nothing colours it. */
    private static Color colourOf(String source, String needle) {
        int start = source.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("'" + needle + "' is not in the sample; the test is wrong, not the code");
        }
        for (Span span : HIGHLIGHTING.spans(Pontif.SCOPE, source)) {
            if (span.start() == start && span.end() == start + needle.length()) {
                return Ink.fixedOf(span.fg());
            }
        }
        return null;
    }

    @Test
    void pontifIsNamedAndColoured() {
        assertEquals(Pontif.SCOPE, HIGHLIGHTING.scopeFor("app.ptf"));
        assertEquals(Pontif.SCOPE, HIGHLIGHTING.scopeFor("APP.PTF"), "matched case-insensitively, like the rest");
        assertEquals("Pontif", HIGHLIGHTING.nameOf(Pontif.SCOPE));
        assertTrue(HIGHLIGHTING.highlights(Pontif.SCOPE));
    }

    @Test
    void pontifColoursEachKindOfToken() {
        assertEquals(KEYWORD, colourOf(SAMPLE, "module"));
        assertEquals(KEYWORD, colourOf(SAMPLE, "struct"));
        assertEquals(COMMENT, colourOf(SAMPLE, "# an account never goes negative"));
        assertEquals(STRING, colourOf(SAMPLE, "\"hello\""));
        assertEquals(STRING, colourOf(SAMPLE, "'x'"));
        assertEquals(CONSTANT, colourOf(SAMPLE, "42"));
        assertEquals(CONSTANT, colourOf(SAMPLE, "true"), "true and false are values, not structure");
        assertEquals(TYPE, colourOf(SAMPLE, "Account"), "a capitalised name is a type");
        assertEquals(TYPE, colourOf(SAMPLE, "Int"));
        assertEquals(NAME, colourOf(SAMPLE, "greeting"));
    }

    /** The point of a native scan: the keywords are the parser's, so none is missed and none goes stale. */
    @Test
    void pontifKeywordsAreTheParsers() {
        for (String keyword : sibarum.pontif.parser.PontifParser.KEYWORDS) {
            Color expected = keyword.equals("true") || keyword.equals("false") ? CONSTANT : KEYWORD;
            assertEquals(expected, colourOf(keyword + "\n", keyword), keyword);
        }
    }

    @Test
    void everyKindOfPontifTokenHasAScope() {
        String text = "x";
        for (PontifTokens.Kind kind : PontifTokens.Kind.values()) {
            assertNotNull(Pontif.scope(new PontifTokens.Token(kind, 0, 1), text), kind.name());
        }
    }

    @Test
    void halfTypedPontifStillColoursAndNeverThrows() {
        String src = "struct P(x:Int\nlet s = \"unterminated\nfunction (((";
        List<Span> spans = HIGHLIGHTING.spans(Pontif.SCOPE, src);
        assertEquals(KEYWORD, colourOf(src, "struct"));
        assertFalse(spans.isEmpty());
    }
}
