package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.nfd.Nfd;
import sibarum.probe.Log;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * {@link Dialogs} over NFDe, parented to the main window.
 *
 * <p>Where a dialog runs is {@code FileDialog}'s decision, not this class's: on Windows it is the framework's
 * dialog thread, so the window keeps drawing behind the dialog (disabled, as a modal's owner should be); on macOS
 * AppKit insists on the main thread, so the request is posted to the frame loop ({@link GuiApp#post}) and the
 * frame loop is blocked for as long as the dialog is up. Either way the answer comes back to a worker.
 *
 * <p>Closed by the framework at shutdown, on the main thread. {@link Nfd#quit} releases only that thread's own
 * initialisation, so it matters on macOS, where the dialogs ran there, and is a no-op on Windows, where the
 * dialog thread quits for itself.
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
        ask(FileDialog.openAsync(app::post, app.windowHandle(), null, start), picked, () -> { });
    }

    @Override
    public void openFolder(Path start, Consumer<Path> picked) {
        ask(FileDialog.pickFolderAsync(app::post, app.windowHandle(), start), picked, () -> { });
    }

    @Override
    public void saveFile(Path start, String name, Consumer<Path> picked, Runnable cancelled) {
        ask(FileDialog.saveAsync(app::post, app.windowHandle(), filters, start, name), picked, cancelled);
    }

    private void ask(CompletableFuture<Optional<Path>> dialog, Consumer<Path> picked, Runnable cancelled) {
        dialog.whenCompleteAsync((answer, failure) -> {
            if (failure != null) {
                LOG.warn("the file dialog could not be shown", failure);
                cancelled.run();
            } else if (answer.isPresent()) {
                picked.accept(answer.get());
            } else {
                cancelled.run();
            }
        }, answers);
    }

    @Override
    public void close() {
        Nfd.quit();
    }
}
