package dev.vexelray.demo.editor.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown read as blocks, for the formatted view ({@link Formatted}): what each block is, its text with the markup
 * taken out, and the ranges of that text that are bold, italic, code or a link.
 *
 * <p>No {@code Gui} here. The framework draws structured text but holds no opinion about markup — its typeset
 * module says so in as many words — so reading the format is the application's, and this is it: a function from
 * text to blocks, tested without a window.
 *
 * <p>It reads the Markdown people write in READMEs, not all of CommonMark: ATX and setext headings, paragraphs with
 * hard breaks, bulleted, numbered and task lists, nested quotes, fenced and indented code, rules and pipe tables.
 * HTML passes through as text, except comments, which are not shown.
 */
public final class Markdown {

    public enum Kind { HEADING, PARAGRAPH, ITEM, CODE, RULE, TABLE }

    public enum Mark { BOLD, ITALIC, CODE, LINK, STRIKE }

    /** {@code [start, end)} of a block's text, carrying {@code mark}. Marks overlap: a bold link is two. */
    public record Style(int start, int end, Mark mark) {
    }

    /** Text with its marks: a paragraph's, a heading's, a table cell's. */
    public record Inline(String text, List<Style> styles) {

        public static final Inline EMPTY = new Inline("", List.of());
    }

    /**
     * One block.
     *
     * @param level  a heading's 1–6; a list item's depth, from 0
     * @param quote  how many quotes it is inside, 0 for none
     * @param marker a list item's bullet or number, as shown; empty otherwise
     * @param body   the text; a code block's is verbatim and unmarked
     * @param rows   a table's rows, header first; empty otherwise
     */
    public record Block(Kind kind, int level, int quote, String marker, Inline body, List<List<Inline>> rows) {

        public Block quoted() {
            return new Block(kind, level, quote + 1, marker, body, rows);
        }
    }

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}(?:(?:\\*[ \\t]*){3,}|(?:-[ \\t]*){3,}|(?:_[ \\t]*){3,})$");
    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})(.*)$");
    private static final Pattern ITEM = Pattern.compile("^([ \\t]*)([-*+]|\\d{1,9}[.)])(?:[ \\t]+(.*))?$");
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])][ \\t]+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}> ?(.*)$");
    private static final Pattern SETEXT = Pattern.compile("^ {0,3}(=+|-+)[ \\t]*$");
    private static final Pattern TABLE_RULE = Pattern.compile("^ *\\|? *:?-+:? *(?:\\| *:?-+:? *)*\\|? *$");

    private Markdown() {
    }

    /** Whether {@code fileName} is Markdown, by its extension. */
    public static boolean handles(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    /** {@code text} as blocks, in reading order. */
    public static List<Block> blocks(String text) {
        return blocks(List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
    }

    private static List<Block> blocks(List<String> lines) {
        List<Block> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                i++;
                continue;
            }
            Matcher m;
            if ((m = FENCE.matcher(line)).matches() && !(m.group(2).charAt(0) == '`' && m.group(3).contains("`"))) {
                String fence = m.group(2);
                int indent = m.group(1).length();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.size() && !closesFence(lines.get(i), fence)) {
                    if (!code.isEmpty()) {
                        code.append('\n');
                    }
                    code.append(stripUpTo(lines.get(i), indent));
                    i++;
                }
                i++;   // the closing fence, or past the end
                out.add(code(code.toString()));
                continue;
            }
            if (indentOf(line) >= 4) {
                StringBuilder code = new StringBuilder();
                int end = i;
                while (end < lines.size() && (lines.get(end).isBlank() || indentOf(lines.get(end)) >= 4)) {
                    end++;
                }
                while (end > i && lines.get(end - 1).isBlank()) {
                    end--;   // blank lines after the block belong to nobody
                }
                for (; i < end; i++) {
                    if (!code.isEmpty()) {
                        code.append('\n');
                    }
                    code.append(stripUpTo(lines.get(i), 4));
                }
                out.add(code(code.toString()));
                continue;
            }
            if ((m = HEADING.matcher(line)).matches()) {
                String title = m.group(2) == null ? "" : m.group(2);
                out.add(new Block(Kind.HEADING, m.group(1).length(), 0, "", inline(title), List.of()));
                i++;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                out.add(new Block(Kind.RULE, 0, 0, "", Inline.EMPTY, List.of()));
                i++;
                continue;
            }
            if (QUOTE.matcher(line).matches()) {
                List<String> inner = new ArrayList<>();
                while (i < lines.size() && (m = QUOTE.matcher(lines.get(i))).matches()) {
                    inner.add(m.group(1));
                    i++;
                }
                for (Block b : blocks(inner)) {
                    out.add(b.quoted());
                }
                continue;
            }
            if (line.trim().startsWith("<!--")) {
                while (i < lines.size() && !lines.get(i).contains("-->")) {
                    i++;
                }
                i++;
                continue;
            }
            if ((m = ITEM.matcher(line)).matches()) {
                i = item(lines, i, m, out);
                continue;
            }
            if (i + 1 < lines.size() && line.contains("|") && TABLE_RULE.matcher(lines.get(i + 1)).matches()
                    && lines.get(i + 1).contains("-")) {
                List<List<Inline>> rows = new ArrayList<>();
                rows.add(cells(line));
                i += 2;
                while (i < lines.size() && !lines.get(i).isBlank() && lines.get(i).contains("|")) {
                    rows.add(cells(lines.get(i)));
                    i++;
                }
                out.add(new Block(Kind.TABLE, 0, 0, "", Inline.EMPTY, rows));
                continue;
            }
            // A paragraph: every line up to a blank one or to the start of something else.
            List<String> para = new ArrayList<>();
            para.add(line);
            i++;
            int heading = 0;
            while (i < lines.size()) {
                String next = lines.get(i);
                if (next.isBlank()) {
                    break;
                }
                if ((m = SETEXT.matcher(next)).matches()) {
                    heading = m.group(1).charAt(0) == '=' ? 1 : 2;
                    i++;
                    break;
                }
                if (startsBlock(next)) {
                    break;
                }
                para.add(next);
                i++;
            }
            out.add(new Block(heading > 0 ? Kind.HEADING : Kind.PARAGRAPH, heading, 0, "", inline(joined(para)),
                    List.of()));
        }
        return out;
    }

    /**
     * A list item at {@code lines[i]}, and the lines that continue it: indented past its marker, or lazily carried
     * on with no indent at all. Returns the index after it. Blocks inside an item other than its text — a nested
     * list, a code block — are read as blocks of their own, so a nested list comes out as the deeper items it is.
     */
    private static int item(List<String> lines, int i, Matcher m, List<Block> out) {
        int depth = indentOf(m.group(1)) / 2;
        String marker = m.group(2);
        String body = m.group(3) == null ? "" : m.group(3);
        Matcher task = TASK.matcher(body);
        if (task.matches()) {
            marker = task.group(1).isBlank() ? "□" : "■";
            body = task.group(2);
        } else if (marker.length() == 1) {
            marker = depth % 2 == 0 ? "•" : "◦";
        } else {
            marker = marker.substring(0, marker.length() - 1) + ".";
        }
        List<String> text = new ArrayList<>();
        text.add(body);
        i++;
        while (i < lines.size()) {
            String next = lines.get(i);
            if (next.isBlank() || ITEM.matcher(next).matches() || (indentOf(next) < 2 && startsBlock(next))) {
                break;
            }
            text.add(next.strip());
            i++;
        }
        out.add(new Block(Kind.ITEM, depth, 0, marker, inline(joined(text)), List.of()));
        return i;
    }

    /** Whether {@code line} starts a block a paragraph does not carry on into. */
    private static boolean startsBlock(String line) {
        return HEADING.matcher(line).matches() || RULE.matcher(line).matches() || QUOTE.matcher(line).matches()
                || FENCE.matcher(line).matches() || ITEM.matcher(line).matches();
    }

    private static boolean closesFence(String line, String fence) {
        String t = line.strip();
        return t.length() >= fence.length() && t.chars().allMatch(c -> c == fence.charAt(0));
    }

    private static Block code(String text) {
        return new Block(Kind.CODE, 0, 0, "", new Inline(text, List.of()), List.of());
    }

    /**
     * Lines of one paragraph as its text: joined with a space, except after a hard break — two trailing spaces or a
     * backslash — which keeps the line end.
     */
    private static String joined(List<String> lines) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            String l = lines.get(k);
            boolean last = k == lines.size() - 1;
            boolean hard = !last && (l.endsWith("  ") || l.endsWith("\\"));
            String t = l.strip();
            if (hard && t.endsWith("\\")) {
                t = t.substring(0, t.length() - 1);
            }
            s.append(t);
            if (!last) {
                s.append(hard ? '\n' : ' ');
            }
        }
        return s.toString();
    }

    private static List<Inline> cells(String row) {
        String t = row.strip();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|") && !t.endsWith("\\|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<Inline> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean code = false;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '\\' && k + 1 < t.length() && t.charAt(k + 1) == '|') {
                cell.append("\\|");
                k++;
            } else if (c == '`') {
                code = !code;
                cell.append(c);
            } else if (c == '|' && !code) {
                cells.add(inline(cell.toString().strip()));
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(inline(cell.toString().strip()));
        return cells;
    }

    private static int indentOf(String line) {
        int n = 0;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == ' ') {
                n++;
            } else if (c == '\t') {
                n += 4 - n % 4;
            } else {
                break;
            }
        }
        return n;
    }

    private static String stripUpTo(String line, int columns) {
        int k = 0;
        int n = 0;
        while (k < line.length() && n < columns && (line.charAt(k) == ' ' || line.charAt(k) == '\t')) {
            n += line.charAt(k) == '\t' ? 4 - n % 4 : 1;
            k++;
        }
        return line.substring(k);
    }

    // ------------------------------------------------------------------ inline

    /**
     * {@code source} with its inline markup taken out, and where that markup was as {@link Style}s over what is
     * left: emphasis with {@code *} and {@code _}, strong with doubled ones, {@code ~~strike~~}, code spans, links
     * and images (an image shows its description), autolinks, and backslash escapes.
     *
     * <p>A delimiter with no partner later in the text is just a character, so {@code 2 * 3} stays {@code 2 * 3}.
     */
    public static Inline inline(String source) {
        StringBuilder out = new StringBuilder(source.length());
        List<Style> styles = new ArrayList<>();
        // Where each open emphasis began in `out`, or -1 when it is not open.
        int[] open = {-1, -1, -1};   // BOLD, ITALIC, STRIKE
        char[] opener = new char[3];
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < n && isPunctuation(source.charAt(i + 1))) {
                out.append(source.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int run = runOf(source, i, '`');
                int close = source.indexOf("`".repeat(run), i + run);
                while (close >= 0 && runOf(source, close, '`') != run) {
                    close = source.indexOf("`".repeat(run), close + runOf(source, close, '`'));
                }
                if (close >= 0) {
                    String code = source.substring(i + run, close);
                    if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank()) {
                        code = code.substring(1, code.length() - 1);
                    }
                    int start = out.length();
                    out.append(code);
                    styles.add(new Style(start, out.length(), Mark.CODE));
                    i = close + run;
                    continue;
                }
                out.append("`".repeat(run));
                i += run;
                continue;
            }
            if (c == '!' && i + 1 < n && source.charAt(i + 1) == '[') {
                int[] link = link(source, i + 1);
                if (link != null) {
                    Inline alt = inline(source.substring(i + 2, link[0]));
                    int start = out.length();
                    out.append(alt.text().isEmpty() ? "image" : alt.text());
                    styles.add(new Style(start, out.length(), Mark.ITALIC));
                    i = link[1];
                    continue;
                }
            }
            if (c == '[') {
                int[] link = link(source, i);
                if (link != null) {
                    Inline label = inline(source.substring(i + 1, link[0]));
                    int start = out.length();
                    out.append(label.text());
                    for (Style s : label.styles()) {
                        styles.add(new Style(s.start() + start, s.end() + start, s.mark()));
                    }
                    styles.add(new Style(start, out.length(), Mark.LINK));
                    i = link[1];
                    continue;
                }
            }
            if (c == '<') {
                int close = source.indexOf('>', i);
                if (close > i) {
                    String inside = source.substring(i + 1, close);
                    if (inside.matches("(?i)(https?|ftp|mailto):[^\\s<>]+|[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) {
                        int start = out.length();
                        out.append(inside);
                        styles.add(new Style(start, out.length(), Mark.LINK));
                        i = close + 1;
                        continue;
                    }
                }
            }
            if (c == '*' || c == '_' || c == '~') {
                int run = runOf(source, i, c);
                if (c == '~' && run != 2) {
                    out.append(source, i, i + run);
                    i += run;
                    continue;
                }
                // An underscore inside a word is part of it: snake_case is not emphasis.
                boolean before = i > 0 && Character.isLetterOrDigit(source.charAt(i - 1));
                boolean after = i + run < n && Character.isLetterOrDigit(source.charAt(i + run));
                if (c == '_' && before && after) {
                    out.append(source, i, i + run);
                    i += run;
                    continue;
                }
                boolean canOpen = i + run < n && !Character.isWhitespace(source.charAt(i + run));
                boolean canClose = i > 0 && !Character.isWhitespace(source.charAt(i - 1));
                int left = run;
                // Strong first, then emphasis: *** is both.
                int[] order = c == '~' ? new int[]{2} : left >= 3 ? new int[]{0, 1} : left == 2 ? new int[]{0} : new int[]{1};
                for (int which : order) {
                    int width = which == 1 ? 1 : 2;
                    if (open[which] >= 0 && opener[which] == c && canClose) {
                        styles.add(new Style(open[which], out.length(), which == 0 ? Mark.BOLD : which == 1 ? Mark.ITALIC : Mark.STRIKE));
                        open[which] = -1;
                        left -= width;
                    } else if (open[which] < 0 && canOpen && closes(source, i + run, c, width)) {
                        open[which] = out.length();
                        opener[which] = c;
                        left -= width;
                    }
                }
                out.append(String.valueOf(c).repeat(Math.max(left, 0)));
                i += run;
                continue;
            }
            out.append(c);
            i++;
        }
        styles.removeIf(s -> s.end() <= s.start());
        return new Inline(out.toString(), List.copyOf(styles));
    }

    /** Whether a run of {@code c} at least {@code width} long, preceded by something other than space, follows {@code from}. */
    private static boolean closes(String s, int from, char c, int width) {
        for (int k = from; k < s.length(); k++) {
            if (s.charAt(k) == c) {
                int run = runOf(s, k, c);
                if (run >= width && !Character.isWhitespace(s.charAt(k - 1))) {
                    return true;
                }
                k += run - 1;
            }
        }
        return false;
    }

    /**
     * A link's {@code [label](target)} starting at the {@code [} at {@code at}: where the label's {@code ]} is and
     * where the whole link ends, or null when this is not one.
     */
    private static int[] link(String s, int at) {
        int depth = 0;
        for (int k = at; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\') {
                k++;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                if (k + 1 < s.length() && s.charAt(k + 1) == '(') {
                    int close = s.indexOf(')', k + 2);
                    return close < 0 ? null : new int[]{k, close + 1};
                }
                if (k + 1 < s.length() && s.charAt(k + 1) == '[') {
                    int close = s.indexOf(']', k + 2);
                    return close < 0 ? null : new int[]{k, close + 1};
                }
                return null;
            }
        }
        return null;
    }

    private static int runOf(String s, int at, char c) {
        int k = at;
        while (k < s.length() && s.charAt(k) == c) {
            k++;
        }
        return k - at;
    }

    private static boolean isPunctuation(char c) {
        return c < 128 && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c);
    }
}
