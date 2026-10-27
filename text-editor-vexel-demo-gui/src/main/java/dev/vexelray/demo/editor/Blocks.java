package dev.vexelray.demo.editor;

import dev.vexelray.gui.fileicons.FileFamily;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * A folder as blocks standing on plates: every file a cube whose <b>volume</b> is its size, every folder a square
 * plate that its files and folders stand on, so a folder's folders rise from it a level at a time. What is large
 * is seen as large and what is deep is seen as high, which is what a folder holding no build has to show.
 *
 * <p>Two steps, both pure apart from the walk. {@link #walk} reads the folder, bounded the way {@link Survey} is
 * because it runs on hover, into a {@link Dir} tree. {@link #layout} packs that tree into {@link Block}s: each
 * folder's contents in rows, largest first, on a square plate just big enough for them. The scene is scaled so the
 * outermost plate is one unit across and centred on the origin, with the floor at {@code y = 0} and up as
 * {@code +y}, which is how the mesh technique's world is turned.
 *
 * <p>A file's side is the cube root of its bytes, so a file a thousand times larger is ten times wider. Every
 * folder is square because a plate is a box scaled the same way on all three axes — the mesh technique's
 * {@code Placement} scales uniformly, so a plate's thickness is a fixed share of its width.
 */
final class Blocks {

    /** At most this many files are read: one block each, and each block is a draw. */
    static final int MAX_FILES = 1500;
    /** And at most this many folders below the one shown. */
    static final int MAX_DEPTH = 8;
    /** A plate's thickness, as a share of its width. */
    static final double PLATE = 0.025;

    /** The side of an empty file: one byte's cube, so it is a speck rather than nothing. */
    private static final double MIN_SIDE = 1;
    /** The gap between blocks, and round a plate's edge, as a share of the typical block on that plate. */
    private static final double GAP = 0.18;

    private Blocks() {
    }

    /** A file read by the walk. */
    record File(String name, long bytes, FileFamily family) {
    }

    /** A folder read by the walk: its files and folders, in no particular order. */
    record Dir(String name, List<Dir> dirs, List<File> files) {
    }

    /**
     * One block of the scene, in scene units: {@code x} and {@code z} its centre on the floor, {@code y} its base,
     * {@code side} its width and depth. A folder's height is {@link #PLATE} of its side; a file's is its side.
     */
    record Block(double x, double y, double z, double side, boolean folder, FileFamily family, String name,
                 long bytes, int depth) {

        /** How high it stands. */
        double height() {
            return folder ? side * PLATE : side;
        }

        /** Where its top is. */
        double top() {
            return y + height();
        }
    }

    /** What is shown: the blocks, the outermost plate first, and whether the walk stopped short. */
    record Scene(Path folder, List<Block> blocks, int files, boolean truncated) {

        /** The height of the tallest block's top. */
        double top() {
            double top = 0;
            for (Block b : blocks) {
                top = Math.max(top, b.top());
            }
            return top;
        }
    }

    /**
     * Read {@code folder} into a tree, or return null if {@code live} turns false part way. Hidden folders and the
     * ones the navigator leaves out are skipped, as {@link Survey} skips them; anything unreadable is left out.
     */
    static Walked walk(Path folder, BooleanSupplier live) {
        Path root = folder.toAbsolutePath().normalize();
        Deque<Building> open = new ArrayDeque<>();
        int[] files = {0};
        boolean[] truncated = {false};
        Building[] top = {null};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH + 1,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!live.getAsBoolean()) {
                                return FileVisitResult.TERMINATE;
                            }
                            String name = String.valueOf(dir.getFileName());
                            if (!dir.equals(root) && (FolderSource.HIDDEN.contains(name) || name.startsWith("."))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            Building b = new Building(dir.equals(root) ? nameOf(root) : name);
                            if (open.isEmpty()) {
                                top[0] = b;
                            } else {
                                open.peek().dirs.add(b);
                            }
                            open.push(b);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!live.getAsBoolean()) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (attrs.isDirectory() || open.isEmpty()) {
                                return FileVisitResult.CONTINUE;   // a folder past the depth limit: not counted
                            }
                            if (files[0] >= MAX_FILES) {
                                truncated[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            files[0]++;
                            String name = String.valueOf(file.getFileName());
                            open.peek().files.add(new File(name, Math.max(0, attrs.size()), familyOf(name)));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                            if (!open.isEmpty()) {
                                open.pop();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            // an unreadable folder shows as what could be read of it
        }
        if (!live.getAsBoolean()) {
            return null;
        }
        Dir dir = top[0] == null ? new Dir(nameOf(root), List.of(), List.of()) : top[0].build();
        return new Walked(root, dir, files[0], truncated[0]);
    }

    /** What {@link #walk} read: the tree, how many files are in it, and whether it stopped at the bound. */
    record Walked(Path folder, Dir dir, int files, boolean truncated) {

        Scene scene() {
            return new Scene(folder, layout(dir), files, truncated);
        }
    }

    /**
     * Pack {@code dir} into blocks, the outermost plate first, scaled so that plate is one unit across and centred
     * on the origin.
     */
    static List<Block> layout(Dir dir) {
        Packed packed = pack(dir);
        List<Block> out = new ArrayList<>();
        double scale = 1 / packed.side;
        place(packed, -0.5, 0, -0.5, scale, 0, out);
        return out;
    }

    /** A packed folder: its square's side, and where each of its contents sits inside that square. */
    private record Packed(Dir dir, double side, List<Slot> slots) {
    }

    /** One of a folder's contents at {@code (x, z)}, its corner, inside the folder's square: a file or a folder. */
    private record Slot(double x, double z, double side, File file, Packed folder) {
    }

    /** Sizes first, bottom up: a folder's square is as big as its contents packed in rows, plus a margin. */
    private static Packed pack(Dir dir) {
        record Item(double side, File file, Packed folder) {
        }
        List<Item> items = new ArrayList<>();
        for (Dir d : dir.dirs()) {
            Packed p = pack(d);
            items.add(new Item(p.side, null, p));
        }
        for (File f : dir.files()) {
            items.add(new Item(sideOf(f.bytes()), f, null));
        }
        if (items.isEmpty()) {
            return new Packed(dir, MIN_SIDE * 4, List.of());
        }
        items.sort(Comparator.comparingDouble(Item::side).reversed());
        double area = 0;
        for (Item i : items) {
            area += i.side * i.side;
        }
        double gap = GAP * Math.sqrt(area / items.size());
        double widest = items.getFirst().side;
        double paddedArea = 0;
        for (Item i : items) {
            paddedArea += (i.side + gap) * (i.side + gap);
        }
        // Rows about as wide as the packed whole is deep, so the square around them wastes little.
        double rowWidth = Math.max(widest, Math.sqrt(paddedArea));

        List<Slot> slots = new ArrayList<>();
        double x = 0;
        double z = 0;
        double rowDepth = 0;
        double width = 0;
        for (Item i : items) {
            if (x > 0 && x + i.side > rowWidth) {
                z += rowDepth + gap;
                x = 0;
                rowDepth = 0;
            }
            slots.add(new Slot(x, z, i.side, i.file, i.folder));
            x += i.side + gap;
            rowDepth = Math.max(rowDepth, i.side);
            width = Math.max(width, x - gap);
        }
        double depth = z + rowDepth;
        double side = Math.max(width, depth) + 2 * gap;
        // Centred in the square: the margin on the short axis takes the difference.
        double dx = (side - width) / 2;
        double dz = (side - depth) / 2;
        List<Slot> centred = new ArrayList<>(slots.size());
        for (Slot s : slots) {
            centred.add(new Slot(s.x + dx, s.z + dz, s.side, s.file, s.folder));
        }
        return new Packed(dir, side, centred);
    }

    /** Positions, top down: a folder's plate at its corner {@code (x, z)}, base {@code y}, then what stands on it. */
    private static void place(Packed p, double x, double y, double z, double scale, int depth, List<Block> out) {
        double side = p.side * scale;
        out.add(new Block(x + side / 2, y, z + side / 2, side, true, FileFamily.FOLDERS, p.dir.name(), 0, depth));
        double floor = y + side * PLATE;
        for (Slot s : p.slots) {
            double sx = x + s.x * scale;
            double sz = z + s.z * scale;
            if (s.folder != null) {
                place(s.folder, sx, floor, sz, scale, depth + 1, out);
            } else {
                double fs = s.side * scale;
                out.add(new Block(sx + fs / 2, floor, sz + fs / 2, fs, false, s.file.family(), s.file.name(),
                        s.file.bytes(), depth + 1));
            }
        }
    }

    /** A file's side: the cube root of its bytes, so its volume is its size. */
    static double sideOf(long bytes) {
        return Math.max(MIN_SIDE, Math.cbrt(bytes));
    }

    private static FileFamily familyOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? FileFamily.UNKNOWN : FileFamily.ofExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String nameOf(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }

    /** A folder being read: lists that grow as the walk goes, frozen into a {@link Dir} when it is done. */
    private static final class Building {

        final String name;
        final List<Building> dirs = new ArrayList<>();
        final List<File> files = new ArrayList<>();

        Building(String name) {
            this.name = name;
        }

        Dir build() {
            List<Dir> built = new ArrayList<>(dirs.size());
            for (Building b : dirs) {
                built.add(b.build());
            }
            return new Dir(name, List.copyOf(built), List.copyOf(files));
        }
    }
}
