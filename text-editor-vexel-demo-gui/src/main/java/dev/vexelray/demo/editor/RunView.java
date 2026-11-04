package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages.RunChecked;
import dev.vexelray.demo.editor.core.Messages.RunThis;
import dev.vexelray.demo.editor.core.Messages.RunTyped;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowOpened;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;

/**
 * Run…'s view: Run (the button, or Enter in the command or the folder) sends what was typed to be checked; a good
 * answer is run, as a line of the Run menu is, and the window goes; a problem stays in the window. Escape closes it.
 */
@dev.vexelray.framework.api.View
public final class RunView {

    private final RunDialog dialog;
    private final Sender<RunTyped> typed;
    private final Sender<RunThis> run;
    private final Sender<WindowCommand> windows;

    public RunView(RunDialog dialog, Sender<RunTyped> typed, Sender<RunThis> run, Sender<WindowCommand> windows) {
        this.dialog = dialog;
        this.typed = typed;
        this.run = run;
        this.windows = windows;
        dialog.gui().shortcut(Shortcut.of(Key.ESCAPE), windows, WindowCommand.close(RunDialog.KEY));
    }

    /** The window came up: the keyboard to the command. */
    @Subscribe
    public void opened(WindowOpened m) {
        if (m.window().equals(RunDialog.KEY)) {
            dialog.focus();
        }
    }

    @Subscribe
    public void pressed(Button.Pressed m) {
        if (dialog.runs(m.button())) {
            go();
        }
    }

    @Subscribe
    public void submitted(TextField.Submitted m) {
        if (dialog.runs(m.field())) {
            go();
        }
    }

    private void go() {
        dialog.problem("");
        typed.send(dialog.typed());
    }

    /** What was typed, checked: run it and put the window away, or say why not. */
    @Subscribe
    public void checked(RunChecked m) {
        if (m.offer().isEmpty()) {
            dialog.problem(m.problem());
            return;
        }
        run.send(new RunThis(m.offer().get()));
        windows.send(WindowCommand.close(RunDialog.KEY));
    }

    /** A folder shown in the main window: where Run… starts from while it has no folder of its own. */
    @Subscribe
    public void folder(ShowFolder m) {
        dialog.folderShown(m.path());
    }
}
