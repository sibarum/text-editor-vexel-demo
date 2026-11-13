package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Declaration;
import dev.vexelray.demo.editor.core.Messages.IndexWanted;
import dev.vexelray.demo.editor.core.Messages.SymbolsFound;
import dev.vexelray.demo.editor.core.Messages.SymbolsWanted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FindSymbols}: Ctrl+T's answer, from the index {@link BuildIndex} built and kept. */
class FindSymbolsTest {

    @TempDir
    Path dir;

    private Path root;
    private Path source;
    private IndexStore store;
    private FindSymbols job;

    @BeforeEach
    void project() throws Exception {
        root = dir.resolve("whole").toAbsolutePath().normalize();
        source = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>whole</artifactId>
                    <version>1</version>
                </project>
                """);
        Files.writeString(source.resolve("Greeter.java"), """
                package demo;

                public class Greeter {
                    String name;

                    @Deprecated
                    public String greet() {
                        return "hello " + name;
                    }
                }
                """);
        Files.writeString(source.resolve("GreetingCard.java"), "package demo;\n\nrecord GreetingCard(String to) {\n}\n");
        store = new IndexStore(dir.resolve("index"), null);
        job = new FindSymbols(store);
    }

    private SymbolsFound ask(String typed) {
        return job.run(new SymbolsWanted(root.resolve("src"), typed, 7));
    }

    @Test
    void aProjectNeverIndexedSaysSoRatherThanFindingNothing() {
        SymbolsFound m = ask("Greeter");
        assertFalse(m.indexed());
        assertEquals("", m.problem());
        assertEquals(root, m.project());
        assertTrue(m.rows().isEmpty());
    }

    @Test
    void theBestFitComesFirst() {
        new BuildIndex(store).run(new IndexWanted(root));
        SymbolsFound m = ask("greet");
        assertTrue(m.indexed());
        assertEquals(7, m.asked());
        List<String> names = m.rows().stream().map(Declaration::name).toList();
        assertEquals("greet", names.getFirst(), names.toString());
        assertTrue(names.containsAll(List.of("Greeter", "GreetingCard")), names.toString());
    }

    @Test
    void aRowSaysWhatItIsAndWhereItBegins() {
        new BuildIndex(store).run(new IndexWanted(root));
        Declaration greet = ask("greet").rows().getFirst();
        assertEquals("method", greet.kind());
        assertEquals("demo.Greeter", greet.owner());
        assertEquals(source.resolve("Greeter.java"), greet.file());
        assertEquals(6, greet.line(), "the declaration begins at its annotation");
        assertEquals(5, greet.column());
        Declaration card = ask("GreetingCard").rows().getFirst();
        assertEquals("record", card.kind());
        assertEquals("demo", card.owner());
    }

    @Test
    void camelHumpsFindAType() {
        new BuildIndex(store).run(new IndexWanted(root));
        assertEquals("GreetingCard", ask("GC").rows().getFirst().name());
    }

    @Test
    void aSavedIndexIsReadWhenNoneIsHeld() {
        new BuildIndex(store).run(new IndexWanted(root));
        // Another run of the editor: the same folder on disk, nothing in memory.
        FindSymbols fresh = new FindSymbols(new IndexStore(dir.resolve("index"), null));
        SymbolsFound m = fresh.run(new SymbolsWanted(root, "Greeter", 1));
        assertTrue(m.indexed());
        assertEquals("Greeter", m.rows().getFirst().name());
    }

    @Test
    void anIndexBuiltAgainIsWhatIsSearched() throws Exception {
        new BuildIndex(store).run(new IndexWanted(root));
        assertTrue(ask("Farewell").rows().isEmpty());
        Files.writeString(source.resolve("Farewell.java"), "package demo;\n\nclass Farewell {\n}\n");
        new BuildIndex(store).run(new IndexWanted(root));
        assertEquals("Farewell", ask("Farewell").rows().getFirst().name());
    }

    @Test
    void manyAreCutAtTheLimitAndCounted() throws Exception {
        StringBuilder many = new StringBuilder("package demo;\n\nclass Many {\n");
        for (int i = 0; i < FindSymbols.LIMIT + 5; i++) {
            many.append("    int field").append(i).append(";\n");
        }
        Files.writeString(source.resolve("Many.java"), many.append("}\n"));
        new BuildIndex(store).run(new IndexWanted(root));
        SymbolsFound m = ask("field");
        assertEquals(FindSymbols.LIMIT, m.rows().size());
        assertEquals(5, m.more());
    }

    @Test
    void theNameIsFoundPastTheAnnotationsBeforeIt() throws Exception {
        new BuildIndex(store).run(new IndexWanted(root));
        String text = Files.readString(source.resolve("Greeter.java"));
        Declaration greet = ask("greet").rows().getFirst();
        int[] at = FindSymbols.nameAt(text, greet);
        assertEquals("greet", text.substring(at[0], at[1]));
        assertEquals(text.indexOf("greet()"), at[0], "the method, not the class whose name it begins");
        Declaration greeter = ask("Greeter").rows().getFirst();
        at = FindSymbols.nameAt(text, greeter);
        assertEquals(text.indexOf("Greeter"), at[0]);
    }

    @Test
    void aNameNoLongerThereLeavesTheCaretAtItsLine() {
        Declaration gone = new Declaration("gone", "method", "demo.A", source.resolve("A.java"), 2, 1);
        int[] at = FindSymbols.nameAt("abc\nxyz\n", gone);
        assertEquals(4, at[0]);
        assertEquals(4, at[1]);
        Declaration past = new Declaration("gone", "method", "demo.A", source.resolve("A.java"), 9, 1);
        assertEquals(8, FindSymbols.nameAt("abc\nxyz\n", past)[0]);
    }

    @Test
    void nothingTypedFindsNothing() {
        new BuildIndex(store).run(new IndexWanted(root));
        SymbolsFound m = ask("  ");
        assertTrue(m.indexed());
        assertTrue(m.rows().isEmpty());
    }

    @Test
    void aFolderInNoProjectSaysSo() throws Exception {
        Path plain = Files.createDirectory(dir.resolve("plain"));
        SymbolsFound m = job.run(new SymbolsWanted(plain, "x", 1));
        assertFalse(m.problem().isEmpty());
    }
}
