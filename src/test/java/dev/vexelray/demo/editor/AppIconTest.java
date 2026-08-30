package dev.vexelray.demo.editor;

import dev.vexelray.os.Icon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The icon is a resource read by name, which is the kind of thing that goes missing silently: a renamed file, a
 * jar built without it, and the application simply comes up wearing the OS default. AppIcon reports and carries
 * on when that happens -- deliberately, because a window under the wrong icon is still a window -- so nothing at
 * runtime will fail loudly enough to notice. This is what notices.
 */
class AppIconTest {

    @Test
    void shipsEverySizeItClaims() {
        Icon icon = AppIcon.load();
        List<Integer> widths = icon.images().stream().map(Icon.Image::width).sorted().toList();
        assertEquals(List.of(16, 32, 48, 64, 128, 256), widths);
        for (Icon.Image image : icon.images()) {
            assertEquals(image.width(), image.height(), "the mark is square at every size");
        }
    }

    @Test
    void answersTheSizesWindowsAsksFor() {
        Icon icon = AppIcon.load();
        // The two slots a Win32 window has: the caption's small icon and Alt-Tab's large one. Both are drawn
        // sizes here, so each is answered with artwork rather than a resample.
        assertEquals(16, icon.bestFor(16).width());
        assertEquals(32, icon.bestFor(32).width());
    }

    @Test
    void hasSomethingDrawnOnIt() {
        // A PNG that decoded to nothing is still a valid Icon, and would put an empty box on the taskbar.
        int[] argb = AppIcon.load().bestFor(32).argb();
        assertTrue(java.util.Arrays.stream(argb).anyMatch(p -> (p >>> 24) != 0), "no opaque pixel");
    }
}
