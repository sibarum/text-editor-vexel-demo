package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.sibarum.suite.git.Numstat;
import dev.sibarum.suite.git.Status;
import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import dev.vexelray.framework.api.Job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Ask git where a folder stands, on the offload lane: the branch and its distance, what is changed, and how many lines
 * each change adds and removes.
 *
 * <p>A job, not a method on the {@link Repository}: {@code git status} on a large repository takes as long as it
 * takes, and on the repository's lane that would be a stalled component. Git not installed, a folder outside any
 * repository and a git that failed are all the same answer, {@code repository} false: nothing here is the user's
 * problem to be told about.
 *
 * <p>Git counts lines only for what it tracks, so an untracked file is read here and counted ({@code Numstat.count}),
 * its record joined to git's own. At most {@link #COUNTED} are: past that, a row shows no count rather than the
 * window waiting on the disk.
 */
@Job(topic = Messages.GIT_STATUS, reply = Messages.GIT_STATUS_READ)
public final class ReadStatus {

    /** How many untracked files are read to count their lines. */
    static final int COUNTED = 200;

    private final Git git = Git.onPath();

    public GitRead run(GitAsk a) {
        Path gitDir = a.folder() == null ? null : git.gitDir(a.folder()).orElse(null);
        // Stamped before git is asked, so a change made while it runs is seen as one by the next look.
        String stamp = ReadStamp.of(gitDir);
        Optional<Status> found = gitDir == null ? Optional.empty() : git.status(a.folder());
        if (found.isEmpty()) {
            return new GitRead(a.generation(), false, "", "", 0, 0, 0, 0, 0, null, null, "", "", "", "");
        }
        Status s = found.get();
        Status.Branch b = s.branch();
        int changed = (int) s.changes().stream().filter(c -> c.staged() || c.unstaged()).count();
        StringBuilder unstagedLines = new StringBuilder(git.numstat(s.root(), false).text());
        int counted = 0;
        for (Status.Change c : s.changes()) {
            if (c.state() == Status.State.UNTRACKED && !c.path().endsWith("/") && counted++ < COUNTED) {
                Path file = s.root().resolve(c.path());
                if (Files.isRegularFile(file)) {
                    unstagedLines.append(Numstat.record(c.path(), Numstat.count(file)));
                }
            }
        }
        return new GitRead(a.generation(), true, name(b), b.upstream() == null ? "" : b.upstream(), b.ahead(),
                b.behind(), s.staged().size(), s.unstaged().size(), changed, s.root(), gitDir, stamp, s.text(),
                unstagedLines.toString(), git.numstat(s.root(), true).text());
    }

    /** The branch, or the short commit when {@code HEAD} is detached, or what a repository with no commit is on. */
    static String name(Status.Branch b) {
        if (b.head() != null) {
            return b.head();
        }
        return b.oid() != null ? b.oid().substring(0, Math.min(7, b.oid().length())) : "no commits";
    }
}
