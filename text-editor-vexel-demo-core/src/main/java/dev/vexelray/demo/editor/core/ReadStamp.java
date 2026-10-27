package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.GitStamp;
import dev.vexelray.demo.editor.core.Messages.GitStampAsk;
import dev.vexelray.framework.api.Job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether git has done anything, without running it: when the files it rewrites were last written, on the offload
 * lane.
 *
 * <p>{@code index} is rewritten by staging, unstaging, a commit and a checkout; {@code HEAD} by switching branch;
 * {@code logs/HEAD} by every move of {@code HEAD}, a commit included, which leaves {@code HEAD} itself alone when it is
 * a branch. Three {@code stat}s every two seconds is what Git mode costs while nothing happens, against a run of git.
 *
 * <p>What it does not see: a file changed by something other than git, which changes the status without touching
 * these, and a push, which moves a remote branch and so the distance. The editor's own saves are told to the
 * {@link Repository} anyway, and Git mode's Refresh asks git outright.
 */
@Job(topic = Messages.GIT_STAMP, reply = Messages.GIT_STAMPED)
public final class ReadStamp {

    public GitStamp run(GitStampAsk a) {
        return new GitStamp(a.generation(), of(a.gitDir()));
    }

    /** The three files' times, as one value to compare: equal while git has done nothing. Empty for no repository. */
    static String of(Path gitDir) {
        if (gitDir == null) {
            return "";
        }
        return time(gitDir.resolve("index")) + "/" + time(gitDir.resolve("HEAD")) + "/"
                + time(gitDir.resolve("logs").resolve("HEAD"));
    }

    /** When it was last written, in nanoseconds where the file system keeps them; -1 if it is not there. */
    private static long time(Path file) {
        try {
            var t = Files.getLastModifiedTime(file);
            return t.toInstant().getEpochSecond() * 1_000_000_000L + t.toInstant().getNano();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
