package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolchainsTest {

    @TempDir
    Path disk;

    /** A JDK at {@code dir}: {@code bin/java} and a release file saying {@code version}. */
    private Path jdk(String dir, String version) throws IOException {
        Path home = disk.resolve(dir);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "");
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Acme\"\nJAVA_VERSION=\"" + version + "\"\n");
        return home.toRealPath();
    }

    /** A Maven at {@code dir}: {@code bin/mvn.cmd} and {@code lib/maven-core-<version>.jar}. */
    private Path maven(String dir, String version) throws IOException {
        Path home = disk.resolve(dir);
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib"));
        Files.writeString(home.resolve("bin").resolve("mvn.cmd"), "");
        Files.writeString(home.resolve("lib").resolve("maven-core-" + version + ".jar"), "");
        return home.toRealPath();
    }

    private static List<Path> homes(List<Tool> tools) {
        return tools.stream().map(Tool::home).toList();
    }

    @Test
    void javaHomeAndItsNeighboursAreFoundNewestFirst() throws IOException {
        Path older = jdk("JDK/jdk-21.0.9", "21.0.9");
        Path named = jdk("JDK/graalvm-jdk-25.0.3", "25.0.3");
        Files.createDirectories(disk.resolve("JDK/not-a-jdk"));
        List<Tool> javas = Toolchains.javas(Map.of("JAVA_HOME", named.toString()), disk.resolve("home"), List.of());
        assertEquals(List.of(named, older), homes(javas), "the one named, then the one beside it");
        assertEquals(new Tool(named, "25.0.3", "Acme"), javas.getFirst());
    }

    @Test
    void theJavaOnThePathAndTheInstallFoldersAreFound() throws IOException {
        Path onPath = jdk("opt/jdk17", "17.0.2");
        Path installed = jdk("Program Files/Java/jdk-11", "11.0.1");
        Path cached = jdk("home/.jdks/corretto-22", "22");
        String path = disk.resolve("elsewhere") + File.pathSeparator + onPath.resolve("bin");
        List<Tool> javas = Toolchains.javas(Map.of("PATH", path), disk.resolve("home"),
                List.of(disk.resolve("Program Files/Java")));
        assertEquals(List.of(cached, onPath, installed), homes(javas));
    }

    @Test
    void mavenHomeTheWrapperCacheAndVersionsAreFound() throws IOException {
        Path named = maven("JDK/apache-maven-3.9.16", "3.9.16");
        Path wrapped = maven("home/.m2/wrapper/dists/apache-maven-3.9.9-bin/abc123/apache-maven-3.9.9", "3.9.9");
        List<Tool> mavens = Toolchains.mavens(Map.of("MAVEN_HOME", named.toString()), disk.resolve("home"),
                List.of());
        assertEquals(List.of(named, wrapped), homes(mavens), "3.9.16 is newer than 3.9.9");
        assertEquals("3.9.16", mavens.getFirst().version());
    }

    @Test
    void nothingThereIsNothingFound() {
        assertTrue(Toolchains.javas(Map.of("JAVA_HOME", disk.resolve("gone").toString()), disk, List.of()).isEmpty());
        assertTrue(Toolchains.mavens(Map.of(), disk, List.of(disk.resolve("nowhere"))).isEmpty());
    }

    @Test
    void aChoiceIsAnsweredAsItIsOnDiskNow() throws IOException {
        Path listed = jdk("JDK/a", "21");
        Path unlisted = jdk("odd/place/b", "17");
        List<Tool> found = new ArrayList<>(List.of(new Tool(listed, "21", "Acme")));

        assertEquals(Optional.of(new Tool(listed, "21", "Acme")),
                FindTools.chosen(listed.toString(), found, Toolchains::java));
        assertEquals(Optional.of(new Tool(unlisted, "17", "Acme")),
                FindTools.chosen(unlisted.toString(), found, Toolchains::java), "there, though looking missed it");
        assertEquals(2, found.size(), "and listed, so the choice can be seen");
        assertEquals(Optional.empty(), FindTools.chosen(disk.resolve("gone").toString(), found, Toolchains::java));
        assertEquals(Optional.empty(), FindTools.chosen("", found, Toolchains::java), "blank is the environment's own");
    }

    @Test
    void versionsCompareByTheirNumbers() {
        assertTrue(Toolchains.compareVersions("25.0.3", "21.0.9") > 0);
        assertTrue(Toolchains.compareVersions("3.9.16", "3.9.9") > 0);
        assertTrue(Toolchains.compareVersions("17", "17.0.1") < 0);
        assertTrue(Toolchains.compareVersions("?", "1") < 0);
    }
}
