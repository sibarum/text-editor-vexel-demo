package dev.vexelray.demo.editor.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Finding git repositories under a folder, breadth first, so the ones nearest the top are found first and a walk cut
 * short by its limits has found the likeliest. A folder holding {@code .git} (a folder, or a file for a worktree or a
 * submodule) is a repository, and the walk does not go into it. {@link ScanRepos} runs it for Welcome.
 *
 * <p>Bounded four ways, since a home folder can be vast: how deep it goes, how many folders it opens, how many
 * repositories it finds, and how long it takes. It follows no link or junction, so it cannot loop and cannot wander
 * onto another drive, goes into no hidden folder, and skips the folders that hold tools' and builds' own files rather
 * than anyone's work. A folder it may not read is passed over.
 */
public final class Repositories {

    /** How the walk is bounded. */
    public record Limits(int depth, int folders, int found, long nanos) {

        /** What Welcome walks the home folder with. */
        public static final Limits WELCOME = new Limits(6, 20_000, 200, 20_000_000_000L);
    }

    /** How a walk ended. */
    public enum End {
        /** Every folder within the depth was looked at. */
        DONE {
            @Override
            public String says(String found, String folders) {
                return found + " in " + folders;
            }
        },
        /** It stopped at one of its limits, with folders left unlooked at. */
        LIMIT {
            @Override
            public String says(String found, String folders) {
                return found + " in the first " + folders + "; stopped looking there";
            }
        },
        /** It was asked to stop. */
        STOPPED {
            @Override
            public String says(String found, String folders) {
                return found + "; stopped looking";
            }
        };

        /** The line Welcome shows for a walk that ended this way: what it {@code found}, in how many {@code folders}. */
        public abstract String says(String found, String folders);
    }

    /** What a walk came to: how it ended, how many folders it opened, and how many repositories it found. */
    public record Walked(End end, int folders, int found) {
    }

    /**
     * Folders that hold tools' and builds' files, never anyone's own repository, by name, in lower case. Hidden folders
     * (a leading dot) are skipped besides.
     */
    static final Set<String> SKIPPED = Set.of("appdata", "application data", "node_modules", "target", "target-linux",
            "build", "out", "dist", "library", "go", "scoop", "miniconda3", "anaconda3", "$recycle.bin");

    /** How many folders between reports of how far it has got, when it has found nothing new. */
    static final int REPORT_EVERY = 500;

    private Repositories() {
    }

    /**
     * Walk {@code root} breadth first within {@code limits}. {@code found} is handed each batch of repositories as they
     * are found, with the count of folders opened so far (an empty batch now and then, to say how far it has got);
     * {@code stop} is asked between folders, and {@code clock} (nanoseconds, {@code System::nanoTime} but in a test)
     * keeps the time limit.
     */
    public static Walked walk(Path root, Limits limits, BatchSink found, BooleanSupplier stop, LongSupplier clock) {
        long until = clock.getAsLong() + limits.nanos();
        Deque<Level> queue = new ArrayDeque<>();
        queue.add(new Level(root, 0));
        int folders = 0;
        int count = 0;
        int sinceReport = 0;
        while (!queue.isEmpty()) {
            if (stop.getAsBoolean()) {
                return new Walked(End.STOPPED, folders, count);
            }
            if (folders >= limits.folders() || count >= limits.found() || clock.getAsLong() > until) {
                return new Walked(End.LIMIT, folders, count);
            }
            Level level = queue.poll();
            folders++;
            List<Path> batch = new ArrayList<>();
            for (Path child : children(level.dir())) {
                if (isRepository(child)) {
                    if (count < limits.found()) {
                        batch.add(child);
                        count++;
                    }
                } else if (level.depth() + 1 < limits.depth()) {
                    queue.add(new Level(child, level.depth() + 1));
                }
            }
            sinceReport++;
            if (!batch.isEmpty() || sinceReport >= REPORT_EVERY) {
                found.accept(batch, folders);
                sinceReport = 0;
            }
        }
        return new Walked(End.DONE, folders, count);
    }

    /** Where a walk's finds go: the repositories just found, in order, and how many folders it has opened. */
    @FunctionalInterface
    public interface BatchSink {
        void accept(List<Path> repositories, int folders);
    }

    private record Level(Path dir, int depth) {
    }

    /** {@code dir}'s folders worth walking, sorted by name so a walk is repeatable; none when it cannot be read. */
    static List<Path> children(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                if (walkable(p)) {
                    out.add(p);
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        out.sort(null);
        return out;
    }

    /** A real folder (no link, no junction) whose name is not hidden or skipped. */
    static boolean walkable(Path p) {
        Path name = p.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString();
        if (n.startsWith(".") || SKIPPED.contains(n.toLowerCase(Locale.ROOT))) {
            return false;
        }
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // A Windows junction reads as "other", not as a link: neither is followed.
            return a.isDirectory() && !a.isSymbolicLink() && !a.isOther();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** Whether {@code dir} is a repository's top: it holds {@code .git}, a folder or (for a worktree) a file. */
    static boolean isRepository(Path dir) {
        return Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
    }
}
