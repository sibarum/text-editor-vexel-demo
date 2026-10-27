package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import dev.vexelray.demo.editor.core.Messages.GitRefresh;
import dev.vexelray.demo.editor.core.Messages.GitStamp;
import dev.vexelray.demo.editor.core.Messages.GitStampAsk;
import dev.vexelray.demo.editor.core.Messages.GitTick;
import dev.vexelray.demo.editor.core.Messages.GitTree;
import dev.vexelray.demo.editor.core.Messages.GitWatch;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Relist;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Overflow;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import sibarum.atchung.Atchung;

import java.nio.file.Path;
import java.time.Duration;

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
 * <h2>Watching, while Git mode is up</h2>
 *
 * <p>Git is also used from a prompt, and a commit made there should show without a click. While Git mode is up
 * ({@link GitWatch}) the view ticks every {@link #WATCH}, and each tick has {@link ReadStamp} look at the files git
 * rewrites when it does anything. Only when they changed is git asked: a quiet repository costs three {@code stat}s a
 * tick, not a run of git.
 */
@Component(lane = "git")
@Publishes({Messages.GIT_STATUS, Messages.GIT_STAMP, Messages.GIT_HEAD, Messages.GIT_TREE})
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
        refresh();
    }

    /** Git mode came up, or went. Coming up, it is asked about at once: what it last showed may be old. */
    @Subscribe(topic = Messages.GIT_WATCH)
    public void watch(GitWatch m) {
        watching = m.on();
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
        bus.publish(Messages.GIT_HEAD_TOPIC, head(r));
        if (r.repository()) {
            bus.publish(Messages.GIT_TREE_TOPIC,
                    new GitTree(r.root(), r.status(), r.unstagedLines(), r.stagedLines()));
        }
        if (again) {
            again = false;
            ask();
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
