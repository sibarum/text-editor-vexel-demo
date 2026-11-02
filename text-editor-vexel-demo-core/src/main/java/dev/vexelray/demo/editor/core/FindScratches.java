package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.ScratchesFound;
import dev.vexelray.demo.editor.core.Messages.ScratchesWanted;
import dev.vexelray.framework.api.Job;

/** Whether there is a scratch to offer, for Open's Scratches button: a walk of a folder, so a job. */
@Job
public final class FindScratches {

    private final Scratches scratches;

    public FindScratches(Scratches scratches) {
        this.scratches = scratches;
    }

    public ScratchesFound run(ScratchesWanted w) {
        return new ScratchesFound(scratches.any());
    }
}
