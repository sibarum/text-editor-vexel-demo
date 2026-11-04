package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages.RunTyped;
import dev.vexelray.demo.editor.core.TypeRun;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.TextField;

import java.nio.file.Path;

/**
 * Run…: a window for a command of the person's own. The command, the folder it runs in, environment variables one
 * {@code NAME=value} a line, and folders to put ahead of the {@code PATH}, one a line; then Run. The
 * {@link RunView}'s, on the GUI thread.
 *
 * <p>It starts from what it last ran, which {@link TypeRun} keeps in the settings, and from the folder shown when it
 * has no folder of its own. Enter in the command or the folder runs it, as Run does; Escape closes. What is typed is
 * checked by {@code TypeRun}, and a problem stays here, under the fields, until it is put right.
 */
final class RunDialog {

    /** The window's key: {@code WindowCommand.show(KEY)} is the Run menu's Run…, and a script's {@code window run}. */
    static final String KEY = "run";

    private final Window window;
    private final Gui gui;
    private final TextField command;
    private final TextField dir;
    private final TextField environment;
    private final TextField path;
    private final Button go;
    private final Node problem;

    RunDialog(Window window, Settings settings) {
        this.window = window;
        this.gui = window.gui();

        command = new TextField(gui, settings.getString(TypeRun.COMMAND, "")).name("command");
        dir = new TextField(gui, settings.getString(TypeRun.DIR, "")).name("dir");
        environment = new TextField(gui, settings.getString(TypeRun.ENVIRONMENT, "")).name("environment")
                .multiline(true);
        path = new TextField(gui, settings.getString(TypeRun.PATH, "")).name("path").multiline(true);
        for (TextField f : new TextField[] {command, dir}) {
            f.node().width(Length.FILL).font(Type.MONO).textSize(Type.SMALL);
        }
        for (TextField f : new TextField[] {environment, path}) {
            f.node().width(Length.FILL).height(Length.rem(4.5f)).font(Type.MONO).textSize(Type.SMALL);
        }
        go = new Button(gui, "Run").name("go");
        problem = gui.text("").font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.DANGER))
                .wordWrap(true).width(Length.FILL);
        gui.landmark(Landmarks.RUN_COMMAND, command.node());
        gui.landmark(Landmarks.RUN_DIR, dir.node());
        gui.landmark(Landmarks.RUN_ENVIRONMENT, environment.node());
        gui.landmark(Landmarks.RUN_PATH, path.node());
        gui.landmark(Landmarks.RUN_GO, go.node());

        Node card = gui.column().width(Length.FILL).height(Length.grow(1f))
                .padding(Type.EDGE)
                .gap(Type.TIGHT)
                .alignItems(AlignItems.STRETCH)
                .background(gui.theme().color(Look.CARD))
                .corner(Type.CORNER)
                .border(Type.RULE, gui.theme().color(Look.RIM))
                .scroll(false, true)
                .children(label("Command"), command.node(),
                        label("Folder"), dir.node(),
                        label("Environment, one NAME=value a line"), environment.node(),
                        label("Ahead of PATH, one folder a line"), path.node(),
                        problem,
                        gui.row().width(Length.FILL).justify(Justify.END).children(go.node()));

        window.titleBar().node().background(gui.theme().color(Role.PAGE));
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(),
                        gui.box().width(Length.FILL).height(Length.grow(1f)).padding(Length.ZERO, Type.GAP)
                                .scroll(false, false).children(card),
                        gui.box().width(Length.FILL).height(Type.GAP));
    }

    private Node label(String text) {
        return gui.text(text).font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.DIM))
                .padding(Length.dp(4), Length.ZERO);
    }

    Window window() {
        return window;
    }

    Gui gui() {
        return gui;
    }

    /** Whether {@code address} is a field whose Enter runs, or the Run button. */
    boolean runs(String address) {
        return address.equals(command.address()) || address.equals(dir.address()) || address.equals(go.address());
    }

    /** What the fields say now, to be checked and run. */
    RunTyped typed() {
        return new RunTyped(command.text(), dir.text(), environment.text(), path.text());
    }

    /** The folder shown in the main window: where a command runs when Run… has no folder of its own yet. */
    void folderShown(Path folder) {
        if (folder != null && dir.text().isBlank()) {
            dir.text(folder.toString());
        }
    }

    /** Why what was typed does not run, under the fields; empty to clear it. */
    void problem(String why) {
        problem.text(why);
    }

    /** The keyboard to the command, as the window comes up. */
    void focus() {
        gui.focus(command.node());
    }
}
