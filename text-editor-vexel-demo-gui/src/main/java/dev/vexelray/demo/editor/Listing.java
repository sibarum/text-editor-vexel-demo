package dev.vexelray.demo.editor;

import dev.vexelray.gui.fileicons.FileFamily;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * A folder's files exploded into one list, the way an archive lists them: every file a path relative to the folder,
 * in path order, with its size. That is what a folder holding no build has to show — what is in it, where, and
 * how large.
 *
 * <p>Bounded the way {@link Survey} is, because it runs on hover: at most {@link #MAX_FILES} files, at most
 * {@link #MAX_DEPTH} folders below the one listed; {@link #truncated} says the list stopped short.
 */
record Listing(Path folder, List<Entry> entries, long bytes, boolean truncated) {

    /** At most this many files are read. */
    static final int MAX_FILES = 1500;
    /** And at most this many folders below the one listed. */
    static final int MAX_DEPTH = 8;

    /** One file: the folder it is in, relative and with {@code /} between names ("" at the top), and its own name. */
    record Entry(String dir, String name, long bytes, FileFamily family) {

        /** Its whole relative path. */
        String path() {
            return dir.isEmpty() ? name : dir + "/" + name;
        }
    }

    /**
     * Archive order: by path, ignoring case, a folder's own files before the folders inside it, so a folder's files
     * stay together and its subfolders follow as a block.
     */
    static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry e) -> e.dir().replace('/', '\0'), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER);

    /** How many files are listed. */
    int files() {
        return entries.size();
    }

    /** The largest file's size, which the size bars are measured against. */
    long largest() {
        long max = 0;
        for (Entry e : entries) {
            max = Math.max(max, e.bytes());
        }
        return max;
    }

    /**
     * Read {@code folder} into a listing, or return null if {@code live} turns false part way. Hidden folders and the
     * ones the navigator leaves out are skipped, as {@link Survey} skips them; anything unreadable is left out.
     */
    static Listing of(Path folder, BooleanSupplier live) {
        Path root = folder.toAbsolutePath().normalize();
        List<Entry> entries = new ArrayList<>();
        long[] bytes = {0};
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH + 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!live.getAsBoolean()) {
                        return FileVisitResult.TERMINATE;
                    }
                    String name = String.valueOf(dir.getFileName());
                    if (!dir.equals(root) && (FolderSource.HIDDEN.contains(name) || name.startsWith("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!live.getAsBoolean()) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isDirectory()) {
                        return FileVisitResult.CONTINUE;   // a folder past the depth limit: not listed
                    }
                    if (entries.size() >= MAX_FILES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    String name = String.valueOf(file.getFileName());
                    long size = Math.max(0, attrs.size());
                    bytes[0] += size;
                    entries.add(new Entry(dirOf(root, file), name, size, familyOf(name)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // an unreadable folder lists as what could be read of it
        }
        if (!live.getAsBoolean()) {
            return null;
        }
        entries.sort(ORDER);
        return new Listing(root, List.copyOf(entries), bytes[0], truncated[0]);
    }

    /** {@code bytes} as a person reads a size: "812 B", "14 KB", "2.1 MB". */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return (v < 10 ? String.format(Locale.ROOT, "%.1f", v) : String.valueOf(Math.round(v))) + " " + units[u];
    }

    private static String dirOf(Path root, Path file) {
        Path parent = root.relativize(file).getParent();
        return parent == null ? "" : parent.toString().replace('\\', '/');
    }

    private static FileFamily familyOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? FileFamily.UNKNOWN : FileFamily.ofExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
