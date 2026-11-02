package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.NewScratch;
import dev.vexelray.demo.editor.core.Messages.ScratchMade;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/**
 * Make a new, empty scratch, on the offload lane: made at once, so Ctrl+S just saves it, and in the platform's line
 * endings, as a new file from the path bar is.
 */
@Job
public final class MakeScratch {

    private static final Log LOG = Log.of("text-editor-vexel-demo.files");
    private static final boolean CRLF = System.lineSeparator().equals("\r\n");

    private final Scratches scratches;

    public MakeScratch(Scratches scratches) {
        this.scratches = scratches;
    }

    public ScratchMade run(NewScratch s) {
        try {
            return new ScratchMade(scratches.create(s.in().orElse(null)), CRLF, "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not make a scratch in {}", scratches.dir(), e);
            return new ScratchMade(scratches.dir(), CRLF, String.valueOf(e.getMessage()));
        }
    }
}
