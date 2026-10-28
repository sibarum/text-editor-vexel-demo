package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Diff;
import dev.vexelray.demo.editor.core.Messages.CommitStaged;
import dev.vexelray.demo.editor.core.Messages.DiffAsk;
import dev.vexelray.demo.editor.core.Messages.GitCommitted;
import dev.vexelray.demo.editor.core.Messages.DiffRead;
import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitDiff;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitHistory;
import dev.vexelray.demo.editor.core.Messages.HistoryAsk;
import dev.vexelray.demo.editor.core.Messages.HistoryRead;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import dev.vexelray.demo.editor.core.Messages.GitRefresh;
import dev.vexelray.demo.editor.core.Messages.GitStamp;
import dev.vexelray.demo.editor.core.Messages.GitStampAsk;
import dev.vexelray.demo.editor.core.Messages.GitTick;
import dev.vexelray.demo.editor.core.Messages.GitTree;
import dev.vexelray.demo.editor.core.Messages.GitWatch;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.GitOp;
import dev.vexelray.demo.editor.core.Messages.GitRan;
import dev.vexelray.demo.editor.core.Messages.GitRun;
import dev.vexelray.demo.editor.core.Messages.Relist;
import dev.vexelray.demo.editor.core.Messages.ShowDiff;
import dev.vexelray.demo.editor.core.Messages.Stage;
import dev.vexelray.demo.editor.core.Messages.Status;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Overflow;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import sibarum.atchung.Atchung;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The controller for git: what is asked of it and when, on a lane of its own, reached only by messages.
 *
 * <p>It follows the root the {@link Editor} shows ({@link ShowFolder}) and hears what the editor changed on the disk
 * ({@link Relist}, after a save or an Add). Each is a reason to ask git again ({@link ReadStatus}); what comes back is
 * told to the view twice over: where the root stands, for the status line and the way into Git mode
 * ({@link GitHead}), and every changed path, for Git mode's lists ({@link GitTree}).
 *
 * <h2>One question at a time</h2>
 *
 * <p>A status of a large repository is slow, and saves come in bursts. So while a question is out, another reason to
 * ask is only noted, and asked once when the answer lands: twenty saves are at most two runs of git, and the last one
 * saw all of them. A new root is different: what was asked about the old one no longer matters, so it is asked at
 * once, and the {@code generation} on each question tells an answer about the old root from one about the new, which
 * is the one kept.
 *
 * <h2>Diffs</h2>
 *
 * <p>A row picked in Git mode is a diff asked for ({@link ShowDiff}); {@link ReadDiff} reads it and the view is sent it
 * ({@link GitDiff}). The diff on show is asked for again with every new status, since whatever changed the status may
 * have changed it, and only the answer to the latest ask is sent on: rows picked fast show the last one.
 *
 * <h2>History and branches</h2>
 *
 * <p>While Git mode is up, the history and the branches ({@link ReadHistory}, sent on as {@link GitHistory}) are asked
 * for with a status whose stamp differs from the one they were last asked at: a commit, a stage, a switch of branch.
 * A save changes none of that, and asks nothing. Coming up, and Refresh, ask whatever the stamp says, since a fetch
 * moves remote branches where the stamp does not look.
 *
 * <h2>Writes</h2>
 *
 * <p>Staging and unstaging ({@link Stage}) are run by {@link RunGit}, one at a time from a queue, since two runs of
 * {@code git add} at once meet at {@code index.lock} and the second fails. Each is followed by a fresh status; one that
 * failed is said on the status line in git's own words. A commit ({@link CommitStaged}) joins the same queue, after
 * the stages asked for before it, one at a time; whether it can go at all is decided here ({@link #committable}), and
 * the view is told how it went ({@link GitCommitted}) so the message box is emptied or given back.
 *
 * <h2>Watching, while Git mode is up</h2>
 *
 * <p>Git is also used from a prompt, and a commit made there should show without a click. While Git mode is up
 * ({@link GitWatch}) the view ticks every {@link #WATCH}, and each tick has {@link ReadStamp} look at the files git
 * rewrites when it does anything. Only when they changed is git asked: a quiet repository costs three {@code stat}s a
 * tick, not a run of git.
 */
@Component(lane = "git")
@Publishes({Messages.GIT_STATUS, Messages.GIT_STAMP, Messages.GIT_DIFF, Messages.GIT_RUN, Messages.GIT_HEAD,
        Messages.GIT_TREE, Messages.GIT_DIFF_SHOWN, Messages.GIT_COMMITTED, Messages.GIT_HISTORY,
        Messages.GIT_HISTORY_SHOWN, Messages.STATUS})
public final class Repository {

    /** How often git is looked at while Git mode is up. */
    public static final Duration WATCH = Duration.ofSeconds(2);

    private final Atchung bus;

    /** The root, or null when there is none: a window on files alone, or before the session is back. */
    private Path folder;
    /** Counts the roots: an answer carrying an older one is about a root that has since gone. */
    private long generation;
    /** Whether a question about this root is out. */
    private boolean asking;
    /** Whether something changed while it was out, so it is asked again when the answer lands. */
    private boolean again;
    /** Whether Git mode is up, and so git watched. */
    private boolean watching;
    /** Whether a look at the stamp is out. */
    private boolean looking;
    /** The root's repository's own directory, from the last answer; null outside one or before the first. */
    private Path gitDir;
    /** What the stamp was when git was last asked. */
    private String stamp = "";
    /** The repository's top, from the last answer; null outside one or before the first. */
    private Path root;
    /** The diff the stage shows, path and side; null path while it shows none. */
    private String diffPath;
    private Diff.Side diffSide;
    /** Counts the diffs asked for: only the answer to the latest is shown, however fast rows are picked. */
    private long diffTicket;
    /** Counts the histories asked for: only the answer to the latest is shown. */
    private long historyTicket;
    /**
     * The stamp the history on show was asked at; null when it must be asked whatever the stamp says: Git mode just
     * came up, or Refresh, which is how a fetch at a prompt (which the stamp does not see) shows.
     */
    private String historyStamp;
    /** Writes waiting their turn: one is out at a time, so two never meet at git's {@code index.lock}. */
    private final Deque<GitRun> writes = new ArrayDeque<>();
    /** Whether a write is out. */
    private boolean writing;
    /** How many paths are staged, from the last answer: nothing staged is nothing to commit. */
    private int staged;
    /** The subject of the commit queued or out; null while none is. One at a time: a second would find nothing. */
    private String committing;

    public Repository(Atchung bus) {
        this.bus = bus;
    }

    /** A new root: whatever was asked about the old one is let go, and this one asked about at once. */
    @Subscribe(topic = Messages.SHOW_FOLDER)
    public void folder(ShowFolder m) {
        forget();
        folder = m.path();
        ask();
    }

    /** No root: nothing to say about git. */
    @Subscribe(topic = Messages.PLAIN)
    public void plain(Plain m) {
        forget();
        folder = null;
        bus.publish(Messages.GIT_HEAD_TOPIC, Messages.NOT_A_REPOSITORY);
    }

    /**
     * The editor wrote or made something. Only that it happened matters, not what or how often, but the view hears
     * the same topic as an edge, and one topic has one loss class (T4.1): so nothing is dropped here, the mailbox
     * holds a save of every tab at once ({@link Editor#SET}), and a burst is folded into one question by
     * {@link #again} instead.
     */
    @Subscribe(topic = Messages.RELIST, capacity = Editor.SET)
    public void changed(Relist m) {
        refresh();
    }

    /** Git mode's Refresh. */
    @Subscribe(topic = Messages.GIT_REFRESH)
    public void refresh(GitRefresh m) {
        historyStamp = null;
        refresh();
    }

    /** Git mode came up, or went. Coming up, it is asked about at once: what it last showed may be old. */
    @Subscribe(topic = Messages.GIT_WATCH)
    public void watch(GitWatch m) {
        watching = m.on();
        historyStamp = null;
        if (watching) {
            refresh();
        }
    }

    /** Time to look whether git did anything. A tick that finds a question or a look already out has nothing to do. */
    @Subscribe(topic = Messages.GIT_TICK, overflow = Overflow.COALESCE_LATEST)
    public void tick(GitTick m) {
        if (!watching || gitDir == null || asking || looking) {
            return;
        }
        looking = true;
        bus.publish(Messages.GIT_STAMP_TOPIC, new GitStampAsk(generation, gitDir));
    }

    @Subscribe(topic = Messages.GIT_STAMPED)
    public void stamped(GitStamp s) {
        if (s.generation() != generation) {
            return;
        }
        looking = false;
        if (!s.stamp().equals(stamp)) {
            refresh();
        }
    }

    @Subscribe(topic = Messages.GIT_STATUS_READ)
    public void read(GitRead r) {
        if (r.generation() != generation) {
            return;
        }
        asking = false;
        gitDir = r.gitDir();
        stamp = r.stamp();
        root = r.root();
        staged = r.staged();
        bus.publish(Messages.GIT_HEAD_TOPIC, head(r));
        if (r.repository()) {
            bus.publish(Messages.GIT_TREE_TOPIC,
                    new GitTree(r.root(), r.status(), r.unstagedLines(), r.stagedLines()));
            // What changed the status may have changed the diff on show.
            askDiff();
            // The history changes only when git did something, which the stamp says; a save is not that.
            if (watching && !r.stamp().equals(historyStamp)) {
                historyStamp = r.stamp();
                historyTicket++;
                bus.publish(Messages.GIT_HISTORY_TOPIC, new HistoryAsk(historyTicket, r.root()));
            }
        }
        if (again) {
            again = false;
            ask();
        }
    }

    /** A row was picked, and its diff is to stand where the editor does; or, with no path, the diff was put away. */
    @Subscribe(topic = Messages.SHOW_DIFF)
    public void showDiff(ShowDiff m) {
        diffPath = m.path();
        diffSide = m.side();
        diffTicket++;
        askDiff();
    }

    @Subscribe(topic = Messages.GIT_HISTORY_READ)
    public void historyRead(HistoryRead h) {
        if (h.ticket() != historyTicket || root == null) {
            return;
        }
        bus.publish(Messages.GIT_HISTORY_SHOWN_TOPIC, new GitHistory(root, h.log(), h.branches()));
    }

    @Subscribe(topic = Messages.GIT_DIFF_READ)
    public void diffRead(DiffRead d) {
        if (d.ticket() != diffTicket || root == null) {
            return;
        }
        bus.publish(Messages.GIT_DIFF_SHOWN_TOPIC, new GitDiff(root, d.path(), d.side(), d.text()));
    }

    /**
     * Stage or unstage a path, or everything under the root: queued, and run when the write before it is done. The
     * view has saved what it covers already.
     */
    @Subscribe(topic = Messages.STAGE)
    public void stage(Stage m) {
        if (root == null || folder == null) {
            return;
        }
        GitOp op = m.on() ? GitOp.STAGE : GitOp.UNSTAGE;
        writes.add(m.path() == null ? new GitRun(generation, folder, op, ".")
                : new GitRun(generation, root, op, m.path()));
        write();
    }

    /**
     * Commit what is staged: queued behind any stage still to run, so it commits what the person staged before they
     * pressed it. One that cannot go is answered at once, with why on the status line.
     */
    @Subscribe(topic = Messages.COMMIT)
    public void commit(CommitStaged m) {
        String refused = root == null ? "Not in a repository"
                : committing != null ? "A commit is already being made"
                : staged == 0 ? "Nothing is staged to commit"
                : !committable(staged, m.message()) ? "A commit needs a message" : null;
        if (refused != null) {
            bus.publish(Messages.STATUS_TOPIC, new Status(refused, true));
            bus.publish(Messages.GIT_COMMITTED_TOPIC, new GitCommitted(false, ""));
            return;
        }
        committing = subject(m.message());
        writes.add(new GitRun(generation, root, GitOp.COMMIT, null, m.message()));
        write();
    }

    /** A write is done. Whatever it did, git is asked again; what went wrong is said on the status line. */
    @Subscribe(topic = Messages.GIT_RAN)
    public void ran(GitRan r) {
        if (r.generation() != generation) {
            return;
        }
        writing = false;
        if (r.op() == GitOp.COMMIT) {
            String subject = committing;
            committing = null;
            bus.publish(Messages.STATUS_TOPIC, r.ok() ? new Status("Committed: " + subject, false)
                    : new Status("Could not commit: " + r.error(), true));
            bus.publish(Messages.GIT_COMMITTED_TOPIC, new GitCommitted(r.ok(), subject));
        } else if (!r.ok()) {
            String what = ".".equals(r.path()) ? "everything" : r.path();
            String verb = r.op() == GitOp.STAGE ? "stage " : "unstage ";
            bus.publish(Messages.STATUS_TOPIC, new Status("Could not " + verb + what + ": " + r.error(), true));
        }
        refresh();
        write();
    }

    private void write() {
        if (writing || writes.isEmpty()) {
            return;
        }
        writing = true;
        bus.publish(Messages.GIT_RUN_TOPIC, writes.poll());
    }

    /** Ask for the diff on show, if there is one and a repository to ask. */
    private void askDiff() {
        if (diffPath != null && root != null) {
            diffTicket++;
            bus.publish(Messages.GIT_DIFF_TOPIC, new DiffAsk(diffTicket, root, diffPath, diffSide));
        }
    }

    /** Let go of the root: answers and looks about it that are still out are dropped when they land. */
    private void forget() {
        generation++;
        asking = false;
        again = false;
        looking = false;
        gitDir = null;
        stamp = "";
        root = null;
        diffPath = null;
        diffTicket++;
        historyTicket++;
        historyStamp = null;
        // Writes for the old root are not made in the new one; one already out finishes there, and is not heard.
        writes.clear();
        writing = false;
        staged = 0;
        if (committing != null) {
            // The message box is waiting on it: it was not made here, and the box is the person's again.
            committing = null;
            bus.publish(Messages.GIT_COMMITTED_TOPIC, new GitCommitted(false, ""));
        }
    }

    /** Whether a commit can go: something is staged, and the message has a line that says something. */
    public static boolean committable(int staged, String message) {
        return staged > 0 && !subject(message).isEmpty();
    }

    /** A message's subject, as git takes it: its first line that is not blank, without the spaces around it. */
    public static String subject(String message) {
        if (message != null) {
            for (String line : message.split("\\R")) {
                if (!line.isBlank()) {
                    return line.strip();
                }
            }
        }
        return "";
    }

    /** Ask git again: now, or when the question that is out comes back. */
    private void refresh() {
        if (folder == null) {
            return;
        }
        if (asking) {
            again = true;
        } else {
            ask();
        }
    }

    private void ask() {
        asking = true;
        bus.publish(Messages.GIT_STATUS_TOPIC, new GitAsk(generation, folder));
    }

    /** What the status line and the way into Git mode say of an answer: {@code main ↑2 ↓1 · 3 changed}. */
    static GitHead head(GitRead r) {
        if (!r.repository()) {
            return Messages.NOT_A_REPOSITORY;
        }
        StringBuilder chip = new StringBuilder(r.branch());
        if (r.ahead() > 0) {
            chip.append(" ↑").append(r.ahead());
        }
        if (r.behind() > 0) {
            chip.append(" ↓").append(r.behind());
        }
        String label = r.changed() > 0 ? chip + " · " + r.changed() + " changed" : chip.toString();
        return new GitHead(true, label, chip.toString(), r.branch(), r.upstream(), r.ahead(), r.behind(), r.staged(),
                r.unstaged());
    }
}
