package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.vexelray.demo.editor.core.Messages.GitRan;
import dev.vexelray.demo.editor.core.Messages.GitRun;
import dev.vexelray.framework.api.Job;

import java.util.List;

/**
 * Every write to the repository, on the offload lane: for now, staging and unstaging.
 *
 * <p>What git said is part of the answer: a write that fails, a path that has gone or an {@code index.lock} left by
 * another git, is {@code ok} false with git's own words, for the {@link Repository} to tell the person. The
 * {@code Repository} sends one at a time, so two of these never meet at the lock.
 */
@Job(topic = Messages.GIT_RUN, reply = Messages.GIT_RAN)
public final class RunGit {

    private final Git git = Git.onPath();

    public GitRan run(GitRun r) {
        List<String> paths = List.of(r.path());
        Git.Result done = switch (r.op()) {
            case STAGE -> git.stage(r.dir(), paths);
            case UNSTAGE -> git.unstage(r.dir(), paths);
        };
        return new GitRan(r.generation(), r.op(), r.path(), done.ok(), String.join(" ", done.command()),
                done.ok() ? "" : firstLine(done.err()));
    }

    /** Git's error, as the status line can say it: its first line that says something. */
    static String firstLine(String err) {
        for (String line : err.split("\\R")) {
            String s = line.strip();
            if (!s.isEmpty()) {
                return s.startsWith("fatal: ") ? s.substring(7) : s.startsWith("error: ") ? s.substring(7) : s;
            }
        }
        return "git failed";
    }
}
