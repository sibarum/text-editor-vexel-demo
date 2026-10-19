package dev.vexelray.demo.editor;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.text.FontSet;
import sibarum.concordance.index.JarCache;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.List;

/**
 * What this application builds — one recipe per part — and nothing about when.
 *
 * <p>{@code TextEditorAppWiring} is generated from this while the project compiles. A part's phase is the latest
 * phase of anything it takes, so the look and the model exist first, the window's contents wait for the
 * {@code Gui}, and the session — which needs the close gate, and so the {@code Shell} — comes last. The dialogs are
 * the framework's {@code Dialogs}, there from the first phase, so nothing waits for the window to ask a question.
 */
@Configuration
final class Recipes {

    /**
     * The look the preferences ask for, and the smallest window this UI is still coherent in. Read here, before the
     * window is built, because a colour is resolved when a widget is made: a theme chosen later waits for the next start.
     */
    @Provides
    Appearance look(Settings settings) {
        return Appearance.of(Look.of(Preferences.theme(settings)),
                Length.rem(TextEditor.MIN_W_EM), Length.rem(TextEditor.MIN_H_EM));
    }

    /** The theme, word wrap and indexing: the editor's own, or a root's own in its {@code .vex}. */
    @Provides
    Preferences preferences(Settings settings) {
        return new Preferences(settings);
    }

    /**
     * Open's blocks given the device: the one recipe here that is main-thread, because what it builds draws with
     * Vulkan. Built at the window phase, drawn from its own frame hook, closed at shutdown before the device.
     */
    @Provides
    @MainThread
    BlockView.Binding blocks(GuiApp app, Ui ui) {
        return ui.preview().blocks().bind(app);
    }

    /**
     * The fonts, as this project's own build bakes them (the {@code <families>} in pom.xml): Noto Sans in its four
     * faces, for the formatted view's bold and italic, and Noto Sans Mono, each borrowing what it lacks from the
     * other as the standard set's do.
     */
    @Provides
    FontSet fonts() {
        return FontSet.fromClassPath(FONTS).withFallback("sans", "mono");
    }

    /** Where the build writes the fonts' manifest, on the class path. */
    static final String FONTS = "/dev/vexelray/demo/editor/fonts/fonts.json";

    /** The session's shape: which documents, which in front, which folder. */
    @Provides
    Model model() {
        return new Model();
    }

    /** Concordance's index of the open folder, built in the background whenever that folder is a Maven project. */
    @Provides
    ProjectIndex projectIndex(Model model) {
        return new ProjectIndex(model);
    }

    /** What is remembered between runs, over the one settings store. */
    @Provides
    Session session(Settings settings, Model model) {
        return new Session(settings, model);
    }

    /**
     * The scratches' folder, beside the settings ({@code ~/.text-editor/scratches}), as the index is: the editor's own
     * place, which {@code AppHome} keeps the same on every platform and a test can move with {@code text-editor.home}.
     */
    @Provides
    Scratches scratches(Settings settings) {
        return new Scratches(settings.path().resolveSibling("scratches"));
    }

    /**
     * The window's contents. Needs the {@code Gui}, its clock and its title bar, and no window, so a test can build it headless.
     * Every change to the session redraws what is derived from it and is remembered for next time.
     */
    @Provides
    Ui ui(Gui gui, KronoGui krono, Model model, TitleBar titleBar, Session session, Scratches scratches) {
        Ui ui = new Ui(gui, new Motion(krono), model, titleBar, scratches);
        model.onChange(doc -> {
            ui.show(doc);
            session.remember();
        });
        zoomShortcuts(gui);
        return ui;
    }

    /**
     * The console, where the Run menu's runs are shown: a window of its own, declared now so its tree is built beside
     * the main one, and opened by the first run. Its {@code tick} is the frame's, and it is {@code AutoCloseable}, so
     * whatever is still running ends with the application.
     */
    @Provides
    Console console(Windows windows, Settings settings, Dialogs dialogs) {
        return new Console(windows.named(Console.DECLARED), settings, dialogs);
    }

    /**
     * Every command, and the chords for them. The tree always has a document in it: an empty Untitled one, until
     * the session or a dialog brings something else.
     */
    @Provides
    Actions actions(Gui gui, Model model, Ui ui, ProjectIndex index, Session session, Dialogs dialogs,
                    Console console, Scratches scratches, Preferences prefs) {
        Actions actions = new Actions(gui, model, ui, index, session, dialogs, console, scratches, prefs);
        actions.shortcuts();
        ui.workspace().untitled();
        return actions;
    }

    /**
     * Arm the main window's close gate, connect the console to the frame loop's wake, let the index keep what it
     * learns, and bring back last time's session. Takes the {@code Shell} to be last: the window and the clipboard
     * exist, and a tree built headless for a test does not restore a session or write one, nor save an index.
     * Indexes go beside the settings, one per project root; jars go to Concordance's cache, which every project on
     * the machine shares.
     *
     * <p>Returns the session's restore as a value only because a provider must return something; see
     * docs/framework-notes.md, FN-3.
     */
    @Provides
    Restored restore(Shell shell, Actions actions, Session session, Launch launch, Gui gui, Settings settings,
                     ProjectIndex index, Console console) {
        shell.windows().main().onClose(actions::guardClose);
        shell.wake(console);
        index.persist(settings.path().resolveSibling("index"), JarCache.defaultLocation());
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
