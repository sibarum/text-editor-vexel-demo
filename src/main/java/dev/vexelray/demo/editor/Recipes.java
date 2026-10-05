package dev.vexelray.demo.editor;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.List;

/**
 * What this application builds — one recipe per part — and nothing about when.
 *
 * <p>{@code TextEditorWiring} is generated from this while the project compiles. A part's phase is the latest
 * phase of anything it takes, so the look and the model exist first, the window's contents wait for the
 * {@code Gui}, the file dialogs wait for the window, and the session — which needs the close gate, and so the
 * {@code Shell} — comes last.
 */
@Configuration
final class Recipes {

    /** The look, and the smallest window this UI is still coherent in. */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.em(TextEditor.MIN_W_EM), Length.em(TextEditor.MIN_H_EM));
    }

    /** The session's shape: which documents, which in front, which folder. */
    @Provides
    Model model() {
        return new Model();
    }

    /** What is remembered between runs, over the one settings store. */
    @Provides
    Session session(Settings settings, Model model) {
        return new Session(settings, model);
    }

    /**
     * The window's contents. Needs the {@code Gui}, its clock and its title bar, and no window, so a test can build it headless.
     * Every change to the session redraws what is derived from it and is remembered for next time.
     */
    @Provides
    Ui ui(Gui gui, KronoGui krono, Model model, TitleBar titleBar, Session session) {
        Ui ui = new Ui(gui, new Motion(krono), model, titleBar);
        model.onChange(doc -> {
            ui.show(doc);
            session.remember();
        });
        zoomShortcuts(gui);
        return ui;
    }

    /**
     * Every command, and the chords for them. The tree always has a document in it: an empty Untitled one, until
     * the session or a dialog brings something else.
     */
    @Provides
    Actions actions(Gui gui, Model model, Ui ui) {
        Actions actions = new Actions(gui, model, ui);
        actions.shortcuts();
        ui.workspace().untitled();
        return actions;
    }

    /**
     * The OS file dialogs, parented to the main window. Main-thread because it takes the {@code GuiApp}; the dialogs
     * it makes post themselves to the frame loop, so {@link Actions} may call them from anywhere.
     */
    @Provides
    @MainThread
    NativeDialogs dialogs(GuiApp app, Gui gui, Actions actions) {
        NativeDialogs dialogs = new NativeDialogs(app, gui.handlers());
        actions.dialogs(dialogs);
        return dialogs;
    }

    /**
     * Arm the close gate and bring back last time's session. Takes the {@code Shell} because {@code onClose} is only
     * reachable through it, which is also what puts this last: the window, the dialogs and the clipboard all exist.
     *
     * <p>Returns the session's restore as a value only because a provider must return something; see
     * docs/framework-notes.md, FN-3.
     */
    @Provides
    Restored restore(Shell shell, Actions actions, Session session, Launch launch, Gui gui) {
        shell.onClose(actions::guardClose);
        List<Path> extra = launch.rest().stream().map(Path::of).toList();
        session.restore(actions, extra, gui.offload());
        return new Restored();
    }

    /** That {@link #restore} has run. A marker: the recipe is all side effect, and a provider must return a type that is not a record. */
    static final class Restored {
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's three. Which chord zooms is the application's decision; how far
     * the zoom goes is {@code Appearance.ZoomRange}, applied by the framework.
     */
    private static void zoomShortcuts(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }
}
