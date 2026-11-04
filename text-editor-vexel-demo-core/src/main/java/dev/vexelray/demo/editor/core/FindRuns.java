package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.RunsOffered;
import dev.vexelray.demo.editor.core.Messages.RunsWanted;
import dev.vexelray.framework.api.Job;

/**
 * What the Run menu offers for a folder and a file ({@link Runs}): it reads poms, looks for a {@code main} and walks
 * the folder for programs, so it is a job. The answer names what it was asked about, so a view that has moved on since
 * can tell a stale answer from the one it wants.
 */
@Job
public final class FindRuns {

    public RunsOffered run(RunsWanted wanted) {
        return new RunsOffered(wanted.folder(), wanted.file(),
                Runs.offered(wanted.folder().orElse(null), wanted.file().orElse(null)));
    }
}
