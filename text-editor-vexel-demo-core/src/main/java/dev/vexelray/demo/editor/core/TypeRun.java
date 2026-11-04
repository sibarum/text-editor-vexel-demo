package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Offer;
import dev.vexelray.demo.editor.core.Messages.RunChecked;
import dev.vexelray.demo.editor.core.Messages.RunTyped;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * What was typed into Run…, checked, remembered and made an {@link Offer}: a job, since it looks at the disk (does the
 * folder exist) and writes the settings, where the next Run… starts from.
 *
 * <p>A problem is an answer, not a throw: an environment line that is not {@code NAME=value}, no command, or a folder
 * that is not there. Then nothing runs, and Run… says why. What is typed is remembered either way, so a slip is
 * corrected rather than typed again.
 */
@Job
public final class TypeRun {

    /** The settings keys Run… is remembered under; read by the window when it is built. */
    public static final String COMMAND = "run.command";
    public static final String DIR = "run.dir";
    public static final String ENVIRONMENT = "run.environment";
    public static final String PATH = "run.path";

    private final Settings settings;

    public TypeRun(Settings settings) {
        this.settings = settings;
    }

    public RunChecked run(RunTyped typed) {
        settings.putString(COMMAND, typed.command())
                .putString(DIR, typed.dir())
                .putString(ENVIRONMENT, typed.environment())
                .putString(PATH, typed.path())
                .save();
        if (typed.command().isBlank()) {
            return refused("Type a command to run");
        }
        Path dir;
        try {
            dir = Path.of(typed.dir().strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return refused("Not a folder: " + typed.dir());
        }
        if (!Files.isDirectory(dir)) {
            return refused("No such folder: " + dir);
        }
        Map<String, String> environment;
        try {
            environment = Runs.environment(typed.environment());
        } catch (IllegalArgumentException e) {
            return refused(e.getMessage());
        }
        return new RunChecked(Optional.of(Runs.typed(typed.command(), dir, environment, Runs.folders(typed.path()))),
                "");
    }

    private static RunChecked refused(String why) {
        return new RunChecked(Optional.empty(), why);
    }
}
