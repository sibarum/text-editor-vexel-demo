package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Navigate;
import dev.vexelray.demo.editor.core.Messages.Navigated;
import dev.vexelray.demo.editor.core.Messages.Navigated.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ctrl+Enter in a Pontif document: where it goes, or what it imports, or what it says. */
class NavigateTextTest {

    private static final String BOOKS = "module my.books\nexports @.{total, credit}\n\n"
            + "function total(n:Int):Int -> n + 1\n\nfunction credit(n:Int):Int -> n * 2\n";

    @TempDir
    Path dir;

    private NavigateText job;
    private Path app;

    @BeforeEach
    void setUp() throws IOException {
        job = new NavigateText(new Scratches(dir.resolve("home/scratches")));
        app = Files.createDirectories(dir.resolve("app")).resolve("app.ptf");
    }

    /** Ctrl+Enter with the caret at the {@code nth} occurrence of {@code word} in {@code text}. */
    private Navigated at(String text, String word, int nth) {
        int at = -1;
        for (int i = 0; i <= nth; i++) {
            at = text.indexOf(word, at + 1);
        }
        return job.run(new Navigate("doc", text, 3, at, app));
    }

    @Test
    void aNameDeclaredHereGoesToItsDeclarationHere() {
        String text = "function greet(n:Int):Int -> n + 1\n\ngreet(41)\n";
        Navigated n = at(text, "greet", 1);
        assertEquals(Kind.HERE, n.kind());
        assertEquals(text.indexOf("greet"), n.start());
        assertEquals("greet", text.substring(n.start(), n.end()));
        assertEquals("doc", n.field());
        assertEquals(3, n.version());
    }

    @Test
    void aRequiredNameGoesToTheFileBesideThisOneThatDeclaresIt() throws IOException {
        Path books = Files.writeString(app.resolveSibling("books.ptf"), BOOKS);
        Navigated n = at("requires my.books.{total}\n\ntotal(41)\n", "total", 1);
        assertEquals(Kind.THERE, n.kind(), n.message());
        assertEquals(books, n.file().orElseThrow());
        assertEquals("total", BOOKS.substring(n.start(), n.end()));
        assertEquals(BOOKS.indexOf("function total") + "function ".length(), n.start());
    }

    @Test
    void aNameOneModuleExportsIsImportedFromIt() throws IOException {
        Files.writeString(app.resolveSibling("books.ptf"), BOOKS);
        String text = "total(41)\n";
        Navigated n = at(text, "total", 0);
        assertEquals(Kind.IMPORT, n.kind(), n.message());
        String after = text.substring(0, n.start()) + n.insert() + text.substring(n.end());
        assertEquals("requires my.books.{total}\ntotal(41)\n", after);
        assertTrue(n.message().contains("my.books"), n.message());
    }

    @Test
    void anImportJoinsTheModulesRequiresWhenThereIsOne() throws IOException {
        Files.writeString(app.resolveSibling("books.ptf"), BOOKS);
        String text = "requires my.books.{total}\n\ntotal(credit(1))\n";
        Navigated n = at(text, "credit", 0);
        assertEquals(Kind.IMPORT, n.kind(), n.message());
        String after = text.substring(0, n.start()) + n.insert() + text.substring(n.end());
        assertEquals("requires my.books.{total, credit}\n\ntotal(credit(1))\n", after);
        assertTrue(n.end() - n.start() < text.length(), "only what changes is replaced");
    }

    @Test
    void severalExportersAreNamedAndNothingIsImported() throws IOException {
        Files.writeString(app.resolveSibling("books.ptf"), BOOKS);
        Files.writeString(app.resolveSibling("ledger.ptf"), BOOKS.replace("my.books", "my.ledger"));
        Navigated n = at("total(41)\n", "total", 0);
        assertEquals(Kind.SAY, n.kind());
        assertTrue(n.message().contains("my.books") && n.message().contains("my.ledger"), n.message());
    }

    @Test
    void aBuiltInModulesSourceIsWrittenToTheHomeAndOpenedThere() throws IOException {
        String text = "requires pontif.core.{Stream}\n\n1\n";
        Navigated n = at(text, "Stream", 0);
        assertEquals(Kind.THERE, n.kind(), n.message());
        Path file = n.file().orElseThrow();
        assertEquals(dir.resolve("home/pontif-builtins/pontif.core.ptf"), file);
        assertEquals(NavigateText.builtins(new Scratches(dir.resolve("home/scratches"))), file.getParent());
        assertEquals("Stream", Files.readString(file).substring(n.start(), n.end()));
    }

    @Test
    void aPrimitiveOrNothingFoundOrAnotherLanguageIsOnlySaid() {
        assertEquals(Kind.SAY, at("function f(n:Int):Int -> n\n\nf(1)\n", "Int", 0).kind());
        Navigated nothing = at("nowhere(1)\n", "nowhere", 0);
        assertEquals(Kind.SAY, nothing.kind());
        assertTrue(nothing.message().contains("nowhere"), nothing.message());
        Navigated java = job.run(new Navigate("doc", "class A {}", 1, 7, dir.resolve("A.java")));
        assertEquals(Kind.SAY, java.kind());
        assertEquals(Kind.SAY, job.run(new Navigate("doc", "1 + 1", 1, 2, app)).kind(), "no name at the caret");
    }

    @Test
    void aPlaceInACrlfFileIsCountedAsTheEditorShowsIt() throws IOException {
        Path books = Files.writeString(app.resolveSibling("books.ptf"), BOOKS.replace("\n", "\r\n"));
        Navigated n = at("requires my.books.{total}\n\ntotal(41)\n", "total", 1);
        assertEquals(books, n.file().orElseThrow());
        assertEquals("total", BOOKS.substring(n.start(), n.end()), "counted in the text with \\n line ends");
        assertEquals(4, NavigateText.shown("a\r\nb\r\nc", 6), "c, past two \\r");
    }

    @Test
    void theWordAtTheCaretIncludesACaretRestingAtItsEnd() {
        assertArrayEquals(new int[] {0, 5}, NavigateText.wordAt("greet(1)", 0));
        assertArrayEquals(new int[] {0, 5}, NavigateText.wordAt("greet(1)", 3));
        assertArrayEquals(new int[] {0, 5}, NavigateText.wordAt("greet(1)", 5));
        assertNull(NavigateText.wordAt("greet (1)", 6));
        assertNull(NavigateText.wordAt("(1)", 1), "a number is not a name");
        assertNull(NavigateText.wordAt("", 0));
    }
}
