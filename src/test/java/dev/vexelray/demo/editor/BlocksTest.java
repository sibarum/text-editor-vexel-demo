package dev.vexelray.demo.editor;

import dev.vexelray.gui.fileicons.FileFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlocksTest {

    private static final double EPS = 1e-9;

    private static Blocks.File file(String name, long bytes) {
        return new Blocks.File(name, bytes, FileFamily.UNKNOWN);
    }

    @Test
    void theOutermostPlateIsOneUnitAcrossOnTheFloorAtTheOrigin() {
        List<Blocks.Block> blocks = Blocks.layout(new Blocks.Dir("root", List.of(),
                List.of(file("a", 1000), file("b", 8))));
        Blocks.Block plate = blocks.getFirst();
        assertTrue(plate.folder());
        assertEquals(1.0, plate.side(), EPS);
        assertEquals(0.0, plate.x(), EPS);
        assertEquals(0.0, plate.z(), EPS);
        assertEquals(0.0, plate.y(), EPS);
    }

    @Test
    void aFilesVolumeIsItsSize() {
        List<Blocks.Block> blocks = Blocks.layout(new Blocks.Dir("root", List.of(),
                List.of(file("big", 8000), file("small", 8))));
        Blocks.Block big = named(blocks, "big");
        Blocks.Block small = named(blocks, "small");
        double ratio = Math.pow(big.side(), 3) / Math.pow(small.side(), 3);
        assertEquals(1000.0, ratio, 1e-6);
    }

    @Test
    void filesStandOnTheirFolderAndInsideIt() {
        Blocks.Dir inner = new Blocks.Dir("inner", List.of(), List.of(file("x", 64), file("y", 27)));
        List<Blocks.Block> blocks = Blocks.layout(new Blocks.Dir("root", List.of(inner), List.of(file("z", 125))));
        Blocks.Block root = blocks.getFirst();
        Blocks.Block in = named(blocks, "inner");
        assertEquals(root.top(), in.y(), EPS);
        for (String name : List.of("x", "y")) {
            Blocks.Block b = named(blocks, name);
            assertEquals(in.top(), b.y(), EPS);
            assertTrue(inside(b, in), name + " is inside its plate");
            assertEquals(2, b.depth());
        }
        assertTrue(inside(named(blocks, "z"), root));
        assertTrue(inside(in, root));
    }

    @Test
    void siblingsNeverOverlap() {
        List<Blocks.File> files = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            files.add(file("f" + i, (long) (Math.pow(1.4, i % 23) * 10)));
        }
        Blocks.Dir sub = new Blocks.Dir("sub", List.of(), List.of(file("s1", 5000), file("s2", 9)));
        List<Blocks.Block> blocks = Blocks.layout(new Blocks.Dir("root", List.of(sub), files));
        List<Blocks.Block> onRoot = blocks.stream().filter(b -> b.depth() == 1).toList();
        assertEquals(61, onRoot.size());
        for (int i = 0; i < onRoot.size(); i++) {
            for (int j = i + 1; j < onRoot.size(); j++) {
                assertFalse(overlap(onRoot.get(i), onRoot.get(j)), onRoot.get(i).name() + " / " + onRoot.get(j).name());
            }
            assertTrue(inside(onRoot.get(i), blocks.getFirst()));
        }
    }

    @Test
    void anEmptyFolderIsAPlateAlone() {
        List<Blocks.Block> blocks = Blocks.layout(new Blocks.Dir("empty", List.of(), List.of()));
        assertEquals(1, blocks.size());
        assertEquals(1.0, blocks.getFirst().side(), EPS);
    }

    @Test
    void theWalkSkipsWhatTheNavigatorLeavesOutAndReadsSizes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("notes.md"), "x".repeat(300));
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src").resolve("A.java"), "class A {}");
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("target").resolve("out.class"), "nope");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git").resolve("HEAD"), "nope");

        Blocks.Walked walked = Blocks.walk(dir, () -> true);
        assertNotNull(walked);
        assertEquals(2, walked.files());
        assertFalse(walked.truncated());
        assertEquals(1, walked.dir().dirs().size());
        assertEquals("src", walked.dir().dirs().getFirst().name());
        Blocks.File notes = walked.dir().files().getFirst();
        assertEquals(300, notes.bytes());
        assertEquals(FileFamily.ofExtension("md"), notes.family());
        assertEquals(FileFamily.ofExtension("java"), walked.dir().dirs().getFirst().files().getFirst().family());
    }

    @Test
    void aWalkNoLongerWantedReturnsNothing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "a");
        assertNull(Blocks.walk(dir, () -> false));
    }

    private static Blocks.Block named(List<Blocks.Block> blocks, String name) {
        return blocks.stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    private static boolean inside(Blocks.Block b, Blocks.Block plate) {
        double h = b.side() / 2;
        double p = plate.side() / 2;
        return b.x() - h >= plate.x() - p - EPS && b.x() + h <= plate.x() + p + EPS
                && b.z() - h >= plate.z() - p - EPS && b.z() + h <= plate.z() + p + EPS;
    }

    private static boolean overlap(Blocks.Block a, Blocks.Block b) {
        double ha = a.side() / 2;
        double hb = b.side() / 2;
        return a.x() - ha < b.x() + hb - EPS && b.x() - hb < a.x() + ha - EPS
                && a.z() - ha < b.z() + hb - EPS && b.z() - hb < a.z() + ha - EPS;
    }
}
