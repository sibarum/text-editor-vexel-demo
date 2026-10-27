package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Relist;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import sibarum.atchung.Atchung;

import java.nio.file.Path;

/**
 * The controller for git: what is asked of it and when, on a lane of its own, reached only by messages.
 *
 * <p>It follows the root the {@link Editor} shows ({@link ShowFolder}) and hears what the editor changed on the disk
 * ({@link Relist}, after a save or an Add). Each is a reason to ask git again ({@link ReadStatus}); what comes back is
 * told to the view as a {@link GitHead}.
 *
 * <h2>One question at a time</h2>
 *
 * <p>A status of a large repository is slow, and saves come in bursts. So while a question is out, another reason to
 * ask is only noted, and asked once when the answer lands: twenty saves are at most two runs of git, and the last one
 * saw all of them. A new root is different: what was asked about the old one no longer matters, so it is asked at
 * once, and the {@code generation} on each question tells an answer about the old root from one about the new, which
 * is the one kept.
 */
@Component(lane = "git")
@Publishes({Messages.GIT_STATUS, Messages.GIT_HEAD})
public final class Repository {

    private final Atchung bus;

    /** The root, or null when there is none: a window on files alone, or before the session is back. */
    private Path folder;
    /** Counts the roots: an answer carrying an older one is about a root that has since gone. */
    private long generation;
    /** Whether a question about this root is out. */
    private boolean asking;
    /** Whether something changed while it was out, so it is asked again when the answer lands. */
    private boolean again;

    public Repository(Atchung bus) {
        this.bus = bus;
    }

    /** A new root: whatever was asked about the old one is let go, and this one asked about at once. */
    @Subscribe(topic = Messages.SHOW_FOLDER)
    public void folder(ShowFolder m) {
        folder = m.path();
        generation++;
        asking = false;
        again = false;
        ask();
    }

    /** No root: nothing to say about git. */
    @Subscribe(topic = Messages.PLAIN)
    public void plain(Plain m) {
        folder = null;
        generation++;
        asking = false;
        again = false;
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
        if (folder == null) {
            return;
        }
        if (asking) {
            again = true;
        } else {
            ask();
        }
    }

    @Subscribe(topic = Messages.GIT_STATUS_READ)
    public void read(GitRead r) {
        if (r.generation() != generation) {
            return;
        }
        asking = false;
        bus.publish(Messages.GIT_HEAD_TOPIC, head(r));
        if (again) {
            again = false;
            ask();
        }
    }

    private void ask() {
        asking = true;
        bus.publish(Messages.GIT_STATUS_TOPIC, new GitAsk(generation, folder));
    }

    /** What the status line says of an answer: {@code main ↑2 ↓1 · 3 changed}, the parts there are. */
    static GitHead head(GitRead r) {
        if (!r.repository()) {
            return Messages.NOT_A_REPOSITORY;
        }
        StringBuilder s = new StringBuilder(r.branch());
        if (r.ahead() > 0) {
            s.append(" ↑").append(r.ahead());
        }
        if (r.behind() > 0) {
            s.append(" ↓").append(r.behind());
        }
        if (r.changed() > 0) {
            s.append(" · ").append(r.changed()).append(" changed");
        }
        return new GitHead(true, s.toString(), r.branch(), r.ahead(), r.behind(), r.staged(), r.unstaged());
    }
}
