package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The name at the caret, and where a line and column are in the text. */
class BufferTest {

    @Test
    void theWordAtTheCaretIsFoundFromInsideItAndJustAfterIt() {
        String text = "x = shape.area (); n = 42;";
        assertEquals(new Buffer.Word("area", 10, 14, true), Buffer.wordAt(text, 12));
        assertEquals(new Buffer.Word("area", 10, 14, true), Buffer.wordAt(text, 14));
        assertEquals(new Buffer.Word("shape", 4, 9, false), Buffer.wordAt(text, 4));
    }

    @Test
    void aNumberAndPunctuationAreNotNames() {
        String text = "n = 42; ";
        assertEquals(null, Buffer.wordAt(text, 5));
        assertEquals(null, Buffer.wordAt(text, 8));
    }

    @Test
    void aLineAndColumnBecomeAnOffsetClampedToTheirLine() {
        String text = "ab\ncdef\ng";
        assertEquals(0, Buffer.offsetOf(text, 1, 1));
        assertEquals(5, Buffer.offsetOf(text, 2, 3));
        assertEquals(7, Buffer.offsetOf(text, 2, 99));
        assertEquals(text.length(), Buffer.offsetOf(text, 9, 1));
    }
}
