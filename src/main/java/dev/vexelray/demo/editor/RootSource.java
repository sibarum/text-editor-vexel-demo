package dev.vexelray.demo.editor;

import dev.vexelray.gui.widget.TreeView;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The folders a new root can be picked from, as the navigator's Open shows them: one row for the folder above the
 * current root, and under it folders only, each case-insensitively by name. Nothing is read until a level opens.
 *
 * <p>Unlike {@link FolderSource}, a run of single-folder folders is <b>not</b> merged into one row. Every folder is
 * a root someone may want, a module half way down a run included, and a merged row would leave it no row to click.
 */
final class RootSource implements TreeView.Source<Path> {

    private static final Comparator<Path> ORDER =
            Comparator.comparing(p -> String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT));

    private volatile Path top;

    /** The one row at the top, or null for none. */
    Path top() {
        return top;
    }

    /** Show {@code folder} as the top row, held absolute and normalised so every folder under it is too. */
    void top(Path folder) {
        this.top = folder == null ? null : folder.toAbsolutePath().normalize();
    }

    /**
     * Where Open starts for {@code root}: the folder above it, so that its siblings are on show beside it, or the
     * root itself at the top of a drive. With no root, the home folder.
     */
    static Path above(Path root) {
        if (root == null) {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        Path r = root.toAbsolutePath().normalize();
        Path parent = r.getParent();
        return parent != null ? parent : r;
    }

    @Override
    public List<Path> roots() {
        Path t = top;
        return t == null ? List.of() : List.of(t);
    }

    @Override
    public String label(Path item) {
        Path name = item.getFileName();
        return name != null ? name.toString() : item.toString();
    }

    @Override
    public boolean hasChildren(Path item) {
        return Files.isDirectory(item);
    }

    @Override
    public List<Path> children(Path item) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(item, Files::isDirectory)) {
            for (Path p : s) {
                if (!FolderSource.HIDDEN.contains(String.valueOf(p.getFileName()))) {
                    found.add(p);
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();   // unreadable: shown as empty rather than taking the tree down
        }
        found.sort(ORDER);
        return found;
    }
}
