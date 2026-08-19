package dev.vexelray.demo.editor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Defensive loading and saving of text files: everything that can go wrong between bytes on disk and a
 * {@code String} the editor can safely hold is decided here, before the document is touched.
 *
 * <p>Policy: a file is either loaded <em>normalized</em> or refused with a reason ({@link Unsupported}).
 * <ul>
 *   <li><b>Refused:</b> files over {@link #MAX_BYTES}; files that are not valid UTF-8 / BOM-marked UTF-16
 *       (a wrong-charset decode would silently corrupt on save); files containing control characters other
 *       than tab/newline/CR after decoding - the signature of binary data that happens to decode.</li>
 *   <li><b>Normalized:</b> a leading BOM is stripped; {@code \r\n} / bare {@code \r} become {@code \n}
 *       (the original convention is remembered and restored on save); tabs become spaces (the editor's
 *       document model is soft-tab only and its renderer has no tab stops).</li>
 * </ul>
 */
final class TextFile {

    /** Refusal with a human-readable reason - the caller shows {@link #getMessage()} verbatim. */
    static final class Unsupported extends Exception {
        Unsupported(String reason) {
            super(reason);
        }
    }

    /**
     * A file's editable content plus what {@link #save} must know to round-trip it.
     *
     * @param text  normalized content ({@code \n} endings, no tabs, no BOM)
     * @param crlf  whether the file used {@code \r\n} line endings, restored on save
     * @param notes human-readable normalization notes ("tabs converted to spaces"), for the status line
     */
    record Loaded(String text, boolean crlf, List<String> notes) {
    }

    static final int MAX_BYTES = 8 * 1024 * 1024;

    private TextFile() {
    }

    /** Load and normalize {@code file}, or refuse it with a reason. */
    static Loaded load(Path file) throws IOException, Unsupported {
        long size = Files.size(file);
        if (size > MAX_BYTES) {
            throw new Unsupported("file is " + (size / (1024 * 1024)) + " MB - the editor opens files up to "
                    + (MAX_BYTES / (1024 * 1024)) + " MB");
        }
        return decode(Files.readAllBytes(file));
    }

    /** Decode and normalize raw bytes (separated from I/O so it can be exercised without a filesystem). */
    static Loaded decode(byte[] bytes) throws Unsupported {
        List<String> notes = new ArrayList<>();
        String text = decodeCharset(bytes, notes);
        rejectControlCharacters(text);

        boolean crlf = text.contains("\r\n");
        if (text.indexOf('\r') >= 0) {
            text = text.replace("\r\n", "\n").replace('\r', '\n');
        }
        if (text.indexOf('\t') >= 0) {
            text = text.replace("\t", "    ");
            notes.add("tabs converted to spaces");
        }
        return new Loaded(text, crlf, List.copyOf(notes));
    }

    /** Encode {@code text} for {@code loaded}'s file: restore its line endings, write UTF-8 without BOM. */
    static byte[] encode(String text, boolean crlf) {
        return (crlf ? text.replace("\n", "\r\n") : text).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Strict decode: UTF-8, or UTF-16 behind its BOM. Any malformed sequence refuses the file - a lenient
     * decode (replacement characters) would let a save silently destroy bytes the editor never understood.
     */
    private static String decodeCharset(byte[] bytes, List<String> notes) throws Unsupported {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
            notes.add("UTF-8 BOM removed");
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
            notes.add("converted from UTF-16 (saves as UTF-8)");
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
            notes.add("converted from UTF-16 (saves as UTF-8)");
        }
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new Unsupported("not valid " + (charset == StandardCharsets.UTF_8 ? "UTF-8" : "UTF-16")
                    + " text - is this a binary file?");
        }
    }

    /**
     * Text that decodes but carries control characters (C0 except tab/LF/CR, DEL, C1) is almost always
     * binary data that happened to be charset-valid; editing it as text would corrupt it, so refuse.
     */
    private static void rejectControlCharacters(String text) throws Unsupported {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean c0 = c < 0x20 && c != '\t' && c != '\n' && c != '\r';
            if (c0 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                throw new Unsupported(String.format(
                        "contains a control character (0x%02X) - is this a binary file?", (int) c));
            }
        }
    }
}
