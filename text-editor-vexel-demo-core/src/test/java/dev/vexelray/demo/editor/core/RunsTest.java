package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Offer;
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
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Run menu offers, from what is on disk. Carried from the 0.1 editor's. */
class RunsTest {

    private static final String MAIN = "package a.b;\n\npublic class App {\n    public static void main(String[] args) {\n    }\n}\n";

    @TempDir
    Path dir;

    @Test
    void aJavaFileWithAMainRunsItThroughMavenInsideAModule() throws IOException {
        pom(dir, "jar", "");
        Path file = write(dir.resolve("src/main/java/a/b/App.java"), MAIN);
        Offer run = Runs.mainMethod(file, MAIN, 0);
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
                Objects.requireNonNull(Runs.mainMethod(file, MAIN, 0)).line());
    }

    @Test
    void outsideMavenTheSourceLauncherRunsTheFile() throws IOException {
        Path file = write(dir.resolve("Hello.java"), "void main() {\n    IO.println(\"hi\");\n}\n");
        Offer run = Runs.mainMethod(file, Files.readString(file), 0);
        assertNotNull(run);
        assertEquals("Run Hello.main()", run.label());
        assertTrue(run.line().startsWith("java "), run.line());
        assertEquals(dir, run.dir());
    }

    @Test
    void noMainNoMainRun() throws IOException {
        Path file = write(dir.resolve("Util.java"), "class Util {\n    // static void main(String[] a) is not here\n}\n");
        assertNull(Runs.mainMethod(file, Files.readString(file), 0));
        assertNull(Runs.mainMethod(dir.resolve("notes.txt"), MAIN, 0));
    }

    @Test
    void aMavenProjectCompilesInstallsAndPackages() throws IOException {
        pom(dir, "jar", "");
        assertEquals(List.of("Compile", "Clean Install", "Package"), labels(Runs.maven(dir, 0)));
    }

    @Test
    void anAggregatorHasNothingToPackage() throws IOException {
        pom(dir, "pom", "");
        assertEquals(List.of("Compile", "Clean Install"), labels(Runs.maven(dir, 0)));
    }

    @Test
    void theExecPluginIsExecute() throws IOException {
        pom(dir, "jar", "<build><plugins><plugin><artifactId>exec-maven-plugin</artifactId>"
                + "<configuration><executable>java</executable></configuration></plugin></plugins></build>");
        assertTrue(labels(Runs.maven(dir, 0)).contains("Execute"));
    }

    @Test
    void theExecPluginInACommentIsNot() throws IOException {
        pom(dir, "jar", "<!-- <artifactId>exec-maven-plugin</artifactId><executable>java</executable> -->");
        assertTrue(!labels(Runs.maven(dir, 0)).contains("Execute"));
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
        Offer recent = Runs.recentBuild(target, 0);
        assertNotNull(recent);
        assertEquals("Run Recent Build (app-0.2.jar)", recent.label());
        assertTrue(recent.line().startsWith("java -jar "), recent.line());
    }

    @Test
    void aJarThatNamesNoMainClassIsNotABuildToRun() throws IOException {
        Path target = Files.createDirectories(dir.resolve("target"));
        jar(target.resolve("lib.jar"), null);
        assertNull(Runs.recentBuild(target, 0));
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
    void theFileInFrontComesFirstAndEachGroupIsNumbered() throws IOException {
        pom(dir, "jar", "");
        Path file = write(dir.resolve("src/main/java/a/b/App.java"), MAIN);
        List<Offer> runs = Runs.offered(dir, file);
        assertEquals("Run App.main()", runs.get(0).label());
        assertEquals("Compile", runs.get(1).label());
        assertTrue(runs.get(1).group() > runs.get(0).group(), "the project's group follows the file's");
    }

    @Test
    void nothingToRunIsAnEmptyMenu() {
        assertEquals(List.of(), Runs.offered(null, null));
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
        List<Offer> programs = Runs.programs(dir, null, 0);
        assertEquals(List.of("build.ps1", "real.exe", "tools/go.cmd"), labels(programs));
        assertTrue(programs.getFirst().line().startsWith("powershell.exe "), programs.getFirst().line());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aScriptInFrontRunsItselfFirst() throws IOException {
        Path script = write(dir.resolve("deploy.ps1"), "Write-Output hi");
        List<Offer> runs = Runs.offered(dir, script);
        assertEquals("Run deploy.ps1", runs.getFirst().label());
        assertEquals(1, runs.size(), "the script in front is not offered twice");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aTypedCommandSetsItsEnvironmentAndPathInTheLineForCmd() {
        Offer run = Runs.typed("  mvn -v ", dir, Runs.environment("JAVA_HOME=C:\\jdk 25\n\n# skipped\nEMPTY="),
                Runs.folders("C:\\tools\n\n  C:\\more bin  \n"));
        assertEquals("set \"JAVA_HOME=C:\\jdk 25\" && set \"EMPTY=\" && set \"PATH=C:\\tools;C:\\more bin;%PATH%\" "
                + "&& mvn -v", run.line());
        assertEquals("mvn -v", run.label());
        assertEquals(dir, run.dir());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aTypedCommandSetsItsEnvironmentAndPathInTheLineForSh() {
        Offer run = Runs.typed("make", dir, Runs.environment("GREETING=hello world"), List.of("/opt/bin"));
        assertEquals("export GREETING='hello world' && export PATH=/opt/bin:\"$PATH\" && make", run.line());
    }

    @Test
    void aTypedCommandAloneIsJustTheCommandAndALongOneIsCutForItsLabel() {
        String longer = "java -jar some/rather/long/path/to/a/program-1.0.0.jar --and --flags";
        Offer run = Runs.typed(longer, dir, Map.of(), List.of());
        assertEquals(longer, run.line());
        assertEquals(40, run.label().length());
        assertTrue(run.label().endsWith("…"), run.label());
    }

    @Test
    void anEnvironmentLineThatIsNotNameEqualsValueIsRefusedByItsNumber() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Runs.environment("A=1\nnot a setting"));
        assertTrue(e.getMessage().startsWith("Line 2 "), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Runs.environment("MY VAR=1"));
        assertEquals("x=y", Runs.environment("A=x=y").get("A"), "only the first = splits");
    }

    // ------------------------------------------------------------------ plumbing

    private static List<String> labels(List<Offer> runs) {
        return runs.stream().map(Offer::label).toList();
    }

    private static void pom(Path at, String packaging, String more) throws IOException {
        write(at.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId>"
                + "<artifactId>b</artifactId><version>1</version><packaging>" + packaging + "</packaging>" + more
                + "</project>");
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private static Path jar(Path file, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        return file;
    }
}
