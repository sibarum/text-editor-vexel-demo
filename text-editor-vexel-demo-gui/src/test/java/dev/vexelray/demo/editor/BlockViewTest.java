package dev.vexelray.demo.editor;

import dev.vexelray.gui.fileicons.FileFamily;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The camera's fit and the icons' projection, which have to agree with the shader without a GPU to ask. */
class BlockViewTest {

    private static Blocks.Scene scene() {
        List<Blocks.File> files = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            files.add(new Blocks.File("f" + i, (long) (Math.pow(1.5, i % 17) * 50), FileFamily.UNKNOWN));
        }
        Blocks.Dir tall = new Blocks.Dir("tall", List.of(),
                List.of(new Blocks.File("huge", 50_000_000, FileFamily.UNKNOWN)));
        Blocks.Dir root = new Blocks.Dir("root", List.of(tall), files);
        return new Blocks.Scene(Path.of("root"), Blocks.layout(root), 41, false);
    }

    @Test
    void everyCornerIsInTheBoxAndThePictureFillsIt() {
        Blocks.Scene s = scene();
        for (double[] box : new double[][] {{1200, 600}, {600, 900}, {400, 400}}) {
            double w = box[0];
            double h = box[1];
            double[] eye = BlockView.eye(s, w / h);
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (Blocks.Block b : s.blocks()) {
                double r = b.side() / 2;
                for (double x : new double[] {b.x() - r, b.x() + r}) {
                    for (double y : new double[] {b.y(), b.top()}) {
                        for (double z : new double[] {b.z() - r, b.z() + r}) {
                            double[] p = BlockView.project(eye, x, y, z, w, h);
                            assertNotNull(p);
                            minX = Math.min(minX, p[0]);
                            maxX = Math.max(maxX, p[0]);
                            minY = Math.min(minY, p[1]);
                            maxY = Math.max(maxY, p[1]);
                        }
                    }
                }
            }
            assertTrue(minX >= 0 && maxX <= w && minY >= 0 && maxY <= h, "inside " + w + "x" + h);
            double fill = Math.max((maxX - minX) / w, (maxY - minY) / h);
            assertTrue(fill > 0.9, "fills " + w + "x" + h + ": " + fill);
            assertTrue(Math.abs((minX + maxX) / 2 - w / 2) < 0.01 * w, "centred across " + w + "x" + h);
            assertTrue(Math.abs((minY + maxY) / 2 - h / 2) < 0.01 * h, "centred down " + w + "x" + h);
        }
    }

    @Test
    void aTopBehindATallerBlockIsHiddenAndTheTallOneIsNot() {
        Blocks.Scene s = scene();
        double[] eye = BlockView.eye(s, 2);
        Blocks.Block huge = s.blocks().stream().filter(b -> b.name().equals("huge")).findFirst().orElseThrow();
        assertFalse(BlockView.hidden(eye, huge, s.blocks()));
        // Something low right behind the tall one, on the line from the eye, is behind it.
        double fx = huge.x() + (huge.x() - eye[0]) * 0.02;
        double fz = huge.z() + (huge.z() - eye[2]) * 0.02;
        Blocks.Block behind = new Blocks.Block(fx, 0, fz, 0.001, false, FileFamily.UNKNOWN, "behind", 1, 1);
        List<Blocks.Block> with = new ArrayList<>(s.blocks());
        with.add(behind);
        assertTrue(BlockView.hidden(eye, behind, with));
    }
}
