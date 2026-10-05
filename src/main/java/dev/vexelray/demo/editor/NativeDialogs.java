package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.nfd.Nfd;
import sibarum.probe.Log;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link Dialogs} over NFDe, parented to the main window.
 *
 * <p>NFDe's dialogs are synchronous, modal, and must be called on the thread that owns the window, so a request
 * from a worker is posted to the frame loop ({@link GuiApp#post}), runs there, and hands the answer back to a
 * worker. <b>The frame loop is blocked for as long as the dialog is up.</b> That is NFDe's shape rather than a
 * choice made here, and the window does not paint behind the dialog while it is open; the OS dialog itself stays
 * responsive.
 *
 * <p>Closed by the framework at shutdown, on the main thread, which is where {@link Nfd#quit} must run.
 */
final class NativeDialogs implements Dialogs, AutoCloseable {

    private static final Log LOG = Log.of("editor.dialogs");

    private final GuiApp app;
    private final Executor answers;
    private final List<FileDialog.Filter> filters;

    NativeDialogs(GuiApp app, Executor answers) {
        this.app = app;
        this.answers = answers;
        this.filters = List.of(FileDialog.Filter.of("Text files",
                Highlighter.knownExtensions().stream().sorted().toArray(String[]::new)));
    }

    @Override
    public void openFile(Path start, Consumer<Path> picked) {
        ask(() -> FileDialog.open(app.windowHandle(), null, start), picked, () -> { });
    }

    @Override
    public void openFolder(Path start, Consumer<Path> picked) {
        ask(() -> FileDialog.pickFolder(app.windowHandle(), start), picked, () -> { });
    }

    @Override
    public void saveFile(Path start, String name, Consumer<Path> picked, Runnable cancelled) {
        ask(() -> FileDialog.save(app.windowHandle(), filters, start, name), picked, cancelled);
    }

    private void ask(Supplier<Optional<Path>> dialog, Consumer<Path> picked, Runnable cancelled) {
        app.post(() -> {
            Optional<Path> answer;
            try {
                answer = dialog.get();
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("the file dialog could not be shown", e);
                answers.execute(cancelled);
                return;
            }
            answers.execute(answer.isPresent() ? () -> picked.accept(answer.get()) : cancelled);
        });
    }

    @Override
    public void close() {
        Nfd.quit();
    }
}
