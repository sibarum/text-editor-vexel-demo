package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages.App;
import dev.vexelray.demo.editor.core.Messages.HandTo;
import dev.vexelray.demo.editor.core.Messages.HandedTo;
import dev.vexelray.framework.api.Job;
import dev.vexelray.framework.shell.Apps;
import dev.vexelray.os.NativePlatform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Hand a row's file or folder to another program, from its Open in: one of the suite's, where the installer put it
 * ({@link Apps#spawn}), or the system's file manager. Not waited for: each program is a process of its own, which
 * outlives this one. Copied from Vexplore's {@code HandOff}, which hands things the other way.
 *
 * <ul>
 *   <li><b>Vexplore</b>, {@code vexplore <path>}: that folder, or the folder a file is in.</li>
 *   <li><b>MainFrame</b>, {@code mainframe --dir=<folder>}, and for a file {@code --dir=<its folder>
 *   --insert-path=<file>}, which types its name on the input line. MainFrame knows the tab's shell and decides
 *   whether the name can be typed safely, so only paths are passed, never console text.</li>
 *   <li><b>Rasterware</b>, {@code pix <path>}: a picture or a video among its folder's, or a folder of them. It is
 *   installed as {@code pix}, a command name the suite renames together later.</li>
 *   <li><b>The file manager</b>: the folder, or the folder a file is in, opened by the desktop's own opener. The
 *   platform cannot yet show a file selected in it (FN-17), so a file's row shows its folder.</li>
 * </ul>
 *
 * <p>Each path goes as one argument, absolute, with no shell between, so it is never read as an option or a command.
 * In {@code -gui} rather than {@code -core}, because {@link Apps} is the framework shell's and {@code -core} builds
 * against none of it.
 */
@Job
public final class HandOff {

    /** Each suite program's id in the installer's records, which is also its command. */
    static final Map<App, String> IDS = Map.of(App.VEXPLORE, "vexplore", App.MAINFRAME, "mainframe",
            App.RASTERWARE, "pix");

    public HandedTo run(HandTo h) {
        return handOff(h.path(), h.folder(), h.app(), Apps.registryDir(), f -> NativePlatform.current().open(f));
    }

    /** As {@link #run}, against {@code registry} and with {@code open} for the desktop's opener. */
    static HandedTo handOff(Path path, boolean folder, App app, Optional<Path> registry, Predicate<String> open) {
        Path abs = path.toAbsolutePath().normalize();
        if (app == App.HOST) {
            Path dir = folder || abs.getParent() == null ? abs : abs.getParent();
            try {
                return open.test(dir.toString()) ? new HandedTo(path, app, null)
                        : new HandedTo(path, app, "this desktop has no file manager to open it in");
            } catch (RuntimeException e) {
                return new HandedTo(path, app, why(e));
            }
        }
        try {
            if (registry.isPresent() && Apps.spawn(registry.get(), IDS.get(app), args(app, abs, folder)).isPresent()) {
                return new HandedTo(path, app, null);
            }
            return new HandedTo(path, app, app.title() + " isn't installed");
        } catch (IOException | RuntimeException e) {
            return new HandedTo(path, app, app.title() + " would not start: " + why(e));
        }
    }

    /** The command line after the program's name: the path, or MainFrame's folder and the file to type. */
    static String[] args(App app, Path abs, boolean folder) {
        if (app != App.MAINFRAME) {
            return new String[]{abs.toString()};
        }
        Path dir = folder ? abs : abs.getParent();
        if (dir == null || dir.equals(abs)) {
            return new String[]{"--dir=" + abs};
        }
        return new String[]{"--dir=" + dir, "--insert-path=" + abs};
    }

    private static String why(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
