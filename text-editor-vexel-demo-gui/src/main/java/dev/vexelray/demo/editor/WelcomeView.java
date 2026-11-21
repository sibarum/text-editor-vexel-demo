package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.OpenProject;
import dev.vexelray.demo.editor.core.Messages.ToolsChosen;
import dev.vexelray.demo.editor.core.Messages.ToolsFound;
import dev.vexelray.demo.editor.core.Messages.ToolsKept;
import dev.vexelray.demo.editor.core.Messages.ToolsWanted;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.Select;
import sibarum.atchung.Sender;

import java.nio.file.Path;

/**
 * Welcome's view: the editor's {@link Messages.Welcome} shown, and the folder the person opens told back as
 * {@link OpenProject}. It decides nothing: which of the session comes back, and what closing Welcome means, are the
 * editor's.
 *
 * <p>Run with is the view's and its jobs', as the navigator's tree is with {@code ListFolder}: it asks
 * {@code FindTools} what there is when Welcome comes up and when Look again is pressed, and a choice goes to
 * {@code KeepTools} as it is made.
 */
@dev.vexelray.framework.api.View
public final class WelcomeView {

    private final Welcome welcome;
    private final Sender<OpenProject> open;
    private final Sender<WindowCommand> windows;
    private final Sender<ToolsWanted> toolsWanted;
    private final Sender<ToolsChosen> toolsChosen;

    public WelcomeView(Welcome welcome, Sender<OpenProject> open, Sender<WindowCommand> windows,
                       Sender<ToolsWanted> toolsWanted, Sender<ToolsChosen> toolsChosen) {
        this.welcome = welcome;
        this.open = open;
        this.windows = windows;
        this.toolsWanted = toolsWanted;
        this.toolsChosen = toolsChosen;
    }

    /** The folders to offer: the window comes up with them, and the tools are looked for. */
    @Subscribe
    public void welcome(Messages.Welcome m) {
        welcome.show(m.recent());
        windows.send(WindowCommand.show(Welcome.KEY));
        toolsWanted.send(new ToolsWanted());
    }

    /** The repositories found so far, as the editor lists them, and how the looking goes. */
    @Subscribe
    public void repos(Messages.Repos m) {
        welcome.repos(m.repositories(), m.note());
    }

    /** The Java runtimes and Mavens on this machine, and the ones chosen. */
    @Subscribe
    public void tools(ToolsFound m) {
        welcome.tools(m);
    }

    /** A Java runtime or a Maven chosen: kept. */
    @Subscribe
    public void committed(Select.Committed m) {
        if (welcome.choosesTool(m.select())) {
            toolsChosen.send(welcome.chosen());
        }
    }

    /** The choice kept, or why not. */
    @Subscribe
    public void kept(ToolsKept m) {
        welcome.kept(m.problem());
    }

    /** Look again: for tools installed since Welcome came up. */
    @Subscribe
    public void pressed(Button.Pressed m) {
        if (welcome.looksAgain(m.button())) {
            toolsWanted.send(new ToolsWanted());
        }
    }

    /**
     * Enter or a double-click: that folder, in the main window. Welcome is hidden rather than closed, since a closed
     * Welcome is a quit while nothing is chosen.
     */
    @Subscribe
    public void activated(ListView.Activated m) {
        if (welcome.opens(m.list()) && m.item() instanceof Path folder) {
            open.send(new OpenProject(folder));
            windows.send(WindowCommand.hide(Welcome.KEY));
        }
    }
}
