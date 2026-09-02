package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.text.Link;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TextField;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.IndexBuilder;
import sibarum.concordance.project.MavenProjectReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the links actually reach the field — the step every other test skips.
 *
 * <p>{@link SymbolLinksTest} checks {@code linksIn}, which is a pure function, and
 * {@link RealIndexLinkTest} checks it against a real index. Neither goes through {@link SymbolLinks} itself,
 * so neither would notice if the object computed perfect links and never handed them over. That is the whole
 * distance between "the logic is right" and "Ctrl+click does something".
 */
class LinkWiringTest {

    private record Harness(Gui gui, KronoGui krono, TextField field, SourceIndex source, SymbolLinks links)
            implements AutoCloseable {
        static Harness open(String text) {
            Gui gui = new Gui(Atchung.create(), Runnable::run);
            KronoGui krono = KronoGui.attach(gui);
            TextField field = new TextField(gui, text).multiline(true);
            SourceIndex source = new SourceIndex();
            SymbolLinks links = new SymbolLinks(gui, field, source, NOWHERE);
            return new Harness(gui, krono, field, source, links);
        }

        @Override
        public void close() {
            links.close();
            field.close();
            krono.close();
            gui.close();
        }
    }

    /**
     * Wait for the links to settle.
     *
     * <p>{@link Gui#async} is a real worker pool, not the same-thread executor a test harness injects for
     * handlers, so a refresh is genuinely in flight when {@code file} or {@code built} returns. Polling rather
     * than sleeping a fixed time: the wait is over when the answer arrives.
     */
    private static boolean awaitLinks(TextField field, boolean wanted) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (!field.links().isEmpty() == wanted) {
                return true;
            }
            Thread.onSpinWait();
        }
        return !field.links().isEmpty() == wanted;
    }

    private static final SymbolLinks.Host NOWHERE = new SymbolLinks.Host() {
        @Override
        public void jump(Path file, String name, int line) {
        }

        @Override
        public void say(String message) {
        }

        @Override
        public boolean shell(String line) {
            return false;
        }

        @Override
        public void note(String line) {
        }
    };

    /** This project, indexed for real — the same thing {@code index .} in the console produces. */
    private static Index thisProject() throws IOException {
        return IndexBuilder.build(MavenProjectReader.read(Path.of("").toAbsolutePath().normalize()));
    }

    /**
     * The whole path: a document with a file, an index that lands, and links on the field afterwards. This is
     * what "Ctrl+click does nothing" would fail on.
     */
    @Test
    void anIndexThatLandsPutsLinksOnTheField() throws IOException {
        Path file = Path.of("src/main/java/dev/vexelray/demo/editor/SymbolLinks.java")
                .toAbsolutePath().normalize();
        String text = Files.readString(file);

        try (Harness h = Harness.open(text)) {
            h.links.file(file);
            assertTrue(h.field.links().isEmpty(), "no index yet means no links");

            Index index = thisProject();
            h.source.built(index, Path.of("").toAbsolutePath().normalize(), index.summary());

            assertTrue(awaitLinks(h.field, true),
                    "an index that lands has to reach a document that is already open");
        }
    }

    /** The other order: the index is already built when the document is given its file. */
    @Test
    void aFileOpenedAfterTheIndexAlsoGetsLinks() throws IOException {
        Path file = Path.of("src/main/java/dev/vexelray/demo/editor/SourceIndex.java")
                .toAbsolutePath().normalize();
        String text = Files.readString(file);

        try (Harness h = Harness.open(text)) {
            Index index = thisProject();
            h.source.built(index, Path.of("").toAbsolutePath().normalize(), index.summary());

            h.links.file(file);

            assertTrue(awaitLinks(h.field, true), "a file opened into a built index has to link");
            // And the links must be findable the way Ctrl+click finds them.
            Link any = h.field.links().getFirst();
            assertNotNull(h.field.linkAt(any.start()), "linkAt must find what links() reported");
            assertEquals(any, h.field.linkAt(any.start()));
        }
    }

    /** A closed document stops being told about indexes, so a closed tab is not still being refreshed. */
    @Test
    void aClosedDocumentGetsNoMoreLinks() throws IOException {
        Path file = Path.of("src/main/java/dev/vexelray/demo/editor/SourceIndex.java")
                .toAbsolutePath().normalize();
        String text = Files.readString(file);

        try (Harness h = Harness.open(text)) {
            h.links.file(file);
            h.links.close();

            Index index = thisProject();
            h.source.built(index, Path.of("").toAbsolutePath().normalize(), index.summary());

            assertFalse(awaitLinks(h.field, true), "a closed document must not be given links");
        }
    }
}
