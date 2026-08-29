package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.widget.TextField;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Syntax highlighting over TM4E's TextMate tokenizer, rendered as {@link Span}s on the editor's
 * document.
 *
 * <p>Grammars are bundled classpath resources (from microsoft/vscode, MIT); the file extension picks
 * the grammar, TM4E tokenizes line by line threading its rule stack, and each token's most specific
 * matching scope prefix picks a colour. Files with no grammar (plain text) just clear the spans.
 *
 * <p>Tokenization runs off the GUI thread via {@link Gui#async}. Results are guarded two ways: a
 * generation counter drops superseded runs, and {@code setSpans} is only committed when the editor
 * still holds the text that was tokenized — which also breaks the feedback loop where
 * {@code setSpans} itself fires {@code onChange}.
 */
final class Highlighter {

    /** Token scope prefix -> colour; first match in order wins. Palette matches the app's dark theme. */
    private static final List<Map.Entry<String, Color>> SCOPE_COLORS = List.of(
            Map.entry("comment",            Color.rgb(0x6b7689)),
            Map.entry("string",             Color.rgb(0x9ece6a)),
            Map.entry("constant.numeric",   Color.rgb(0xff9e64)),
            Map.entry("constant.language",  Color.rgb(0xff9e64)),
            // CSS keeps its interesting values here: #fff, auto, print, and the rest of the keyword values.
            Map.entry("constant.other",     Color.rgb(0xff9e64)),
            Map.entry("support.constant",   Color.rgb(0xff9e64)),
            Map.entry("keyword",            Color.rgb(0xbb9af7)),
            Map.entry("storage",            Color.rgb(0xbb9af7)),
            Map.entry("entity.name.function", Color.rgb(0xe0af68)),
            Map.entry("entity.name.type",   Color.rgb(0x2ac3de)),
            Map.entry("support.type",       Color.rgb(0x2ac3de)),
            Map.entry("support.class",      Color.rgb(0x2ac3de)),
            Map.entry("variable.parameter", Color.rgb(0xe8d4b0)),
            // Shell and PowerShell are mostly variables, and an uncoloured $name reads as prose. This sits a
            // step off the default ink rather than shouting, and it tints plain identifiers in JS and Java too.
            Map.entry("variable.other",     Color.rgb(0xc0caf5)),
            Map.entry("entity.name.section", Color.rgb(0x7aa2f7)),
            Map.entry("entity.name.tag",    Color.rgb(0xf7768e)),
            Map.entry("entity.other.attribute-name", Color.rgb(0x7dcfff)),
            Map.entry("support.function",   Color.rgb(0x2ac3de)),
            Map.entry("markup.heading",     Color.rgb(0x7aa2f7)),
            Map.entry("markup.bold",        Color.rgb(0xe0af68)),
            Map.entry("markup.italic",      Color.rgb(0xbb9af7)),
            Map.entry("markup.inline.raw",  Color.rgb(0x2ac3de)),
            Map.entry("markup.fenced_code", Color.rgb(0x2ac3de)),
            Map.entry("markup.underline.link", Color.rgb(0x73daca)),
            Map.entry("markup.quote",       Color.rgb(0x6b7689)),
            // A diff without red and green is not a diff. These three carry .diff files, and markdown's
            // ```diff fences with them.
            Map.entry("markup.inserted",    Color.rgb(0x9ece6a)),
            Map.entry("markup.deleted",     Color.rgb(0xf7768e)),
            Map.entry("markup.changed",     Color.rgb(0xe0af68)),
            // The ---/+++/@@ furniture around the hunks, which the grammar scopes as meta, not markup.
            Map.entry("meta.diff",          Color.rgb(0x7aa2f7)),
            Map.entry("punctuation",        Color.rgb(0x93a0b4)));

    /** Extension -> TextMate scope name, for the bundled grammars. */
    private static final Map<String, String> EXT_TO_SCOPE = Map.ofEntries(
            Map.entry("java", "source.java"),
            Map.entry("py", "source.python"),
            Map.entry("pyw", "source.python"),
            Map.entry("pyi", "source.python"),
            Map.entry("json", "source.json"),
            Map.entry("md", "text.html.markdown"),
            Map.entry("markdown", "text.html.markdown"),
            Map.entry("xml", "text.xml"),
            Map.entry("xsd", "text.xml"),
            Map.entry("xsl", "text.xml"),
            Map.entry("svg", "text.xml"),
            Map.entry("html", "text.html.basic"),
            Map.entry("htm", "text.html.basic"),
            Map.entry("js", "source.js"),
            Map.entry("mjs", "source.js"),
            Map.entry("cjs", "source.js"),
            Map.entry("css", "source.css"),
            Map.entry("yaml", "source.yaml"),
            Map.entry("yml", "source.yaml"),
            Map.entry("jsonc", "source.json.comments"),
            Map.entry("diff", "source.diff"),
            Map.entry("patch", "source.diff"),
            Map.entry("ini", "source.ini"),
            Map.entry("properties", "source.ini"),
            Map.entry("sh", "source.shell"),
            Map.entry("bash", "source.shell"),
            Map.entry("zsh", "source.shell"),
            Map.entry("ksh", "source.shell"),
            Map.entry("ps1", "source.powershell"),
            Map.entry("psm1", "source.powershell"),
            Map.entry("psd1", "source.powershell"),
            Map.entry("bat", "source.batchfile"),
            Map.entry("cmd", "source.batchfile"),
            Map.entry("dockerfile", "source.dockerfile"));

    /**
     * Whole file name -> TextMate scope, for the files an extension cannot describe: {@code Dockerfile} has
     * none at all, and {@code .bashrc} is nothing but one. Matched before {@link #EXT_TO_SCOPE}, because a
     * whole name is the more specific claim of the two.
     */
    private static final Map<String, String> NAME_TO_SCOPE = Map.ofEntries(
            Map.entry("dockerfile", "source.dockerfile"),
            Map.entry("containerfile", "source.dockerfile"),
            Map.entry(".bashrc", "source.shell"),
            Map.entry(".bash_profile", "source.shell"),
            Map.entry(".bash_aliases", "source.shell"),
            Map.entry(".zshrc", "source.shell"),
            Map.entry(".zprofile", "source.shell"),
            Map.entry(".profile", "source.shell"));

    private static final Duration LINE_TIME_LIMIT = Duration.ofMillis(200);

    /** Above these, highlighting turns off (plain text) rather than grinding regexes on pathological input. */
    private static final int MAX_HIGHLIGHT_CHARS = 512 * 1024;
    private static final int MAX_HIGHLIGHT_LINE_CHARS = 20_000;

    /**
     * The bundled grammars, parsed once for the process rather than once per open document.
     *
     * <p>Held in a holder class so the parse is paid on the first document that wants a grammar and not on
     * the first touch of any static here: {@link #knownExtensions()} feeds the save dialog's filter list, and
     * that is read while the file actions are being built, long before anything needs tokenizing.
     */
    private static final class Grammars {
        static final Registry REGISTRY = loadGrammars();
    }

    private final Gui gui;
    private final TextField editor;
    private final AtomicLong generation = new AtomicLong();

    private volatile IGrammar grammar; // null = plain text
    private volatile boolean closed;

    // The markdown grammar references embedded languages we don't bundle; TM4E drops those rules with a
    // WARNING per missing scope, which would flood the console on the first tokenize. A strong reference
    // is required: JUL holds loggers weakly, so an unreferenced logger can be collected and its level lost.
    private static final java.util.logging.Logger TM4E_LOG =
            java.util.logging.Logger.getLogger("org.eclipse.tm4e");
    static {
        TM4E_LOG.setLevel(java.util.logging.Level.SEVERE);
    }

    Highlighter(Gui gui, TextField editor) {
        this.gui = gui;
        this.editor = editor;
        editor.onChange(text -> refresh());
    }

    /** A registry holding every bundled grammar. Package-private so a test can load the same set. */
    static Registry loadGrammars() {
        Registry registry = new Registry();
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/java.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/python.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/JSON.tmLanguage.json"));
        // Markdown references embedded-language scopes (fenced code blocks) that are not bundled;
        // TM4E treats a missing include as a no-op, so those blocks just render uncolored.
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/markdown.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/xml.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/html.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/JavaScript.tmLanguage.json"));
        // CSS is not only for .css files: the HTML grammar includes source.css for <style> blocks, so without
        // it here those blocks tokenize as nothing at all. <script> already worked, source.js being bundled.
        //
        // Pinned to vscode 1.96.0, unlike its neighbours, which track main. The version on main rewrote the
        // selector rule with a variable-length look-behind, and joni - the JVM's Oniguruma, which TM4E runs its
        // patterns through - rejects those outright. It throws while tokenizing rather than at load, so the
        // damage would not have stopped at .css files: an HTML file with a <style> block would have thrown
        // mid-document and fallen back to plain text, which is worse than the uncoloured block we had before.
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/css.tmLanguage.json"));
        // YAML is pinned to 1.96.0 for a different reason than CSS. main splits YAML into a small dispatcher
        // over per-spec-version grammars, and that dispatcher's captures use a shorthand TM4E's rule parser
        // cannot read - it throws a ClassCastException partway through the first document. The 1.96.0 grammar
        // is the older self-contained one: one file, no includes, and it tokenizes.
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/yaml.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/JSONC.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/diff.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/ini.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/docker.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/shell-unix-bash.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/powershell.tmLanguage.json"));
        registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/batchfile.tmLanguage.json"));
        return registry;
    }

    /** Stop this highlighter: in-flight and future refreshes become no-ops (its editor is being torn down). */
    void close() {
        closed = true;
        generation.incrementAndGet();
    }

    /**
     * Whether {@link #close} has been called. Package-private for the tests: a highlighter that outlives its
     * document holds a pending tokenize against a field nobody can see, and nothing else about it is visible
     * from outside.
     */
    boolean closed() {
        return closed;
    }

    /** Every extension that maps to a grammar; the save dialog offers these, so the two cannot drift. */
    static Set<String> knownExtensions() {
        return EXT_TO_SCOPE.keySet();
    }

    /** Every whole file name that maps to a grammar. Not save-dialog material: none of these is an extension. */
    static Set<String> knownFileNames() {
        return NAME_TO_SCOPE.keySet();
    }

    /** The TextMate scope for {@code fileName}, or null for plain text (no name, no match). */
    static String scopeFor(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        String byName = NAME_TO_SCOPE.get(name);
        if (byName != null) {
            return byName;
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : EXT_TO_SCOPE.get(name.substring(dot + 1));
    }

    /** Pick the grammar for {@code fileName}'s extension (null name or unknown extension = plain text). */
    void language(String fileName) {
        String scope = scopeFor(fileName);
        grammar = scope == null ? null : grammarFor(scope);
        // A recognized format is code: switch the editor to the atlas's monospace face. Plain text reads
        // better in the proportional UI face, so an unknown extension switches back.
        editor.node().font(grammar != null ? 1 : 0);
        refresh();
    }

    /**
     * The shared grammar for {@code scope}.
     *
     * <p>Locked because {@code grammarForScopeName} is not a lookup: the first ask for a scope compiles a
     * {@code Grammar} and caches it in the registry's own map. One registry for the process means that write
     * is now reachable from more than one document, and this is the only place it happens.
     */
    static IGrammar grammarFor(String scope) {
        synchronized (Grammars.class) {
            return Grammars.REGISTRY.grammarForScopeName(scope);
        }
    }

    /** Re-tokenize the editor's current text on a worker and commit the spans if still current. */
    void refresh() {
        long gen = generation.incrementAndGet();
        gui.async(() -> {
            if (closed || generation.get() != gen) {
                return; // superseded before it started, or the tab is gone
            }
            String text = editor.text();
            IGrammar g = grammar;
            List<Span> spans;
            try {
                spans = g == null ? List.of() : tokenize(g, text);
            } catch (RuntimeException e) {
                // A tokenizer failure means no colours, never a broken editor.
                System.out.println("highlighting failed (" + e.getMessage() + "); showing plain text");
                spans = List.of();
            }
            // Only commit against the text we tokenized: a stale result would attach spans to the
            // wrong characters, and skipping unchanged text stops the setSpans->onChange->refresh loop.
            if (generation.get() == gen && editor.text().equals(text) && !spans.equals(editor.spans())) {
                editor.setSpans(spans);
            }
        });
    }

    /**
     * Tokenize {@code text}, one document at a time per grammar.
     *
     * <p><b>The lock is load-bearing and not obvious.</b> A {@code Grammar} compiles its rules lazily while
     * tokenizing — it hands out rule ids from a counter and stores the compiled rules in a plain map as it
     * goes — so it is mutable state, not a parsed constant. That was nobody else's business while every
     * document had a registry of its own; now that the registry is shared, two tabs of the same language
     * tokenizing at once on the {@link Gui#async} workers would be two threads mutating one counter and one
     * map. Opening several files in one go is exactly that case: {@code ls | first 3 | edit}.
     *
     * <p>Per grammar rather than one lock for all of them, so a Java document and a Python one never wait on
     * each other. Contention is slight in practice regardless — a document only re-tokenizes when its own
     * text changes, and only one document is being typed into.
     */
    static List<Span> tokenize(IGrammar grammar, String text) {
        if (text.length() > MAX_HIGHLIGHT_CHARS) {
            return List.of();
        }
        synchronized (grammar) {
            return tokenizeLines(grammar, text);
        }
    }

    private static List<Span> tokenizeLines(IGrammar grammar, String text) {
        List<Span> spans = new ArrayList<>();
        IStateStack state = null;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd);
            if (line.length() > MAX_HIGHLIGHT_LINE_CHARS) {
                // A minified/generated line: past it the grammar state would be wrong anyway, so give up
                // on the whole file rather than mislabel the rest.
                return List.of();
            }
            ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine(line, state, LINE_TIME_LIMIT);
            state = result.getRuleStack();
            for (IToken token : result.getTokens()) {
                Color color = colorFor(token.getScopes());
                int start = lineStart + Math.min(token.getStartIndex(), line.length());
                int end = lineStart + Math.min(token.getEndIndex(), line.length());
                if (color != null && end > start) {
                    spans.add(Span.foreground(start, end, color));
                }
            }
            lineStart = lineEnd + 1;
        }
        return spans;
    }

    /** Most specific scope wins: scan scopes innermost-first, rules in declared priority order. */
    private static Color colorFor(List<String> scopes) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String scope = scopes.get(i);
            for (Map.Entry<String, Color> rule : SCOPE_COLORS) {
                if (scope.startsWith(rule.getKey())) {
                    return rule.getValue();
                }
            }
        }
        return null;
    }
}
