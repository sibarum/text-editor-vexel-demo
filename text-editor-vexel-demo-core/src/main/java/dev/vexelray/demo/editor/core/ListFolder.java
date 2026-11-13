package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Item;
import dev.vexelray.demo.editor.core.Messages.Listed;
import dev.vexelray.demo.editor.core.Messages.Listing;
import dev.vexelray.framework.api.Job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One level of the navigator's tree, read from the disk on the offload lane: folders first, then files, each
 * case-insensitively by name.
 *
 * <p>The tree asks for a level the first time it is opened, and the view asks this; the answer is data (each entry's
 * path, name, whether it is a folder and whether a Maven project is around it), so the view can tell the tree what to
 * draw without touching the disk on the GUI thread. An unreadable folder answers empty rather than taking the tree
 * down.
 *
 * <h2>Compacted folders</h2>
 *
 * <p>Asked to ({@link Listing#compact}), a folder that holds one folder and nothing else is listed as one entry with
 * it, and so on down to the first folder that holds anything more: {@code dev/vexelray/gui/widget} is one row, whose
 * path is {@code widget}, so a package nine levels down is a few levels in and its files have room for their names.
 * Inside a source root ({@code src/main/java}) the names are joined with dots, as the package is; elsewhere with
 * slashes, as the path is.
 */
@Job
public final class ListFolder {

    /** Directories nobody opens a file in, left out of the listing so a project's tree is the project. */
    static final Set<String> HIDDEN = Set.of(".git", ".svn", ".hg", ".idea", "node_modules", "target", "target-linux",
            "__pycache__");

    /** The folders under {@code src/<set>/} whose subfolders are packages. */
    private static final Set<String> SOURCE_ROOTS = Set.of("java", "kotlin", "groovy", "scala");

    /** How many folders one row may stand for: a bound on a chain that a link turns into a loop. */
    private static final int MAX_COMPACTED = 32;

    private static final Comparator<Item> ORDER = Comparator
            .comparing((Item i) -> !i.folder())
            .thenComparing(i -> i.name().toLowerCase(Locale.ROOT));

    public Listed run(Listing l) {
        // One walk up for the level, then one look per folder for a pom of its own.
        boolean inProject = Poms.projectOf(l.dir()) != null;
        List<Item> items = list(l.dir(), inProject, l.compact());
        if (l.foldersOnly()) {
            items = items.stream().filter(Item::folder).toList();
        }
        return new Listed(l.tree(), l.dir(), l.roots(), inProject, items);
    }

    static List<Item> list(Path dir) {
        return list(dir, Poms.projectOf(dir) != null, false);
    }

    /** {@code inProject}: whether a Maven project is at or above {@code dir}, which every entry in it shares. */
    static List<Item> list(Path dir, boolean inProject, boolean compact) {
        List<Item> items = new ArrayList<>();
        try {
            for (Path p : entries(dir)) {
                boolean folder = Files.isDirectory(p);
                Path at = p;
                StringBuilder name = new StringBuilder(name(p));
                if (folder && compact) {
                    String joint = packages(p) ? "." : "/";
                    for (int i = 0; i < MAX_COMPACTED; i++) {
                        Path only = onlyFolder(at);
                        if (only == null || packages(only) != packages(p)) {
                            break;   // nor across a source root: main/java is one row and its packages another
                        }
                        at = only;
                        name.append(joint).append(name(only));
                    }
                }
                boolean project = inProject || folder && Files.isRegularFile(at.resolve("pom.xml"));
                items.add(new Item(at, name.toString(), folder, project));
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        items.sort(ORDER);
        return items;
    }

    /** What {@code dir} holds, less what is never listed. */
    private static List<Path> entries(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !HIDDEN.contains(String.valueOf(p.getFileName()))).toList();
        }
    }

    /** The one folder {@code dir} holds, when it holds that and nothing else that is listed; null otherwise. */
    private static Path onlyFolder(Path dir) {
        try {
            List<Path> in = entries(dir);
            return in.size() == 1 && Files.isDirectory(in.getFirst()) ? in.getFirst() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether {@code folder} is a package: under a source root, {@code src/<set>/java} or the like. */
    static boolean packages(Path folder) {
        int n = folder.getNameCount();
        for (int i = 0; i + 2 < n - 1; i++) {
            if (folder.getName(i).toString().equals("src") && SOURCE_ROOTS.contains(folder.getName(i + 2).toString())) {
                return true;
            }
        }
        return false;
    }

    private static String name(Path p) {
        Path name = p.getFileName();
        return name != null ? name.toString() : p.toString();
    }

    /**
     * The chain of paths from just under {@code base} down to {@code file}, inclusive: every folder on the way, and
     * the file. A compacted row stands for several of them, so the files tree maps this onto its rows before walking
     * it. Empty when the file is not under the base.
     */
    public static List<Path> chain(Path base, Path file) {
        if (base == null || file == null) {
            return List.of();
        }
        Path root = base.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            return List.of();
        }
        List<Path> chain = new ArrayList<>();
        Path at = root;
        for (Path part : root.relativize(target)) {
            at = at.resolve(part);
            chain.add(at);
        }
        return chain;
    }
}
