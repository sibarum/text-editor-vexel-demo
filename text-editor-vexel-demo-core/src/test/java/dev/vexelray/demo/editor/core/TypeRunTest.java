package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.RunChecked;
import dev.vexelray.demo.editor.core.Messages.RunTyped;
import dev.vexelray.gui.core.app.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run…'s typing, checked and remembered: what the job answers, and what the next Run… starts from. */
class TypeRunTest {

    @TempDir
    Path dir;

    private Settings settings() {
        return Settings.at(dir.resolve("settings.properties"));
    }

    @Test
    void aGoodCommandIsAnOfferInItsFolder() {
        RunChecked c = new TypeRun(settings()).run(new RunTyped("mvn -v", dir.toString(), "", ""));
        assertEquals("", c.problem());
        assertEquals("mvn -v", c.offer().orElseThrow().line());
        assertEquals(dir.toAbsolutePath().normalize(), c.offer().orElseThrow().dir());
    }

    @Test
    void whatWasTypedIsRememberedEvenWhenItDoesNotRun() {
        Path file = dir.resolve("settings.properties");
        RunChecked c = new TypeRun(Settings.at(file)).run(new RunTyped("make", dir.toString(), "not a setting", "/opt"));
        assertTrue(c.offer().isEmpty());
        assertTrue(c.problem().startsWith("Line 1 "), c.problem());
        Settings again = Settings.at(file);
        assertEquals("make", again.getString(TypeRun.COMMAND, ""));
        assertEquals("not a setting", again.getString(TypeRun.ENVIRONMENT, ""));
        assertEquals("/opt", again.getString(TypeRun.PATH, ""));
    }

    @Test
    void noCommandOrNoFolderRunsNothingAndSaysWhy() {
        TypeRun job = new TypeRun(settings());
        assertEquals("Type a command to run", job.run(new RunTyped("  ", dir.toString(), "", "")).problem());
        RunChecked missing = job.run(new RunTyped("ls", dir.resolve("nowhere").toString(), "", ""));
        assertTrue(missing.offer().isEmpty());
        assertTrue(missing.problem().startsWith("No such folder"), missing.problem());
    }
}
