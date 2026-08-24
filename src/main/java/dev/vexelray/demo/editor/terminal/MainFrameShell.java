package dev.vexelray.demo.editor.terminal;

import dev.mainframe.ExitRequest;
import dev.mainframe.MfError;
import dev.mainframe.Session;
import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Interpreter;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.fs.IndexStore;
import dev.mainframe.lang.Parser;
import dev.mainframe.ui.Renderer;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.mainframe.value.Values;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MainFrame, embedded. The shell is a library — a {@link Session} that talks through a {@link Renderer} over two
 * streams, and an {@link Interpreter} that runs a parsed program — so this window neither drives a subprocess nor
 * scrapes a terminal: it hands MainFrame two {@link LineSink}s and gets lines back.
 *
 * <p>Everything here runs on one job thread, in submission order, one line at a time. Nothing about running a
 * command touches the GUI thread; the only crossing is {@link Scrollback#post}, which queues.
 *
 * <p>The session is deliberately <b>not</b> interactive, and that is a decision rather than an omission.
 * MainFrame hands an external program the process's own stdio when the program is the last stage of an
 * <em>interactive</em> line — in a GUI that would send {@code ^git log} to whatever launched the JVM instead of
 * to this pane. And a command that can lose data would otherwise stop on a question this pane has no way to
 * answer. Non-interactive, MainFrame captures the program's output and answers the destructive command with
 * {@code add --yes once you are sure}: both of those are visible here, which is the point.
 */
final class MainFrameShell implements AutoCloseable {

    /** One command's output ceiling. Past it the rest is dropped, with a line saying so. */
    private static final int MAX_LINES = 20_000;
    private static final long MAX_CHARS = 4L * 1024 * 1024;

    private final Scrollback scrollback;
    /** The command line, as something MainFrame's forms and confirmations can read a line from. */
    private final PromptPipe pipe = new PromptPipe();
    private final Session session;
    private final Interpreter interpreter;
    private final ExecutorService jobs;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Runnable onExit;

    private volatile Thread worker;
    private volatile String lastError = "";
    /** The profile last applied to this session's environment, for the window to show. */
    private volatile String applied = "";
    private int lines;
    private long chars;
    private boolean truncated;

    /**
     * @param openFile called with a file that {@code edit} should open in a tab
     * @param openDir  called with a directory that {@code reveal} should point the folder window at
     * @param onExit   called when the {@code exit} builtin runs, to close the window
     * @param profiles the user's environment profiles, which the profile commands read and write
     * @param project  the project whose {@code .vtext} a project-scoped default is written to, read on demand
     *                 because the project changes under a running shell
     */
    MainFrameShell(Scrollback scrollback, Path cwd, Consumer<Path> openFile, Consumer<Path> openDir,
                   Runnable onExit, ProfileStore profiles,
                   java.util.function.Supplier<dev.vexelray.demo.editor.ProjectSettings> project) {
        this.scrollback = scrollback;
        this.onExit = onExit;

        PrintStream out = new PrintStream(new LineSink(this::emit), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new LineSink(this::emitError), true, StandardCharsets.UTF_8);
        // The session reads from the command line now. That is what lets a form ask a question in the scrollback
        // and get an answer back -- see PromptPipe. It stays *non*-interactive all the same: interactive means
        // "hand an external program the process's own stdio", which in a GUI sends it somewhere nobody can see.
        this.session = new Session(new Renderer(out, err, true), IndexStore.inState(),
                new BufferedReader(pipe), cwd);
        session.interactive(false);

        Registry registry = Registry.standard();
        registry.add(editCommand(openFile));
        registry.add(revealCommand(openDir));
        new ProfileCommands(profiles, project, name -> applied = name).register(registry);
        this.interpreter = new Interpreter(session, registry);

        this.jobs = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mainframe-job");
            t.setDaemon(true);
            return t;
        });
    }

    // ---- what the window asks --------------------------------------------------------

    /** Where MainFrame is standing — {@code cd} moves it, and the prompt reads it each frame. */
    Path cwd() {
        return session.cwd();
    }

    boolean busy() {
        return busy.get();
    }

    /** The last error, for the status line. Empty once a command succeeds. */
    String lastError() {
        return lastError;
    }

    /** Queue a line. Called from a handler thread; a line typed while a command runs waits its turn. */
    void submit(String source) {
        jobs.execute(() -> run(source));
    }

    /**
     * Whether the shell is waiting to be told something rather than to be given a command -- a form asking for a
     * field, or a destructive command asking for a yes.
     *
     * <p>This is what the window routes on, and it is a fact about the shell rather than a mode the window is put
     * into: it is true exactly while the job thread is blocked reading. A form that finishes, cancels or fails
     * stops reading, so the next line is a command again without anything having to say so.
     */
    boolean asking() {
        return pipe.waiting();
    }

    /** Hand a typed line to whatever is asking. */
    void answer(String line) {
        pipe.offer(line);
    }

    /** The profile last applied to this session, or {@code ""} if none has been. */
    String appliedProfile() {
        return applied;
    }

    /**
     * Ask the running command to stop.
     *
     * <p>Interrupting reaches what blocks — a child process being waited on, a read — but MainFrame's own loops
     * do not poll for it, so a long {@code find} or {@code index-build} runs to the end regardless. The status
     * line says so rather than implying the command died.
     */
    boolean interrupt() {
        Thread t = worker;
        if (t == null || !busy.get()) {
            return false;
        }
        t.interrupt();
        return true;
    }

    // ---- running ---------------------------------------------------------------------

    /** Job thread. Every way a command can end is handled here, so nothing reaches the frame loop. */
    private void run(String source) {
        worker = Thread.currentThread();
        busy.set(true);
        lines = 0;
        chars = 0;
        truncated = false;
        session.source(source);
        try {
            interpreter.run(Parser.parse(source));
            lastError = "";
        } catch (ExitRequest e) {
            onExit.run();
        } catch (MfError e) {
            session.out().error(e, source);
            lastError = "error[" + e.code() + "] " + e.getMessage();
        } catch (StackOverflowError e) {
            report("E901", "that nested too deeply for me to follow", source,
                    "check for a block that runs itself");
        } catch (RuntimeException e) {
            // A bug in MainFrame or in this integration, not in what was typed. Say which.
            report("E902", "MainFrame hit an internal problem: " + e, source,
                    "this is a bug in MainFrame, not in what you typed");
        } finally {
            if (truncated) {
                scrollback.post("... output truncated at " + MAX_LINES
                        + " lines -- send the rest to a file with: ... | save ./out.txt");
            }
            // Clear the interrupt so the next command on this thread does not inherit it.
            Thread.interrupted();
            busy.set(false);
        }
    }

    private void report(String code, String message, String source, String hint) {
        session.out().error(MfError.of(code, message).hint(hint).build(), source);
        lastError = "error[" + code + "] " + message;
    }

    private void emit(String line) {
        if (lines >= MAX_LINES || chars >= MAX_CHARS) {
            truncated = true;
            return;
        }
        lines++;
        chars += line.length() + 1;
        scrollback.post(line);
    }

    /** Warnings and error reports arrive here, already coloured by the renderer. Never capped. */
    private void emitError(String line) {
        scrollback.post(line);
    }

    // ---- the two commands the editor adds --------------------------------------------

    /**
     * {@code edit} — the reason a shell inside an editor beats a shell beside one. It takes paths as arguments or
     * a table with a {@code path} column from the pipe, so everything that produces files feeds it:
     * {@code find "*Test.java" | first 3 | edit}.
     */
    private Builtin editCommand(Consumer<Path> openFile) {
        Signature signature = Signature.named("edit", "editor")
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
    private Builtin revealCommand(Consumer<Path> openDir) {
        Signature signature = Signature.named("reveal", "editor")
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

    @Override
    public void close() {
        // Before the interrupt: a form parked on a read has to be told the input ended, or the job thread never
        // leaves it and the shutdown waits the full two seconds for nothing.
        pipe.close();
        interrupt();
        jobs.shutdownNow();
        try {
            jobs.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
