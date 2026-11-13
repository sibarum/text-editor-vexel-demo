package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.ProjectFound;
import dev.vexelray.demo.editor.core.Messages.ProjectWanted;
import dev.vexelray.framework.api.Job;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The Maven project a file is in, for Reveal in navigator: the nearest folder above it with a {@code pom.xml}, and on
 * up while the folder above that has one too, so a module reveals in the reactor it is built in. The same project
 * {@link BuildIndex} indexes, so the tree shown is the one Symbols searches.
 */
@Job
public final class FindProject {

    public ProjectFound run(ProjectWanted w) {
        Path file = w.file().toAbsolutePath().normalize();
        return new ProjectFound(file, Optional.ofNullable(BuildIndex.root(file.getParent())));
    }
}
