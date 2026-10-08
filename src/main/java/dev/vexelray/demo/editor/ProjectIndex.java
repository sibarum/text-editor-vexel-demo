package dev.vexelray.demo.editor;

import sibarum.concordance.index.Index;
import sibarum.concordance.index.IndexBuilder;
import sibarum.concordance.index.Reference;
import sibarum.concordance.index.Symbol;
import sibarum.concordance.project.MavenProject;
import sibarum.concordance.project.MavenProjectReader;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concordance's structural index of the open folder, when that folder is a Maven project.
 *
 * <p>Opening a folder with a {@code pom.xml} in it starts an index in the background; opening anything else drops
 * the one there was. What the index is <em>for</em> — find usages, go to a declaration — reads {@link #current},
 * which is null until a run has finished and is never half-built.
 *
 * <p><b>Its own thread, not the offload lane.</b> A large reactor takes seconds to parse, and the offload lane is
 * where file opens and saves queue; an index ahead of them in the queue would make Ctrl+O wait for it. The thread
 * is a daemon at minimum priority, so it neither holds the process open at quit nor competes with the frame loop.
 *
 * <p><b>A newer folder wins.</b> Each run carries a generation, and checks it between files: opening another
 * folder while one is being indexed abandons the old run at the next file rather than finishing it and then
 * throwing it away. Files are parsed one at a time for that reason — {@link IndexBuilder#buildFiles} over the
 * whole list would be one uninterruptible call — and the pieces are concatenated, which is all an {@link Index}
 * is.
 */
final class ProjectIndex {

    private static final Log LOG = Log.of("editor.index");

    private final Model model;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "editor-index");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicLong generation = new AtomicLong();
    /** The generation that last came to rest: finished, failed, or was not a project. Behind {@link #generation} while a run is going. */
    private volatile long settled;

    private volatile Index current;
    private volatile Path root;

    ProjectIndex(Model model) {
        this.model = model;
    }

    /** The finished index of the open project, or null: no project, or its first run has not finished. */
    Index current() {
        return current;
    }

    /** Whether an index of the open folder is being built now. */
    boolean indexing() {
        return settled != generation.get();
    }

    /** The project {@link #current} is of, or null. */
    Path root() {
        return root;
    }

    /**
     * The navigator now shows {@code folder}: index it if it is a Maven project, and forget the last index either
     * way. Reopening the folder already indexed indexes it again, which is the way to refresh it for now.
     */
    void folder(Path folder) {
        long mine;
        // Under the lock the run publishes under, so a run that has just passed its last check cannot land after this.
        synchronized (this) {
            mine = generation.incrementAndGet();
            current = null;
            root = null;
        }
        if (folder == null || !Files.isRegularFile(folder.resolve("pom.xml"))) {
            settled = mine;
            return;
        }
        worker.execute(() -> run(folder, mine));
    }

    private void run(Path folder, long mine) {
        String name = String.valueOf(folder.getFileName() == null ? folder : folder.getFileName());
        long started = System.nanoTime();
        List<Path> files;
        try {
            MavenProject project = MavenProjectReader.read(folder);
            files = project.javaFiles();
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not read the Maven project at {}", folder, e);
            if (stale(mine)) {
                return;
            }
            settled = mine;
            model.say("Not indexed: " + e.getMessage());
            return;
        }
        model.say("Indexing " + name + " (" + files.size() + " files)");

        List<Symbol> symbols = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        int failed = 0;
        for (Path file : files) {
            if (stale(mine)) {
                LOG.debug("index of {} abandoned for a newer folder", folder);
                return;
            }
            try {
                Index one = IndexBuilder.buildFiles(List.of(file));
                symbols.addAll(one.symbols());
                references.addAll(one.references());
            } catch (IOException | RuntimeException e) {
                // One unreadable file costs that file, not the project.
                failed++;
                LOG.debug("could not index {}", file, e);
            }
        }
        Index index = new Index(symbols, references);
        long ms = (System.nanoTime() - started) / 1_000_000;
        synchronized (this) {
            if (stale(mine)) {
                return;
            }
            current = index;
            root = folder;
            settled = mine;
        }
        LOG.info("indexed {} in {} ms: {}", folder, ms, index.summary());
        model.say("Indexed " + name + ": " + index.summary()
                + (failed == 0 ? "" : ", " + failed + " files unreadable"));
    }

    private boolean stale(long mine) {
        return generation.get() != mine;
    }
}
