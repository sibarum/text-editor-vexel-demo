package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Dropping;
import dev.vexelray.framework.api.Job;

/** A scratch made and closed with nothing written to it goes again: a deletion, so off the editor's lane. */
@Job(topic = Messages.DROP_SCRATCH)
public final class DropScratch {

    private final Scratches scratches;

    public DropScratch(Scratches scratches) {
        this.scratches = scratches;
    }

    public void run(Dropping d) {
        scratches.closed(d.path());
    }
}
