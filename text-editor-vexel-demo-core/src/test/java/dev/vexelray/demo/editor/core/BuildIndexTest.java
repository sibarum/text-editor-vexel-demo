package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.IndexWanted;
import dev.vexelray.demo.editor.core.Messages.Indexed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BuildIndex}: a Maven project's index, of the whole reactor, kept so a second run rereads nothing. */
class BuildIndexTest {

    @TempDir
    Path dir;

    private Path root;
    private BuildIndex job;
    private IndexStore store;

    @BeforeEach
    void project() throws Exception {
        root = dir.resolve("whole").toAbsolutePath().normalize();
        Path source = Files.createDirectories(root.resolve("lib/src/main/java/demo"));
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>whole</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules><module>lib</module></modules>
                </project>
                """);
        Files.writeString(root.resolve("lib/pom.xml"), """
                <project>
                    <parent><groupId>com.example</groupId><artifactId>whole</artifactId><version>1</version></parent>
                    <artifactId>lib</artifactId>
                </project>
                """);
        Files.writeString(source.resolve("Greeter.java"),
                "package demo;\n\npublic class Greeter {\n    String name;\n}\n");
        // No jar cache: a test leaves the machine's alone.
        store = new IndexStore(dir.resolve("index"), null);
        job = new BuildIndex(store);
    }

    @Test
    void aModuleIsIndexedAsTheReactorItIsIn() {
        Indexed m = job.run(new IndexWanted(root.resolve("lib/src")));
        assertEquals("", m.problem());
        assertEquals(root, m.project());
        assertTrue(m.summary().startsWith("1 types, 0 methods, 1 fields"), m.summary());
        assertEquals(0, m.unreadable());
        assertTrue(Files.isRegularFile(store.savedAt(root)), "kept for the next run");
    }

    @Test
    void theNextRunStartsFromTheOneKept() throws Exception {
        job.run(new IndexWanted(root));
        Files.writeString(root.resolve("lib/src/main/java/demo/Other.java"), "package demo;\n\nclass Other {\n}\n");
        Indexed m = job.run(new IndexWanted(root));
        assertTrue(m.summary().startsWith("2 types"), m.summary());
    }

    @Test
    void aKeptIndexThatDoesNotReadIsBuiltAfresh() throws Exception {
        Files.createDirectories(store.dir());
        Files.writeString(store.savedAt(root), "not an index");
        Indexed m = job.run(new IndexWanted(root));
        assertEquals("", m.problem());
        assertTrue(m.summary().startsWith("1 types"), m.summary());
    }

    @Test
    void aFolderInNoProjectSaysSo() throws Exception {
        Path plain = Files.createDirectory(dir.resolve("plain"));
        Indexed m = job.run(new IndexWanted(plain));
        assertFalse(m.problem().isEmpty());
        assertEquals(plain, m.project());
        assertNull(BuildIndex.root(plain));
    }
}
