package dev.vexelray.demo.editor.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundle of grammars, held to its word. A mapping with no grammar behind it, or a grammar TM4E cannot actually run,
 * both fail the same silent way in the editor: the file opens as plain text and nothing says why. These tests say why.
 *
 * <p>Two of them guard upstream breakages found while bundling, both invisible until a document was tokenized: CSS on
 * vscode's {@code main} uses a variable-length look-behind that joni rejects, which threw partway through a document
 * and cost an HTML file with a {@code <style>} block all its colours; and YAML on {@code main} is a dispatcher whose
 * captures TM4E cannot parse. Both are pinned to the 1.96.0 tag. If someone re-syncs them from main, these fail.
 */
final class GrammarsTest {

    private static final Color STRING = Color.rgb(0xa6d189);
    private static final Color KEYWORD = Color.rgb(0x8aa9f0);
    private static final Color INSERTED = Color.rgb(0xa6d189);
    private static final Color DELETED = Color.rgb(0xf7768e);

    /** The families these tests ask about, in the shape of the application's own table. */
    private static final List<Grammars.Colour> COLOURS = List.of(
            new Grammars.Colour("comment", Color.rgb(0x7f8994)),
            new Grammars.Colour("string", STRING),
            new Grammars.Colour("constant", Color.rgb(0xe5a477)),
            new Grammars.Colour("keyword", KEYWORD),
            new Grammars.Colour("storage", KEYWORD),
            new Grammars.Colour("entity.name.type", Color.rgb(0x7dd3e6)),
            new Grammars.Colour("variable.other", Color.rgb(0xc8d3e2)),
            new Grammars.Colour("entity.name.tag", Color.rgb(0xf7768e)),
            new Grammars.Colour("support.type", Color.rgb(0x7dd3e6)),
            new Grammars.Colour("markup.inserted", INSERTED),
            new Grammars.Colour("markup.deleted", DELETED),
            new Grammars.Colour("punctuation", Color.rgb(0x8b95a1)));

    private static final Grammars GRAMMARS = new Grammars(COLOURS);

    private static String scope(String fileName) {
        String scope = Grammars.scopeFor(fileName);
        assertNotNull(scope, fileName + " should map to a scope");
        assertTrue(GRAMMARS.colours(scope), scope + " is mapped from " + fileName + " but nothing colours it");
        return scope;
    }

    /** The colour of the span covering exactly {@code needle}, or null if nothing colours it. */
    private static Color colourOf(String fileName, String source, String needle) {
        int start = source.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("'" + needle + "' is not in the sample; the test is wrong, not the code");
        }
        for (Span span : GRAMMARS.spans(scope(fileName), source)) {
            if (span.start() == start && span.end() == start + needle.length()) {
                return span.fg();
            }
        }
        return null;
    }

    /**
     * The colour covering the first character of {@code needle}. Some grammars, YAML among them, split a scalar's first
     * character into a token of its own, so no one span covers the whole word though every part of it is one colour.
     */
    private static Color colourAt(String fileName, String source, String needle) {
        int at = source.indexOf(needle);
        if (at < 0) {
            throw new AssertionError("'" + needle + "' is not in the sample; the test is wrong, not the code");
        }
        for (Span span : GRAMMARS.spans(scope(fileName), source)) {
            if (span.covers(at)) {
                return span.fg();
            }
        }
        return null;
    }

    // ---- nothing is mapped to a grammar that is not there ----

    @Test
    void everyMappedExtensionHasAGrammar() {
        for (String extension : Grammars.extensions()) {
            scope("sample." + extension);
        }
    }

    @Test
    void everyMappedFileNameHasAGrammar() {
        for (String name : Grammars.fileNames()) {
            scope(name);
        }
    }

    /** A whole name beats an extension, and it is the only thing that works for these two shapes of file. */
    /** The status line says a language's name, never its scope: a grammar added without a name would show one. */
    @Test
    void everyMappedFormatHasAName() {
        for (String extension : Grammars.extensions()) {
            String scope = Grammars.scopeFor("sample." + extension);
            assertFalse(Grammars.nameOf(scope).equals(scope), scope + " has no name");
        }
        for (String name : Grammars.fileNames()) {
            String scope = Grammars.scopeFor(name);
            assertFalse(Grammars.nameOf(scope).equals(scope), scope + " has no name");
        }
        assertEquals("Plain text", Grammars.nameOf(null));
    }

    @Test
    void filesWithoutAUsableExtensionStillFindTheirGrammar() {
        assertEquals("source.dockerfile", Grammars.scopeFor("Dockerfile"), "no extension at all");
        assertEquals("source.dockerfile", Grammars.scopeFor("dockerfile"));
        assertEquals("source.shell", Grammars.scopeFor(".bashrc"), "nothing but an extension");
        assertEquals("source.shell", Grammars.scopeFor(".ZSHRC"), "matched case-insensitively, like extensions");
    }

    @Test
    void plainTextHasNoScopeAndNoColours() {
        assertEquals(null, Grammars.scopeFor("notes.txt"));
        assertEquals(null, Grammars.scopeFor("README"));
        assertTrue(GRAMMARS.spans(null, "anything").isEmpty());
    }

    // ---- the two upstream breakages ----

    /** CSS is bundled for HTML's sake as much as for .css files, and the failure guarded was a throw, not a colour. */
    @Test
    void cssTokenizesRatherThanThrowing() {
        String src = ".cls, a#id > b + i { color: #fff; margin: 0 auto; }\n"
                + "@media print and (min-width: 5px) { a:hover::before { content: \"x\"; } }\n";
        assertFalse(GRAMMARS.spans(scope("site.css"), src).isEmpty(),
                "a stylesheet with no spans means the grammar silently did nothing");
    }

    @Test
    void htmlStyleBlocksAreColouredAndDoNotPoisonThePage() {
        String src = "<html><style>a { color: red; }</style><p>hi</p></html>\n";
        assertNotNull(colourOf("page.html", src, "color"), "<style> is why CSS is bundled at all");
        assertNotNull(colourOf("page.html", src, "p"), "and the markup around it still has to colour");
    }

    @Test
    void yamlActuallyColoursSomething() {
        String src = "# c\nname: value\nlist:\n  - one\nquoted: \"s\"\n";
        assertFalse(GRAMMARS.spans(scope("config.yaml"), src).isEmpty(),
                "the dispatcher-only grammar on main parses but colours nothing; this catches that swap");
        assertEquals(STRING, colourAt("config.yaml", src, "value"));
    }

    // ---- Pontif, by its own scan rather than a grammar ----

    private static final Color COMMENT = Color.rgb(0x7f8994);
    private static final Color CONSTANT = Color.rgb(0xe5a477);
    private static final Color TYPE = Color.rgb(0x7dd3e6);
    private static final Color NAME = Color.rgb(0xc8d3e2);

    private static final String PONTIF = """
            module ledger
            # an account never goes negative
            struct Account(balance:[Int:@>=0])
            let greeting = "hello"
            method Account.deposit(n:Int):Account -> match n { _ -> Account(balance + n) }
            main ( if true then 42 else 'x' )
            """;

    @Test
    void pontifIsNamedAndColoured() {
        assertEquals(Grammars.PONTIF, scope("app.ptf"));
        assertEquals(Grammars.PONTIF, Grammars.scopeFor("APP.PTF"), "matched case-insensitively, like the rest");
        assertEquals("Pontif", Grammars.nameOf(Grammars.PONTIF));
    }

    @Test
    void pontifColoursEachKindOfToken() {
        assertEquals(KEYWORD, colourOf("app.ptf", PONTIF, "module"));
        assertEquals(KEYWORD, colourOf("app.ptf", PONTIF, "struct"));
        assertEquals(COMMENT, colourOf("app.ptf", PONTIF, "# an account never goes negative"));
        assertEquals(STRING, colourOf("app.ptf", PONTIF, "\"hello\""));
        assertEquals(STRING, colourOf("app.ptf", PONTIF, "'x'"));
        assertEquals(CONSTANT, colourOf("app.ptf", PONTIF, "42"));
        assertEquals(CONSTANT, colourOf("app.ptf", PONTIF, "true"), "true and false are values, not structure");
        assertEquals(TYPE, colourOf("app.ptf", PONTIF, "Account"), "a capitalised name is a type");
        assertEquals(TYPE, colourOf("app.ptf", PONTIF, "Int"));
        assertEquals(NAME, colourOf("app.ptf", PONTIF, "greeting"));
    }

    /** The point of a native scan: the keywords are the parser's, so none is missed and none goes stale. */
    @Test
    void pontifKeywordsAreTheParsers() {
        for (String keyword : sibarum.pontif.parser.PontifParser.KEYWORDS) {
            Color expected = keyword.equals("true") || keyword.equals("false") ? CONSTANT : KEYWORD;
            assertEquals(expected, colourOf("k.ptf", keyword + "\n", keyword), keyword);
        }
    }

    @Test
    void everyKindOfPontifTokenHasAScope() {
        String text = "x";
        for (sibarum.pontif.tooling.PontifTokens.Kind kind : sibarum.pontif.tooling.PontifTokens.Kind.values()) {
            assertNotNull(Grammars.pontifScope(new sibarum.pontif.tooling.PontifTokens.Token(kind, 0, 1), text), kind.name());
        }
    }

    @Test
    void halfTypedPontifStillColoursAndNeverThrows() {
        String src = "struct P(x:Int\nlet s = \"unterminated\nfunction (((";
        List<Span> spans = GRAMMARS.spans(Grammars.PONTIF, src);
        assertEquals(KEYWORD, colourOf("p.ptf", src, "struct"));
        assertFalse(spans.isEmpty());
    }

    // ---- the languages, briefly ----

    @Test
    void javaColoursItsKeywordsAndStrings() {
        String src = "final class C { String s = \"text\"; }\n";
        assertEquals(KEYWORD, colourOf("C.java", src, "final"));
        assertEquals(STRING, colourAt("C.java", src, "text"));
    }

    @Test
    void aDiffIsRedAndGreen() {
        String src = "--- a/f.txt\n+++ b/f.txt\n@@ -1,2 +1,2 @@\n context\n-gone\n+added\n";
        assertEquals(DELETED, colourOf("change.diff", src, "gone"));
        assertEquals(INSERTED, colourOf("change.diff", src, "added"));
    }

    @Test
    void shellPowerShellAndBatchColourTheirKeywords() {
        assertEquals(KEYWORD, colourOf("run.sh", "if [ -f x ]; then echo hi; fi\n", "if"));
        String ps = "function Get-Thing {\n    return $items.Count\n}\n";
        assertEquals(KEYWORD, colourOf("run.ps1", ps, "function"));
        assertNotNull(colourOf("run.ps1", ps, "items"), "PowerShell is mostly variables");
        assertNotNull(colourOf("run.cmd", "@echo off\nset NAME=world\n", "set"));
    }

    @Test
    void theRestOfTheFormatsColourTheirObviousTokens() {
        assertEquals(KEYWORD, colourOf("app.ini", "[section]\nkey=value\n", "key"));
        assertNotNull(colourOf("Dockerfile", "FROM alpine:3.19\nRUN true\n", "FROM"));
        assertNotNull(colourOf("tsconfig.jsonc", "{\n  // c\n  \"k\": 1\n}\n", " c"),
                "the comment is the whole point of jsonc");
    }

    /** Markdown colours fenced code with whatever grammar the fence names, so each one bundled pays off twice. */
    @Test
    void markdownFencesUseTheOtherGrammars() {
        assertEquals(DELETED, colourOf("notes.md", "```diff\n-gone\n```\n", "gone"));
        assertEquals(KEYWORD, colourOf("notes.md", "```python\ndef f():\n    pass\n```\n", "def"));
        assertTrue(GRAMMARS.spans(scope("notes.md"), "```yaml\nkey: value\n```\n").size() > 2,
                "a yaml fence should be more than its own backticks");
    }

    // ---- limits ----

    @Test
    void aLineTooLongToTrustLeavesTheWholeFilePlain() {
        String src = "int a;\n" + "x".repeat(20_001) + "\nint b;\n";
        assertTrue(GRAMMARS.spans(scope("A.java"), src).isEmpty());
    }

    // ---- one part, two requests at once ----

    /**
     * MarkText may run two requests at once, and a grammar compiles its rules lazily as it tokenizes, so this is
     * several threads driving one piece of mutable state. The race is worst on a cold grammar, so each round starts a
     * fresh part. Asserts the answers rather than merely that nothing threw: a lost rule id need not crash, it can just
     * as well colour a token wrong.
     */
    @Test
    void oneGrammarTokenizesTheSameUnderConcurrentDocuments() throws Exception {
        String source = """
                package p;
                /* a comment */
                final class C {
                    String s = "text";
                    int n = 42;
                }
                """;
        String scope = scope("Cold.java");
        List<Span> expected = GRAMMARS.spans(scope, source);
        assertFalse(expected.isEmpty(), "the sample should colour something to begin with");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 4; round++) {
                Grammars cold = new Grammars(COLOURS);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<Span>>> runs = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    runs.add(pool.submit(() -> {
                        start.await();
                        return cold.spans(scope, source);
                    }));
                }
                start.countDown();
                for (Future<List<Span>> run : runs) {
                    assertEquals(expected, run.get(30, TimeUnit.SECONDS), "every thread should see the same colours");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Two different languages at once, from one cold part: what restoring a session of a Java file, a pom and a README
     * does. The registry's theme answers every grammar's scope lookups through one plain {@code HashMap}, which a lock
     * per grammar did not cover in the 0.1 editor.
     */
    @Test
    void differentGrammarsFromOnePartTokenizeTogether() throws Exception {
        List<String> scopes = List.of(scope("a.java"), scope("a.md"), scope("a.xml"));
        List<String> sources = List.of(
                "package p;\n/* c */\nfinal class C { String s = \"t\"; int n = 4; }\n".repeat(20),
                "# Title\n\nSome *text* with `code` and a [link](http://x).\n\n```java\nint x;\n```\n".repeat(20),
                "<?xml version=\"1.0\"?>\n<a b=\"c\"><!-- d --><e>f</e></a>\n".repeat(20));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 8; round++) {
                Grammars cold = new Grammars(COLOURS);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<Span>>> runs = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    int which = i % 3;
                    runs.add(pool.submit(() -> {
                        start.await();
                        return cold.spans(scopes.get(which), sources.get(which));
                    }));
                }
                start.countDown();
                for (Future<List<Span>> run : runs) {
                    assertFalse(run.get(30, TimeUnit.SECONDS).isEmpty(), "every document should be coloured");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The grammars are parsed once for the part, not once per document: the reason it is a part at all. */
    @Test
    void everyDocumentOfALanguageGetsTheSameGrammar() {
        IGrammar first = GRAMMARS.grammar(Grammars.scopeFor("A.java"));
        assertSame(first, GRAMMARS.grammar(Grammars.scopeFor("B.java")), "two Java documents should share one grammar");
        assertNotSame(first, GRAMMARS.grammar(Grammars.scopeFor("b.py")), "different languages are different grammars");
    }
}
