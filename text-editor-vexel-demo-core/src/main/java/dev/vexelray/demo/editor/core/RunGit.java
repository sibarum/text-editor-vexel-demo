package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.vexelray.demo.editor.core.Messages.GitRan;
import dev.vexelray.demo.editor.core.Messages.GitRun;
import dev.vexelray.framework.api.Job;

import java.util.List;

/**
 * Every write to the repository, on the offload lane: staging, unstaging and committing. A commit runs the
 * repository's hooks, which may take minutes ({@code Git.COMMIT_TIMEOUT}); a slow answer is what this lane is for.
 *
 * <p>What git said is part of the answer: a write that fails, a path that has gone or an {@code index.lock} left by
 * another git, is {@code ok} false with git's own words, for the {@link Repository} to tell the person. The
 * {@code Repository} sends one at a time, so two of these never meet at the lock.
 */
@Job(topic = Messages.GIT_RUN, reply = Messages.GIT_RAN)
public final class RunGit {

    private final Git git = Git.onPath();

    public GitRan run(GitRun r) {
        Git.Result done = switch (r.op()) {
            case STAGE -> git.stage(r.dir(), List.of(r.path()));
            case UNSTAGE -> git.unstage(r.dir(), List.of(r.path()));
            case COMMIT -> git.commit(r.dir(), r.message());
        };
        return new GitRan(r.generation(), r.op(), r.path(), done.ok(), String.join(" ", done.command()),
                done.ok() ? "" : firstLine(done.err(), done.text()));
    }

    /**
     * Git's error, as the status line can say it: its first line that says something. Some failures are said on
     * standard output instead, after a summary ({@code nothing to commit} closes a {@code git status}), so then it is
     * the last line.
     */
    static String firstLine(String err, String out) {
        List<String> lines = List.of((err.isBlank() ? out : err).split("\\R"));
        for (String line : err.isBlank() ? lines.reversed() : lines) {
            String s = line.strip();
            if (!s.isEmpty()) {
                return s.startsWith("fatal: ") ? s.substring(7) : s.startsWith("error: ") ? s.substring(7) : s;
            }
        }
        return "git failed";
    }
}
