package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.ProjectFound;
import dev.vexelray.demo.editor.core.Messages.ProjectWanted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which project Reveal in navigator shows: the reactor around a module, a project of its own, or none. */
class FindProjectTest {

    @TempDir
    Path dir;

    private final FindProject job = new FindProject();

    private Path pom(Path folder) throws Exception {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("pom.xml"), "<project/>");
        return folder;
    }

    private Path file(Path at) throws Exception {
        Files.createDirectories(at.getParent());
        return Files.writeString(at, "");
    }

    @Test
    void aModulesFileRevealsInTheParentPomsFolder() throws Exception {
        Path parent = pom(dir.resolve("parent"));
        pom(parent.resolve("core"));
        Path a = file(parent.resolve("core/src/main/java/A.java"));
        assertEquals(new ProjectFound(a, Optional.of(parent)), job.run(new ProjectWanted(a)));
    }

    @Test
    void aProjectOnItsOwnRevealsInItsOwnPomsFolder() throws Exception {
        Path alone = pom(dir.resolve("alone"));
        Path a = file(alone.resolve("src/A.java"));
        assertEquals(new ProjectFound(a, Optional.of(alone)), job.run(new ProjectWanted(a)));
    }

    @Test
    void aFileInNoProjectHasNone() throws Exception {
        Path a = file(dir.resolve("notes/a.md"));
        assertEquals(new ProjectFound(a, Optional.empty()), job.run(new ProjectWanted(a)));
    }
}
