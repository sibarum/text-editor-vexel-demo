package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.Begin;
import dev.vexelray.demo.editor.core.Preferences;
import dev.vexelray.demo.editor.core.ProjectIndex;
import dev.vexelray.demo.editor.core.Scratches;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.text.FontSet;
import sibarum.atchung.Atchung;
import sibarum.concordance.index.JarCache;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * What this module builds — one recipe per part — and nothing about when.
 *
 * <p>{@code TextEditorAppWiring} is generated from this while the project compiles. A part's phase is the latest
 * phase of anything it takes, so the look and the services exist first, the window's contents wait for the
 * {@code Gui}, and the start, which needs the close gate and so the {@code Shell}, comes last. The dialogs are the
 * framework's {@code Dialogs}, there from the first phase, so nothing waits for the window to ask a question.
 *
 * <h2>Where the rest comes from</h2>
 *
 * <p>Not every part is a recipe. The {@link View} is a {@code @Component}: a lane of its own, built from its one
 * constructor, reached by messages. The {@code Editor} and the jobs that touch the disk are in {@code -core}, and reach
 * the wiring through {@code Core}, which {@code TextEditorApp} names among its starters beside
 * {@code AutomationStarter}, the automation sockets. The three services both modules read are built here: to this
 * module {@code -core}'s classes are somebody else's, which a recipe may return as they are.
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
     * The scratches' folder, beside the settings ({@code ~/.text-editor/scratches}), as the index is: the editor's own
     * place, which {@code AppHome} keeps the same on every platform and a test can move with {@code text-editor.home}.
     */
    @Provides
    Scratches scratches(Settings settings) {
        return new Scratches(settings.path().resolveSibling("scratches"));
    }

    /** Concordance's index of the open folder, built in the background whenever that folder is a Maven project. */
    @Provides
    ProjectIndex projectIndex(Atchung bus) {
        return new ProjectIndex(bus);
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

    /**
     * The window's contents. Needs the {@code Gui}, its clock and its title bar, and no window, so a test can build it
     * headless ({@code VexelApplication.tree}). What it shows is the {@link View}'s to say.
     */
    @Provides
    Ui ui(Gui gui, KronoGui krono, TitleBar titleBar, Scratches scratches) {
        Ui ui = new Ui(gui, new Motion(krono), titleBar, scratches);
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

    /** Run…'s dialog, where a command is typed: a satellite window, declared now and opened from the Run menu. */
    @Provides
    RunDialog runDialog(Windows windows, Settings settings, Dialogs dialogs) {
        return new RunDialog(windows.named(RunDialog.DECLARED), settings, dialogs);
    }

    /** The close gate and the navigator's, as messages: built here so the view can answer them and the start arm one. */
    @Provides
    Gates gates(Atchung bus) {
        return new Gates(bus);
    }

    /**
     * Arm the main window's close gate, connect the console to the frame loop's wake, let the index keep what it
     * learns, and tell the editor to bring back last time's session. Takes the {@code Shell} to be last: the window
     * and the clipboard exist, and a tree built headless for a test does not restore a session or write one, nor
     * save an index. Indexes go beside the settings, one per project root; jars go to Concordance's cache, which every
     * project on the machine shares.
     *
     * <p>Run for what it does, so it returns nothing. The message waits in the editor's mailbox until the framework
     * starts the components.
     */
    @Provides
    @Publishes(Messages.BEGIN)
    void start(Shell shell, Gates gates, Atchung bus, Console console, ProjectIndex index, Settings settings) {
        shell.windows().main().onClose(gates::quit);
        shell.wake(console);
        index.persist(settings.path().resolveSibling("index"), JarCache.defaultLocation());
        bus.publish(Messages.BEGIN_TOPIC, new Begin());
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
