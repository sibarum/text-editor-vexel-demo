package dev.vexelray.demo.editor;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;
import dev.vexelray.gui.core.input.MenuSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The editor, as far as MainFrame is concerned: two commands and a window it can raise.
 *
 * <h2>Which way round this goes</h2>
 * The console has no idea what a text editor is. It knows this application handed it a {@link ConsoleApp}, that
 * the app named itself {@code editor}, and that {@code launch "editor"} brings its window forward. Everything
 * specific to editing — what a tab is, what a folder window is, which file is open — stays on this side of the
 * line, and the console stays the same console it would be in any other application.
 *
 * <p>Which is also why the wiring is three {@link Consumer}s rather than a reference to the application: the two
 * things a shell wants of an editor are "open this file" and "show me this directory", and neither of them needs
 * to know what is on the other end.
 *
 * <h2>Threads</h2>
 * A command body runs on the shell's job thread. So the three callbacks here are handed straight through to the
 * application's own request queue rather than being run where they were called — see
 * {@code FileActions.openPath}.
 */
public final class EditorApp implements ConsoleApp {

    private final Consumer<Path> openFile;
    private final Consumer<Path> openDir;
    private final Runnable raise;

    /**
     * @param openFile called with a file that {@code edit} should open in a tab
     * @param openDir  called with a directory that {@code reveal} should point the folder window at
     * @param raise    called to bring the editor window forward, which is what {@code launch "editor"} means
     */
    public EditorApp(Consumer<Path> openFile, Consumer<Path> openDir, Runnable raise) {
        this.openFile = openFile;
        this.openDir = openDir;
        this.raise = raise;
    }

    @Override
    public String name() {
        return "editor";
    }

    @Override
    public String summary() {
        return "open files in tabs, and point the file tree at a directory";
    }

    @Override
    public void commands(Registry registry, ConsoleContext console) {
        registry.add(editCommand());
        registry.add(revealCommand());
    }

    /** The editor window always exists while this application is running, so it can always be raised. */
    @Override
    public boolean launchable() {
        return true;
    }

    @Override
    public void launch(ConsoleContext console) {
        raise.run();
    }

    @Override
    public void menu(MenuSink menu, ConsoleContext console) {
        menu.item("Edit a file here...", () -> console.run("ls | where kind == \"file\" | first 1 | edit"));
        menu.item("Show this directory in the file tree", () -> console.run("reveal"));
    }

    /**
     * {@code edit} — the reason a shell inside an editor beats a shell beside one. It takes paths as arguments or
     * a table with a {@code path} column from the pipe, so everything that produces files feeds it:
     * {@code find "*Test.java" | first 3 | edit}.
     */
    private Builtin editCommand() {
        Signature signature = Signature.named("edit", name())
                .summary("open files in the editor window")
                .rest("file", ValueType.PATH, "a file to open; or pipe in rows with a path column")
                .input(ValueType.ANY)
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.READS)
                .example("edit ./pom.xml")
                .example("ls | where ext == \"java\" | first 3 | edit")
                .build();
        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                List<Path> files = targets(args);
                for (Path file : files) {
                    if (!Files.isRegularFile(file)) {
                        throw args.fail("E802", "there is no file to edit at " + file)
                                .hint(Files.isDirectory(file)
                                        ? "that is a directory -- use cd or reveal for one of those"
                                        : "check the path")
                                .hint("keep only files first: ... | where kind == \"file\" | edit").build();
                    }
                }
                for (Path file : files) {
                    openFile.accept(file);
                }
                args.session().out().note("opening " + files.size()
                        + (files.size() == 1 ? " file" : " files") + " in the editor");
                return Value.Nothing.INSTANCE;
            }
        };
    }

    /** {@code reveal} — point the folder window at a directory. Defaults to where MainFrame is standing. */
    private Builtin revealCommand() {
        Signature signature = Signature.named("reveal", name())
                .summary("show a directory in the folder window")
                .optional("directory", ValueType.PATH, "the directory to show; defaults to the current one")
                .output(ValueType.NOTHING)
                .effect(Signature.Effect.READS)
                .example("reveal")
                .example("reveal ./src/main/java")
                .build();
        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                Path dir = args.path(0, args.session().cwd());
                if (!Files.isDirectory(dir)) {
                    throw args.fail("E803", dir + " is not a directory")
                            .hint("reveal shows directories; for a file use edit").build();
                }
                openDir.accept(dir);
                args.session().out().note("showing " + dir + " in the folder window");
                return Value.Nothing.INSTANCE;
            }
        };
    }

    /** The paths named as arguments, or the {@code path} column of whatever came through the pipe. */
    private static List<Path> targets(Args args) {
        List<Path> files = new ArrayList<>(args.paths(0));
        if (!files.isEmpty()) {
            return files;
        }
        if (!args.hasInput()) {
            throw args.failUsage("E801", "edit needs a file")
                    .hint("name one, or pipe in rows carrying a path column").build();
        }
        for (Value.Rec row : args.rows()) {
            Value cell = row.get("path");
            if (cell instanceof Value.PathVal p) {
                files.add(p.path());
            } else if (cell != null) {
                files.add(args.session().resolve(Values.display(cell)));
            }
        }
        if (files.isEmpty()) {
            throw args.fail("E804", "those rows have no column called path")
                    .hint("pipe in something that carries paths, e.g. ls | edit").build();
        }
        return files;
    }
}
