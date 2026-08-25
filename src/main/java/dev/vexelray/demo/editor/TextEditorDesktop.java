package dev.vexelray.demo.editor;

import dev.mainframe.gui.desktop.Desktop;

import java.util.List;

/**
 * The editor, the other way round: MainFrame comes up, and the editor is something it opens.
 *
 * <pre>{@code
 * mvn compile exec:exec "-Dapp.mainClass=dev.vexelray.demo.editor.TextEditorDesktop"
 *
 * ~ > apps
 * name      launchable  summary
 * profiles  false       named sets of environment variables and binary directories
 * editor    true        open files in tabs, and point the file tree at a directory
 *
 * ~ > editor                                        # or launch "editor"
 * ~ > edit ./pom.xml
 * ~ > ls | where ext == "java" | first 3 | edit
 * ~ > reveal ./src/main/java
 * }</pre>
 *
 * <h2>Why this is six lines</h2>
 * Everything that is not the editor is somebody else's now. The frame loop, the window memory, the input
 * backend, the clipboard, the dialogs, the title bar, the shell and its profiles are all in
 * {@link Desktop#run}, which is one boot shared by every application built this way. What is left here is the
 * one fact this application has that no other does: which apps are in it.
 *
 * <p>{@link TextEditorApp#main} still exists and still works, and is still the editor as its own program with
 * its own terminal on Ctrl+`. The two arrangements are built out of exactly the same parts — see
 * {@link TextEditorApp.Window} — so neither one is a fork of the other.
 *
 * <p>Settings are shared with the standalone editor on purpose: the same {@code text-editor} settings file, so
 * the file tree comes back pointed where it was left whichever way the editor was started. The editor's own
 * window is remembered under {@code editor} rather than {@code main}, because here the main window is the
 * shell.
 *
 * <p>Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class TextEditorDesktop {

    private TextEditorDesktop() {
    }

    public static void main(String[] args) throws Exception {
        Desktop.run("text-editor", "MainFrame",
                (settings, memory) -> List.of(new Editor(memory)), args);
    }
}
