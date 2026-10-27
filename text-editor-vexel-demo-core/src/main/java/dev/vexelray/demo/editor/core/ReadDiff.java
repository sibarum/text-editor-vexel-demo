package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.vexelray.demo.editor.core.Messages.DiffAsk;
import dev.vexelray.demo.editor.core.Messages.DiffRead;
import dev.vexelray.framework.api.Job;

/**
 * One file's diff, on the offload lane: not staged, staged, or the whole of an untracked file, as git writes it.
 *
 * <p>The answer is the text, which crosses as one string and is read where it is shown. A git that failed is no
 * difference, as {@code Git.diff} has it: the stage says so rather than an error.
 */
@Job(topic = Messages.GIT_DIFF, reply = Messages.GIT_DIFF_READ)
public final class ReadDiff {

    private final Git git = Git.onPath();

    public DiffRead run(DiffAsk a) {
        return new DiffRead(a.ticket(), a.path(), a.side(), git.diff(a.root(), a.path(), a.side()).text());
    }
}
