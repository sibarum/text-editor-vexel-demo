package dev.vexelray.demo.editor.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
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
 * reads or writes its text.
 *
 * <p>Every method that touches the disk is for the jobs ({@link MakeScratch}, {@link DropScratch},
 * {@link FindScratches}); the view uses only the path arithmetic. A value, the folder and nothing else, so one can be
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
