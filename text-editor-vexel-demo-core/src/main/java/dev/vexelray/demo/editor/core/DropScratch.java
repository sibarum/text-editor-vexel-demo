package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Dropping;
import dev.vexelray.framework.api.Job;

/**
 * A scratch made and closed with nothing written to it goes again: a deletion, so a job. Run only for its effect, so it
 * has no reply.
 */
@Job
public final class DropScratch {

    private final Scratches scratches;

    public DropScratch(Scratches scratches) {
        this.scratches = scratches;
    }

    public void run(Dropping d) {
        scratches.closed(d.path());
    }
}
