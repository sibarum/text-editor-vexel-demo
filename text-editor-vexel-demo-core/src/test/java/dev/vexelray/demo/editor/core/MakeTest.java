package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Add;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.Template;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The navigator's Add, as the job makes it on the disk, and the questions the menu asks before it offers each. */
class MakeTest {

    @TempDir
    Path dir;

    private final Make make = new Make();

    private Created add(Path folder, Made what, Template template, String name) {
        return make.run(new Add(folder, what, template, name, null));
    }

    @Test
    void aFolderAndAFileOfEachKind() throws Exception {
        Created folder = add(dir, Made.FOLDER, null, "notes");
        assertEquals("", folder.problem());
        assertTrue(Files.isDirectory(dir.resolve("notes")));

        for (Template t : Template.values()) {
            String name = Make.suggested(Made.FILE, t, dir);
            Created file = add(dir, Made.FILE, t, name);
            assertEquals("", file.problem(), t + ": " + file.problem());
            assertTrue(Files.isRegularFile(dir.resolve(name)), t.toString());
        }
        assertEquals("# new" + System.lineSeparator() + System.lineSeparator(),
                Files.readString(dir.resolve("new.md")));
        assertTrue(Files.readString(dir.resolve("script.sh")).startsWith("#!/usr/bin/env bash\n"));
        assertTrue(Files.readString(dir.resolve("script.zsh")).startsWith("#!/usr/bin/env zsh\n"));
        assertEquals("", Files.readString(dir.resolve("new-file")));
    }

    @Test
    void aJavaFileIsAClassNamedForItInThePackageItsFolderIs() throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("app/src/main/java/dev/example/shapes"));
        add(pkg, Made.FILE, Template.JAVA, "Circle.java");
        String nl = System.lineSeparator();
        assertEquals("package dev.example.shapes;" + nl + nl + "public class Circle {" + nl + "}" + nl,
                Files.readString(pkg.resolve("Circle.java")));

        add(dir, Made.FILE, Template.JAVA, "Loose.java");
        assertTrue(Files.readString(dir.resolve("Loose.java")).startsWith("public class Loose {"),
                "outside a source root, no package");
    }

    @Test
    void aNameAlreadyThereIsRefusedAndNothingIsTouched() throws Exception {
        Files.writeString(dir.resolve("taken.txt"), "mine");
        Created c = add(dir, Made.FILE, Template.TEXT, "taken.txt");
        assertFalse(c.problem().isEmpty());
        assertEquals("mine", Files.readString(dir.resolve("taken.txt")));
    }

    @Test
    void aProjectWhereThereIsNoneAndAModuleOfTheOneThereIs() throws Exception {
        assertNull(Make.projectOf(dir), "nothing here yet");
        Created project = add(dir, Made.PROJECT, null, "Shapes App");
        assertEquals("", project.problem());
        Path root = dir.resolve("Shapes App");
        String pom = Files.readString(root.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>shapes-app</artifactId>"), pom);
        assertTrue(Files.isDirectory(root.resolve("src/main/java")) && Files.isDirectory(root.resolve("src/test/java")));
        assertEquals(root.toAbsolutePath().normalize(), Make.projectOf(root.resolve("src/main/java")),
                "a folder inside it is in a project now");

        Created module = add(root, Made.SUBMODULE, null, "geometry");
        assertEquals("", module.problem());
        assertTrue(module.note().contains("not pom"), "a jar project's modules do not build yet: " + module.note());
        String child = Files.readString(root.resolve("geometry/pom.xml"));
        assertTrue(child.contains("<parent>") && child.contains("<artifactId>shapes-app</artifactId>")
                && child.contains("<version>0.1.0-SNAPSHOT</version>") && child.contains("<artifactId>geometry</artifactId>"),
                child);
        assertFalse(child.contains("<relativePath>"), "the parent is the folder above, Maven's default");
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("<module>geometry</module>"));
    }

    @Test
    void aModuleDeeperDownNamesItsParentByPathAndJoinsTheModulesThereAre() throws Exception {
        Path root = Files.createDirectories(dir.resolve("reactor"));
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                    <groupId>g</groupId>
                    <artifactId>reactor</artifactId>
                    <version>2</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>one</module>
                    </modules>
                </project>
                """);
        Path apps = Files.createDirectories(root.resolve("apps"));
        Created c = add(apps, Made.SUBMODULE, null, "two");
        assertEquals("", c.problem());
        assertEquals("a module of reactor", c.note());
        String parent = Files.readString(root.resolve("pom.xml"));
        assertTrue(parent.contains("<module>one</module>") && parent.contains("<module>apps/two</module>"), parent);
        assertTrue(Files.readString(apps.resolve("two/pom.xml")).contains("<relativePath>../../pom.xml</relativePath>"));
    }

    @Test
    void anArchiveHoldsEverythingInTheFolderButItself() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("box"));
        assertFalse(Make.anyFile(folder), "an empty folder has nothing to archive");
        Files.createDirectories(folder.resolve("sub"));
        assertFalse(Make.anyFile(folder), "nor one of folders only");
        Files.writeString(folder.resolve("a.txt"), "a");
        Files.writeString(folder.resolve("sub/b.txt"), "b");
        assertTrue(Make.anyFile(folder));

        Created c = add(folder, Made.ARCHIVE, null, Make.suggested(Made.ARCHIVE, null, folder));
        assertEquals("", c.problem());
        assertEquals("2 files", c.note());
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(folder.resolve("box.zip").toFile())) {
            zip.stream().forEach(e -> names.add(e.getName()));
        }
        assertEquals(List.of("a.txt", "sub/", "sub/b.txt"), names.stream().sorted().toList());
    }

    @Test
    void aLinkPointsWhereItWasAsked() throws Exception {
        if (!Make.links()) {
            return;   // not offered on this platform
        }
        Path target = Files.writeString(dir.resolve("target.txt"), "t");
        Created c = make.run(new Add(dir, Made.SYMLINK, null, "link.txt", target));
        assertEquals("", c.problem());
        assertTrue(Files.isSymbolicLink(dir.resolve("link.txt")));
        assertEquals("t", Files.readString(dir.resolve("link.txt")));
    }

    @Test
    void theModuleLineGoesWhereTheModulesAre() {
        String flat = "<project>\n    <artifactId>a</artifactId>\n</project>\n";
        assertEquals("<project>\n    <artifactId>a</artifactId>\n\n    <modules>\n        <module>m</module>\n"
                + "    </modules>\n</project>\n", Make.withModule(flat, "m"));
    }
}
