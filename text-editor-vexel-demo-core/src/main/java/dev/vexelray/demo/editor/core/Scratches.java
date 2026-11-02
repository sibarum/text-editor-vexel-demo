package dev.vexelray.demo.editor.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scratches: files that belong to no project. A note, a pasted log, a snippet being worked out: nothing to delete, and
 * nowhere in particular to save it. They live in one folder of the editor's own, beside its settings
 * ({@code ~/.text-editor-vexel-demo/scratches}), which Open offers as a folder of its own once there is at least one.
 *
 * <p>A scratch is an ordinary file once it exists: it saves with Ctrl+S and highlights by its extension. Nothing here
 * reads or writes its text. They and their folders can be moved and renamed among themselves ({@link #into},
 * {@link #named}, {@link #relocate}).
 *
 * <p>Every method that touches the disk is for the jobs ({@link MakeScratch}, {@link DropScratch},
 * {@link FindScratches}, {@link MoveScratch}, {@link MakeScratchFolder}); the view uses only the path arithmetic. A value, the folder and nothing else, so one can be
 * handed to every part that needs it. Which scratches were made empty in this run and not written since is the
 * {@link Editor}'s to know, since it sees every save and every close; it asks for one to go with {@link #closed}.
 *
 * @param dir the folder. It may not exist yet: it appears with the first scratch.
 */
public record Scratches(Path dir) {

    /** A new scratch's name: the minute it was made, so the folder sorts by age. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    /** What a new scratch is. Markdown, since a scratch is mostly a note, and it then has the formatted view. */
    public static final String EXTENSION = ".md";

    /** The name Open and the chip give the folder, in place of its own. */
    public static final String NAME = "Scratches";

    /** How far {@link #any} looks before calling the folder empty: a folder of only folders is not worth a walk. */
    private static final int LOOK = 2_000;

    public Scratches {
        dir = dir.toAbsolutePath().normalize();
    }

    /** Whether {@code path} is the folder itself or anything in it. Path arithmetic only. */
    public boolean holds(Path path) {
        return path != null && path.toAbsolutePath().normalize().startsWith(dir);
    }

    /** Whether {@code path} is the folder itself. */
    public boolean is(Path path) {
        return path != null && path.toAbsolutePath().normalize().equals(dir);
    }

    /** Whether there is at least one scratch, anywhere under the folder. */
    public boolean any() {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        Deque<Path> folders = new ArrayDeque<>();
        folders.add(dir);
        int seen = 0;
        while (!folders.isEmpty() && seen < LOOK) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(folders.poll())) {
                for (Path p : s) {
                    if (Files.isRegularFile(p)) {
                        return true;
                    }
                    if (Files.isDirectory(p)) {
                        folders.add(p);
                    }
                    seen++;
                }
            } catch (IOException | RuntimeException e) {
                // unreadable: counts as nothing there, as the navigator lists it
            }
        }
        return false;
    }

    /** The folder, made if it is not there yet. */
    public Path ensure() throws IOException {
        return Files.createDirectories(dir);
    }

    /**
     * Make a new, empty scratch in {@code folder} (the scratches folder itself for null, or for anywhere outside it),
     * named for this minute, and return it. Two in one minute are {@code -2}, {@code -3} and so on.
     */
    public Path create(Path folder) throws IOException {
        Path in = folder != null && holds(folder) && Files.isDirectory(folder) ? folder : ensure();
        String stem = STAMP.format(LocalDateTime.now());
        for (int n = 1; ; n++) {
            try {
                return Files.createFile(in.resolve(n == 1 ? stem + EXTENSION : stem + "-" + n + EXTENSION));
            } catch (FileAlreadyExistsException taken) {
                // the next number
            }
        }
    }

    /** A new folder in {@code parent} (the scratches folder for null), called {@code Folder}, {@code Folder 2}... */
    public Path folder(Path parent) throws IOException {
        Path in = parent != null && holds(parent) && Files.isDirectory(parent) ? parent : ensure();
        for (int n = 1; ; n++) {
            try {
                return Files.createDirectory(in.resolve(n == 1 ? "Folder" : "Folder " + n));
            } catch (FileAlreadyExistsException taken) {
                // the next number
            }
        }
    }

    /**
     * Where {@code moved}, a scratch or a folder of them, would be once put in {@code folder}, under the same name;
     * null when that is no move to make: either outside the scratches, the folder it is in already, or a folder put
     * into itself or anything under it. Path arithmetic only, so the view can work it out as a drop lands; whether the
     * name is free there is {@link #relocate}'s to find.
     */
    public Path into(Path moved, Path folder) {
        if (moved == null || folder == null || !holds(moved) || is(moved) || !holds(folder)) {
            return null;
        }
        Path from = moved.toAbsolutePath().normalize();
        Path in = folder.toAbsolutePath().normalize();
        if (in.equals(from.getParent()) || in.startsWith(from)) {
            return null;
        }
        return in.resolve(from.getFileName());
    }

    /**
     * Where {@code file}, a scratch or a folder of them, would be once called {@code name}, in the folder it is in;
     * null for a name that is blank, is no change, or names another folder ({@code ..}, a separator). Path arithmetic
     * only: whether the name is free is {@link #relocate}'s to find.
     */
    public Path named(Path file, String name) {
        if (file == null || !holds(file) || is(file) || name == null) {
            return null;
        }
        String n = name.strip();
        if (n.isEmpty() || n.equals(".") || n.equals("..") || n.contains("/") || n.contains("\\")) {
            return null;
        }
        Path from = file.toAbsolutePath().normalize();
        try {
            Path to = from.resolveSibling(n).normalize();
            return to.equals(from) || !from.getParent().equals(to.getParent()) ? null : to;
        } catch (RuntimeException invalid) {
            return null;   // a character the platform will not have in a name
        }
    }

    /**
     * Move {@code from} to {@code to}, both among the scratches, never over anything already there. A change of case
     * alone is refused as taken on a disk that ignores case, since it then finds the file itself under the new name:
     * the lesser surprise than a rename that silently does nothing.
     *
     * @throws IOException saying why, in words for the status line
     */
    public void relocate(Path from, Path to) throws IOException {
        Path a = from.toAbsolutePath().normalize();
        Path b = to.toAbsolutePath().normalize();
        if (!holds(a) || is(a) || !holds(b) || is(b) || b.startsWith(a)) {
            throw new IOException("only among the scratches, and not into itself");
        }
        if (!Files.exists(a, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(a.getFileName() + " is not there any more");
        }
        if (!Files.isDirectory(b.getParent())) {
            throw new IOException(b.getParent().getFileName() + " is not there any more");
        }
        if (Files.exists(b, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("there is already a " + b.getFileName() + " there");
        }
        Files.move(a, b);
    }

    /**
     * {@code file}, a scratch this run made and nothing was ever written to, has closed: it goes too, if it is among
     * the scratches and still empty. Which scratches those are is the {@link Editor}'s to say; anything else is left
     * alone, an empty file the person made themself included.
     */
    public void closed(Path file) {
        if (file == null || !holds(file) || is(file)) {
            return;
        }
        Path p = file.toAbsolutePath().normalize();
        try {
            if (Files.isRegularFile(p) && Files.size(p) == 0) {
                Files.delete(p);
            }
        } catch (IOException e) {
            // left behind, empty: the lesser problem
        }
    }
}
