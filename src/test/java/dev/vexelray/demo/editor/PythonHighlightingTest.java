package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Python grammar, held to its word. {@link Highlighter} runs on a GUI thread against a live
 * {@code TextField}, but the two parts that decide what a file looks like - which grammar its name picks,
 * and which colour each token gets - are pure functions over a string, so those are the parts a plain JUnit
 * run can pin down.
 *
 * <p>The colours below are repeated from {@code SCOPE_COLORS} on purpose: a scope rename upstream in
 * MagicPython would otherwise silently drop Python back to uncoloured text, and this is what notices.
 */
final class PythonHighlightingTest {

    private static final Color COMMENT = Color.rgb(0x6b7689);
    private static final Color STRING = Color.rgb(0x9ece6a);
    private static final Color NUMBER = Color.rgb(0xff9e64);
    private static final Color KEYWORD = Color.rgb(0xbb9af7);
    private static final Color FUNCTION = Color.rgb(0xe0af68);
    private static final Color TYPE = Color.rgb(0x2ac3de);

    private static final IGrammar PYTHON = Highlighter.loadGrammars().grammarForScopeName("source.python");

    /** The colour of the span covering exactly {@code needle}, or null if nothing colours it. */
    private static Color colorOf(String source, String needle) {
        int start = source.indexOf(needle);
        assertNotNull(PYTHON, "the bundled grammar has to load before anything else here means much");
        if (start < 0) {
            throw new AssertionError("'" + needle + "' is not in the sample; the test is wrong, not the code");
        }
        int end = start + needle.length();
        for (Span span : Highlighter.tokenize(PYTHON, source)) {
            if (span.start() == start && span.end() == end) {
                return span.fg();
            }
        }
        return null;
    }

    // ---- the name picks the grammar ----

    @Test
    void pythonExtensionsFindThePythonGrammar() {
        assertEquals("source.python", Highlighter.scopeFor("build.py"));
        assertEquals("source.python", Highlighter.scopeFor("launcher.pyw"));
        assertEquals("source.python", Highlighter.scopeFor("stubs.pyi"));
        assertEquals("source.python", Highlighter.scopeFor("SHOUTING.PY"), "extensions are matched case-insensitively");
    }

    @Test
    void everythingElseIsStillPlainText() {
        assertNull(Highlighter.scopeFor("notes.txt"));
        assertNull(Highlighter.scopeFor("Makefile"), "no extension is not a language");
        assertNull(Highlighter.scopeFor(null), "an unsaved tab has no name yet");
        assertNull(Highlighter.scopeFor("python"), "the word is not the extension");
    }

    @Test
    void theGrammarIsActuallyBundled() {
        assertNotNull(PYTHON, "/grammars/python.tmLanguage.json must be on the classpath");
    }

    // ---- the tokens get their colours ----

    @Test
    void keywordsAndDefinitionsAreColoured() {
        String src = "def area(r):\n    return r\n";
        assertEquals(KEYWORD, colorOf(src, "def"));
        assertEquals(FUNCTION, colorOf(src, "area"), "the name being defined reads as a function");
        assertEquals(KEYWORD, colorOf(src, "return"));
    }

    @Test
    void commentsStringsAndNumbersAreColoured() {
        String src = "# note\nname = 'ada'\ncount = 42\nratio = 3.5\n";
        assertEquals(COMMENT, colorOf(src, " note"));
        assertEquals(STRING, colorOf(src, "ada"));
        assertEquals(NUMBER, colorOf(src, "42"));
        assertEquals(NUMBER, colorOf(src, "3.5"));
    }

    @Test
    void classesAndBuiltinsReadAsTypes() {
        String src = "class Circle(object):\n    pass\n";
        assertEquals(KEYWORD, colorOf(src, "class"));
        assertEquals(TYPE, colorOf(src, "Circle"));
        assertEquals(TYPE, colorOf(src, "object"), "builtins are types too, as far as the palette cares");
    }

    @Test
    void theLiteralsPythonHasThatOthersDoNot() {
        String src = "flag = True\nnothing = None\nraw = b'zero'\n";
        assertEquals(NUMBER, colorOf(src, "True"), "language constants share the numeric colour");
        assertEquals(NUMBER, colorOf(src, "None"));
        assertEquals(KEYWORD, colorOf(src, "b"), "the bytes prefix is storage, not part of the string body");
    }

    // ---- state carries across lines ----

    @Test
    void tripleQuotedStringsStayStringsOnLaterLines() {
        String src = "s = \"\"\"one\ntwo def not_a_keyword\nthree\"\"\"\nx = 1\n";
        // Spans are emitted a line at a time, so the body arrives as one span per line, not one for the string.
        assertEquals(STRING, colorOf(src, "two def not_a_keyword"),
                "the rule stack has to thread between lines, or line two would be code");
        assertEquals(STRING, colorOf(src, "three"));
        assertEquals(NUMBER, colorOf(src, "1"), "and the file has to recover once the string closes");
    }

    /** Indentation is Python's syntax; a dedent must not leave the tokenizer inside the block. */
    @Test
    void indentationDoesNotConfuseTheTokenizer() {
        String src = "if x:\n    y = 1\nelse:\n    z = 2\n";
        assertEquals(KEYWORD, colorOf(src, "if"));
        assertEquals(KEYWORD, colorOf(src, "else"));
        assertEquals(NUMBER, colorOf(src, "2"));
    }
}
