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
 *       than tab/newline/CR after decoding - the signature of binary data that happens to decode; files with
 *       a line longer than {@link #MAX_LINE_CHARS}, which the renderer cannot lay out in bounded time.</li>
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
     * A file's editable content plus what {@link #encode} must know to round-trip it.
     *
     * @param text  normalized content ({@code \n} endings, no tabs, no BOM)
     * @param crlf  whether the file used {@code \r\n} line endings, restored on save
     * @param notes human-readable normalization notes ("tabs converted to spaces"), for the status line
     */
    record Loaded(String text, boolean crlf, List<String> notes) {
    }

    static final int MAX_BYTES = 8 * 1024 * 1024;

    /**
     * Longest line the editor will take on, in characters after normalization.
     *
     * <p>A second limit is needed because the two costs are not the same shape. Total size is linear and the
     * widget is comfortable with it: only the visible rows are ever laid out, so 8 MB of ordinary text and
     * 8 M empty lines both come up in a second or two however much is below the fold. A line, though, is laid
     * out whole however little of it shows, and the cost grows with the square of its length.
     *
     * <p>Measured on the editor's own {@code TextField}, one frame, against a ~1.5 s baseline for the same
     * bytes wrapped: 200 k characters on one line costs about 3 s, 250 k about 6 s, 500 k about 13 s, 1 M
     * about 50 s, and the 8 MB {@link #MAX_BYTES} allows does not finish at all. Turning word wrap off does
     * not rescue it — the line then lays out unclipped, one glyph quad per character, and 1 M is an
     * {@code OutOfMemoryError}, which {@code FileActions.loadInto} does not catch because it is an
     * {@code Error}. Either way the window is gone, with every other open tab still in it.
     *
     * <p>So a file can pass every other check here — valid UTF-8, no control characters, comfortably under
     * the size cap — and still take the editor down on the strength of having no newlines in it. That is not
     * an exotic shape: it is a minified bundle, a base64 blob, a one-line JSON dump, and much of what a
     * binary file looks like when it happens to decode.
     *
     * <p>100 k is set below where the curve turns, not at it: the excess over the wrapped baseline is around
     * a second there, and the run-to-run spread at that end is wide enough that the last comfortable point
     * cannot be read off the numbers precisely. The margin is the point — this is the limit that decides
     * whether the window survives, so it is placed where being somewhat wrong about the measurement does not
     * matter.
     *
     * <p>It does refuse real files. Swept over ~34 k files on this machine, 96 were turned away by this rule
     * and every one was generated: minified {@code .js} and {@code .css} bundles, and single-line i18n JSON.
     * Those are precisely the files that cost seconds of frozen window, so the trade is deliberate — but it
     * is a trade, and raising the limit is the knob if a real workflow needs them.
     *
     * <p>Between {@code Highlighter}'s 20 k line limit and this one a file still opens, just without colour.
     * The degradation is staged, and only past here is it a refusal.
     */
    static final int MAX_LINE_CHARS = 100_000;

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
        // Last, and deliberately: this measures the lines the widget will actually be handed. Before the CR
        // normalization above, a CR-only file is one unbroken line and every one of them would be refused;
        // before the tab expansion, a line of tabs is a quarter of its real width.
        rejectLongLines(text);
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

    /**
     * Refuse a file carrying a line the renderer cannot lay out in bounded time - see {@link #MAX_LINE_CHARS}.
     * The message names the line, because on a file with one very long line among short ones that is the only
     * part of the answer the reader cannot get by looking.
     */
    private static void rejectLongLines(String text) throws Unsupported {
        int line = 1;
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            if (end - start > MAX_LINE_CHARS) {
                throw new Unsupported("line " + line + " is " + (end - start)
                        + " characters - the editor opens lines up to " + MAX_LINE_CHARS
                        + " (is this a minified or binary file?)");
            }
            start = end + 1;
            line++;
        }
    }
}
