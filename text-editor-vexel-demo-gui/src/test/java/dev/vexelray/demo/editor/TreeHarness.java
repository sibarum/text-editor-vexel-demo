package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.Begin;
import dev.vexelray.demo.editor.core.Messages.Open;
import dev.vexelray.demo.editor.core.Messages.Recent;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.nfd.FileDialog;
import dev.vexelray.gui.widget.Modal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The editor's real tree, built headless by the generated wiring (no window, no GPU) and driven the way a person
 * drives it: through the messages a gesture sends, the view's intents, the navigator and the gates.
 *
 * <p>{@code VexelApplication.tree} runs the wiring as far as the tree and starts the components, so the editor, the
 * view and the jobs are all running on their own lanes, exactly as in the window. What does not run is the last
 * recipe, the one that takes the {@code Shell}: no session is restored and no settings are written unless a test
 * sends {@link #begin()} itself, which a launch with paths does without writing anything.
 *
 * <p>The harness listens on the bus as one more subscriber, which is how a test sees what the editor told the view.
 * Every part answers on a lane of its own, so a test waits for what it asked for ({@link #eventually}) rather than
 * sleeping.
 */
final class TreeHarness implements AutoCloseable {

    final Shell shell;
    final TextEditorAppWiring wiring;
    /** How a question is answered: by default it fails the test, since nothing should be asked unexpectedly. */
    volatile Consumer<Modal> answers = modal -> {
        throw new AssertionError("asked unexpectedly: " + modal.title());
    };
    /** Where Save As answers, or null to cancel it. */
    volatile Path saveTo;
    /** What the open-file dialog answers, or null to cancel it. */
    volatile Path openTo;

    private volatile String status = "";
    private volatile Recent recent = new Recent(null, null, null);
    private final Map<Long, String> titles = new ConcurrentHashMap<>();

    /** The tree, launched with {@code args} on its command line. */
    TreeHarness(String... args) {
        wiring = new TextEditorAppWiring();
        shell = VexelApplication.tree(wiring, args);
        // This test's dialogs in place of the framework's, which would hold every question for a window that never
        // comes. Taken back as late as WINDOW, so handing them over after the tree is built is in time.
        shell.dialogs(new Answering());
        shell.bus().subscribe(Messages.STATUS_TOPIC, s -> status = s.message());
        shell.bus().subscribe(Messages.RETITLE_TOPIC, r -> titles.put(r.id(), r.title()));
        shell.bus().subscribe(Messages.RECENT_TOPIC, r -> recent = r);
    }

    /** Dialogs that answer a question with {@link #answers}, Save As with {@link #saveTo}, Open with {@link #openTo}. */
    private final class Answering implements Dialogs {

        @Override
        public void ask(Modal modal) {
            answers.accept(modal);
        }

        @Override
        public void openFile(Path start, List<FileDialog.Filter> filters, Consumer<Path> picked, Runnable cancelled) {
            Path target = openTo;
            if (target == null) {
                cancelled.run();
            } else {
                picked.accept(target);
            }
        }

        @Override
        public void openFolder(Path start, Consumer<Path> picked, Runnable cancelled) {
            cancelled.run();
        }

        @Override
        public void saveFile(Path start, String name, List<FileDialog.Filter> filters, Consumer<Path> picked,
                             Runnable cancelled) {
            Path target = saveTo;
            if (target == null) {
                cancelled.run();
            } else {
                picked.accept(target);
            }
        }
    }

    Workspace ws() {
        return wiring.ui().workspace();
    }

    Navigator nav() {
        return wiring.ui().navigator();
    }

    Ui ui() {
        return wiring.ui();
    }

    /** The tab in front, once there is one: the Untitled tab a tree starts with arrives from the editor. */
    Buffer front() throws InterruptedException {
        eventually("a tab", () -> ws().front() != null);
        return ws().front();
    }

    /** Open {@code file}, as the navigator or a dialog would, and give it the keyboard. */
    void open(Path file) {
        shell.bus().publish(Messages.OPEN_TOPIC, new Open(file.toAbsolutePath().normalize(), true));
    }

    /** Make {@code folder} the root, as picking it in the navigator's Open does. */
    void root(Path folder) {
        nav().pick(folder);
    }

    /** What a chord or a menu item asks of the view. */
    void tell(Intent.Command command, long id) {
        shell.bus().publish(Intent.INTENTS, new Intent(command, id));
    }

    /** What the start recipe sends once the window exists: the session, or the command line's paths. */
    void begin() {
        shell.bus().publish(Messages.BEGIN_TOPIC, new Begin());
    }

    /** The last thing the status line was told. */
    String status() {
        return status;
    }

    /** What the editor last called tab {@code id}, once it has retitled it; null before that. */
    String title(long id) {
        return titles.get(id);
    }

    /** The recent roots the editor last offered. */
    Recent recent() {
        return recent;
    }

    /** The files open now, in tab order, by name. */
    List<String> tabNames() {
        return ws().all().stream()
                .map(b -> b.path() == null ? "Untitled" : String.valueOf(b.path().getFileName()))
                .toList();
    }

    static void eventually(String what, BooleanSupplier test) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!test.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    /** Answer every question by pressing the button labelled {@code label}, and remember the question. */
    static Consumer<Modal> pressing(String label, List<String> asked) {
        return modal -> {
            asked.add(modal.title());
            modal.buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow().action().run();
        };
    }

    @Override
    public void close() {
        shell.disposer().close();
    }
}
