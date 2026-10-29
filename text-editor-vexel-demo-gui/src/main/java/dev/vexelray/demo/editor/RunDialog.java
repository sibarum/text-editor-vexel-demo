package dev.vexelray.demo.editor;

import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.TextField;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Run…, the first item of the Run menu: a command typed rather than offered, with the folder it runs in, variables set
 * for it, and folders put before the {@code PATH}. What it makes is a {@link Runs.Run} like any the menu offers
 * ({@link Runs#typed}), run the same way: the documents saved, then a tab in the console.
 *
 * <p>A named {@link Window}, a satellite of the main one, since the framework's {@code Modal} has nowhere to type
 * (FN-22). It remembers what was last run from it, in the settings, so running the same again is Run… and Enter. The
 * folder is the remembered one only while it is inside the navigator's; otherwise it starts at the navigator's.
 *
 * <p>Enter in a one-line field runs, as does Ctrl+Enter anywhere, since Enter in the two lists is the next line.
 * Escape closes without running.
 */
final class RunDialog {

    /** The window, as {@link Recipes} declares it. */
    static final NamedWindow DECLARED = NamedWindow.of("run", "Run", 640, 480).satellite();

    /** Settings: what was last run from here. */
    static final String COMMAND = "run.command";
    static final String DIR = "run.dir";
    static final String ENVIRONMENT = "run.environment";
    static final String PATH = "run.path";

    private final Window window;
    private final Gui gui;
    private final Settings settings;
    private final Dialogs dialogs;
    private final TextField command;
    private final TextField dir;
    private final TextField environment;
    private final TextField path;
    /** Why the last Run did not run, in the danger colour; empty, and so not shown, otherwise. */
    private final Node problem;
    private volatile Consumer<Runs.Run> onRun = r -> { };

    RunDialog(Window window, Settings settings, Dialogs dialogs) {
        this.window = window;
        this.gui = window.gui();
        this.settings = settings;
        this.dialogs = dialogs;

        command = field(false, Landmarks.RUN_COMMAND).onSubmit(text -> run());
        dir = field(false, Landmarks.RUN_DIR).onSubmit(text -> run());
        Button browse = new Button(gui, "Browse…").kind(Button.Kind.SECONDARY).onPress(this::browse);
        dir.node().width(Length.grow(1));
        Node dirRow = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .children(dir.node(), browse.node());
        environment = field(true, Landmarks.RUN_ENVIRONMENT);
        environment.node().height(Length.rem(6));
        path = field(true, Landmarks.RUN_PATH);
        path.node().height(Length.rem(4.5f));

        problem = gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.DANGER))
                .width(Length.FILL).visible(false);
        Button cancel = new Button(gui, "Cancel").kind(Button.Kind.GHOST).onPress(window::close);
        Button go = new Button(gui, "Run").kind(Button.Kind.PRIMARY).onPress(this::run);
        gui.landmark(Landmarks.RUN_GO, go.node());
        Node buttons = gui.row().width(Length.FILL).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .children(gui.box().width(Length.grow(1)), cancel.node(), go.node());

        Node page = gui.column().width(Length.FILL).height(Length.grow(1)).padding(Type.WIDE).gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .children(label("Command"), command.node(),
                        label("Working directory"), dirRow,
                        label("Environment variables, one NAME=value a line"), environment.node(),
                        label("Add to PATH, one folder a line, ahead of what is there"), path.node(),
                        problem, gui.box().height(Length.grow(1)), buttons);
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), page);
        gui.claimUi(gui.root(), Shortcut.of(Key.ESCAPE), ClaimScope.VISIBLE, window::close);
        gui.claimUi(gui.root(), Shortcut.of(Key.ENTER, Modifier.CONTROL), ClaimScope.VISIBLE, this::run);
        gui.claimUi(gui.root(), Shortcut.of(Key.NUMPAD_ENTER, Modifier.CONTROL), ClaimScope.VISIBLE, this::run);
        window.onOpened(() -> {
            command.select(0, command.text().length());
            gui.focus(command.node());
        });
    }

    /** Be handed what to run when Run is pressed and it can. On whichever thread pressed it. */
    void onRun(Consumer<Runs.Run> handler) {
        this.onRun = handler == null ? r -> { } : handler;
    }

    /** Open, filled with what was last run from here, in {@code folder} unless that was somewhere inside it. */
    void show(Path folder) {
        command.text(settings.getString(COMMAND, ""));
        String last = settings.getString(DIR, "");
        Path was = last.isEmpty() ? null : path(last);
        boolean inside = was != null && folder != null && was.startsWith(folder) && Files.isDirectory(was);
        dir.text(inside || folder == null ? last : folder.toString());
        environment.text(String.join("\n", settings.getList(ENVIRONMENT)));
        path.text(String.join("\n", settings.getList(PATH)));
        say("");
        window.show();
    }

    private void run() {
        String what = command.text().strip();
        if (what.isEmpty()) {
            say("Type a command to run.");
            gui.focus(command.node());
            return;
        }
        Path where = path(dir.text().strip());
        if (where == null || !Files.isDirectory(where)) {
            say("The working directory is not a folder: " + dir.text().strip());
            gui.focus(dir.node());
            return;
        }
        Map<String, String> env;
        try {
            env = Runs.environment(environment.text());
        } catch (IllegalArgumentException e) {
            say(e.getMessage());
            gui.focus(environment.node());
            return;
        }
        List<String> folders = Runs.folders(path.text());
        settings.putString(COMMAND, what).putString(DIR, where.toString())
                .putList(ENVIRONMENT, environment.text().lines().filter(l -> !l.isBlank()).toList())
                .putList(PATH, folders);
        settings.save();
        window.close();
        onRun.accept(Runs.typed(what, where, env, folders));
    }

    private void browse() {
        Path at = path(dir.text().strip());
        Path start = at != null && Files.isDirectory(at) ? at : Path.of(System.getProperty("user.home"));
        dialogs.openFolder(start, picked -> dir.text(picked.toString()), () -> { });
    }

    private void say(String why) {
        problem.text(why).visible(!why.isEmpty());
    }

    /** {@code text} as a path, absolute; null when it is empty or no path at all. */
    private static Path path(String text) {
        try {
            return text.isEmpty() ? null : Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** A field in the code face, with a past of its own, as the console shows what runs in it. */
    private TextField field(boolean lines, String landmark) {
        TextField f = new TextField(gui, "").history(new History()).multiline(lines);
        if (lines) {
            f.wordWrap(false);
        }
        f.node().width(Length.FILL).font(Type.MONO).textSize(Type.CODE);
        gui.landmark(landmark, f.node());
        return f;
    }

    private Node label(String text) {
        return gui.text(text).font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.FAINT))
                .wordWrap(false);
    }
}
