package dev.vexelray.demo.editor;

import dev.vexelray.gui.widget.TreeView;

import java.nio.file.Path;
import java.util.List;

/** The filesystem as a lazy {@link TreeView.Source}: directories first, then files, case-insensitive. */
final class FolderSource implements TreeView.Source<Path> {
    private final Path base;

    FolderSource(Path base) {
        this.base = base;
    }

    @Override
    public List<Path> roots() {
        return children(base);
    }

    @Override
    public String label(Path item) {
        Path name = item.getFileName();
        return name != null ? name.toString() : item.toString();
    }

    @Override
    public boolean hasChildren(Path item) {
        return java.nio.file.Files.isDirectory(item);
    }

    @Override
    public List<Path> children(Path item) {
        try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(item)) {
            return s.sorted(java.util.Comparator
                            .comparing((Path p) -> !java.nio.file.Files.isDirectory(p))
                            .thenComparing(p -> label(p).toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        } catch (java.io.IOException e) {
            return List.of();   // unreadable directory: shown as empty, not fatal
        }
    }
}
