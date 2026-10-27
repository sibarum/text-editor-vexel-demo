package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.sibarum.suite.git.Status;
import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import dev.vexelray.framework.api.Job;

import java.util.Optional;

/**
 * Ask git where a folder stands, on the offload lane.
 *
 * <p>A job, not a method on the {@link Repository}: {@code git status} on a large repository takes as long as it
 * takes, and on the repository's lane that would be a stalled component. Git not installed, a folder outside any
 * repository and a git that failed are all the same answer, {@code repository} false: nothing here is the user's
 * problem to be told about.
 */
@Job(topic = Messages.GIT_STATUS, reply = Messages.GIT_STATUS_READ)
public final class ReadStatus {

    private final Git git = Git.onPath();

    public GitRead run(GitAsk a) {
        Optional<Status> found = a.folder() == null ? Optional.empty() : git.status(a.folder());
        if (found.isEmpty()) {
            return new GitRead(a.generation(), false, "", 0, 0, 0, 0, 0);
        }
        Status s = found.get();
        Status.Branch b = s.branch();
        int changed = (int) s.changes().stream().filter(c -> c.staged() || c.unstaged()).count();
        return new GitRead(a.generation(), true, name(b), b.ahead(), b.behind(), s.staged().size(),
                s.unstaged().size(), changed);
    }

    /** The branch, or the short commit when {@code HEAD} is detached, or what a repository with no commit is on. */
    static String name(Status.Branch b) {
        if (b.head() != null) {
            return b.head();
        }
        return b.oid() != null ? b.oid().substring(0, Math.min(7, b.oid().length())) : "no commits";
    }
}
