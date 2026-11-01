package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Create;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.demo.editor.core.Messages.Item;
import dev.vexelray.demo.editor.core.Messages.Made;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a row's menu adds beyond files and folders, made by {@link CreateFile} against a real folder: projects, modules,
 * archives and links. Whatever fails leaves the disk as it was.
 */
class AddTest {

    @TempDir
    Path dir;

    private static final String PARENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>whole</artifactId>
                <version>1.2.0</version>
                <packaging>pom</packaging>
            %s</project>
            """;

    private Created make(Path at, Made what) {
        return new CreateFile().run(new Create(at, what));
    }

    @Test
    void aProjectIsAPomAndTwoSourceRoots() throws Exception {
        Path at = dir.resolve("My Tool");
        Created made = make(at, Made.PROJECT);
        assertEquals("", made.problem());
        String pom = Files.readString(at.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>my-tool</artifactId>"), pom);
        assertTrue(pom.contains("<groupId>dev.example</groupId>"), pom);
        assertTrue(Files.isDirectory(at.resolve("src/main/java")));
        assertTrue(Files.isDirectory(at.resolve("src/test/java")));
        assertFalse(made.note().isEmpty());
    }

    @Test
    void aModuleInheritsTheProjectAroundItAndIsListedInIt() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), PARENT.formatted("    <modules>\n        <module>a</module>\n    </modules>\n"));
        Created made = make(dir.resolve("b"), Made.MODULE);
        assertEquals("", made.problem());
        assertEquals("a module of whole", made.note());

        String child = Files.readString(dir.resolve("b/pom.xml"));
        assertTrue(child.contains("<groupId>com.example</groupId>") && child.contains("<version>1.2.0</version>"), child);
        assertFalse(child.contains("<relativePath>"), "the default relative path is not written: " + child);
        String parent = Files.readString(dir.resolve("pom.xml"));
        assertTrue(parent.contains("        <module>a</module>\n        <module>b</module>\n    </modules>"), parent);
    }

    /** Deeper than one level, and in a parent with no {@code <modules>} and CRLF lines, which it keeps. */
    @Test
    void aDeeperModuleSaysWhereItsParentIsAndAParentGetsModulesInItsOwnLineEndings() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), PARENT.formatted("").replace("\n", "\r\n"));
        Path at = Files.createDirectory(dir.resolve("libs")).resolve("c");
        assertEquals("", make(at, Made.MODULE).problem());
        assertTrue(Files.readString(at.resolve("pom.xml")).contains("<relativePath>../../pom.xml</relativePath>"));
        String parent = Files.readString(dir.resolve("pom.xml"));
        assertTrue(parent.contains("    <modules>\r\n        <module>libs/c</module>\r\n    </modules>\r\n</project>"),
                parent);
    }

    @Test
    void aModuleOfAPomItCannotReadMakesNothing() throws Exception {
        String broken = "<project><artifactId>x</artifactId></project>";
        Files.writeString(dir.resolve("pom.xml"), broken);
        assertFalse(make(dir.resolve("d"), Made.MODULE).problem().isEmpty());
        assertFalse(Files.exists(dir.resolve("d")));
        assertEquals(broken, Files.readString(dir.resolve("pom.xml")));
    }

    @Test
    void aJarParentIsSaidToNeedPomPackaging() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), PARENT.formatted("").replace("pom</packaging>", "jar</packaging>"));
        assertTrue(make(dir.resolve("e"), Made.MODULE).note().contains("not pom"));
    }

    @Test
    void anArchiveHoldsTheFolderAndGoesBesideIt() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("stuff"));
        Files.writeString(folder.resolve("a.txt"), "a");
        Files.createDirectories(folder.resolve("sub/empty"));
        Files.writeString(folder.resolve("sub/b.txt"), "b");
        Path zip = dir.resolve("stuff.zip");

        Created made = new CreateFile().run(new Create(zip, Made.ARCHIVE, Optional.of(folder)));
        assertEquals("", made.problem());
        assertEquals("2 files", made.note());
        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            z.stream().forEach(e -> names.add(e.getName()));
        }
        assertTrue(names.containsAll(List.of("a.txt", "sub/", "sub/b.txt", "sub/empty/")), names.toString());
    }

    @Test
    void anArchiveOfNothingIsNotMade() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("hollow/inside"));
        Path zip = dir.resolve("hollow.zip");
        Created made = new CreateFile().run(new Create(zip, Made.ARCHIVE, Optional.of(folder.getParent())));
        assertTrue(made.problem().startsWith("nothing to archive"), made.problem());
        assertFalse(Files.exists(zip));
    }

    @Test
    void anArchiveInsideItsOwnFolderLeavesItselfOut() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "a");
        Path zip = dir.resolve("self.zip");
        assertEquals("1 file", new CreateFile().run(new Create(zip, Made.ARCHIVE, Optional.of(dir))).note());
    }

    @Test
    void nothingIsMadeOverWhatIsThere() throws Exception {
        Files.createDirectory(dir.resolve("taken"));
        assertFalse(make(dir.resolve("taken"), Made.PROJECT).problem().isEmpty());
        assertFalse(Files.exists(dir.resolve("taken/pom.xml")));
    }

    @Test
    void aLinkPointsAtWhatItWasMadeFrom() throws Exception {
        assumeTrue(CreateFile.links(), "links are offered only where a person can make them");
        Path target = Files.writeString(dir.resolve("real.txt"), "r");
        Created made = new CreateFile().run(new Create(dir.resolve("alias"), Made.LINK, Optional.of(target)));
        assertEquals("", made.problem());
        assertEquals(target, Files.readSymbolicLink(dir.resolve("alias")));
    }

    /** The listing says where a project is around a row, so the row's menu offers a module there and a project elsewhere. */
    @Test
    void aListingSaysWhichFoldersAreInAProject() throws Exception {
        Files.createDirectory(dir.resolve("plain"));
        Path built = Files.createDirectory(dir.resolve("built"));
        Files.writeString(built.resolve("pom.xml"), PARENT.formatted(""));
        Files.createDirectory(built.resolve("inner"));

        List<Item> top = ListFolder.list(dir);
        assertFalse(item(top, "plain").inProject());
        assertTrue(item(top, "built").inProject());
        assertTrue(item(ListFolder.list(built), "inner").inProject());
    }

    private static Item item(List<Item> items, String name) {
        return items.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }
}
