package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Tool;
import dev.vexelray.demo.editor.core.Messages.ToolsChosen;
import dev.vexelray.demo.editor.core.Messages.ToolsKept;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.core.app.Settings;

/**
 * The Java runtime and Maven chosen in Welcome, kept in the settings under {@link FindTools#JAVA} and
 * {@link FindTools#MAVEN}: a folder each, or blank for the environment's own. A job, since the settings are a file.
 */
@Job
public final class KeepTools {

    private final Settings settings;

    public KeepTools(Settings settings) {
        this.settings = settings;
    }

    public ToolsKept run(ToolsChosen chosen) {
        settings.putString(FindTools.JAVA, chosen.java().map(Tool::home).map(Object::toString).orElse(""))
                .putString(FindTools.MAVEN, chosen.maven().map(Tool::home).map(Object::toString).orElse(""))
                .save();
        return new ToolsKept(chosen.java(), chosen.maven(), "");
    }
}
