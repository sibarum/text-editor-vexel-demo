package dev.vexelray.demo.editor;

import dev.vexelray.framework.shell.Apps;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * The other applications of the suite, as far as the editor reaches them: today, showing a file or folder in
 * Vexplore.
 *
 * <p>Vexplore is a separate install, found through its install record ({@link Apps}) and started as a new process
 * and a new window: {@code vexplore <folder>} opens the folder, {@code vexplore <file>} opens the folder it is in
 * with it selected. That argument shape is agreed between the two apps, not by the framework.
 */
final class Suite {

    /** Vexplore's install id, which is also its command. */
    static final String VEXPLORE = "vexplore";

    private static final Log LOG = Log.of("editor.suite");

    private Suite() {
    }

    /** Whether Vexplore is installed, which is what decides the menu item. Reads one small file. */
    static boolean hasVexplore() {
        return Apps.find(VEXPLORE).isPresent();
    }

    /** The menu item's words: what it does, or why it cannot. */
    static String vexploreLabel(boolean installed) {
        return installed ? "Open in Vexplore" : "Vexplore not installed";
    }

    /** Show {@code path} in a new Vexplore window, on {@code io} since it reads the disk and starts a process. */
    static void showInVexplore(Path path, Executor io) {
        io.execute(() -> {
            try {
                if (Apps.spawn(VEXPLORE, path.toAbsolutePath().toString()).isEmpty()) {
                    LOG.warn("Vexplore is not installed; {} was not shown", path);
                }
            } catch (IOException e) {
                LOG.warn("could not start Vexplore for {}", path, e);
            }
        });
    }
}
