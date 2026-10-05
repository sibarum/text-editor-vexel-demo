package dev.vexelray.demo.editor;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The OS's file dialogs, asked from any thread, answered later.
 *
 * <p>An interface because the real ones need the window, and the window does not exist when the tree is built:
 * {@link Actions} is built with {@link #NONE} and handed {@link NativeDialogs} once there is a window to parent
 * them to. A test can hand it its own.
 *
 * <p>Each answer arrives on a worker with the path picked; a cancelled open does not call back at all.
 */
interface Dialogs {

    void openFile(Path start, Consumer<Path> picked);

    void openFolder(Path start, Consumer<Path> picked);

    /** As the others, except that cancelling is an answer too: a save something is waiting on must hear it. */
    void saveFile(Path start, String name, Consumer<Path> picked, Runnable cancelled);

    /** No window yet: every request is dropped. */
    Dialogs NONE = new Dialogs() {
        @Override
        public void openFile(Path start, Consumer<Path> picked) {
        }

        @Override
        public void openFolder(Path start, Consumer<Path> picked) {
        }

        @Override
        public void saveFile(Path start, String name, Consumer<Path> picked, Runnable cancelled) {
            cancelled.run();
        }
    };
}
