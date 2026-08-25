package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The load policy, held to its word. {@link TextFile} is the gate every path into a document goes through -
 * the open dialog, the folder tree, and the shell - and it is the only part of this application that is pure
 * policy over bytes, so it is the part a plain JUnit run can actually pin down.
 *
 * <p>Each refusal below is a way the editor was reachable from a file the user could plausibly pick.
 */
final class TextFileTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Decode {@code bytes}, failing the test rather than the compiler if the policy refuses them. */
    private static TextFile.Loaded accept(byte[] bytes) {
        try {
            return TextFile.decode(bytes);
        } catch (TextFile.Unsupported e) {
            throw new AssertionError("expected these bytes to load, but: " + e.getMessage(), e);
        }
    }

    private static String refuse(byte[] bytes) {
        return assertThrows(TextFile.Unsupported.class, () -> TextFile.decode(bytes)).getMessage();
    }

    // ---- accepted, and normalized on the way in ----

    @Test
    void plainTextSurvivesUnchanged() {
        TextFile.Loaded loaded = accept(utf8("hello\nworld\n"));
        assertEquals("hello\nworld\n", loaded.text());
        assertFalse(loaded.crlf());
        assertTrue(loaded.notes().isEmpty());
    }

    @Test
    void emptyFileIsAFileLikeAnyOther() {
        assertEquals("", accept(new byte[0]).text());
    }

    @Test
    void crlfIsNormalizedAndRemembered() {
        TextFile.Loaded loaded = accept(utf8("a\r\nb\r\n"));
        assertEquals("a\nb\n", loaded.text());
        assertTrue(loaded.crlf(), "the convention has to survive, because save restores it");
        assertArrayEquals0(utf8("a\r\nb\r\n"), TextFile.encode(loaded.text(), loaded.crlf()));
    }

    @Test
    void bareCarriageReturnsBecomeNewlinesButAreNotCrlf() {
        TextFile.Loaded loaded = accept(utf8("a\rb\r"));
        assertEquals("a\nb\n", loaded.text());
        assertFalse(loaded.crlf(), "an old Mac file is not a Windows one, and must not save as one");
    }

    @Test
    void tabsBecomeSpacesAndSaySo() {
        TextFile.Loaded loaded = accept(utf8("\tx\n"));
        assertEquals("    x\n", loaded.text());
        assertTrue(loaded.notes().contains("tabs converted to spaces"));
    }

    @Test
    void utf8BomIsStripped() {
        byte[] bytes = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        TextFile.Loaded loaded = accept(bytes);
        assertEquals("hi", loaded.text(), "a BOM left in the text would be an invisible first character");
        assertTrue(loaded.notes().contains("UTF-8 BOM removed"));
    }

    @Test
    void utf16IsDecodedBehindItsBomAndSavesAsUtf8() {
        byte[] le = new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0};
        assertEquals("hi", accept(le).text());
        byte[] be = new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'h', 0, 'i'};
        TextFile.Loaded loaded = accept(be);
        assertEquals("hi", loaded.text());
        assertTrue(loaded.notes().contains("converted from UTF-16 (saves as UTF-8)"));
        assertArrayEquals0(utf8("hi"), TextFile.encode(loaded.text(), loaded.crlf()));
    }

    @Test
    void nonAsciiTextIsOrdinaryText() {
        assertEquals("café — 日本語 🚀",
                accept(utf8("café — 日本語 🚀")).text());
    }

    // ---- refused, each for its own reason ----

    @Test
    void nulBytesAreRefused() {
        // The single most common thing in a binary file, and the reason this gate exists.
        assertTrue(refuse(new byte[]{'a', 0, 'b'}).contains("control character"));
    }

    @Test
    void invalidUtf8IsRefused() {
        // Lone continuation bytes: no charset claim can make these text, and a lenient decode would turn
        // them into replacement characters that a later save would write back over the real bytes.
        assertTrue(refuse(new byte[]{(byte) 0x80, (byte) 0x81}).contains("not valid UTF-8"));
    }

    @Test
    void everyByteValueAtOnceIsRefused() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        assertThrows(TextFile.Unsupported.class, () -> TextFile.decode(all));
    }

    @Test
    void truncatedUtf16IsRefusedRatherThanHalfDecoded() {
        // An odd byte count behind a UTF-16 BOM: the last character is half there.
        assertTrue(refuse(new byte[]{(byte) 0xFF, (byte) 0xFE, 'A'}).contains("not valid UTF-16"));
    }

    @Test
    void controlCharactersThatDecodeCleanlyAreStillRefused() {
        // 0x07 is valid UTF-8 and perfectly decodable - which is the point. Charset validity is not
        // evidence of text, so the decode passing is not the end of the question.
        assertTrue(refuse(utf8("ok" + (char) 0x07)).contains("0x07"), "BEL");
        assertTrue(refuse(utf8("ok" + (char) 0x7F)).contains("7F"), "DEL");
        assertTrue(refuse(utf8("ok" + (char) 0x85)).contains("85"), "a C1 control");
    }

    @Test
    void tabNewlineAndCarriageReturnAreNotControlCharacters() {
        assertEquals("    a\nb\n", accept(utf8("\ta\r\nb\r\n")).text());
    }

    // ---- refused for length: the gap that let a file hang the renderer ----

    @Test
    void aLineAtTheLimitIsAccepted() {
        assertEquals(TextFile.MAX_LINE_CHARS, accept(utf8("x".repeat(TextFile.MAX_LINE_CHARS))).text().length());
    }

    @Test
    void aLineOverTheLimitIsRefused() {
        // Valid UTF-8, no control characters, far under MAX_BYTES - it passes every other check here, and
        // the renderer lays a line out whole, so this is the file that used to lock the editor up.
        String message = refuse(utf8("x".repeat(TextFile.MAX_LINE_CHARS + 1)));
        assertTrue(message.contains("line 1"), message);
        assertTrue(message.contains(String.valueOf(TextFile.MAX_LINE_CHARS)), message);
    }

    @Test
    void theOffendingLineIsNamed() {
        String message = refuse(utf8("short\nshort\n" + "x".repeat(TextFile.MAX_LINE_CHARS + 1)));
        assertTrue(message.contains("line 3"), message);
    }

    @Test
    void manyShortLinesAreFineNoMatterHowMany() {
        // Line *count* is not the problem: only visible rows are ever laid out. Guarding it too would
        // refuse perfectly ordinary logs and data dumps.
        assertEquals(400_000, accept(utf8("\n".repeat(400_000))).text().length());
    }

    @Test
    void lengthIsMeasuredAfterCarriageReturnsBecomeNewlines() {
        // A CR-only file is one unbroken run of bytes. Measured before normalization it would be refused
        // outright, though every line in it is short.
        String crOnly = ("x".repeat(1000) + "\r").repeat(500);
        assertEquals(500_500, accept(utf8(crOnly)).text().length());
    }

    @Test
    void lengthIsMeasuredAfterTabsExpand() {
        // A line of tabs is four times as wide as its character count suggests, and it is the expanded
        // width the renderer is handed.
        int tabs = TextFile.MAX_LINE_CHARS / 4 + 1;
        assertThrows(TextFile.Unsupported.class, () -> TextFile.decode(utf8("\t".repeat(tabs))));
    }

    // ---- save ----

    @Test
    void encodeRoundTripsWithoutABom() {
        byte[] out = TextFile.encode("hi\n", false);
        assertArrayEquals0(utf8("hi\n"), out);
        assertFalse(out.length > 2 && (out[0] & 0xFF) == 0xEF, "a BOM must not be reintroduced on save");
    }

    private static void assertArrayEquals0(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8));
    }
}
