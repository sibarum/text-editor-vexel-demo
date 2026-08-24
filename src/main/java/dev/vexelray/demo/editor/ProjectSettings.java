package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.app.Settings;

import java.nio.file.Path;

/**
 * The settings that belong to a <b>project</b> rather than to the user: a {@code .vtext} file in the project's own
 * directory, holding the decisions that are about this tree of files and would be wrong anywhere else.
 *
 * <h2>What a project is</h2>
 * The folder the file tree is showing. That is the one thing in this application that already means "the thing I
 * am working on": it is chosen deliberately (Ctrl+Shift+O), it survives a restart, and it does not move when the
 * terminal cds somewhere to look at something. A project is not the shell's working directory, because a shell's
 * working directory is a place you visit.
 *
 * <p>With no folder open there is no project, and {@link #none()} stands in: every read answers with the caller's
 * default and every write is dropped. That is deliberate too — an application with no project open must not
 * quietly write a {@code .vtext} into whatever directory it happened to be launched from.
 *
 * <h2>Why the same boring format</h2>
 * {@link Settings#at} over Java properties, which is what {@code ~/.text-editor/settings.properties} already is.
 * Line-diffable, hand-editable, and forgiving of keys it does not know — so a newer build can put something in
 * here that an older one ignores instead of failing, and a person can fix a bad value in an editor. It is also
 * atomic on write, so a crash leaves the previous file rather than half of this one.
 *
 * <p><b>It is meant to be committed.</b> So nothing machine-specific goes in it: this file records that the
 * project prefers the profile <em>named</em> {@code rust-nightly}, and never what that profile contains. The
 * contents are the user's, in the user's own settings, because a directory of toolchains on one machine is not a
 * directory of toolchains on another. A checkout that names a profile nobody has is told so, once, and carries on.
 */
public final class ProjectSettings {

    /** The file, in the project's own directory. Dotted, so it sorts out of the way of the project's own files. */
    public static final String FILE = ".vtext";

    /** The profile this project prefers, by name. */
    private static final String PROFILE = "profile";

    private final Path root;
    private final Settings store;

    private ProjectSettings(Path root, Settings store) {
        this.root = root;
        this.store = store;
    }

    /** The settings for the project rooted at {@code folder}, reading {@code folder/.vtext} if it is there. */
    public static ProjectSettings of(Path folder) {
        Path root = folder.toAbsolutePath().normalize();
        return new ProjectSettings(root, Settings.at(root.resolve(FILE)));
    }

    /**
     * No project open: reads answer with the default, writes go nowhere.
     *
     * <p>A null object rather than a null, because every caller would otherwise have to ask first, and the one
     * that forgot would write a {@code .vtext} somewhere nobody asked for one.
     */
    public static ProjectSettings none() {
        return new ProjectSettings(null, null);
    }

    /** Whether there is a project here at all. */
    public boolean present() {
        return root != null;
    }

    /** The project's directory, or {@code null} when there is no project. */
    public Path root() {
        return root;
    }

    /** The project's name, as it is worth showing: the directory's own name. */
    public String name() {
        if (root == null) {
            return "";
        }
        Path leaf = root.getFileName();
        return leaf == null ? root.toString() : leaf.toString();
    }

    /** Where the file is, or would be. Useful in a message about what was just written. */
    public Path file() {
        return root == null ? null : root.resolve(FILE);
    }

    /** Whether the file exists yet. It is only written when something is actually set. */
    public boolean written() {
        return root != null && java.nio.file.Files.isRegularFile(file());
    }

    /** The profile this project prefers, or {@code ""} if it does not name one. */
    public String profile() {
        return store == null ? "" : store.getString(PROFILE, "");
    }

    /**
     * Name the profile this project prefers, and write the file. An empty name clears the preference and leaves
     * the file behind — a {@code .vtext} that exists with nothing in it says "this project has been configured
     * and wants the default", which is a different statement from a file that was never written.
     */
    public void profile(String name) {
        if (store == null) {
            return;
        }
        if (name == null || name.isBlank()) {
            store.remove(PROFILE);
        } else {
            store.putString(PROFILE, name.trim());
        }
        store.save();
    }
}
