package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.ScratchFor;
import dev.vexelray.demo.editor.core.Messages.ScratchNamed;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/** A free name among the scratches for an Untitled tab kept as one, the scratches' folder made if it must be. */
@Job(topic = Messages.NAME_SCRATCH, reply = Messages.SCRATCH_NAMED)
public final class NameScratch {

    private static final Log LOG = Log.of("editor.files");

    private final Scratches scratches;

    public NameScratch(Scratches scratches) {
        this.scratches = scratches;
    }

    public ScratchNamed run(ScratchFor s) {
        try {
            return new ScratchNamed(s.id(), scratches.name(), "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not make the scratches folder {}", scratches.dir(), e);
            return new ScratchNamed(s.id(), null, String.valueOf(e.getMessage()));
        }
    }
}
