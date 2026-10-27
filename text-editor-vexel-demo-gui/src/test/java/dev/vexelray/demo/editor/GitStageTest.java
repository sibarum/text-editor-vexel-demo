package dev.vexelray.demo.editor;

import dev.sibarum.suite.git.Diff;
import dev.vexelray.demo.editor.GitStage.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a diff becomes the stage's rows, and which line of the file each one opens at. No window. */
class GitStageTest {

    @Test
    void eachHunkIsItsHeaderThenItsLinesEachKnowingWhereItIsInTheFile() {
        Diff d = Diff.parse("""
                @@ -27,4 +27,5 @@ final class Ui {
                 keep
                -old
                -older
                +new
                +newer
                +newest
                 kept
                """);
        assertEquals(List.of(
                new Row(0, null, 0, 0, 27, "@@ -27,4 +27,5 @@ final class Ui {"),
                new Row(1, Diff.Kind.CONTEXT, 27, 27, 27, "keep"),
                new Row(2, Diff.Kind.REMOVED, 28, 0, 28, "old"),
                new Row(3, Diff.Kind.REMOVED, 29, 0, 28, "older"),
                new Row(4, Diff.Kind.ADDED, 0, 28, 28, "new"),
                new Row(5, Diff.Kind.ADDED, 0, 29, 29, "newer"),
                new Row(6, Diff.Kind.ADDED, 0, 30, 30, "newest"),
                new Row(7, Diff.Kind.CONTEXT, 30, 31, 31, "kept")), GitStage.rows(d));
    }

    @Test
    void aRemovedLineAtTheEndOpensWhereTheFileNowEnds() {
        List<Row> rows = GitStage.rows(Diff.parse("@@ -4,2 +4,1 @@\n kept\n-gone\n"));
        assertEquals(5, rows.get(2).at());
        assertEquals("−", rows.get(2).glyph());
    }

    @Test
    void twoHunksStartWhereTheirHeadersSay() {
        List<Row> rows = GitStage.rows(Diff.parse("@@ -1 +1 @@\n-a\n+b\n@@ -40,1 +40,2 @@\n x\n+y\n"));
        assertEquals(40, rows.get(3).at());
        assertTrue(rows.get(3).header());
        assertEquals(41, rows.get(5).at());
    }

    @Test
    void noHunksIsNoRows() {
        assertTrue(GitStage.rows(Diff.none()).isEmpty());
        assertTrue(GitStage.rows(Diff.parse("Binary files differ\n")).isEmpty());
    }
}
