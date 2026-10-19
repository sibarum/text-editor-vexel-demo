package dev.vexelray.demo.editor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scratches: files that belong to no project. A note, a pasted log, a snippet being worked out — nothing to delete,
 * and nowhere in particular to save it. They live in one folder of the editor's own, beside its settings
 * ({@code ~/.text-editor/scratches}, the framework's {@code AppHome}), which the navigator offers two ways: as a
 * project of its own in Open, once there is at least one, and laid over the files of whatever root is open, on
 * Alt+N, for a quick look without leaving it.
 *
 * <p>A scratch is an ordinary file once it exists: it saves with Ctrl+S, highlights by its extension, and can be
 * moved or renamed in Vexplore like any other. Nothing here reads or writes its text.
 *
 * <p>Every method that touches the disk is for the offload lane.
 */
final class Scratches {

    /** A new scratch's name: the minute it was made, so the folder sorts by age. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    /** What a new scratch is. Markdown, since a scratch is mostly a note, and it then has the Formatted view. */
    static final String EXTENSION = ".md";

    /** The name Open and the navigator give the folder, in place of its own. */
    static final String NAME = "Scratches";

    /** How far {@link #any} looks before calling the folder empty: a folder of only folders is not worth a walk. */
    private static final int LOOK = 2_000;

    private final Path dir;
    /**
     * The scratches made by {@link #create} in this run and not yet written to. Closing one of those while it is
     * still empty takes the file away again, so a Ctrl+Shift+N changed nothing leaves nothing behind.
     */
    private final Set<Path> fresh = ConcurrentHashMap.newKeySet();

    Scratches(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    /** The folder. It may not exist yet: it appears with the first scratch. */
    Path dir() {
        return dir;
    }

    /** Whether {@code path} is the folder itself or anything in it. Path arithmetic only. */
    boolean holds(Path path) {
        return path != null && path.toAbsolutePath().normalize().startsWith(dir);
    }

    /** Whether {@code path} is the folder itself. */
    boolean is(Path path) {
        return path != null && path.toAbsolutePath().normalize().equals(dir);
    }

    /** Whether there is at least one scratch, anywhere under the folder. */
    boolean any() {
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
    Path ensure() throws IOException {
        return Files.createDirectories(dir);
    }

    /**
     * Make a new, empty scratch in {@code folder} (the scratches folder itself for null, or for anywhere outside
     * it), named for this minute, and return it. Two in one minute are {@code -2}, {@code -3} and so on.
     */
    Path create(Path folder) throws IOException {
        Path in = folder != null && holds(folder) && Files.isDirectory(folder) ? folder : ensure();
        Path made = unused(in, STAMP.format(LocalDateTime.now()), EXTENSION, true);
        fresh.add(made.toAbsolutePath().normalize());
        return made;
    }

    /**
     * A name in the folder for an Untitled document being kept as a scratch: as {@link #create} names one, not yet
     * on disk, since it is the save that writes it.
     */
    Path name() throws IOException {
        return unused(ensure(), STAMP.format(LocalDateTime.now()), EXTENSION, false);
    }

    /** A new folder in {@code parent} (the scratches folder for null), called {@code Folder}, {@code Folder 2}... */
    Path folder(Path parent) throws IOException {
        Path in = parent != null && holds(parent) && Files.isDirectory(parent) ? parent : ensure();
        for (int n = 1; ; n++) {
            Path p = in.resolve(n == 1 ? "Folder" : "Folder " + n);
            try {
                return Files.createDirectory(p);
            } catch (FileAlreadyExistsException taken) {
                // the next number
            }
        }
    }

    /**
     * {@code file}'s tab has closed: if it is a scratch this run made and nothing was ever written to it, it goes
     * too. Anything else is left alone, an empty file the user made themself included.
     */
    void closed(Path file) {
        if (file == null) {
            return;
        }
        Path p = file.toAbsolutePath().normalize();
        if (!fresh.remove(p)) {
            return;
        }
        try {
            if (Files.isRegularFile(p) && Files.size(p) == 0) {
                Files.delete(p);
            }
        } catch (IOException e) {
            // left behind, empty: the lesser problem
        }
    }

    /** {@code file} has been saved: it is a scratch with something in it now, and stays. */
    void written(Path file) {
        if (file != null) {
            fresh.remove(file.toAbsolutePath().normalize());
        }
    }

    /** {@code stem + ext} in {@code folder}, or {@code -2}, {@code -3}... until one is free; made if {@code make}. */
    private static Path unused(Path folder, String stem, String ext, boolean make) throws IOException {
        for (int n = 1; ; n++) {
            Path p = folder.resolve(n == 1 ? stem + ext : stem + "-" + n + ext);
            if (!make) {
                if (!Files.exists(p)) {
                    return p;
                }
                continue;
            }
            try {
                return Files.createFile(p);
            } catch (FileAlreadyExistsException taken) {
                // the next number
            }
        }
    }
}
