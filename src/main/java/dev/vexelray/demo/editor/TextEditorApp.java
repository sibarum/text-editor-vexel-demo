package dev.vexelray.demo.editor;

import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;

/**
 * The application declaration: {@link TextEditor}'s facts plus {@link AutomationStarter}, the automation sockets, in
 * every build, the release one included.
 *
 * <p>The sockets are off unless the launch asks ({@code --automation}, {@code VEXELRAY_AUTOMATION}) or a person
 * switches them on from the title bar's plug, and loopback-only when they are on, because remote control hands
 * whoever reaches it full control of the application's input. The plug's lamps show each one's port open or not and
 * anything connected now or earlier; {@code --automation.available=none} leaves them out of a run altogether. The
 * processor generates {@code TextEditorAppWiring} from this.
 */
@VexelApp(name = TextEditor.APP, title = TextEditor.TITLE, width = TextEditor.W, height = TextEditor.H,
        icon = "/editor.ico", starters = AutomationStarter.class)
final class TextEditorApp {

    private TextEditorApp() {
    }
}
