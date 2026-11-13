package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.IndexWanted;
import dev.vexelray.demo.editor.core.Messages.Indexed;
import dev.vexelray.framework.api.Job;
import sibarum.concordance.index.ProjectIndex;
import sibarum.concordance.project.Classpath;
import sibarum.concordance.project.Dependency;
import sibarum.concordance.project.DependencyResolver;
import sibarum.concordance.project.LocalRepository;
import sibarum.concordance.project.MavenProject;
import sibarum.concordance.project.MavenProjectReader;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Concordance's index of a Maven project: every declaration and every use of a name in its sources, and what its
 * libraries export, read from their {@code -sources.jar} or, failing that, their class files. This builds it and keeps
 * it ({@link IndexStore}), on disk and in memory; {@link FindSymbols} reads it.
 *
 * <p><b>The whole project.</b> The folder asked about may be a module, so the project is the outermost of the
 * folders with a {@code pom.xml} that run unbroken up from it: the reactor a module is built in, not the module alone.
 *
 * <p><b>Kept between runs.</b> The index saved last time is loaded first, so a project indexed before rereads only
 * the files that changed since, and the result is saved again when anything did. A saved index that cannot be read is
 * built afresh. A library the local repository does not have is left out, and the log says so.
 *
 * <p>It holds an offload thread for as long as a large project takes to parse, seconds at most; the lane is a pool, so
 * opens and saves go on around it.
 */
@Job
public final class BuildIndex {

    private static final Log LOG = Log.of("text-editor-vexel-demo.index");

    private final IndexStore store;

    public BuildIndex(IndexStore store) {
        this.store = store;
    }

    public Indexed run(IndexWanted w) {
        Path root = root(w.folder());
        if (root == null) {
            return new Indexed(w.folder(), "", 0, "there is no pom.xml here or above to index");
        }
        long started = System.nanoTime();
        try {
            MavenProject project = MavenProjectReader.read(root);
            Classpath classpath = libraries(project);
            List<Path> classes = classpath.withoutSources().stream()
                    .map(Dependency::jar).filter(Objects::nonNull).toList();
            Path saved = store.savedAt(root);
            ProjectIndex live = load(saved, root);
            live.useJarCache(store.jars());
            ProjectIndex.Changes changes = live.synchronize(project.javaFiles(), classpath.sourcesJars(), classes);
            List<String> problems = live.problems();
            problems.forEach(p -> LOG.debug("not indexed: {}", p));
            if (!changes.isEmpty() || !Files.isRegularFile(saved)) {
                try {
                    Files.createDirectories(saved.getParent());
                    live.save(saved);
                } catch (IOException e) {
                    // The index stands; only the next run's head start is lost.
                    LOG.warn("could not save the index of {} to {}: {}", root, saved, e.toString());
                }
            }
            store.hold(root, live.index());   // what FindSymbols reads from now on
            String summary = live.index().summary();
            LOG.info("indexed {} in {} ms: {}; {}", root, (System.nanoTime() - started) / 1_000_000, summary, changes);
            return new Indexed(root, summary, problems.size(), "");
        } catch (IOException | RuntimeException e) {
            // A line, not a trace: the reply says why, and a pom that does not read is not a bug in this program.
            LOG.warn("could not index the Maven project at {}: {}", root, e.toString());
            return new Indexed(root, "", 0, String.valueOf(e.getMessage()));
        }
    }

    /** The project {@code folder} is in, the whole of it: see the class comment. Null when there is none. */
    static Path root(Path folder) {
        Path root = Poms.projectOf(folder);
        while (root != null && root.getParent() != null && Files.isRegularFile(root.getParent().resolve("pom.xml"))) {
            root = root.getParent();
        }
        return root;
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

    /** The index saved at {@code saved}, or an empty one: none saved, or unreadable. */
    private static ProjectIndex load(Path saved, Path root) {
        try {
            Optional<ProjectIndex> found = ProjectIndex.load(saved, root);
            if (found.isPresent()) {
                return found.get();
            }
        } catch (IOException | RuntimeException e) {
            LOG.info("could not read the saved index at {}, so building afresh: {}", saved, e.toString());
        }
        return ProjectIndex.empty(root);
    }
}
