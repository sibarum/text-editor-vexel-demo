package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.ScratchFolderMade;
import dev.vexelray.demo.editor.core.Messages.ScratchFolderWanted;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/** Make a new folder among the scratches, on the offload lane, for the view to list and name. */
@Job
public final class MakeScratchFolder {

    private static final Log LOG = Log.of("text-editor-vexel-demo.files");

    private final Scratches scratches;

    public MakeScratchFolder(Scratches scratches) {
        this.scratches = scratches;
    }

    public ScratchFolderMade run(ScratchFolderWanted w) {
        try {
            return new ScratchFolderMade(scratches.folder(w.in().orElse(null)), "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not make a folder in {}", scratches.dir(), e);
            return new ScratchFolderMade(scratches.dir(), String.valueOf(e.getMessage()));
        }
    }
}
