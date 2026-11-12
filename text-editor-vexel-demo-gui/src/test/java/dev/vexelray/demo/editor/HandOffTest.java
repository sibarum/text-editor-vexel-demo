package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.Messages.App;
import dev.vexelray.demo.editor.core.Messages.HandedTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A row's Open in: a suite program where the installer's record says it is, or the file manager on its folder. */
class HandOffTest {

    @TempDir
    Path dir;

    /** Record {@code app} as installed: a program that is surely there and ends at once, the JVM running this test. */
    private void installed(App app) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String id = HandOff.IDS.get(app);
        Files.writeString(dir.resolve(id + ".json"), "{\"commands\": {\"" + id
                + "\": {\"path\": \"" + java.replace("\\", "\\\\") + "\"}}}");
    }

    @Test
    void aProgramNotInstalledIsSaidAndNothingElseIsAsked() {
        List<String> opened = new ArrayList<>();
        for (App app : List.of(App.VEXPLORE, App.MAINFRAME, App.RASTERWARE)) {
            HandedTo h = HandOff.handOff(dir.resolve("a.png"), false, app, Optional.of(dir), opened::add);
            assertEquals(new HandedTo(dir.resolve("a.png"), app, app.title() + " isn't installed"), h);
        }
        assertTrue(opened.isEmpty(), "the desktop's opener does not stand in for a suite program");
    }

    @Test
    void installedIsTheProgramStarted() throws Exception {
        for (App app : List.of(App.VEXPLORE, App.MAINFRAME, App.RASTERWARE)) {
            installed(app);
            assertEquals(new HandedTo(dir, app, null), HandOff.handOff(dir, true, app, Optional.of(dir), f -> false));
        }
    }

    @Test
    void theFileManagerOpensAFolderOrTheFolderAFileIsIn() {
        List<String> opened = new ArrayList<>();
        Path abs = dir.toAbsolutePath().normalize();
        assertEquals(new HandedTo(dir, App.HOST, null), HandOff.handOff(dir, true, App.HOST, Optional.empty(),
                f -> opened.add(f)));
        HandOff.handOff(dir.resolve("a.txt"), false, App.HOST, Optional.empty(), f -> opened.add(f));
        assertEquals(List.of(abs.toString(), abs.toString()), opened);
        assertEquals("this desktop has no file manager to open it in",
                HandOff.handOff(dir, true, App.HOST, Optional.empty(), f -> false).problem());
    }

    @Test
    void eachProgramIsGivenPathsAndNeverConsoleText() {
        Path abs = dir.toAbsolutePath().normalize();
        Path file = abs.resolve("a b&c.txt");
        assertArrayEquals(new String[]{file.toString()}, HandOff.args(App.VEXPLORE, file, false));
        assertArrayEquals(new String[]{abs.toString()}, HandOff.args(App.RASTERWARE, abs, true));
        assertArrayEquals(new String[]{"--dir=" + abs}, HandOff.args(App.MAINFRAME, abs, true));
        assertArrayEquals(new String[]{"--dir=" + abs, "--insert-path=" + file},
                HandOff.args(App.MAINFRAME, file, false));
    }
}
