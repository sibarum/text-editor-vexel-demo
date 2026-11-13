package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Declaration;
import dev.vexelray.demo.editor.core.Messages.SymbolsFound;
import dev.vexelray.demo.editor.core.Messages.SymbolsWanted;
import dev.vexelray.framework.api.Job;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.ProjectIndex;
import sibarum.concordance.index.Source;
import sibarum.concordance.index.Symbol;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The declarations whose names answer what was typed, in the index of the project a folder is in: Ctrl+T's list.
 *
 * <p>The matching and the order are Concordance's ({@link Index#namesContaining}): exact first, then but for case, a
 * prefix, a substring, camel humps ({@code MPR} for {@code MavenProjectReader}), and a subsequence last. Only
 * declarations in files on disk are listed, since a tab is a file it can save; a library's, in a sources jar or
 * decompiled, waits for a read-only tab.
 *
 * <p>The index read is the one {@link IndexStore} holds, so a keystroke costs a search and no disk; the first question
 * about a project in a run reads its saved index once. A project never indexed says so ({@code indexed} false)
 * rather than answering an empty list, which would look like a name that is not there.
 */
@Job
public final class FindSymbols {

    private static final Log LOG = Log.of("text-editor-vexel-demo.index");

    /** How many declarations an answer lists; the rest are counted. */
    static final int LIMIT = 200;

    private final IndexStore store;

    public FindSymbols(IndexStore store) {
        this.store = store;
    }

    public SymbolsFound run(SymbolsWanted w) {
        Path root = BuildIndex.root(w.folder());
        if (root == null) {
            return new SymbolsFound(w.folder(), w.typed(), w.asked(), false, List.of(), 0,
                    "there is no Maven project here to search");
        }
        Optional<Index> index = index(root);
        if (index.isEmpty()) {
            return new SymbolsFound(root, w.typed(), w.asked(), false, List.of(), 0, "");
        }
        String typed = w.typed().strip();
        if (typed.isEmpty()) {
            return new SymbolsFound(root, typed, w.asked(), true, List.of(), 0, "");
        }
        List<Declaration> rows = new ArrayList<>();
        int more = 0;
        for (Symbol s : index.get().namesContaining(typed)) {
            if (!(s.source() instanceof Source.OnDisk disk)) {
                continue;
            }
            if (rows.size() < LIMIT) {
                rows.add(new Declaration(s.name(), kind(s.kind()), s.owner(), disk.file(), s.line(), s.at().column()));
            } else {
                more++;
            }
        }
        return new SymbolsFound(root, typed, w.asked(), true, List.copyOf(rows), more, "");
    }

    /** {@code root}'s index: the one held, or the one saved, read once and then held; empty when there is neither. */
    private Optional<Index> index(Path root) {
        Optional<Index> held = store.held(root);
        if (held.isPresent()) {
            return held;
        }
        try {
            return ProjectIndex.load(store.savedAt(root), root).map(p -> store.adopt(root, p.index()));
        } catch (IOException | RuntimeException e) {
            LOG.info("could not read the saved index of {}: {}", root, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Where {@code d}'s name is in {@code text}, the file as a tab holds it, lines ending in {@code \n}: the first
     * whole word {@code d.name()} at or after its line and column, which are where the declaration begins, annotations
     * and all. {@code [start, end)}; where the text has changed since it was indexed and the name is not found on or
     * soon after its line, an empty range at the start of the line, or at the end of the text when it is shorter.
     */
    public static int[] nameAt(String text, Declaration d) {
        int at = 0;
        for (int line = 1; line < d.line() && at >= 0; line++) {
            at = text.indexOf('\n', at);
            at = at < 0 ? -1 : at + 1;
        }
        if (at < 0) {
            return new int[] {text.length(), text.length()};
        }
        int lineStart = at;
        int from = Math.min(text.length(), lineStart + Math.max(0, d.column() - 1));
        // A declaration's annotations, modifiers and type parameters come before its name: a few lines at most.
        int limit = lineEnd(text, lineStart, 8);
        for (int i = text.indexOf(d.name(), from); i >= 0 && i < limit; i = text.indexOf(d.name(), i + 1)) {
            int end = i + d.name().length();
            if (!word(text, i - 1) && !word(text, end)) {
                return new int[] {i, end};
            }
        }
        return new int[] {lineStart, lineStart};
    }

    /** The end of the {@code lines}th line from {@code from}, or of the text. */
    private static int lineEnd(String text, int from, int lines) {
        int at = from;
        for (int i = 0; i < lines; i++) {
            int nl = text.indexOf('\n', at);
            if (nl < 0) {
                return text.length();
            }
            at = nl + 1;
        }
        return at;
    }

    private static boolean word(String text, int i) {
        return i >= 0 && i < text.length() && Character.isJavaIdentifierPart(text.charAt(i));
    }

    /** What a kind of declaration is called on a row: "class", "enum constant". */
    static String kind(Symbol.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
