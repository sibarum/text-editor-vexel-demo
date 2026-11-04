package dev.vexelray.demo.editor;

import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.demo.editor.core.Messages.Ended;
import dev.vexelray.demo.editor.core.Messages.Fitted;
import dev.vexelray.demo.editor.core.Messages.Launch;
import dev.vexelray.demo.editor.core.Messages.Output;
import dev.vexelray.demo.editor.core.Messages.RunThis;
import dev.vexelray.demo.editor.core.Messages.StopRun;
import dev.vexelray.demo.editor.core.Messages.Typed;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.widget.Tabs;
import sibarum.atchung.Sender;

/**
 * The console's view: what the runs say, shown in their tabs, and what is done in the tabs, told to the runs. On the
 * GUI thread, in the frame, as the main window's {@link View} is.
 *
 * <p>Between the {@code Runner} and the terminals it only translates: a run's {@link Output} is fed to its tab, and
 * what the tab's terminal sends ({@link TerminalScreen.Sent}, a key or its answer to the program's question) and the
 * size it is laid out at ({@link TerminalScreen.Fitted}) go to the run, by the run's id, which the tab is named by.
 */
@dev.vexelray.framework.api.View
public final class ConsoleView {

    private final Console console;
    private final Ui ui;
    private final Sender<Typed> typed;
    private final Sender<Fitted> fitted;
    private final Sender<StopRun> stop;
    private final Sender<RunThis> again;
    private final Sender<WindowCommand> windows;

    public ConsoleView(Console console, Ui ui, Sender<Typed> typed, Sender<Fitted> fitted, Sender<StopRun> stop,
                       Sender<RunThis> again, Sender<WindowCommand> windows) {
        this.console = console;
        this.ui = ui;
        this.typed = typed;
        this.fitted = fitted;
        this.stop = stop;
        this.again = again;
        this.windows = windows;
    }

    /** A run was launched: its tab in front, and the console brought up to show it. */
    @Subscribe
    public void launched(Launch m) {
        console.launched(m.run(), m.offer());
        windows.send(WindowCommand.show(Console.KEY));
    }

    @Subscribe
    public void output(Output m) {
        console.output(m.run(), m.text());
    }

    @Subscribe
    public void ended(Ended m) {
        ui.status(console.ended(m), Console.bad(m));
    }

    /** A key typed into a tab, or the terminal's own answer: to the run, while it is going. */
    @Subscribe
    public void sent(TerminalScreen.Sent m) {
        Console.Entry e = console.byAddress(m.terminal());
        if (e != null && e.running) {
            typed.send(new Typed(e.run, m.text()));
        }
    }

    /** A tab laid out at a new size: its pseudo-terminal is told, so the program lays its output out to it. */
    @Subscribe
    public void fitted(TerminalScreen.Fitted m) {
        Console.Entry e = console.byAddress(m.terminal());
        if (e != null && e.running) {
            fitted.send(new Fitted(e.run, m.cols(), m.rows()));
        }
    }

    /** A tab's menu: Stop, Run again, Copy output, Close. */
    @Subscribe
    public void chosen(Tabs.Chosen m) {
        if (!m.at().equals(console.address()) || !(m.item() instanceof Integer index)) {
            return;
        }
        Console.Entry e = console.at(index);
        if (e == null) {
            return;
        }
        switch (m.action()) {
            case Console.STOP -> {
                if (e.running) {
                    stop.send(new StopRun(e.run));
                }
            }
            case Console.AGAIN -> {
                if (e.offer != null) {
                    again.send(new RunThis(e.offer));
                }
            }
            case Console.COPY -> e.screen.copyAll();
            case Console.CLOSE -> {
                if (e.running) {
                    stop.send(new StopRun(e.run));
                }
                console.close(e);
            }
            default -> {
                // another action of another bar's: not this console's
            }
        }
    }
}
