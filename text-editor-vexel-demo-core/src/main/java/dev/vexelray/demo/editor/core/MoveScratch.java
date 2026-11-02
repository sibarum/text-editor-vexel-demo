package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Relocate;
import dev.vexelray.demo.editor.core.Messages.Relocated;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/**
 * Move or rename a scratch, or a folder of them, on the offload lane: one rename on the disk, never over anything
 * already there. What it finds in the way is the answer's {@code problem}, so an undo after the files have changed some
 * other way is refused rather than moving something that is no longer what it was.
 */
@Job
public final class MoveScratch {

    private static final Log LOG = Log.of("text-editor-vexel-demo.files");

    private final Scratches scratches;

    public MoveScratch(Scratches scratches) {
        this.scratches = scratches;
    }

    public Relocated run(Relocate r) {
        try {
            scratches.relocate(r.from(), r.to());
            return new Relocated(r.from(), r.to(), r.undoable(), "");
        } catch (IOException | RuntimeException e) {
            LOG.info("did not move {} to {}: {}", r.from(), r.to(), e.getMessage());
            return new Relocated(r.from(), r.to(), r.undoable(), String.valueOf(e.getMessage()));
        }
    }
}
