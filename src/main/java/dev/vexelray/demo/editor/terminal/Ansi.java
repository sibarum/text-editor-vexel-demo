package dev.vexelray.demo.editor.terminal;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * MainFrame's colour, translated. Everything MainFrame prints goes through its {@code Renderer}, which paints
 * with exactly seven SGR codes — {@code 0m} reset, {@code 1m} bold, {@code 2m} dim and four colours — so this
 * handles those and <em>discards</em> every other escape sequence rather than letting it reach the atlas as
 * garbage glyphs.
 *
 * <p>Attributes do not nest: a code replaces the one before it and {@code 0m} clears both slots. That matches
 * how the renderer emits them (each helper closes its own span) and keeps the state to two fields.
 */
final class Ansi {

    /** One scrollback line: the text with escapes stripped, and the spans their positions became. */
    record Line(String text, List<Span> spans) {
        static Line plain(String text) {
            return new Line(text, List.of());
        }
    }

    // The atlas has no bold face, so bold is brighter ink — the one honest mapping available.
    static final Color INK = Color.rgb(0xc7d1e0);
    static final Color BRIGHT = Color.rgb(0xeef3fa);
    static final Color DIM = Color.rgb(0x7d8798);
    static final Color RED = Color.rgb(0xff7b72);
    static final Color GREEN = Color.rgb(0x7ee787);
    static final Color YELLOW = Color.rgb(0xe3b341);
    static final Color CYAN = Color.rgb(0x56d4dd);

    private Ansi() {
    }

    /** Split {@code raw} into printable text plus the spans its escape codes asked for. */
    static Line parse(String raw) {
        if (raw.indexOf(0x1b) < 0) {
            return Line.plain(raw);
        }
        StringBuilder text = new StringBuilder(raw.length());
        List<Span> spans = new ArrayList<>();
        Color colour = null;
        boolean bold = false;
        int runStart = 0;

        for (int i = 0; i < raw.length(); ) {
            char c = raw.charAt(i);
            if (c != 0x1b) {
                text.append(c);
                i++;
                continue;
            }
            // An escape closes the run before it, whatever the sequence turns out to mean.
            close(spans, runStart, text.length(), colour, bold);
            int end = skip(raw, i);
            if (i + 1 < raw.length() && raw.charAt(i + 1) == '[' && end <= raw.length() && end > 0
                    && raw.charAt(end - 1) == 'm') {
                String body = raw.substring(i + 2, end - 1);
                for (String code : body.split(";", -1)) {
                    switch (code) {
                        case "", "0" -> {
                            colour = null;
                            bold = false;
                        }
                        case "1" -> bold = true;
                        case "2" -> colour = DIM;
                        case "31" -> colour = RED;
                        case "32" -> colour = GREEN;
                        case "33" -> colour = YELLOW;
                        case "36" -> colour = CYAN;
                        default -> {
                            // A code MainFrame does not emit. Dropping it beats guessing at it.
                        }
                    }
                }
            }
            runStart = text.length();
            i = end;
        }
        close(spans, runStart, text.length(), colour, bold);
        return new Line(text.toString(), List.copyOf(spans));
    }

    private static void close(List<Span> spans, int start, int end, Color colour, boolean bold) {
        if (end <= start) {
            return;
        }
        Color ink = colour != null ? colour : bold ? BRIGHT : null;
        if (ink != null) {
            spans.add(Span.foreground(start, end, ink));
        }
    }

    /** The index just past the escape sequence starting at {@code i}. */
    private static int skip(String raw, int i) {
        int j = i + 1;
        if (j < raw.length() && raw.charAt(j) == '[') {
            j++;
            while (j < raw.length() && raw.charAt(j) >= 0x20 && raw.charAt(j) <= 0x3f) {
                j++;   // parameter and intermediate bytes
            }
            return Math.min(j + 1, raw.length());   // the final byte
        }
        if (j < raw.length() && (raw.charAt(j) == ']' || raw.charAt(j) == 'P')) {
            // OSC / DCS: runs to BEL or ST. Consumed whole; nothing here wants a window title.
            while (j < raw.length() && raw.charAt(j) != 0x07 && raw.charAt(j) != 0x1b) {
                j++;
            }
            return Math.min(j + 1, raw.length());
        }
        return Math.min(j + 1, raw.length());
    }
}
