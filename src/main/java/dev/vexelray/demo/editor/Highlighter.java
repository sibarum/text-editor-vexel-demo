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
import sibarum.probe.Log;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Syntax highlighting over TM4E's TextMate tokenizer, rendered as {@link Span}s on one document.
 *
 * <p>Grammars are bundled classpath resources (from microsoft/vscode, MIT); the file name picks the grammar, TM4E
 * tokenizes line by line threading its rule stack, and each token's most specific matching scope prefix picks a
 * colour. A file with no grammar clears its spans.
 *
 * <p>Tokenizing runs off the GUI thread, on {@link Gui#async}. A result is committed only if it is still the
 * newest run and the field still holds the text that was tokenized, which also stops {@code setSpans} — itself a
 * document change — from setting off another run.
 */
final class Highlighter {

    private static final Log LOG = Log.of("editor.highlight");

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

    /** The bundled grammars, parsed once for the process, on the first document that wants one. */
    private static final class Grammars {
        static final Registry REGISTRY = loadGrammars();
    }

    // The markdown grammar references embedded languages that are not bundled, and TM4E logs a WARNING per
    // missing scope through java.util.logging. Held strongly: JUL keeps loggers weakly, and a collected logger
    // forgets the level set on it.
    private static final java.util.logging.Logger TM4E_LOG = java.util.logging.Logger.getLogger("org.eclipse.tm4e");

    static {
        TM4E_LOG.setLevel(java.util.logging.Level.SEVERE);
    }

    private final Gui gui;
    private final TextField editor;
    private final AtomicLong generation = new AtomicLong();

    private volatile IGrammar grammar; // null = plain text
    private volatile String language = "Plain text";
    private volatile boolean closed;

    Highlighter(Gui gui, TextField editor) {
        this.gui = gui;
        this.editor = editor;
    }

    /** A registry holding every bundled grammar. Package-private so a test can load the same set. */
    static Registry loadGrammars() {
        Registry registry = new Registry();
        for (String name : List.of("java", "python", "JSON", "markdown", "xml", "html", "JavaScript",
                // css and yaml are pinned to vscode 1.96.0, unlike the rest: main's css uses a variable-length
                // look-behind that joni rejects mid-document, and main's yaml is a dispatcher whose captures
                // TM4E cannot parse. GrammarBundleTest fails if either is re-synced from main.
                "css", "yaml", "JSONC", "diff", "ini", "docker", "shell-unix-bash", "powershell", "batchfile")) {
            registry.addGrammar(IGrammarSource.fromResource(Highlighter.class, "/grammars/" + name + ".tmLanguage.json"));
        }
        return registry;
    }

    /** Stop this highlighter: in-flight and future refreshes become no-ops (its document is going). */
    void close() {
        closed = true;
        generation.incrementAndGet();
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

    /** What the status line calls each scope. A scope's own segments say {@code basic} for HTML and {@code comments} for JSONC. */
    private static final Map<String, String> SCOPE_NAMES = Map.ofEntries(
            Map.entry("source.java", "Java"),
            Map.entry("source.python", "Python"),
            Map.entry("source.json", "JSON"),
            Map.entry("source.json.comments", "JSON with comments"),
            Map.entry("text.html.markdown", "Markdown"),
            Map.entry("text.xml", "XML"),
            Map.entry("text.html.basic", "HTML"),
            Map.entry("source.js", "JavaScript"),
            Map.entry("source.css", "CSS"),
            Map.entry("source.yaml", "YAML"),
            Map.entry("source.diff", "Diff"),
            Map.entry("source.ini", "INI"),
            Map.entry("source.shell", "Shell"),
            Map.entry("source.powershell", "PowerShell"),
            Map.entry("source.batchfile", "Batch"),
            Map.entry("source.dockerfile", "Dockerfile"));

    /** What the status line calls a scope. Plain text for none. */
    static String languageOf(String scope) {
        return scope == null ? "Plain text" : SCOPE_NAMES.getOrDefault(scope, scope);
    }

    /**
     * Pick the grammar for {@code fileName} (null or unknown = plain text) and re-highlight. A recognised format
     * is code and gets the atlas's mono face; plain text reads better in the proportional one.
     */
    void language(String fileName) {
        String scope = scopeFor(fileName);
        grammar = scope == null ? null : grammarFor(scope);
        language = languageOf(grammar == null ? null : scope);
        editor.node().font(grammar != null ? Type.MONO : Type.UI);
        refresh();
    }

    /** The name of the grammar in force, for the status line. */
    String language() {
        return language;
    }

    /**
     * The shared grammar for {@code scope}. Under the tokenizing lock, because the registry compiles and caches a
     * grammar on its first ask, a write into the same registry every running tokenize is reading.
     */
    static IGrammar grammarFor(String scope) {
        synchronized (TOKENIZING) {
            return Grammars.REGISTRY.grammarForScopeName(scope);
        }
    }

    /** Re-tokenize the current text on a worker and commit the spans if they are still current. */
    void refresh() {
        long gen = generation.incrementAndGet();
        gui.async(() -> {
            if (closed || generation.get() != gen) {
                return;
            }
            String text = editor.text();
            IGrammar g = grammar;
            List<Span> spans;
            try {
                spans = g == null ? List.of() : tokenize(g, text);
            } catch (RuntimeException e) {
                // A tokenizer failure means no colours, never a broken editor.
                LOG.warn("highlighting failed; showing plain text", e);
                spans = List.of();
            }
            if (generation.get() == gen && editor.text().equals(text) && !spans.equals(editor.spans())) {
                editor.setSpans(spans);
            }
        });
    }

    /**
     * Tokenize {@code text}, one document at a time for the whole process.
     *
     * <p>The lock is load-bearing, and it is one lock rather than one per grammar. A {@code Grammar} compiles its
     * rules lazily while tokenizing, handing out ids from a counter into a plain map — that much a per-grammar
     * lock covered. What it did not cover, and the previous editor shipped with, is that every grammar a registry
     * makes resolves scopes through the registry's one theme, whose match cache is a plain {@code HashMap}: a Java
     * document and a Markdown one tokenizing together threw {@code ConcurrentModificationException} out of
     * {@code Theme.match} (GrammarBundleTest.differentGrammarsFromOneRegistryTokenizeTogether). So documents of
     * different languages now wait for each other. A document only re-tokenizes when its own text changes, and
     * only one is being typed into, so the wait is felt only while a session restores.
     */
    static List<Span> tokenize(IGrammar grammar, String text) {
        if (text.length() > MAX_HIGHLIGHT_CHARS) {
            return List.of();
        }
        synchronized (TOKENIZING) {
            return tokenizeLines(grammar, text);
        }
    }

    /** See {@link #tokenize}: the registry's shared state is guarded as one thing, because it is one thing. */
    private static final Object TOKENIZING = new Object();

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
                // A minified or generated line: past it the grammar state would be wrong anyway, so give up on
                // the whole file rather than mislabel the rest.
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
