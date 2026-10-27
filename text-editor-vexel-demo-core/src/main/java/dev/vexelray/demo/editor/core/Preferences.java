package dev.vexelray.demo.editor.core;

import dev.vexelray.gui.core.app.Settings;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * What the user prefers: the theme, word wrap, and whether a Maven project is indexed when it is opened.
 *
 * <h2>Two places, one answer</h2>
 *
 * <p>Everything lives in the editor's own settings ({@code ~/.text-editor/settings.properties}) unless the root has a
 * {@code .vex/preferences.properties}: then that root's word wrap and indexing are read from there and written there,
 * and the editor's own values are left for every other folder. A root gets the file when <i>Keep for this project</i>
 * is ticked, which starts it from the values in force, and loses it when it is unticked. The file is ordinary
 * properties, so it can be edited by hand, or committed for a team that wants one setting.
 *
 * <p><b>The theme is the editor's, never a project's.</b> The framework resolves a colour when a widget is built, and
 * has no pass to repaint a built tree (its {@code Theme} says so), so the look is chosen once, at launch, before the
 * window has anything in it. A theme that changed with the root would change only on the next start, under whatever
 * root that happened to be.
 *
 * <p><b>{@code .vex} and git.</b> With <i>Add .vex to .gitignore</i> on (the default), making a {@code .vex} folder in a
 * git working tree adds {@code .vex/} to the root's {@code .gitignore}, creating it if need be, unless a line already
 * says so. A folder that is not in a working tree is left alone.
 *
 * <p>Thread-safe: a root arrives on the offload lane and a menu choice on a worker.
 */
public final class Preferences {

    private static final Log LOG = Log.of("editor.files");

    /**
     * A look VexelRay IDE can start in. Dark is the default; Light is for bright or artificial light, and is
     * VexelRay IDE's alone.
     */
    public enum Shade {
        DARK, LIGHT
    }

    public static final String THEME = "prefs.theme";
    public static final String WRAP = "prefs.wrap";
    public static final String INDEX = "prefs.index";
    public static final String GITIGNORE = "prefs.gitignore";

    /** The folder a project's preferences are kept in, under its root. */
    public static final String DIR = ".vex";
    /** The file in {@link #DIR}. */
    public static final String FILE = "preferences.properties";
    /** The line {@link #ignore} adds. */
    public static final String IGNORE_LINE = DIR + "/";

    private final Settings global;
    /** The root the navigator shows, or null. */
    private Path root;
    /** {@link #root}'s own preferences, or null when it keeps none. */
    private Settings project;

    public Preferences(Settings global) {
        this.global = global;
    }

    /** The look the editor starts in, from its own settings: read once, at launch, before the window is built. */
    public static Shade theme(Settings settings) {
        return Shade.LIGHT.name().equalsIgnoreCase(settings.getString(THEME, "")) ? Shade.LIGHT : Shade.DARK;
    }

    /** The look the next start will use. */
    public Shade theme() {
        return theme(global);
    }

    /** Start in {@code shade} from next time. */
    public void theme(Shade shade) {
        global.putString(THEME, shade.name().toLowerCase()).save();
    }

    /** The navigator now shows {@code folder} (null for nothing): read its own preferences, if it keeps any. */
    public synchronized void root(Path folder) {
        root = folder == null ? null : folder.toAbsolutePath().normalize();
        Path file = root == null ? null : root.resolve(DIR).resolve(FILE);
        project = file != null && Files.isRegularFile(file) ? Settings.at(file) : null;
    }

    /** The root, or null. */
    public synchronized Path root() {
        return root;
    }

    /** Whether the root keeps its own preferences in {@code .vex}. */
    public synchronized boolean projectScoped() {
        return project != null;
    }

    public synchronized boolean wrap() {
        return read(WRAP, true);
    }

    public synchronized void wrap(boolean on) {
        write(WRAP, on);
    }

    /** Whether a Maven project is indexed when it is opened. */
    public synchronized boolean index() {
        return read(INDEX, true);
    }

    public synchronized void index(boolean on) {
        write(INDEX, on);
    }

    /** Whether making a {@code .vex} folder adds it to the root's {@code .gitignore}. The editor's, not a project's. */
    public boolean gitignore() {
        return global.getBoolean(GITIGNORE, true);
    }

    /** Turn {@link #gitignore} on or off; on, and with the root already keeping its own, ignore it now. */
    public void gitignore(boolean on) {
        global.putBoolean(GITIGNORE, on).save();
        Path r;
        synchronized (this) {
            r = project == null ? null : root;
        }
        if (on && r != null) {
            ignore(r);
        }
    }

    /**
     * Keep the root's preferences in its own {@code .vex} folder, starting from the values in force; or stop, taking
     * the file away (and the folder, if nothing else is in it) so the editor's own apply again.
     *
     * @return false when there is no root, or the file could not be written or taken away; the reason is in the log
     */
    public boolean keepForProject(boolean keep) {
        Path r;
        synchronized (this) {
            r = root;
            if (r == null || keep == (project != null)) {
                return r != null;
            }
            Path file = r.resolve(DIR).resolve(FILE);
            if (keep) {
                Settings own = Settings.at(file);
                own.putBoolean(WRAP, read(WRAP, true)).putBoolean(INDEX, read(INDEX, true));
                try {
                    own.save();
                } catch (RuntimeException e) {
                    LOG.warn("could not write {}", file, e);
                    return false;
                }
                project = own;
            } else {
                try {
                    Files.deleteIfExists(file);
                    deleteIfEmpty(file.getParent());
                } catch (IOException e) {
                    LOG.warn("could not take away {}", file, e);
                    return false;
                }
                project = null;
                return true;
            }
        }
        if (gitignore()) {
            ignore(r);
        }
        return true;
    }

    private boolean read(String key, boolean def) {
        boolean mine = global.getBoolean(key, def);
        return project == null ? mine : project.getBoolean(key, mine);
    }

    private void write(String key, boolean on) {
        Settings to = project == null ? global : project;
        try {
            to.putBoolean(key, on).save();
        } catch (RuntimeException e) {
            // A preference that did not save still holds for this run.
            LOG.warn("could not save {} to {}", key, to.path(), e);
        }
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var entries = Files.list(dir)) {
            if (entries.findAny().isEmpty()) {
                Files.delete(dir);
            }
        }
    }

    /**
     * Add {@code .vex/} to {@code root}'s {@code .gitignore} if {@code root} is in a git working tree and no line
     * there already ignores it. Keeps the file's own line endings. Any failure is logged and otherwise ignored: a
     * {@code .vex} folder git can see is a nuisance, not a loss.
     */
    public static void ignore(Path root) {
        if (!inGit(root)) {
            return;
        }
        Path file = root.resolve(".gitignore");
        try {
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            for (String line : text.split("\r?\n")) {
                String t = line.strip();
                if (t.equals(DIR) || t.equals(IGNORE_LINE) || t.equals("/" + DIR) || t.equals("/" + IGNORE_LINE)) {
                    return;
                }
            }
            String nl = text.contains("\r\n") ? "\r\n" : "\n";
            String add = (text.isEmpty() || text.endsWith("\n") ? "" : nl) + IGNORE_LINE + nl;
            Files.writeString(file, add, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not add {} to {}", IGNORE_LINE, file, e);
        }
    }

    /** Whether {@code dir} is in a git working tree: it, or a folder above it, holds {@code .git}. */
    public static boolean inGit(Path dir) {
        for (Path p = dir.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }
}
