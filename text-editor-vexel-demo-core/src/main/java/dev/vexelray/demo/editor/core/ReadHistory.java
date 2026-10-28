package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.sibarum.suite.git.Log;
import dev.sibarum.suite.git.Refs;
import dev.vexelray.demo.editor.core.Messages.HistoryAsk;
import dev.vexelray.demo.editor.core.Messages.HistoryRead;
import dev.vexelray.framework.api.Job;

/**
 * The history and the branches, on the offload lane: the newest {@link #COMMITS} commits from {@code HEAD}, and every
 * local and remote-tracking branch with how far it stands from what it tracks. Two quick runs of git.
 *
 * <p>Lists cannot cross (FN-24), so each answer is text, written by {@code Log.text} and {@code Refs.text} and read
 * where it is shown. A git that failed, or a repository with no commit yet, is nothing to list.
 */
@Job(topic = Messages.GIT_HISTORY, reply = Messages.GIT_HISTORY_READ)
public final class ReadHistory {

    /** How many commits the stage lists: a screenful or two, not the whole history. */
    public static final int COMMITS = 200;

    private final Git git = Git.onPath();

    public HistoryRead run(HistoryAsk a) {
        return new HistoryRead(a.ticket(), Log.text(git.log(a.root(), null, COMMITS)),
                Refs.text(git.branches(a.root())));
    }
}
