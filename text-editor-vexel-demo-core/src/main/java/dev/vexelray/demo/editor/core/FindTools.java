package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Tool;
import dev.vexelray.demo.editor.core.Messages.ToolsFound;
import dev.vexelray.demo.editor.core.Messages.ToolsWanted;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The Java runtimes and Maven installations on this machine ({@link Toolchains}), and the ones chosen for Run, which
 * {@link KeepTools} keeps in the settings. A job: it reads the disk, the environment and the settings.
 *
 * <p>A chosen one is answered as it is on disk now: one whose folder no longer holds the tool is answered as none, the
 * environment's own, rather than as a choice that would fail at the next run. One that is there but was not found by
 * looking (installed somewhere no rule knows) is listed all the same, so the choice can be seen.
 */
@Job
public final class FindTools {

    /** The settings keys the choice is kept under: a folder, or blank for the environment's own. */
    public static final String JAVA = "run.java.home";
    public static final String MAVEN = "run.maven.home";

    private final Settings settings;

    public FindTools(Settings settings) {
        this.settings = settings;
    }

    public ToolsFound run(ToolsWanted wanted) {
        Map<String, String> env = System.getenv();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        List<Path> roots = Toolchains.roots(env);
        List<Tool> javas = new ArrayList<>(Toolchains.javas(env, home, roots));
        List<Tool> mavens = new ArrayList<>(Toolchains.mavens(env, home, roots));
        Optional<Tool> java = chosen(settings.getString(JAVA, ""), javas, Toolchains::java);
        Optional<Tool> maven = chosen(settings.getString(MAVEN, ""), mavens, Toolchains::maven);
        return new ToolsFound(javas, mavens, java, maven);
    }

    /** The tool kept under {@code text}, as {@code read} finds it now, added to {@code found} if looking missed it. */
    static Optional<Tool> chosen(String text, List<Tool> found, Function<Path, Optional<Tool>> read) {
        Optional<Tool> now = ReadSession.path(text).flatMap(read);
        now.ifPresent(t -> {
            Optional<Tool> listed = found.stream().filter(f -> same(f.home(), t.home())).findFirst();
            if (listed.isEmpty()) {
                found.add(t);
            }
        });
        return now.flatMap(t -> found.stream().filter(f -> same(f.home(), t.home())).findFirst());
    }

    private static boolean same(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (java.io.IOException | SecurityException e) {
            return a.equals(b);
        }
    }
}
