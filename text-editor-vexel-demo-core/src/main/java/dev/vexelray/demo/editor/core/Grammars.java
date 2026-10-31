package dev.vexelray.demo.editor.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;
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

/**
 * Syntax highlighting: the bundled TextMate grammars, which file each one is for, and a document's colours as
 * {@link Span}s, through TM4E's tokenizer.
 *
 * <p>The grammars are from microsoft/vscode (MIT, {@code grammars/VSCODE-LICENSE.txt}). The file name picks the
 * grammar, TM4E tokenizes line by line threading its rule stack, and each token's most specific scope that a
 * {@link Colour} names picks its colour.
 *
 * <h2>A part, not a job's state</h2>
 *
 * <p>Parsing a grammar is slow and its compiled rules are worth keeping, so this is built once, by a recipe, and
 * {@link MarkText} takes it. A job may keep no state of its own, because two requests may run at once; a part the
 * container supplies is how it takes something that does. Those two requests are why every tokenize is under one
 * lock, and it is one lock rather than one per grammar: a grammar compiles its rules lazily while it tokenizes, and
 * every grammar a registry makes resolves scopes through the registry's one theme, whose match cache is a plain
 * {@code HashMap}. A Java document and a Markdown one tokenizing together threw
 * {@code ConcurrentModificationException} out of {@code Theme.match} in the 0.1 editor, which locked per grammar.
 * A document only re-tokenizes when its own text changes, and only one is being typed into, so the wait is felt
 * only while a session restores.
 */
public final class Grammars {

    private static final Log LOG = Log.of("text-editor-vexel-demo.highlight");

    /** One rule of a colour table: a token in {@code scope}, or in a scope under it, is drawn in {@code color}. */
    public record Colour(String scope, Color color) {
    }

    /** Extension -> TextMate scope name, for the bundled grammars. */
    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
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
     * Whole file name -> TextMate scope, for the files an extension cannot describe: {@code Dockerfile} has none, and
     * {@code .bashrc} is nothing but one. Matched before {@link #BY_EXTENSION}, the more specific claim of the two.
     */
    private static final Map<String, String> BY_NAME = Map.ofEntries(
            Map.entry("dockerfile", "source.dockerfile"),
            Map.entry("containerfile", "source.dockerfile"),
            Map.entry(".bashrc", "source.shell"),
            Map.entry(".bash_profile", "source.shell"),
            Map.entry(".bash_aliases", "source.shell"),
            Map.entry(".zshrc", "source.shell"),
            Map.entry(".zprofile", "source.shell"),
            Map.entry(".profile", "source.shell"));

    /**
     * The grammar files, by name under {@code grammars/}. css and yaml are pinned to vscode 1.96.0, unlike the rest:
     * main's css uses a variable-length look-behind that joni rejects mid-document, and main's yaml is a dispatcher
     * whose captures TM4E cannot parse. {@code GrammarsTest} fails if either is re-synced from main.
     */
    private static final List<String> FILES = List.of("java", "python", "JSON", "markdown", "xml", "html",
            "JavaScript", "css", "yaml", "JSONC", "diff", "ini", "docker", "shell-unix-bash", "powershell", "batchfile");

    private static final Duration LINE_TIME_LIMIT = Duration.ofMillis(200);

    /** Above these, a document is plain text rather than regexes ground over pathological input. */
    private static final int MAX_CHARS = 512 * 1024;
    private static final int MAX_LINE_CHARS = 20_000;

    // The markdown grammar refers to embedded languages that are not bundled, and TM4E logs a WARNING per missing
    // scope through java.util.logging. Held strongly: JUL keeps loggers weakly, and a collected logger forgets the
    // level set on it.
    private static final java.util.logging.Logger TM4E_LOG = java.util.logging.Logger.getLogger("org.eclipse.tm4e");

    static {
        TM4E_LOG.setLevel(java.util.logging.Level.SEVERE);
    }

    private final List<Colour> colours;
    /** Everything TM4E shares between documents; see the class comment. */
    private final Object tokenizing = new Object();
    /** Parsed on the first document that wants a grammar, so a start that opens none pays nothing. */
    private Registry registry;

    /** @param colours the colour table, first match in order wins within a scope */
    public Grammars(List<Colour> colours) {
        this.colours = List.copyOf(colours);
    }

    /** The TextMate scope for {@code fileName}, or null for plain text. */
    public static String scopeFor(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        String byName = BY_NAME.get(name);
        if (byName != null) {
            return byName;
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : BY_EXTENSION.get(name.substring(dot + 1));
    }

    /** Every extension that maps to a grammar. */
    static Set<String> extensions() {
        return BY_EXTENSION.keySet();
    }

    /** Every whole file name that maps to a grammar. */
    static Set<String> fileNames() {
        return BY_NAME.keySet();
    }

    /**
     * The colours of {@code text} in the grammar for {@code scope}: none for a scope with no grammar, a document
     * too large to be worth it, or a tokenizer that failed, since a failure means no colours and never a broken
     * editor.
     */
    public List<Span> spans(String scope, String text) {
        if (text.length() > MAX_CHARS) {
            return List.of();
        }
        synchronized (tokenizing) {
            IGrammar grammar = grammar(scope);
            if (grammar == null) {
                return List.of();
            }
            try {
                return tokenize(grammar, text);
            } catch (RuntimeException e) {
                LOG.warn("highlighting " + scope + " failed; showing plain text", e);
                return List.of();
            }
        }
    }

    /** The grammar for {@code scope}, compiled once and shared by every document of it. Under the lock. */
    IGrammar grammar(String scope) {
        synchronized (tokenizing) {
            if (registry == null) {
                registry = new Registry();
                for (String file : FILES) {
                    registry.addGrammar(IGrammarSource.fromResource(Grammars.class,
                            "/grammars/" + file + ".tmLanguage.json"));
                }
            }
            return scope == null ? null : registry.grammarForScopeName(scope);
        }
    }

    private List<Span> tokenize(IGrammar grammar, String text) {
        List<Span> spans = new ArrayList<>();
        IStateStack state = null;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd);
            if (line.length() > MAX_LINE_CHARS) {
                // A minified or generated line: past it the grammar's state would be wrong anyway, so give up on the
                // whole file rather than mislabel the rest.
                return List.of();
            }
            ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine(line, state, LINE_TIME_LIMIT);
            state = result.getRuleStack();
            for (IToken token : result.getTokens()) {
                Color color = colourOf(token.getScopes());
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

    /** The most specific scope wins: scopes innermost first, the table's rules in order. */
    private Color colourOf(List<String> scopes) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String scope = scopes.get(i);
            for (Colour rule : colours) {
                if (scope.startsWith(rule.scope())) {
                    return rule.color();
                }
            }
        }
        return null;
    }
}
