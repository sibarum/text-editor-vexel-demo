package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.RunsOffered;
import dev.vexelray.demo.editor.core.Messages.RunsWanted;
import dev.vexelray.demo.editor.core.Messages.Tool;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the Run menu offers for a folder and a file ({@link Runs}): it reads poms, looks for a {@code main} and walks
 * the folder for programs, so it is a job. The answer names what it was asked about, so a view that has moved on since
 * can tell a stale answer from the one it wants.
 *
 * <p>Maven and Java run with the JDK and Maven chosen in Welcome ({@link FindTools#JAVA}, {@link FindTools#MAVEN} in
 * the settings, kept by {@link KeepTools}), read at each request, so a choice counts from the next Run menu. One whose
 * folder no longer holds the tool counts as none: the environment's own, as with nothing chosen.
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
        return new RunsOffered(wanted.folder(), wanted.file(), Runs.withTools(
                Runs.offered(wanted.folder().orElse(null), wanted.file().orElse(null),
                        pontif.isEmpty() ? Runs.PONTIF : pontif),
                chosen(FindTools.JAVA, Toolchains::java), chosen(FindTools.MAVEN, Toolchains::maven)));
    }

    /** The folder kept under {@code key}, while it still holds the tool {@code read} looks for. */
    private Optional<Path> chosen(String key, Function<Path, Optional<Tool>> read) {
        return ReadSession.path(settings.getString(key, "")).flatMap(read).map(Tool::home);
    }
}
