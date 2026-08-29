package dev.vexelray.demo.editor;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.eval.Signature.Effect;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.IndexBuilder;
import sibarum.concordance.index.Reference;
import sibarum.concordance.index.Symbol;
import sibarum.concordance.project.MavenProject;
import sibarum.concordance.project.MavenProjectReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * Concordance in the shell: build an index of a Maven project, then ask it where things are.
 *
 * <p>Four commands — {@code index}, {@code names}, {@code usages}, {@code impls} — and the last three emit rows
 * carrying a {@code path} column, which is the whole point of putting them here rather than in a CLI. The editor
 * already has {@code edit}, which opens rows with a {@code path} column, so the queries compose with it:
 *
 * <pre>{@code
 * index .
 * names "Chapter" | first 5 | edit
 * usages area | where kind == "CALL" | edit
 * impls Shape
 * }</pre>
 *
 * <p>The index is held for the session and rebuilt only when {@code index} is run again. It is a snapshot of what
 * was on disk at that moment: editing a file does not update it, and the commands say so rather than quietly
 * answering from a stale index.
 */
public final class ConcordanceApp implements ConsoleApp {

    /** The built index, and where it was built from. Null until {@code index} has been run. */
    private Index index;
    private Path indexedRoot;
    private String builtSummary = "";

    @Override
    public String name() {
        return "concordance";
    }

    @Override
    public String summary() {
        return "index a Maven project, and search what it declares and uses";
    }

    @Override
    public void commands(Registry registry, ConsoleContext console) {
        registry.add(indexCommand());
        registry.add(namesCommand());
        registry.add(usagesCommand());
        registry.add(implsCommand());
    }

    // --- index ---------------------------------------------------------------------------------

    private Builtin indexCommand() {
        Signature signature = Signature.named("index", name())
                .summary("build a Concordance index of a Maven project")
                .optional("directory", ValueType.PATH,
                        "the project root, or a pom.xml; defaults to where the shell is standing")
                .output(ValueType.ANY)
                .effect(Effect.READS)
                .example("index .")
                .example("index ../vexelray-gui")
                .build();

        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                Path root = args.has(0) ? args.paths(0).get(0) : args.session().cwd();
                if (!Files.exists(root)) {
                    throw args.fail("E900", "there is nothing at " + root)
                            .hint("give the directory holding the project's pom.xml").build();
                }
                long started = System.nanoTime();
                MavenProject project;
                Index built;
                try {
                    project = MavenProjectReader.read(root);
                    built = IndexBuilder.build(project);
                } catch (IOException e) {
                    throw args.fail("E901", "could not index " + root + ": " + e.getMessage())
                            .hint("a module named in a pom may be missing from the disk")
                            .hint("index the directory holding the root pom.xml").build();
                }
                long millis = (System.nanoTime() - started) / 1_000_000;

                index = built;
                indexedRoot = root;
                builtSummary = built.summary();

                int files;
                try {
                    files = project.javaFiles().size();
                } catch (IOException e) {
                    files = -1;
                }
                args.session().out().note("indexed " + root + " -- " + built.summary()
                        + ", from " + project.modules().size() + " modules and "
                        + files + " files, in " + millis + " ms");

                SequencedMap<String, Value> row = new LinkedHashMap<>();
                row.put("root", new Value.PathVal(root));
                row.put("modules", new Value.Int(project.modules().size()));
                row.put("files", new Value.Int(files));
                row.put("types", new Value.Int(built.types().size()));
                row.put("symbols", new Value.Int(built.symbols().size()));
                row.put("references", new Value.Int(built.references().size()));
                row.put("ms", new Value.Int(millis));
                return new Value.ListVal(List.of(new Value.Rec(row)));
            }
        };
    }

    // --- names ---------------------------------------------------------------------------------

    private Builtin namesCommand() {
        Signature signature = Signature.named("names", name())
                .summary("find declarations whose name contains a fragment")
                .required("fragment", ValueType.STRING, "the substring to look for, case-insensitively")
                .output(ValueType.ANY)
                .effect(Effect.READS)
                .example("names Chapter")
                .example("names index | where kind == \"CLASS\"")
                .example("names Widget | first 5 | edit")
                .build();

        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                Index current = require(args);
                List<Symbol> hits = current.namesContaining(args.str(0));
                if (hits.isEmpty()) {
                    args.session().out().note("no declaration's name contains " + args.str(0));
                }
                return rowsOfSymbols(hits);
            }
        };
    }

    // --- usages --------------------------------------------------------------------------------

    private Builtin usagesCommand() {
        Signature signature = Signature.named("usages", name())
                .summary("find where a name is used")
                .required("name", ValueType.STRING, "the simple name of a method, type or field")
                .output(ValueType.ANY)
                .effect(Effect.READS)
                .example("usages area")
                .example("usages append | where kind == \"CALL\" | first 10 | edit")
                .build();

        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                Index current = require(args);
                String name = args.str(0);
                List<Reference> uses = current.usagesOf(name);
                if (uses.isEmpty()) {
                    args.session().out().note("nothing uses " + name
                            + " -- names are matched exactly, and this index has no resolver");
                }
                List<Value> rows = uses.stream().map(reference -> {
                    SequencedMap<String, Value> row = new LinkedHashMap<>();
                    row.put("kind", new Value.Str(reference.kind().name()));
                    row.put("name", new Value.Str(reference.name()));
                    row.put("from", new Value.Str(reference.from()));
                    row.put("path", new Value.PathVal(reference.at().file()));
                    row.put("line", new Value.Int(reference.at().line()));
                    return (Value) new Value.Rec(row);
                }).toList();
                return new Value.ListVal(rows);
            }
        };
    }

    // --- impls ---------------------------------------------------------------------------------

    private Builtin implsCommand() {
        Signature signature = Signature.named("impls", name())
                .summary("find the types that implement or extend a type, transitively")
                .required("type", ValueType.STRING, "the simple name of an interface or class")
                .optional("method", ValueType.STRING,
                        "a method name; narrows to the implementations of that method")
                .output(ValueType.ANY)
                .effect(Effect.READS)
                .example("impls Chapter")
                .example("impls Shape area")
                .example("impls ConsoleApp | edit")
                .build();

        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                Index current = require(args);
                String type = args.str(0);
                List<Symbol> hits = args.has(1)
                        ? current.implementationsOfMethod(type, args.str(1))
                        : current.implementationsOf(type);
                if (hits.isEmpty()) {
                    args.session().out().note("nothing implements or extends " + type);
                }
                return rowsOfSymbols(hits);
            }
        };
    }

    // --- shared --------------------------------------------------------------------------------

    /**
     * The index, or a failure that says how to get one.
     *
     * <p>A query against no index is the most likely mistake here, so it is worth a real message rather than an
     * empty result that looks like a genuine answer.
     */
    private Index require(Args args) {
        if (index == null) {
            throw args.fail("E902", "there is no index yet")
                    .hint("run: index .")
                    .hint("or point it somewhere: index ../vexelray-gui").build();
        }
        return index;
    }

    private static Value rowsOfSymbols(List<Symbol> symbols) {
        List<Value> rows = symbols.stream().map(symbol -> {
            SequencedMap<String, Value> row = new LinkedHashMap<>();
            row.put("kind", new Value.Str(symbol.kind().name()));
            row.put("name", new Value.Str(symbol.name()));
            row.put("owner", new Value.Str(symbol.owner()));
            row.put("path", new Value.PathVal(symbol.at().file()));
            row.put("line", new Value.Int(symbol.at().line()));
            return (Value) new Value.Rec(row);
        }).toList();
        return new Value.ListVal(rows);
    }

    /** What the status line can say about this app, when there is anything to say. */
    public String indexStatus() {
        return index == null ? "" : indexedRoot.getFileName() + ": " + builtSummary;
    }
}
