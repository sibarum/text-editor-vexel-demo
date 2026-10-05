package dev.vexelray.demo.editor;

import dev.vexelray.gui.widget.TreeView;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The filesystem under one folder, as a lazy {@link TreeView.Source}: directories first, then files, each
 * case-insensitively by name. Nothing is read until a level is opened.
 *
 * <p><b>Runs of single-folder folders are one row</b>, as VS Code's compact folders are: a Java project's
 * {@code src/main/java/dev/vexelray/demo/editor} would otherwise sit eight indents deep in a sixteen-rem panel,
 * with no width left for the names that matter. The row's item is the deepest folder of the run — the one whose
 * contents it opens onto — and its label is the run, "dev/vexelray/demo/editor". The folders above it have no row,
 * which is why {@link #chainTo} has to know about the merge too.
 *
 * <p>The folder can be changed, because a tree cannot be handed a new source: pointing the navigator somewhere
 * else is {@link #base(Path)} followed by {@code TreeView.refresh()}, which re-reads the roots and drops every
 * row that is no longer among them.
 */
final class FolderSource implements TreeView.Source<Path> {

    /** Directories nobody opens a file in, left out of the listing so a project's tree is the project. */
    static final Set<String> HIDDEN = Set.of(".git", ".svn", ".hg", ".idea", "node_modules", "target", "__pycache__");

    private static final Comparator<Path> ORDER = Comparator
            .comparing((Path p) -> !Files.isDirectory(p))
            .thenComparing(p -> String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT));

    /**
     * What a merged row's label puts between the folders it stands for. Not the design's dot, because a folder's
     * own name can hold a dot and "a.b" would then read two ways.
     */
    static final String MERGE = "/";

    private volatile Path base;

    /**
     * The label of every row that stands for a run of folders, keyed by the deepest of them, which is the row's
     * item. Written by {@link #children} as it lists, read by {@link #label}; any other row is just its own name.
     */
    private final Map<Path, String> merged = new ConcurrentHashMap<>();

    FolderSource(Path base) {
        base(base);
    }

    /** The folder whose contents are the roots, or null for none. */
    Path base() {
        return base;
    }

    /** Point at {@code folder}, held absolute and normalised so every item listed under it is too. */
    void base(Path folder) {
        this.base = folder == null ? null : folder.toAbsolutePath().normalize();
        merged.clear();   // labels of the old folder's runs; the refresh that follows lists the new one's
    }

    @Override
    public List<Path> roots() {
        Path b = base;
        return b == null ? List.of() : children(b);
    }

    @Override
    public String label(Path item) {
        String chain = merged.get(item);
        if (chain != null) {
            return chain;
        }
        Path name = item.getFileName();
        return name != null ? name.toString() : item.toString();
    }

    @Override
    public boolean hasChildren(Path item) {
        return Files.isDirectory(item);
    }

    @Override
    public boolean acceptsChildren(Path item) {
        return Files.isDirectory(item);
    }

    /**
     * What is in {@code item}, with each run of single-folder folders standing as its deepest folder.
     *
     * <p>The merge is decided here, while listing, because this is the one place that is already off the frame
     * loop and already touching the disk; {@link #label} is asked under the tree's lock, wherever a row happens to
     * be built, and must not. So the label each merged row will need is worked out now and left in
     * {@link #merged} — and taken out again for a folder that is no longer merged, or a row would keep a chain
     * that the disk has since broken.
     */
    @Override
    public List<Path> children(Path item) {
        // Sorted by the top of each run, before merging: that name is what the label starts with, so it is the one
        // the eye looks for in alphabetical order.
        List<Path> tops = visible(item, Integer.MAX_VALUE);
        tops.sort(ORDER);
        List<Path> listed = new ArrayList<>(tops.size());
        for (Path p : tops) {
            Path deepest = Files.isDirectory(p) ? deepest(p) : p;
            if (deepest.equals(p)) {
                merged.remove(p);
            } else {
                merged.put(deepest, joined(item.relativize(deepest)));
            }
            listed.add(deepest);
        }
        return listed;
    }

    /**
     * The chain of rows from just under the base down to {@code file}, inclusive — what
     * {@code TreeView.revealPath} walks. Empty if the file is not under the base.
     *
     * <p>A folder that was merged into the one below it has no row of its own, so it is left out: the walk finds
     * rows by item, and a step it cannot find is where it stops. Knowing which folders those are is a question for
     * the disk, one short listing per folder on the way down, so this is not for the GUI thread.
     */
    List<Path> chainTo(Path file) {
        Path root = base;
        if (!under(root, file)) {
            return List.of();
        }
        Path target = file.toAbsolutePath().normalize();
        List<Path> folders = new ArrayList<>();
        Path at = root;
        for (Path part : root.relativize(target)) {
            at = at.resolve(part);
            folders.add(at);
        }
        List<Path> chain = new ArrayList<>();
        for (int i = 0; i < folders.size() - 1; i++) {
            Path folder = folders.get(i);
            if (!folders.get(i + 1).equals(loneFolder(folder))) {
                chain.add(folder);
            }
        }
        // The target itself always ends the chain, as the row that stands for it: a folder merged further down
        // is shown as the deepest of its run, so that is the row a reveal of it lands on.
        Path last = folders.getLast();
        chain.add(Files.isDirectory(last) ? deepest(last) : last);
        return chain;
    }

    /** Whether {@code file} is somewhere under the base, and so has a way down to it. Path arithmetic only. */
    boolean holds(Path file) {
        return under(base, file);
    }

    private static boolean under(Path root, Path file) {
        if (root == null || file == null) {
            return false;
        }
        Path target = file.toAbsolutePath().normalize();
        return target.startsWith(root) && !target.equals(root);
    }

    /**
     * The entries of {@code folder} the tree shows, unsorted, reading no further than {@code enough} of them. An
     * unreadable directory shows as empty rather than taking the tree down.
     */
    private static List<Path> visible(Path folder, int enough) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(folder)) {
            for (Path p : s) {
                if (!HIDDEN.contains(String.valueOf(p.getFileName()))) {
                    found.add(p);
                    if (found.size() >= enough) {
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();   // mutable like the other answer, since children sorts it
        }
        return found;
    }

    /**
     * The one folder {@code folder} holds, when that is all it holds — counting only what the tree would show, so
     * a folder whose other entry is a hidden {@code target} still merges. Null for anything else: files, two
     * entries, nothing at all.
     *
     * <p>Reads at most two entries, which is what keeps the merge cheap however large the folder is. A link is not
     * followed into: a folder linking to its own parent would otherwise merge forever.
     */
    private static Path loneFolder(Path folder) {
        List<Path> two = visible(folder, 2);
        if (two.size() != 1) {
            return null;
        }
        Path only = two.getFirst();
        return Files.isDirectory(only, LinkOption.NOFOLLOW_LINKS) ? only : null;
    }

    /** The bottom of {@code folder}'s run of lone folders: itself, when it holds anything but exactly one folder. */
    private static Path deepest(Path folder) {
        Path at = folder;
        for (Path next = loneFolder(at); next != null; next = loneFolder(at)) {
            at = next;
        }
        return at;
    }

    /** {@code relative}'s names joined by {@link #MERGE}, whatever the platform's own separator is. */
    private static String joined(Path relative) {
        StringBuilder out = new StringBuilder();
        for (Path name : relative) {
            if (!out.isEmpty()) {
                out.append(MERGE);
            }
            out.append(name);
        }
        return out.toString();
    }
}
