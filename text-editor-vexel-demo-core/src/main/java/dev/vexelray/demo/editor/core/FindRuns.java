package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.RunsOffered;
import dev.vexelray.demo.editor.core.Messages.RunsWanted;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

/**
 * What the Run menu offers for a folder and a file ({@link Runs}): it reads poms, looks for a {@code main} and walks
 * the folder for programs, so it is a job. The answer names what it was asked about, so a view that has moved on since
 * can tell a stale answer from the one it wants.
 *
 * <p>Pontif is run by the command line under {@link #PONTIF} in the settings: {@code pontif} on the {@code PATH} when
 * it is not set, or, say, {@code java -jar C:/pontif-framework/pontif-cli/target/pontif-cli.jar} where there is only
 * the jar. There is no preference for it yet (it waits for the preferences window), so it is set by editing
 * {@code settings.properties} while the IDE is closed: the settings are read once, at start-up.
 */
@Job
public final class FindRuns {

    /** The settings key holding the command line that runs Pontif, put in front of {@code run}. */
    public static final String PONTIF = "pontif.command";

    private final Settings settings;

    public FindRuns(Settings settings) {
        this.settings = settings;
    }

    public RunsOffered run(RunsWanted wanted) {
        String pontif = settings.getString(PONTIF, "").strip();
        return new RunsOffered(wanted.folder(), wanted.file(),
                Runs.offered(wanted.folder().orElse(null), wanted.file().orElse(null),
                        pontif.isEmpty() ? Runs.PONTIF : pontif));
    }
}
