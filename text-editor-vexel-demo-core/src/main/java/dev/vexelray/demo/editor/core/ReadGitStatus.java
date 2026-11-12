package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.sibarum.suite.git.Status;
import dev.vexelray.demo.editor.core.Messages.GitStatusRead;
import dev.vexelray.demo.editor.core.Messages.GitStatusWanted;
import dev.vexelray.framework.api.Job;

import java.util.Optional;

/**
 * What git says of the working tree under a folder, for the navigator's colours: the suite's {@link Git#status}, which
 * runs {@code git status} with the ignored files listed. A run of git is slow on a large repository, so it is a job.
 *
 * <p>Ignored is what git says it is: git reports a path as ignored only when it is untracked, so a tracked file that a
 * {@code .gitignore} also matches is reported as what it is, modified or clean. The navigator takes git's word.
 */
@Job
public final class ReadGitStatus {

    private final Git git = Git.onPath();

    public GitStatusRead run(GitStatusWanted wanted) {
        Optional<Status> status = git.status(wanted.folder());
        return new GitStatusRead(wanted.folder(), wanted.asked(), status.map(Status::root),
                status.map(Status::text).orElse(""));
    }
}
