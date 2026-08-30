package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.harness.HarnessApp;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.ContextMenu;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.MouseButton;

/**
 * How long a tab's context menu takes to appear, against a loop that parks between frames.
 *
 * <p>Measurement, not assertion: prints wall time and the budgets the loop parked on, so the answer to
 * "why does the right-click menu feel like a second" is a number rather than a reading of the code.
 */
class TabMenuLatencyTest {

    @Test
    void tabContextMenuLatency() throws Exception {
        Gui gui = new Gui();
        gui.theme(Palettes.EDITOR);
        KronoGui krono = KronoGui.attach(gui);
        Workspace ws = new Workspace(gui, krono);
        String big = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/dev/vexelray/demo/editor/FileActions.java"));
        ws.newTab(big, java.nio.file.Path.of("src/main/java/dev/vexelray/demo/editor/FileActions.java"), false);
        ws.newTab(big, java.nio.file.Path.of("src/main/java/dev/vexelray/demo/editor/Workspace.java"), false);
        // The real menu source, as the application installs it — FileActions, not a stand-in.
        dev.vexelray.gui.core.app.WindowMemory memory =
                new dev.vexelray.gui.core.app.WindowMemory(
                        dev.vexelray.gui.core.app.Settings.open("text-editor-latency-probe"));
        ContextMenu.presentOn(gui);
        ContextMenu menu = (ContextMenu) gui.menus();

        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("harness", 900, 600))) {
            // Exactly what TextEditorApp asks for on an uncapped run.
            harness.app()
                    .pacing(() -> Math.min(Long.MAX_VALUE, memory.nanosUntilSettle()))
                    .idleRefresh(200_000_000L)
                    .maxFrameRate(16_666_666L);
            FileActions files = new FileActions(gui, ws, harness.app(), memory, false, krono,
                    harness.app()::windowHandle, null);
            harness.settle();

            Rect r = ws.tabs.header(1).layout().rect();
            int x = (int) (r.x() + r.w() / 2);
            int y = (int) (r.y() + r.h() / 2);
            System.out.println("header 1 at " + r + " -> right-clicking " + x + "," + y);

            harness.window().resetBudgets();
            long frames = harness.frames();
            long t0 = System.nanoTime();
            harness.click(x, y, MouseButton.RIGHT);
            boolean shown = harness.await(menu::shown, 5_000);
            long visible = System.nanoTime() - t0;
            // shown is set on the handler thread; the frame that draws it is the second half of the wait.
            harness.awaitFrame(frames, 5_000);
            long drawn = System.nanoTime() - t0;

            System.out.printf("menu shown=%s after %.1f ms, first frame after %.1f ms, %d frames%n",
                    shown, visible / 1e6, drawn / 1e6, harness.frames() - frames);
            System.out.println("budgets parked on (ms): " + harness.window().budgets().stream()
                    .map(b -> b == Long.MAX_VALUE ? "forever" : (b / 1_000_000) + "ms").toList());
            System.out.println("menu items: " + menu.items());

            // What one frame costs: uncapped, nothing animating, the document laid out and drawn.
            harness.app().pacing(() -> 0L).maxFrameRate(0L);
            harness.window().postWake();
            long f0 = harness.frames();
            long m0 = System.nanoTime();
            Thread.sleep(2000);
            long df = harness.frames() - f0;
            System.out.printf("free-running: %d frames in %.1f ms = %.1f ms/frame (%.1f fps)%n",
                    df, (System.nanoTime() - m0) / 1e6, (System.nanoTime() - m0) / 1e6 / Math.max(1, df),
                    df / ((System.nanoTime() - m0) / 1e9));
        }
    }
}
