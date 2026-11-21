package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages;
import dev.vexelray.demo.editor.core.Messages.OpenProject;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.widget.ListView;
import sibarum.atchung.Sender;

import java.nio.file.Path;

/**
 * Welcome's view: the editor's {@link Messages.Welcome} shown, and the folder the person opens told back as
 * {@link OpenProject}. It decides nothing: which of the session comes back, and what closing Welcome means, are the
 * editor's.
 */
@dev.vexelray.framework.api.View
public final class WelcomeView {

    private final Welcome welcome;
    private final Sender<OpenProject> open;
    private final Sender<WindowCommand> windows;

    public WelcomeView(Welcome welcome, Sender<OpenProject> open, Sender<WindowCommand> windows) {
        this.welcome = welcome;
        this.open = open;
        this.windows = windows;
    }

    /** The folders to offer: the window comes up with them. */
    @Subscribe
    public void welcome(Messages.Welcome m) {
        welcome.show(m.recent());
        windows.send(WindowCommand.show(Welcome.KEY));
    }

    /** The repositories found so far, as the editor lists them, and how the looking goes. */
    @Subscribe
    public void repos(Messages.Repos m) {
        welcome.repos(m.repositories(), m.note());
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
