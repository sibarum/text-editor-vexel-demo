package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.vexelray.demo.editor.core.Messages.GitOp;
import dev.vexelray.demo.editor.core.Messages.GitRan;
import dev.vexelray.demo.editor.core.Messages.GitRun;
import dev.vexelray.framework.api.Job;

import java.util.List;

/**
 * Every write to the repository, on the offload lane: staging, unstaging, committing, pushing and switching branch. A
 * commit runs the repository's hooks, and a push waits on the network and perhaps on a person signing in, either of
 * which may take minutes ({@code Git.COMMIT_TIMEOUT}, {@code Git.PUSH_TIMEOUT}); a slow answer is what this lane is
 * for.
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
            case PUSH -> git.push(r.dir());
            case SWITCH -> git.switchTo(r.dir(), r.path());
        };
        String error = done.ok() ? ""
                : r.op() == GitOp.PUSH ? pushLine(done.err()) : firstLine(done.err(), done.text());
        return new GitRan(r.generation(), r.op(), r.path(), done.ok(), String.join(" ", done.command()), error);
    }

    /**
     * Why a push failed, as the status line can say it. Git first says where it pushed to ({@code To …}) and ends with
     * hints, so neither is it; a ref the remote turned away is said as git says it, with its reason:
     * {@code rejected main -> main (fetch first)}.
     */
    static String pushLine(String err) {
        for (String line : err.split("\\R")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("To ") || s.startsWith("hint:")) {
                continue;
            }
            int bracket = s.indexOf('[');
            if (s.startsWith("!") && bracket >= 0) {
                return s.substring(bracket + 1).replace("]", "").replaceAll("\\s+", " ");
            }
            return firstLine(s, "");
        }
        return "git failed";
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
