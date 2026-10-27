package dev.vexelray.demo.editor;

import dev.sibarum.suite.git.Numstat;
import dev.sibarum.suite.git.Status;
import dev.vexelray.demo.editor.GitPane.Row;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a status, as the repository sends it, becomes Git mode's two lists. No window. */
class GitPaneTest {

    private static final Path ROOT = Path.of("C:/repo").toAbsolutePath();
    private static final String HASHES = "100644 100644 100644 6178079 6178079 ";

    private static String records(String... records) {
        StringBuilder s = new StringBuilder();
        for (String r : records) {
            s.append(r).append('\0');
        }
        return s.toString();
    }

    @Test
    void eachPathIsListedOnceForEachSideWithThatSidesLetterAndLines() {
        Status s = Status.parse(ROOT, records(
                "# branch.head main",
                "1 MM N... " + HASHES + "src/Ui.java",
                "1 .D N... " + HASHES + "src/Old.java",
                "1 A. N... " + HASHES + "src/New.java",
                "u UU N... 100644 100644 100644 100644 h1 h2 h3 pom.xml",
                "? notes.md",
                "? build/",
                "! target/"));
        Numstat unstaged = Numstat.parse("1\t0\tsrc/Ui.java\0" + "0\t4\tsrc/Old.java\0" + "7\t0\tnotes.md\0");
        Numstat staged = Numstat.parse("4\t1\tsrc/Ui.java\0" + "-\t-\tsrc/New.java\0");

        List<Row>[] sides = GitPane.rows(s, unstaged, staged);
        assertEquals(List.of(
                new Row("src/Ui.java", null, 'M', new Numstat.Lines(1, 0, false), false),
                new Row("src/Old.java", null, 'D', new Numstat.Lines(0, 4, false), false),
                new Row("pom.xml", null, '!', null, false),
                new Row("notes.md", null, 'U', new Numstat.Lines(7, 0, false), false),
                new Row("build/", null, 'U', null, false)), sides[0]);
        assertEquals(List.of(
                new Row("src/Ui.java", null, 'M', new Numstat.Lines(4, 1, false), true),
                new Row("src/New.java", null, 'A', Numstat.BINARY, true)), sides[1]);
    }

    @Test
    void aRowSaysItsNameItsFolderAndItsCounts() {
        Row r = new Row("src/main/Ui.java", null, 'M', new Numstat.Lines(4, 1, false), false);
        assertEquals("Ui.java", r.name());
        assertEquals("src/main", r.folder());
        assertEquals("+4 −1", r.counts());
        Row top = new Row("build/", null, 'U', null, false);
        assertEquals("build/", top.name());
        assertEquals("", top.folder());
        assertEquals("", top.counts());
        assertEquals("bin", new Row("p.png", null, 'A', Numstat.BINARY, true).counts());
        assertEquals("−3", new Row("x", null, 'M', new Numstat.Lines(0, 3, false), false).counts());
    }
}
