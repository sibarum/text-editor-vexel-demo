package dev.vexelray.demo.editor.core;

import sibarum.concordance.index.Index;
import sibarum.concordance.index.JarCache;
import sibarum.concordance.project.Classpath;
import sibarum.concordance.project.Dependency;
import sibarum.concordance.project.DependencyResolver;
import sibarum.concordance.project.LocalRepository;
import sibarum.concordance.project.MavenProject;
import sibarum.concordance.project.MavenProjectReader;
import dev.vexelray.framework.api.Publishes;
import sibarum.atchung.Atchung;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concordance's structural index of the open folder and its libraries, when that folder is a Maven project.
 *
 * <p>Opening a folder with a {@code pom.xml} in it starts an index in the background; opening anything else drops
 * the one there was. What the index is <em>for</em> — find usages, go to a declaration — reads {@link #current},
 * which is null until a run has finished and is never half-built.
 *
 * <p><b>Libraries too.</b> The project's dependencies are worked out from its poms and the local repository, as
 * Maven would, and each is indexed from its {@code -sources.jar} or, failing that, from its class files, to be
 * shown decompiled. A library the repository does not have is left out, and says so in the log.
 *
 * <p><b>Kept between runs, once {@link #persist} is called.</b> The index is saved per project root, so reopening a
 * project rereads only the files that changed while it was closed, and each jar's index is shared through
 * Concordance's machine-wide {@link JarCache}, so a library another project already indexed costs a lookup. Until
 * then nothing is written anywhere, which is how a tree built headless for a test leaves the home directory alone.
 *
 * <p><b>Its own thread, not the offload lane.</b> A large reactor takes seconds to parse, and the offload lane is
 * where file opens and saves queue; an index ahead of them in the queue would make Ctrl+O wait for it. The thread
 * is a daemon at minimum priority, so it neither holds the process open at quit nor competes with the frame loop.
 *
 * <p><b>A newer folder wins.</b> Each run carries a generation, and opening another folder interrupts the run in
 * progress. Concordance answers an interrupt by marking each remaining file interrupted rather than parsing it, so
 * the old run ends quickly; its index is then thrown away, never saved, and never published. An interrupted jar is
 * not put in the jar cache either.
 */
@Publishes(Messages.STATUS)
public final class ProjectIndex {

    private static final Log LOG = Log.of("editor.index");

    private final Atchung bus;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "editor-index");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicLong generation = new AtomicLong();
    /** The generation that last came to rest: finished, failed, or was not a project. Behind {@link #generation} while a run is going. */
    private volatile long settled;
    /** The run going now, to interrupt when a newer folder arrives. Guarded by {@code this}. */
    private Future<?> running;

    /** Where each project's index is saved, by root; null to save nothing. */
    private volatile Path store;
    /** The machine's shared jar indexes; null to use none. */
    private volatile JarCache jars;

    private volatile Index current;
    private volatile Path root;

    public ProjectIndex(Atchung bus) {
        this.bus = bus;
    }

    /**
     * From now on, save each project's index under {@code store} and share jars through {@code jars}. Called by the
     * session's restore, before it opens the last folder, so the first index of a run is already the saved one.
     */
    public void persist(Path store, JarCache jars) {
        this.store = store;
        this.jars = jars;
    }

    /** The finished index of the open project, or null: no project, or its first run has not finished. */
    public Index current() {
        return current;
    }

    /** Whether an index of the open folder is being built now. */
    public boolean indexing() {
        return settled != generation.get();
    }

    /** The project {@link #current} is of, or null. */
    public Path root() {
        return root;
    }

    /**
     * The navigator now shows {@code folder}: index it if it is a Maven project, and forget the last index either
     * way. Reopening the folder already indexed brings it up to date, which is the way to refresh it for now.
     */
    public void folder(Path folder) {
        long mine;
        // Under the lock the run publishes under, so a run that has just passed its last check cannot land after this.
        synchronized (this) {
            mine = generation.incrementAndGet();
            current = null;
            root = null;
            if (running != null) {
                running.cancel(true);
                running = null;
            }
            if (folder == null || !Files.isRegularFile(folder.resolve("pom.xml"))) {
                settled = mine;
                return;
            }
            running = worker.submit(() -> run(folder, mine));
        }
    }

    private void run(Path folder, long mine) {
        String name = String.valueOf(folder.getFileName() == null ? folder : folder.getFileName());
        long started = System.nanoTime();
        try {
            MavenProject project = MavenProjectReader.read(folder);
            List<Path> files = project.javaFiles();
            Classpath classpath = libraries(project);
            List<Path> sources = classpath.sourcesJars();
            List<Path> classes = classpath.withoutSources().stream()
                    .map(Dependency::jar).filter(Objects::nonNull).toList();
            if (stale(mine)) {
                return;
            }
            say("Indexing " + name + " (" + files.size() + " files, "
                    + (sources.size() + classes.size()) + " libraries)");

            Path saved = savedAt(folder);
            sibarum.concordance.index.ProjectIndex live = load(saved, folder);
            live.useJarCache(jars);
            sibarum.concordance.index.ProjectIndex.Changes changes = live.synchronize(files, sources, classes);
            if (stale(mine)) {
                LOG.debug("index of {} abandoned for a newer folder", folder);
                return;
            }
            Index index = live.index();
            List<String> problems = live.problems();
            problems.forEach(p -> LOG.debug("not indexed: {}", p));
            if (saved != null && !changes.isEmpty()) {
                try {
                    live.save(saved);
                } catch (IOException e) {
                    // The index stands; only the next run's head start is lost.
                    LOG.warn("could not save the index of {} to {}", folder, saved, e);
                }
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            synchronized (this) {
                if (stale(mine)) {
                    return;
                }
                current = index;
                root = folder;
                settled = mine;
            }
            LOG.info("indexed {} in {} ms: {}; {}", folder, ms, index.summary(), changes);
            say("Indexed " + name + ": " + index.summary()
                    + (problems.isEmpty() ? "" : ", " + problems.size() + " not readable"));
        } catch (IOException | RuntimeException e) {
            if (stale(mine)) {
                return;   // an interrupt can surface as a closed channel; the newer folder is what matters
            }
            LOG.warn("could not index the Maven project at {}", folder, e);
            synchronized (this) {
                if (stale(mine)) {
                    return;
                }
                settled = mine;
            }
            say("Not indexed: " + e.getMessage());
        }
    }

    /** The project's dependencies as the local repository has them; none when there is no repository to look in. */
    private static Classpath libraries(MavenProject project) {
        LocalRepository repository;
        try {
            repository = LocalRepository.locate();
        } catch (IOException | RuntimeException e) {
            LOG.info("no local Maven repository, so no libraries: {}", e.getMessage());
            return new Classpath(List.of(), List.of());
        }
        Classpath classpath = DependencyResolver.resolve(project, repository);
        classpath.problems().forEach(p -> LOG.debug("library left out: {}", p));
        return classpath;
    }

    /** The index saved for {@code folder}, or an empty one: none saved, not saving, or unreadable. */
    private static sibarum.concordance.index.ProjectIndex load(Path saved, Path folder) {
        if (saved != null) {
            try {
                Optional<sibarum.concordance.index.ProjectIndex> found =
                        sibarum.concordance.index.ProjectIndex.load(saved, folder);
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug("could not read the saved index at {}; building afresh", saved, e);
            }
        }
        return sibarum.concordance.index.ProjectIndex.empty(folder);
    }

    /** Where {@code folder}'s index is saved: named by a hash of its path, since a path is not a file name. */
    private Path savedAt(Path folder) {
        Path dir = store;
        if (dir == null) {
            return null;
        }
        String key = folder.toAbsolutePath().normalize().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return dir.resolve(HexFormat.of().formatHex(digest, 0, 12) + ".concordance");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    private boolean stale(long mine) {
        return generation.get() != mine;
    }

    /** Say something on the status line: what indexing is doing, which the person is otherwise left to guess. */
    private void say(String message) {
        bus.publish(Messages.STATUS_TOPIC, new Messages.Status(message, false));
    }
}
