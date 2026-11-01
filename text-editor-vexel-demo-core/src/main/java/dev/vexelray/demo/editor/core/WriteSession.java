package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Remember;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Path;

/**
 * Keep the session for next time, on the offload lane: the settings store writes a file. Run only for its effect, so
 * it has no reply. The {@link Editor} sends one only when the session differs from the last it sent.
 */
@Job
public final class WriteSession {

    private final Settings settings;

    public WriteSession(Settings settings) {
        this.settings = settings;
    }

    public void run(Remember r) {
        settings.putString(ReadSession.FOLDER, r.folder().map(Path::toString).orElse(""))
                .putList(ReadSession.FILES, r.files().stream().map(Path::toString).toList())
                .putString(ReadSession.FRONT, r.front().map(Path::toString).orElse(""))
                .putList(ReadSession.RECENT, r.recent().stream().map(Path::toString).toList())
                .save();
    }
}
