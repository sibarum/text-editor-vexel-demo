package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Write;
import dev.vexelray.demo.editor.core.Messages.Written;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;

/** Write a tab's text to its file, with the file's own line endings, on the offload lane. */
@Job(topic = Messages.WRITE, reply = Messages.WRITTEN)
public final class WriteFile {

    private static final Log LOG = Log.of("editor.files");

    public Written run(Write w) {
        try {
            TextFile.write(w.path(), TextFile.encode(w.text(), w.crlf()));
            return new Written(w.id(), w.path(), "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not save {}", w.path(), e);
            return new Written(w.id(), w.path(), String.valueOf(e.getMessage()));
        }
    }
}
