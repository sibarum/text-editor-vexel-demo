package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clues a folder gives about whether it is a project's root, read from a small reactor on disk. */
class SurveyTest {

    @TempDir
    Path dir;

    private Path reactor() throws IOException {
        Path root = Files.createDirectories(dir.resolve("shapes"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId><artifactId>shapes</artifactId><version>1</version><packaging>pom</packaging>
                <modules><module>core</module><module>app</module></modules></project>
                """);
        module(root, "core", """
                package core;
                /** A record, not a class. */
                public record Point(int x, int y) { }
                """, """
                package core;
                @FunctionalInterface
                public interface Shape { double area(); }
                """);
        module(root, "app", """
                package app;
                // class Commented would not count
                public final class App {
                    enum Mode { ON, OFF }
                    @Override public String toString() { return "class Quoted"; }
                }
                """);
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve("README.md"), "# shapes\n");
        return root;
    }

    private static void module(Path root, String name, String... sources) throws IOException {
        Path m = Files.createDirectories(root.resolve(name));
        Files.writeString(m.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <parent><groupId>g</groupId><artifactId>shapes</artifactId><version>1</version></parent>
                <artifactId>%s</artifactId></project>
                """.formatted(name));
        Path src = Files.createDirectories(m.resolve("src/main/java/" + name));
        for (int i = 0; i < sources.length; i++) {
            Files.writeString(src.resolve("F" + i + ".java"), sources[i]);
        }
    }

    @Test
    void anAggregatorShowsItsBuildItsModulesAndTheShapeOfItsCode() throws IOException {
        Survey s = Survey.of(reactor(), () -> true, partial -> { });
        assertEquals("shapes", s.artifact());
        assertTrue(s.aggregator());
        assertEquals(List.of(new Survey.Module("core", 2), new Survey.Module("app", 1)), s.modules());
        assertTrue(s.repository());
        assertNull(s.partOf());
        assertEquals(3, s.javaFiles());
        assertEquals(1, s.classes(), "comments and strings do not declare anything");
        assertEquals(1, s.records());
        assertEquals(1, s.interfaces());
        assertEquals(1, s.enums());
        assertEquals(List.of(new Survey.Count("FunctionalInterface", 1), new Survey.Count("Override", 1)),
                s.annotations());
        assertEquals("Java", s.languages().getFirst().name());
        assertFalse(s.truncated());
    }

    @Test
    void aModuleSaysWhichBuildItIsPartOf() throws IOException {
        Path root = reactor();
        Survey s = Survey.of(root.resolve("core"), () -> true, partial -> { });
        assertEquals("core", s.artifact());
        assertEquals("shapes", s.partOf(), "the bigger build is the likelier root");
        assertEquals(List.of(), s.modules(), "a single jar is the project itself");
        assertFalse(s.repository());
    }

    @Test
    void aPlainFolderHasNoBuild() throws IOException {
        Files.writeString(dir.resolve("notes.md"), "hi");
        Survey s = Survey.of(dir, () -> true, partial -> { });
        assertFalse(s.maven());
        assertEquals(List.of(new Survey.Count("Markdown", 1)), s.languages());
    }

    @Test
    void aSurveyNoLongerWantedStops() throws IOException {
        assertNull(Survey.of(reactor(), () -> false, partial -> { }));
    }

    @Test
    void progressIsTheSurveySoFar() throws IOException {
        Path root = reactor();
        for (int i = 0; i < 400; i++) {
            Files.writeString(root.resolve("n" + i + ".md"), "x");
        }
        List<Survey> seen = new ArrayList<>();
        Survey whole = Survey.of(root, () -> true, seen::add);
        for (Survey partial : seen) {
            assertTrue(partial.files() <= whole.files());
        }
    }
}
