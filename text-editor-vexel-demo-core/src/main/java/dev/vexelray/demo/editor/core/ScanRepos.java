package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.ReposFound;
import dev.vexelray.demo.editor.core.Messages.Scan;
import dev.vexelray.demo.editor.core.Messages.Scanned;
import dev.vexelray.demo.editor.core.Messages.StopScan;
import dev.vexelray.framework.api.Source;
import sibarum.atchung.Sender;

/**
 * Welcome's look for git repositories: {@link Repositories#walk} on a thread of its own, sending what it finds as it
 * finds it ({@link ReposFound}), and saying how it ended when it does ({@link Scanned}).
 *
 * <p>A source rather than a job because it answers more than once, and because a walk of a large home folder can take
 * seconds, which would hold one of the offload lane's few threads. {@link StopScan} interrupts it, which the walk
 * notices between folders.
 */
@Source(stop = StopScan.class)
public final class ScanRepos {

    private final Sender<ReposFound> found;

    public ScanRepos(Sender<ReposFound> found) {
        this.found = found;
    }

    public Scanned run(Scan scan) {
        Repositories.Walked w = Repositories.walk(scan.root(), Repositories.Limits.WELCOME,
                (repos, folders) -> found.send(new ReposFound(repos, folders)),
                () -> Thread.currentThread().isInterrupted(), System::nanoTime);
        return new Scanned(w.end(), w.folders(), w.found());
    }
}
