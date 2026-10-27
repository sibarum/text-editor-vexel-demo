package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.NewScratch;
import dev.vexelray.demo.editor.core.Messages.ScratchMade;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/** Make a new, empty scratch, on the offload lane: made at once, so Ctrl+S just saves it. */
@Job(topic = Messages.MAKE_SCRATCH, reply = Messages.SCRATCH_MADE)
public final class MakeScratch {

    private static final Log LOG = Log.of("editor.files");

    private final Scratches scratches;

    public MakeScratch(Scratches scratches) {
        this.scratches = scratches;
    }

    public ScratchMade run(NewScratch s) {
        try {
            return new ScratchMade(scratches.create(s.folder()), "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not make a scratch in {}", scratches.dir(), e);
            return new ScratchMade(null, String.valueOf(e.getMessage()));
        }
    }
}
