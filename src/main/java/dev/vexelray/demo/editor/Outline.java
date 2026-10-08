package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.widget.TextField;
import sibarum.concordance.index.IndexBuilder;
import sibarum.concordance.index.Symbol;
import sibarum.probe.Log;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * What one Java document declares, as Concordance reads it — from the text in the field, not the file on disk.
 *
 * <p>The project's index is of the files as they were saved, and goes stale the moment a line is typed above a
 * declaration. This is the same parse over the document as it is now, refreshed the way the {@link Highlighter}
 * refreshes: on a worker, after the text changes, a newer change abandoning an older one. It is what the status
 * line's "where am I" reads, and what go to declaration trusts for the document in front.
 *
 * <p><b>Where a declaration ends is not known.</b> Concordance records where each one begins and not where it
 * ends, so {@link #scopeAt} is the last declaration beginning before the caret. Inside a member that is exact;
 * on the blank lines after a class's last member it still names that member.
 */
final class Outline {

    private static final Log LOG = Log.of("editor.index");

    /** Larger than this is not parsed, as the highlighter does not colour past its own limit. */
    private static final int MAX_CHARS = 2_000_000;

    private final Gui gui;
    private final TextField field;
    private final Supplier<Path> path;
    private final Runnable changed;
    private final AtomicLong generation = new AtomicLong();

    private volatile boolean java;
    private volatile boolean closed;
    /** In source order. Empty for a document that is not Java, and until the first parse lands. */
    private volatile List<Symbol> declarations = List.of();

    /**
     * @param path    the document's file, which declarations are attributed to; may answer null
     * @param changed told when {@link #declarations} has changed, on the worker that changed it
     */
    Outline(Gui gui, TextField field, Supplier<Path> path, Runnable changed) {
        this.gui = gui;
        this.field = field;
        this.path = path;
        this.changed = changed;
    }

    /** Whether the document is Java, which is all this reads. Called when it is opened and when it is saved as. */
    void language(String language) {
        java = "Java".equals(language);
        refresh();
    }

    List<Symbol> declarations() {
        return declarations;
    }

    void refresh() {
        long gen = generation.incrementAndGet();
        if (!java) {
            if (!declarations.isEmpty()) {
                declarations = List.of();
                changed.run();
            }
            return;
        }
        gui.async(() -> {
            if (closed || generation.get() != gen) {
                return;
            }
            String text = field.text();
            if (text.length() > MAX_CHARS) {
                return;
            }
            Path file = path.get();
            List<Symbol> found;
            try {
                found = IndexBuilder.buildSource(file == null ? Path.of("Untitled.java") : file, text).symbols();
            } catch (RuntimeException e) {
                // A parse that throws costs the outline, never the editor. A parse that merely fails to produce a
                // tree is not this: the last good outline stands while the text is mid-edit and does not parse.
                LOG.debug("outline parse failed", e);
                return;
            }
            if (found.isEmpty() && !text.isBlank()) {
                return;   // mid-edit and unparseable: keep the last outline rather than blanking the status line
            }
            List<Symbol> sorted = found.stream()
                    .sorted((a, b) -> a.line() != b.line()
                            ? Integer.compare(a.line(), b.line()) : Integer.compare(a.at().column(), b.at().column()))
                    .toList();
            if (generation.get() == gen && !sorted.equals(declarations)) {
                declarations = sorted;
                changed.run();
            }
        });
    }

    /**
     * Where {@code line}:{@code column} is, as the status line says it: {@code Outer › Inner › method}. Empty
     * before the first declaration, and for a document that is not Java.
     */
    String scopeAt(int line, int column) {
        List<Symbol> all = declarations;
        Symbol in = null;
        for (Symbol s : all) {
            if (s.line() < line || (s.line() == line && s.at().column() <= column)) {
                in = s;
            } else {
                break;
            }
        }
        return in == null ? "" : label(in, all);
    }

    /** {@code Outer › Inner} for a type, {@code Outer › Inner › member} for a member: the package left off. */
    static String label(Symbol s, List<Symbol> all) {
        Set<String> types = new HashSet<>();
        for (Symbol t : all) {
            if (t.kind().isType()) {
                types.add(t.qualified());
            }
        }
        String type = s.kind().isType() ? s.qualified() : s.owner();
        String shown = withoutPackage(type, types);
        return s.kind().isType() ? shown : shown + " › " + s.name();
    }

    /** The type's own name and its enclosing types', without the package: the first prefix that is a type starts it. */
    private static String withoutPackage(String qualified, Set<String> types) {
        String[] parts = qualified.split("\\.");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                prefix.append('.');
            }
            prefix.append(parts[i]);
            if (types.contains(prefix.toString())) {
                return String.join(" › ", List.of(parts).subList(i, parts.length));
            }
        }
        return parts[parts.length - 1];
    }

    /** Stop: in-flight and future parses become no-ops (the document is going). */
    void close() {
        closed = true;
    }
}
