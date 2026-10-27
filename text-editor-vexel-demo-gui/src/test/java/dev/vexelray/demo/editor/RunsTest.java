package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Run menu offers, from what is on disk. */
class RunsTest {

    private static final String MAIN = "package a.b;\n\npublic class App {\n    public static void main(String[] args) {\n    }\n}\n";

    @TempDir
    Path dir;

    @Test
    void aJavaFileWithAMainRunsItThroughMavenInsideAModule() throws IOException {
        pom(dir, "jar", "");
        Path file = write(dir.resolve("src/main/java/a/b/App.java"), MAIN);
        Runs.Run run = Runs.mainMethod(file, MAIN);
        assertNotNull(run);
        assertEquals("Run App.main()", run.label());
        assertEquals("mvn -q compile exec:java -Dexec.mainClass=a.b.App", run.line());
        assertEquals(dir, run.dir());
    }

    @Test
    void aTestSourceRunsOnTheTestClassPath() throws IOException {
        pom(dir, "jar", "");
        Path file = write(dir.resolve("src/test/java/a/b/App.java"), MAIN);
        assertEquals("mvn -q test-compile exec:java -Dexec.mainClass=a.b.App -Dexec.classpathScope=test",
                Objects.requireNonNull(Runs.mainMethod(file, MAIN)).line());
    }

    @Test
    void outsideMavenTheSourceLauncherRunsTheFile() throws IOException {
        Path file = write(dir.resolve("Hello.java"), "void main() {\n    IO.println(\"hi\");\n}\n");
        Runs.Run run = Runs.mainMethod(file, Files.readString(file));
        assertNotNull(run);
        assertEquals("Run Hello.main()", run.label());
        assertTrue(run.line().startsWith("java "), run.line());
        assertEquals(dir, run.dir());
    }

    @Test
    void noMainNoMainRun() throws IOException {
        Path file = write(dir.resolve("Util.java"), "class Util {\n    // static void main(String[] a) is not here\n}\n");
        assertNull(Runs.mainMethod(file, Files.readString(file)));
        assertNull(Runs.mainMethod(dir.resolve("notes.txt"), MAIN));
    }

    @Test
    void aMavenProjectCompilesInstallsAndPackages() throws IOException {
        pom(dir, "jar", "");
        assertEquals(List.of("Compile", "Clean Install", "Package"), labels(Runs.maven(dir)));
    }

    @Test
    void anAggregatorHasNothingToPackage() throws IOException {
        pom(dir, "pom", "");
        assertEquals(List.of("Compile", "Clean Install"), labels(Runs.maven(dir)));
    }

    @Test
    void theExecPluginIsExecute() throws IOException {
        pom(dir, "jar", "<build><plugins><plugin><artifactId>exec-maven-plugin</artifactId>"
                + "<configuration><executable>java</executable></configuration></plugin></plugins></build>");
        assertTrue(labels(Runs.maven(dir)).contains("Execute"));
    }

    @Test
    void theExecPluginInACommentIsNot() throws IOException {
        pom(dir, "jar", "<!-- <artifactId>exec-maven-plugin</artifactId><executable>java</executable> -->");
        assertTrue(!labels(Runs.maven(dir)).contains("Execute"));
    }

    @Test
    void theNewestRunnableJarInTargetIsTheRecentBuild() throws IOException {
        pom(dir, "jar", "");
        Path target = Files.createDirectories(dir.resolve("target"));
        Path older = jar(target.resolve("app-0.1.jar"), "a.b.App");
        Path newer = jar(target.resolve("app-0.2.jar"), "a.b.App");
        jar(target.resolve("app-0.3-sources.jar"), null);
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000));
        Runs.Run recent = Runs.recentBuild(target);
        assertNotNull(recent);
        assertEquals("Run Recent Build (app-0.2.jar)", recent.label());
        assertTrue(recent.line().startsWith("java -jar "), recent.line());
    }

    @Test
    void aJarThatNamesNoMainClassIsNotABuildToRun() throws IOException {
        Path target = Files.createDirectories(dir.resolve("target"));
        jar(target.resolve("lib.jar"), null);
        assertNull(Runs.recentBuild(target));
    }

    @Test
    void aFolderWithNoPomBuildsTheModuleOfTheFileInFront() throws IOException {
        Path module = Files.createDirectories(dir.resolve("mod"));
        pom(module, "jar", "");
        Path file = write(module.resolve("src/main/java/X.java"), "class X {}");
        assertEquals(module, Runs.mavenRoot(dir, file));
        assertNull(Runs.mavenRoot(dir, null));
    }

    @Test
    void theMenuSeparatesItsGroups() throws IOException {
        pom(dir, "jar", "");
        Path file = write(dir.resolve("src/main/java/a/b/App.java"), MAIN);
        List<Runs.Run> runs = Runs.offered(dir, file, MAIN);
        assertEquals("Run App.main()", runs.get(0).label());
        assertNull(runs.get(1));
        assertEquals("Compile", runs.get(2).label());
        assertTrue(runs.getLast() != null, "no separator at the end");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheProjectsProgramsAreOfferedAndAFakeExecutableIsNot() throws IOException {
        write(dir.resolve("build.ps1"), "Write-Output hi");
        write(dir.resolve("tools/go.cmd"), "@echo hi");
        write(dir.resolve("fake.exe"), "not a program");
        Files.write(dir.resolve("real.exe"), new byte[] {'M', 'Z', 0, 0});
        write(dir.resolve("target/skipped.ps1"), "skipped");
        write(dir.resolve("a/b/c/d/too-deep.ps1"), "too deep");
        List<Runs.Run> programs = Runs.programs(dir, null);
        assertEquals(List.of("build.ps1", "real.exe", "tools/go.cmd"), labels(programs));
        assertTrue(programs.getFirst().line().startsWith("powershell.exe "), programs.getFirst().line());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aScriptInFrontRunsItselfFirst() throws IOException {
        Path script = write(dir.resolve("deploy.ps1"), "Write-Output hi");
        List<Runs.Run> runs = Runs.offered(dir, script, "Write-Output hi");
        assertEquals("Run deploy.ps1", runs.getFirst().label());
        assertEquals(1, runs.size(), "the script in front is not offered twice");
    }

    @Test
    void aDurationReadsInSecondsThenMinutes() {
        assertEquals("1.5 s", Job.duration(1_500_000_000L));
        assertEquals("2 min 5 s", Job.duration(125_000_000_000L));
    }

    // -- fixtures ---------------------------------------------------------------------------------------------

    private static List<String> labels(List<Runs.Run> runs) {
        return runs.stream().map(Runs.Run::label).toList();
    }

    private static void pom(Path at, String packaging, String extra) throws IOException {
        write(at.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                + "<artifactId>a</artifactId><version>1</version><packaging>" + packaging + "</packaging>"
                + extra + "</project>");
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private static Path jar(Path file, String mainClass) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream j = new JarOutputStream(out, m)) {
            j.flush();
        }
        return file;
    }
}
