package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.registry.Registry;
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
 * The bundle of grammars, held to its word. A mapping with no grammar behind it, or a grammar TM4E cannot
 * actually run, both fail the same silent way in the editor: the file opens as plain text and nothing says
 * why. These tests are the thing that says why.
 *
 * <p>Two of them guard specific upstream breakages found while bundling, because both were invisible until a
 * document was actually tokenized:
 *
 * <ul>
 *   <li>CSS on vscode's {@code main} rewrote its selector rule with a variable-length look-behind, which joni
 *       rejects. It threw partway through a document, so an HTML file with a {@code <style>} block lost its
 *       colours entirely - a regression on a language already shipping.
 *   <li>YAML on {@code main} is a dispatcher over per-version grammars whose captures TM4E cannot parse.
 * </ul>
 *
 * <p>Both are pinned to the 1.96.0 tag as a result. If someone re-syncs them from main, these fail.
 */
final class GrammarBundleTest {

    private static final Registry REGISTRY = Highlighter.loadGrammars();

    private static final Color STRING = Color.rgb(0x9ece6a);
    private static final Color KEYWORD = Color.rgb(0xbb9af7);
    private static final Color INSERTED = Color.rgb(0x9ece6a);
    private static final Color DELETED = Color.rgb(0xf7768e);

    private static IGrammar grammarFor(String fileName) {
        String scope = Highlighter.scopeFor(fileName);
        assertNotNull(scope, fileName + " should map to a scope");
        IGrammar grammar = REGISTRY.grammarForScopeName(scope);
        assertNotNull(grammar, scope + " is mapped from " + fileName + " but no bundled grammar provides it");
        return grammar;
    }

    /** The colour of the span covering exactly {@code needle}, or null if nothing colours it. */
    private static Color colorOf(IGrammar grammar, String source, String needle) {
        int start = source.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("'" + needle + "' is not in the sample; the test is wrong, not the code");
        }
        for (Span span : Highlighter.tokenize(grammar, source)) {
            if (span.start() == start && span.end() == start + needle.length()) {
                return span.fg();
            }
        }
        return null;
    }

    /**
     * The colour covering the first character of {@code needle}. Some grammars - YAML among them - split a
     * scalar's first character into its own token, so there is no one span covering the whole word even though
     * every part of it is the same colour. Position is the honest question to ask in that case.
     */
    private static Color colorAt(IGrammar grammar, String source, String needle) {
        int at = source.indexOf(needle);
        if (at < 0) {
            throw new AssertionError("'" + needle + "' is not in the sample; the test is wrong, not the code");
        }
        for (Span span : Highlighter.tokenize(grammar, source)) {
            if (span.covers(at)) {
                return span.fg();
            }
        }
        return null;
    }

    // ---- nothing is mapped to a grammar that is not there ----

    @Test
    void everyMappedExtensionHasAGrammar() {
        for (String extension : Highlighter.knownExtensions()) {
            grammarFor("sample." + extension);
        }
    }

    @Test
    void everyMappedFileNameHasAGrammar() {
        for (String name : Highlighter.knownFileNames()) {
            grammarFor(name);
        }
    }

    /** A whole name beats an extension, and it is the only thing that works for these two shapes of file. */
    @Test
    void filesWithoutAUsableExtensionStillFindTheirGrammar() {
        assertEquals("source.dockerfile", Highlighter.scopeFor("Dockerfile"), "no extension at all");
        assertEquals("source.dockerfile", Highlighter.scopeFor("dockerfile"));
        assertEquals("source.shell", Highlighter.scopeFor(".bashrc"), "nothing but an extension");
        assertEquals("source.shell", Highlighter.scopeFor(".ZSHRC"), "matched case-insensitively, like extensions");
    }

    // ---- the two upstream breakages ----

    /**
     * CSS is bundled for the sake of HTML as much as for .css files. The failure this guards was not a
     * missing colour but a thrown exception, which cost the whole surrounding document its highlighting.
     */
    @Test
    void cssTokenizesRatherThanThrowing() {
        String src = ".cls, a#id > b + i { color: #fff; margin: 0 auto; }\n"
                + "@media print and (min-width: 5px) { a:hover::before { content: \"x\"; } }\n";
        List<Span> spans = Highlighter.tokenize(grammarFor("site.css"), src);
        assertFalse(spans.isEmpty(), "a stylesheet with no spans means the grammar silently did nothing");
    }

    @Test
    void htmlStyleBlocksAreColouredAndDoNotPoisonThePage() {
        IGrammar html = grammarFor("page.html");
        String src = "<html><style>a { color: red; }</style><p>hi</p></html>\n";
        assertNotNull(colorOf(html, src, "color"), "<style> is why CSS is bundled at all");
        assertNotNull(colorOf(html, src, "p"), "and the markup around it still has to colour");
    }

    @Test
    void yamlActuallyColoursSomething() {
        IGrammar yaml = grammarFor("config.yaml");
        String src = "# c\nname: value\nlist:\n  - one\nquoted: \"s\"\n";
        assertFalse(Highlighter.tokenize(yaml, src).isEmpty(),
                "the dispatcher-only grammar on main parses but colours nothing; this catches that swap");
        assertEquals(STRING, colorAt(yaml, src, "value"));
    }

    // ---- the languages, briefly ----

    @Test
    void aDiffIsRedAndGreen() {
        IGrammar diff = grammarFor("change.diff");
        String src = "--- a/f.txt\n+++ b/f.txt\n@@ -1,2 +1,2 @@\n context\n-gone\n+added\n";
        assertEquals(DELETED, colorOf(diff, src, "gone"));
        assertEquals(INSERTED, colorOf(diff, src, "added"));
    }

    @Test
    void shellPowerShellAndBatchColourTheirKeywords() {
        IGrammar shell = grammarFor("run.sh");
        String shellSrc = "if [ -f x ]; then echo hi; fi\n";
        assertEquals(KEYWORD, colorOf(shell, shellSrc, "if"));

        IGrammar powershell = grammarFor("run.ps1");
        String psSrc = "function Get-Thing {\n    return $items.Count\n}\n";
        assertEquals(KEYWORD, colorOf(powershell, psSrc, "function"));
        assertNotNull(colorOf(powershell, psSrc, "items"), "PowerShell is mostly variables");

        IGrammar batch = grammarFor("run.cmd");
        assertNotNull(colorOf(batch, "@echo off\nset NAME=world\n", "set"));
    }

    @Test
    void theRestOfTheNewFormatsColourTheirObviousTokens() {
        assertEquals(KEYWORD, colorOf(grammarFor("app.ini"), "[section]\nkey=value\n", "key"));
        assertNotNull(colorOf(grammarFor("Dockerfile"), "FROM alpine:3.19\nRUN true\n", "FROM"));
        assertNotNull(colorOf(grammarFor("tsconfig.jsonc"), "{\n  // c\n  \"k\": 1\n}\n", " c"),
                "the comment is the whole point of jsonc");
    }

    /** Markdown colours fenced code with whatever grammar the fence names, so each one added pays off twice. */
    @Test
    void markdownFencesUseTheNewGrammars() {
        IGrammar markdown = grammarFor("notes.md");
        assertEquals(DELETED, colorOf(markdown, "```diff\n-gone\n```\n", "gone"));
        assertEquals(KEYWORD, colorOf(markdown, "```python\ndef f():\n    pass\n```\n", "def"));
        assertTrue(Highlighter.tokenize(markdown, "```yaml\nkey: value\n```\n").size() > 2,
                "a yaml fence should be more than its own backticks");
    }

    /**
     * One registry for the process means one {@code Grammar} object shared by every tab of a language, and a
     * {@code Grammar} compiles its rules lazily as it tokenizes — so this is several threads driving one piece
     * of mutable state. {@code ls | first 3 | edit} does exactly this, and the first tokenize is the one that
     * does the compiling, so the race is worst on a cold grammar rather than a warm one.
     *
     * <p>Asserts the answers rather than merely that nothing threw: a lost rule id does not have to crash, it
     * can just as easily colour a token wrong, and that is the failure nobody would trace back to here.
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
        String scope = Highlighter.scopeFor("Cold.java");
        List<Span> expected = Highlighter.tokenize(grammarFor("Cold.java"), source);
        assertFalse(expected.isEmpty(), "the sample should colour something to begin with");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // A fresh registry per round, because the mutation being guarded happens only on the way through
            // the first document: the grammar hands out rule ids and fills its rule map as it compiles, and
            // after that it is effectively read-only. Racing a grammar that has already tokenized once proves
            // nothing, which is the trap this test fell into first time round.
            for (int round = 0; round < 4; round++) {
                IGrammar cold = Highlighter.loadGrammars().grammarForScopeName(scope);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<Span>>> runs = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    runs.add(pool.submit(() -> {
                        start.await();
                        return Highlighter.tokenize(cold, source);
                    }));
                }
                start.countDown();
                for (Future<List<Span>> run : runs) {
                    assertEquals(expected, run.get(30, TimeUnit.SECONDS),
                            "every thread should see the same colours");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The grammars are parsed once for the process, not once per document — the whole point of the holder. */
    @Test
    void everyDocumentOfALanguageGetsTheSameGrammarInstance() {
        IGrammar first = Highlighter.grammarFor(Highlighter.scopeFor("A.java"));
        IGrammar second = Highlighter.grammarFor(Highlighter.scopeFor("B.java"));
        assertSame(first, second, "two Java documents should share one grammar");
        assertNotSame(first, Highlighter.grammarFor(Highlighter.scopeFor("b.py")),
                "different languages are still different grammars");
    }
}
