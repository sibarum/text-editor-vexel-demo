package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Check;
import dev.vexelray.demo.editor.core.Messages.Checked;
import dev.vexelray.demo.editor.core.Messages.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A document's problems: Pontif compiled as it is typed, anything else unchecked. */
class CheckTextTest {

    private static final CheckText JOB = new CheckText();

    @TempDir
    Path dir;

    @Test
    void aProgramThatCompilesHasNoProblemsAndTheAnswerNamesItsQuestion() {
        Checked checked = JOB.run(new Check("doc-1", "function greet(n:Int):Int -> n + 1\n\ngreet(41)\n", 7,
                dir.resolve("app.ptf")));
        assertEquals("doc-1", checked.field());
        assertEquals(7, checked.version());
        assertEquals(List.of(), checked.problems());
    }

    @Test
    void anUnknownNameIsMarkedWhereItIsUsed() {
        String text = "main ( nonExistentFn(1) )";
        List<Problem> problems = CheckText.problems(text, dir.resolve("app.ptf"));
        assertEquals(1, problems.size(), () -> "got " + problems);
        Problem p = problems.getFirst();
        assertEquals("nonExistentFn", text.substring(p.start(), p.end()));
        assertFalse(p.message().isBlank());
    }

    @Test
    void anUnfinishedProgramIsAProblemNotAThrow() {
        for (String text : List.of("function", "greet(", "match x {", "\"unterminated", "", "(((((((((((")) {
            List<Problem> problems = CheckText.problems(text, dir.resolve("half.ptf"));
            for (Problem p : problems) {
                assertTrue(0 <= p.start() && p.start() <= p.end() && p.end() <= text.length(), text + ": " + p);
            }
        }
    }

    @Test
    void aSiblingModuleIsFoundBesideTheFile() throws IOException {
        Files.writeString(dir.resolve("books.ptf"), "module my.books\nexports @.{total}\n\nfunction total(n:Int):Int -> n + 1\n");
        String text = "requires my.books.{total}\n\ntotal(41)\n";
        assertEquals(List.of(), CheckText.problems(text, dir.resolve("app.ptf")),
                "what the file requires is read from its folder");
        assertFalse(CheckText.problems(text.replace("total(41)", "missing(41)"), dir.resolve("app.ptf")).isEmpty());
    }

    @Test
    void onlyPontifIsChecked() {
        assertEquals(List.of(), CheckText.problems("main ( nonExistentFn(1) )", dir.resolve("notes.txt")));
        assertTrue(CheckText.checks(Path.of("a.ptf")));
        assertFalse(CheckText.checks(Path.of("a.ptfpkg")));
        assertFalse(CheckText.checks(null));
    }
}
