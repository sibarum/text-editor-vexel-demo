package dev.vexelray.demo.editor;

import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.console.Console;
import dev.mainframe.gui.console.ConsoleSpec;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowMemory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code index .} typed into the real console, against the real apps, reaching the index the editor reads.
 *
 * <p>Every other test builds an {@link sibarum.concordance.index.Index} and hands it over directly. That skips
 * the join this feature actually rests on: {@code index} is a command on one {@link ConsoleApp} and the links
 * are on another object entirely, and they only meet because {@code TextEditorDesktop} hands both the same
 * {@link SourceIndex}. If that wiring were wrong — two holders, or the command writing to one nobody reads —
 * every existing test would still pass and Ctrl+click would do nothing at all.
 *
 * <p>Built the way {@code Desktop.capture} builds it: a headless {@link Console} over the same app list, with
 * lines submitted and waited on. No window, which is why this can run here at all.
 */
class SharedIndexTest {

    /** The same pairing {@code TextEditorDesktop.apps} makes: one index, two apps that were handed it. */
    private static List<ConsoleApp> apps(WindowMemory memory, SourceIndex source) {
        return List.of(new Editor(memory, file -> null, source), new ConcordanceApp(source));
    }

    /** Submit {@code line} and wait for the job thread to finish with it, as the capture path does. */
    private static void run(Console console, String line) throws InterruptedException {
        console.submit(line);
        long deadline = System.nanoTime() + 30_000_000_000L;
        Thread.sleep(50);
        while (console.busy() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(50);
    }

    @Test
    void indexInTheConsoleFillsTheIndexTheEditorReads() throws Exception {
        Settings settings = Settings.open("text-editor-test");
        WindowMemory memory = new WindowMemory(settings);
        SourceIndex source = new SourceIndex();

        // The listener the open documents hang off: if this never fires, no already-open tab lights up.
        boolean[] announced = {false};
        source.onBuilt(() -> announced[0] = true);

        try (Console console = new Console(ConsoleSpec.builder().apps(apps(memory, source)).build())) {
            console.start(Path.of("").toAbsolutePath().normalize());
            run(console, "index .");

            String error = console.lastError();
            assertTrue(error == null || error.isBlank(), "index . reported: " + error);
            assertTrue(source.present(),
                    "index . must fill the very index the editor's documents read - if this fails, the two "
                            + "apps are not sharing one SourceIndex");
            assertNotNull(source.index());
            assertTrue(announced[0], "an already-open document has to be told the index landed");
            assertTrue(source.index().symbols().size() > 50,
                    "indexing this project should find plenty: " + source.status());
        }
    }
}
