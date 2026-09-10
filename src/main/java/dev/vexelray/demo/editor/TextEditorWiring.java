package dev.vexelray.demo.editor;

import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Wiring;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;

import java.util.Set;

/**
 * This application's own wiring: what it builds, and in which phase.
 *
 * <p>What is <em>not</em> here is the point. Opening an input backend and settling its coordinate space,
 * opening the OS clipboard, opening one {@code Settings} and no more, remembering where the window was and
 * what it was zoomed to, attaching the clock before the first widget, building the title bar and pointing it
 * at real window controls, putting the application's mark on the process, installing the dialogs, wiring the
 * frame loop with its stages and its pacing and its wakes, parsing the command line, and closing all of it in
 * reverse order — all of that was {@link TextEditorApp}'s and is now the framework's. What is left below is
 * the part that is actually about editing text.
 *
 * <p>Written by hand, deliberately, on the same terms as {@code calculator-vexel-demo}'s: this is the file the
 * annotation processor will be made to generate, so its shape is being settled against real code first. Each
 * method is one {@code Phase}, and the phase a component belongs to is decided by what it needs — which is
 * exactly the inference the processor will do from constructor parameters.
 */
final class TextEditorWiring extends Wiring {

    /**
     * The one setting this application declares, and so the one {@code --flag} beyond the framework's own that
     * {@code Launch} will accept: {@code --terminal} opens the shell window at startup instead of on Ctrl+`,
     * so the second window can be looked at, and its shutdown exercised, without a hand on the keyboard.
     *
     * <p>A setting rather than a mode, on {@code RunMode}'s own grounds — opening the terminal is orthogonal to
     * whether this is a session or a fixed-frame run, so it is not an alternative to either.
     */
    static final String TERMINAL = "terminal";

    /**
     * The frame probe, which is a framework key rather than one of ours. Off by default, because it is not a
     * passive instrument: besides printing a line every three seconds it deliberately pokes the loop — a
     * timeline post, a node mutated from a worker, a handler that does nothing — to prove each wake path is
     * still alive. Those are exactly the things worth checking and exactly the things a shipped run should not
     * be doing to itself on a timer.
     */
    private static final String PROFILE = "profile";

    /**
     * The facts the framework needs about this application, as a constant.
     *
     * <p>The mark is asked for here rather than installed by this class: the framework puts it on the process
     * before the first window exists <em>and</em> names it on the main window's own config, which is the pair
     * of calls {@link AppIcon} used to have to explain for itself.
     */
    private static final AppInfo INFO = new AppInfo(
            "text-editor", TextEditorApp.TITLE, TextEditorApp.W, TextEditorApp.H, Set.of(TERMINAL))
            .withIcon(AppIcon.optional());

    private SourceIndex source;
    private Workspace ws;
    private FileActions files;

    @Override
    public AppInfo info() {
        return INFO;
    }

    /**
     * The look, and the smallest window this layout stays coherent in.
     *
     * <p>The editor keeps the framework's own look unshifted — it is the reference the other two windows are
     * departures from (see {@link Palettes}). Stated rather than left to the default, because the choice is one
     * of three and a default is not a choice anyone can read.
     *
     * <p>Two em more height than the page needs on its own: the title bar sits inside the canvas, so the
     * smallest layout has to hold it as well as the tabs and the status line. Both of these are values, which
     * is what lets them be settled before there is a {@code Gui} to apply them to — the constraint that used to
     * be a comment about roles resolving at the moment a widget writes a prop.
     */
    @Override
    public void config(Shell shell) {
        shell.appearance(Appearance.of(Palettes.EDITOR, Length.em(30), Length.em(22)));
    }

    /**
     * The project index, shared between this editor's documents and the Concordance commands in its own
     * terminal.
     *
     * <p>{@code MODEL} because it is what the application knows and it needs nothing drawn — which is also why
     * a capture reaches it. Built empty: {@code index .} in the terminal is what fills it, and until then a
     * document has no links and Ctrl+click finds nothing under the pointer.
     */
    @Override
    public void model(Shell shell) {
        source = new SourceIndex();
    }

    /**
     * The documents, the tab bar and the status line. Needs the {@code Gui} and the clock, both of which exist
     * by now, and no window — which is the whole of why {@code --capture} works.
     *
     * <p>The title bar is the framework's: chrome placement belongs to whoever owns the window, so that the
     * instrument strip in it means the same thing in every window on the desk. This application places the node
     * and supplies every colour in it, and no longer constructs it or hands it controls.
     */
    @Override
    public void tree(Shell shell) {
        ws = new Workspace(shell.gui(), shell.krono(), source, shell.titleBar());
        TextEditorApp.zoomShortcuts(shell.gui());
    }

    /**
     * The file actions, and with them the file tree and the terminal.
     *
     * <p>Phase {@code WINDOW} because a modal file dialog parents to an OS window and a command has to be
     * posted onto the frame loop's queue, so this cannot exist before {@code GuiApp} does. The two lines after
     * it are part of building it: the documents have existed since {@code TREE} and only now is there anywhere
     * for a Ctrl+click to go, and the shortcuts have to reach every window rather than only the focused one.
     *
     * <p>Registered with the disposer at the moment it is constructed, so a failure later in startup still
     * stops MainFrame's job thread.
     */
    @Override
    public void window(Shell shell) {
        // The editor is this application's main window, so that is what a dialog parents to, and it already
        // exists by the time anything can ask.
        files = shell.disposer().register(new FileActions(shell.gui(), ws, shell.app(), shell.memory(),
                true, shell.krono(), shell.app()::windowHandle, null, source));
        ws.navigation(files.navigation());
        files.shortcuts();
    }

    /**
     * What needed the window: the close gate, the clipboard on the windows the framework did not build, this
     * application's per-frame work, and whatever was left open last time.
     */
    @Override
    public void attach(Shell shell) {
        // Closing the main window is quitting, so it goes through a gate that can still ask about unsaved work
        // while the window stays open. The framework installs no gate of its own -- the default has to be that
        // closing closes -- but it owns the dialog the answer is given in.
        shell.onClose(files::guardClose);
        // Every window gets the OS clipboard, not just the main one: copy out of the terminal's prompt has to
        // reach the same place copy out of a tab does. The framework has already bound the Gui it built; these
        // are the two it did not, and both own a Gui that existed before any window did.
        for (Gui window : files.windows()) {
            shell.clipboard().installOn(window);
        }
        // This application's own per-frame work, in the one stage an application should be writing hooks in.
        // What used to be beside it in the frame lambda -- the input pump, the clock tick, the memory poll --
        // are the framework's stages now.
        shell.hooks().add(FrameStage.APP, files::perFrame);
        // Whatever was up last time comes back up. --terminal on top of that is harmless: opening a window
        // that is already open focuses it.
        files.restore();
        if (shell.launch().flag(TERMINAL)) {
            files.openTerminal();
        }
        if (shell.launch().flag(PROFILE)) {
            profile(shell);
        }
    }

    /**
     * The frame probe, on {@code --profile}.
     *
     * <p>{@code SETTLE} rather than {@code APP}, and registered after the framework's own hooks, so the sample
     * is taken at the same point in the frame it was taken at when this was a lambda: last, after the window
     * memory has polled. A measurement is exactly the kind of work that is allowed to be skipped rather than
     * paid late, which is what that stage is for.
     *
     * <p>The report is a close rather than a line after the loop, because the disposer is the only thing that
     * knows the loop is over — and it runs before the console and the window it is reporting about go away.
     */
    private void profile(Shell shell) {
        Gui gui = shell.gui();
        FpsProbe probe = new FpsProbe(shell.krono().kron(), () -> gui.root().opacity(1f), gui.handlers());
        shell.hooks().add(FrameStage.SETTLE, probe::sample);
        shell.disposer().register(() -> {
            probe.report("text editor, idle");
            probe.close();
        });
    }
}
