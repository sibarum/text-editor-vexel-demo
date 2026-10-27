package dev.vexelray.demo.editor;

import dev.vexelray.demo.editor.core.TextFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the editor copes with large and pathological files: the baseline the large-file work is judged against.
 *
 * <p>Off unless asked for, so {@code mvn test} stays fast. Run it with
 * <pre>mvn -Plarge-file-bench test</pre>
 * It writes its fixtures to {@code target/large-files} (generated, kept between runs, never committed) and its
 * results to {@code target/large-file-bench.md}. Each fixture runs in a JVM of its own ({@link LargeFileBenchRun}),
 * so one that runs out of memory or never finishes costs only its own row.
 *
 * <p>Knobs, as {@code -D} on the mvn command line: {@code bench.only} (comma-separated fixture names),
 * {@code bench.xmx} (the child's heap, default {@value #DEFAULT_XMX}) and {@code bench.timeout} (minutes per
 * fixture, default {@value #DEFAULT_TIMEOUT}) and {@code bench.gpu} ({@code false} skips the frame drawn on the GPU,
 * for a machine without one).
 */
@EnabledIfSystemProperty(named = "bench.large", matches = "true")
class LargeFileBench {

    private static final String DEFAULT_XMX = "8g";
    private static final int DEFAULT_TIMEOUT = 10;

    private static final Path TARGET = Path.of("target");
    private static final Path FIXTURES = TARGET.resolve("large-files");
    private static final Path REPORT = TARGET.resolve("large-file-bench.md");

    private static final long MB = 1024L * 1024L;

    /** A fixture: its file name, and how to write it. */
    private record Fixture(String name, String what, Writer writer) {
    }

    @FunctionalInterface
    private interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private static final List<Fixture> FIXTURE_LIST = List.of(
            new Fixture("source-8mb.java", "ordinary Java source, just under TextFile's 8 MB cap",
                    out -> source(out, 8 * MB - 64 * 1024)),
            new Fixture("log-100mb.log", "log lines, ~110 bytes each", out -> log(out, 100 * MB)),
            new Fixture("log-1gb.log", "log lines, ~110 bytes each", out -> log(out, 1024 * MB)),
            new Fixture("empty-10m-lines.txt", "10,000,000 empty lines", out -> repeat(out, "\n", 10_000_000)),
            new Fixture("one-line-1m.js", "one line of 1,000,000 characters, minified-JS-like",
                    out -> minified(out, 1_000_000)),
            new Fixture("mixed-eol.txt", "alternating CRLF and LF lines, 4 MB", out -> mixedEol(out, 4 * MB)),
            new Fixture("tabs.txt", "tab-indented and tab-separated lines, 4 MB", out -> tabs(out, 4 * MB)));

    @Test
    void measure() throws Exception {
        Files.createDirectories(FIXTURES);
        Set<String> only = Arrays.stream(System.getProperty("bench.only", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        String xmx = System.getProperty("bench.xmx", DEFAULT_XMX);
        int timeout = Integer.getInteger("bench.timeout", DEFAULT_TIMEOUT);

        Map<Fixture, Map<String, String>> results = new LinkedHashMap<>();
        for (Fixture f : FIXTURE_LIST) {
            if (!only.isEmpty() && !only.contains(f.name())) {
                continue;
            }
            Path file = generate(f);
            System.out.println("[bench] " + f.name() + " (" + Files.size(file) / MB + " MB)");
            Map<String, String> r = run(file, xmx, timeout);
            System.out.println("[bench] " + f.name() + " -> " + r);
            results.put(f, r);
        }
        write(results, xmx, timeout);
        assertTrue(Files.exists(REPORT));
    }

    // --- running --------------------------------------------------------------------------------------------

    /** Run one fixture in its own JVM and collect its {@code BENCH} lines; the rest of its output goes to a log. */
    private static Map<String, String> run(Path file, String xmx, int timeoutMinutes) throws Exception {
        Path home = Files.createDirectories(FIXTURES.resolve("home"));
        Path log = FIXTURES.resolve(file.getFileName() + ".run.log");
        List<String> cmd = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Xmx" + xmx,
                // The tree's settings and session in the target folder, not the user's: the fixtures must never
                // become tabs the real editor tries to restore.
                "-Dtext-editor.home=" + home.toAbsolutePath(),
                "-Dbench.gpu=" + System.getProperty("bench.gpu", "true"),
                "-cp", System.getProperty("java.class.path"),
                LargeFileBenchRun.class.getName(),
                file.toAbsolutePath().toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        Map<String, String> r = new LinkedHashMap<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.println(line);
                    if (line.startsWith("BENCH ")) {
                        int eq = line.indexOf('=');
                        synchronized (r) {
                            r.put(line.substring(6, eq), line.substring(eq + 1));
                        }
                    }
                }
            } catch (IOException e) {
                // The process went; what was read is what there is.
            }
        });
        if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            p.destroyForcibly().waitFor();
            reader.join();
            synchronized (r) {
                r.putIfAbsent("fail", "timed out after " + timeoutMinutes + " min");
            }
        } else {
            reader.join();
            synchronized (r) {
                if (!r.containsKey("done") && !r.containsKey("fail")) {
                    r.put("fail", "exited " + p.exitValue() + " without finishing - see " + log.getFileName());
                }
            }
        }
        synchronized (r) {
            return new LinkedHashMap<>(r);
        }
    }

    // --- the report -----------------------------------------------------------------------------------------

    private static void write(Map<Fixture, Map<String, String>> results, String xmx, int timeout) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Large-file benchmark\n\n");
        md.append("Generated by `mvn -Plarge-file-bench test` on ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(".\n\n");
        md.append("- JVM: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.version")).append(", child heap -Xmx").append(xmx)
                .append(", timeout ").append(timeout).append(" min per fixture\n");
        md.append("- OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(", ").append(Runtime.getRuntime().availableProcessors()).append(" processors, ")
                .append(physicalGb()).append(" GB RAM\n");
        md.append("- Viewport ").append(TextEditor.W).append('x').append(TextEditor.H)
                .append(", word wrap on (the editor's default), headless `Gui.frame` with the real `TextFaces`\n\n");

        md.append("All times in ms. An edit's time is the call plus the frames that show it, framed until nothing is ")
                .append("pending; the median of up to 5 samples after a warm-up, worst in brackets. ")
                .append("Heap is what the open file retains after a GC, over the empty editor.\n\n");
        md.append("| Fixture | Size | Lines | Longest line | TextFile | Open | First frame | Caret ↓ | Insert char | ")
                .append("Scroll to end | Heap (MB) |\n");
        md.append("|---|--:|--:|--:|---|--:|--:|--:|--:|--:|--:|\n");
        List<String> notes = new ArrayList<>();
        for (Map.Entry<Fixture, Map<String, String>> e : results.entrySet()) {
            Fixture f = e.getKey();
            Map<String, String> r = e.getValue();
            String verdict = r.getOrDefault("verdict", "?");
            md.append("| `").append(f.name()).append("` | ")
                    .append(size(FIXTURES.resolve(f.name()))).append(" | ")
                    .append(num(r.get("lines"))).append(" | ")
                    .append(num(r.get("longest"))).append(" | ")
                    .append(verdict.startsWith("refused") ? "refused¹" : verdict).append(" | ")
                    .append(cell(r, "open")).append(" | ")
                    .append(cell(r, "first")).append(" | ")
                    .append(timed(r, "down")).append(" | ")
                    .append(timed(r, "insert")).append(" | ")
                    .append(cell(r, "end")).append(" | ")
                    .append(cell(r, "heap")).append(" |\n");
            StringBuilder note = new StringBuilder("- `" + f.name() + "`: " + f.what() + ".");
            if (verdict.startsWith("refused")) {
                note.append(" TextFile ").append(verdict).append("; opened past the limit for the measurement.");
            }
            if (r.containsKey("open.read")) {
                note.append(" Open = read and decode ").append(r.get("open.read")).append(" + tab ")
                        .append(r.get("open.add")).append(".");
            }
            if (r.containsKey("first.frames")) {
                note.append(" First frame took ").append(r.get("first.frames")).append(" frame(s) to settle");
                note.append(r.containsKey("end.frames") ? ", scroll to end " + r.get("end.frames") + "." : ".");
            }
            if (r.containsKey("fail")) {
                note.append(" **Did not finish: ").append(r.get("fail")).append(".**");
            }
            if (r.containsKey("gpu.fail")) {
                note.append(" GPU capture failed: ").append(r.get("gpu.fail")).append(".");
            }
            if (r.containsKey("gpu.empty.fail")) {
                note.append(" Empty editor's GPU capture failed: ").append(r.get("gpu.empty.fail")).append(".");
            }
            notes.add(note.toString());
        }
        md.append("\n¹ Refused by `TextFile`; the reason is in the notes below.\n\n");
        md.append("— means the step was never reached: the run stopped earlier (see the notes).\n\n");

        md.append("## Drawn frame\n\n");
        md.append("The table above stops at layout. Here each step's settled frame is drawn: *Emit* is the CPU half, ")
                .append("the tree emitted into a canvas (glyph quads and all) and copied out as the vertex array, the ")
                .append("same work the window does before every present, timed apart from the layout above. ")
                .append("*Vertices* are what that frame emitted, six per quad. *GPU capture* is one whole frame ")
                .append("drawn on the GPU by `GuiApp.capture` after scroll to end; the empty editor's capture is ")
                .append("beside it. The capture lays the tree out twice itself, so the difference between the two is ")
                .append("about two of the layout frames above plus what drawing the file costs. Times in ms.\n\n");
        md.append("| Fixture | Emit: first frame | Vertices | Emit: caret ↓ | Emit: insert char | ")
                .append("Emit: scroll to end | Vertices | GPU capture | Empty editor |\n");
        md.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        for (Map.Entry<Fixture, Map<String, String>> e : results.entrySet()) {
            Map<String, String> r = e.getValue();
            md.append("| `").append(e.getKey().name()).append("` | ")
                    .append(cell(r, "first.draw")).append(" | ")
                    .append(count(r, "first.vertices")).append(" | ")
                    .append(timed(r, "down.draw")).append(" | ")
                    .append(timed(r, "insert.draw")).append(" | ")
                    .append(cell(r, "end.draw")).append(" | ")
                    .append(count(r, "end.vertices")).append(" | ")
                    .append(gpu(r, "gpu")).append(" | ")
                    .append(gpu(r, "gpu.empty")).append(" |\n");
        }
        md.append('\n');
        md.append("## Notes\n\n").append(String.join("\n", notes)).append('\n');
        md.append("""

                ## Method

                Each fixture runs in a fresh JVM (`LargeFileBenchRun`) that builds the editor's tree headless, as \
                `EditorTreeTest` does, and drives `Gui.frame` itself. *Open* is `TextFile.load` (or, for a refused \
                file, the same normalization without the limits) plus `Workspace.add`. *First frame* frames until \
                nothing is pending. *Caret ↓* moves the caret to the start of the next line (80 characters on, \
                along a line with no next one). *Insert char* inserts one character at the caret. *Scroll to end* \
                puts the caret at the end of the text and frames until the reveal settles. Background work \
                (highlighting, the outline) runs on its own threads and is not in these times, except as it \
                competes for the CPU. Nor is drawing: a headless frame lays out but builds no glyph quads and \
                submits nothing to the GPU, which is why the drawn frame has a table of its own. Its *Emit* is \
                `TreeRenderer.emit` into a fresh `Canvas` with the default canvas shader's looks, run once on each \
                step's settled frame (the window draws every frame, the unsettled ones too, so a step that took \
                two frames to settle would draw twice). Its *GPU capture* is `GuiApp.capture`, which frames twice, \
                settles, emits, makes a Vulkan device of its own, uploads, draws, reads back and writes a PNG \
                (`target/large-files/<fixture>.png`); the device setup is most of the empty editor's figure. \
                `-Dbench.gpu=false` skips it. Fixtures are written to `target/large-files` on first use and \
                reused after.
                """);
        Files.writeString(REPORT, md.toString());
        System.out.println("[bench] wrote " + REPORT.toAbsolutePath());
    }

    private static String cell(Map<String, String> r, String key) {
        String v = r.get(key);
        return v != null ? v : r.containsKey("fail") ? "—" : "?";
    }

    private static String timed(Map<String, String> r, String key) {
        String v = r.get(key);
        if (v == null) {
            return cell(r, key);
        }
        String n = r.get(key + ".n");
        return v + " (" + r.get(key + ".max") + ")" + ("1".equals(n) ? "*" : "");
    }

    private static String count(Map<String, String> r, String key) {
        String v = r.get(key);
        return v != null ? num(v) : cell(r, key);
    }

    /** A GPU capture's time, "failed" if it threw (the reason is in the notes), "off" if it was skipped. */
    private static String gpu(Map<String, String> r, String key) {
        if (r.containsKey(key + ".fail")) {
            return "failed";
        }
        if (r.containsKey(key)) {
            return r.get(key);
        }
        return r.containsKey("done") ? "off" : cell(r, key);
    }

    private static String num(String v) {
        if (v == null) {
            return "—";
        }
        return String.format("%,d", Long.parseLong(v));
    }

    private static String size(Path file) throws IOException {
        long bytes = Files.size(file);
        return bytes >= MB ? String.format("%,d MB", bytes / MB) : String.format("%,d KB", bytes / 1024);
    }

    private static long physicalGb() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return Math.round(os.getTotalMemorySize() / (1024.0 * 1024 * 1024));
        }
        return -1;
    }

    // --- fixtures -------------------------------------------------------------------------------------------

    /** The fixture's file, written if it is not there already (a half-written one is replaced). */
    private static Path generate(Fixture f) throws IOException {
        Path file = FIXTURES.resolve(f.name());
        Path done = FIXTURES.resolve(f.name() + ".done");
        if (Files.exists(file) && Files.exists(done)) {
            return file;
        }
        System.out.println("[bench] generating " + f.name());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
            f.writer().write(out);
        }
        Files.writeString(done, "");
        return file;
    }

    private static void put(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void repeat(OutputStream out, String s, int times) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < times; i++) {
            out.write(b);
        }
    }

    /** Java classes of ordinary shape, numbered so no two are the same text, until {@code bytes}. */
    private static void source(OutputStream out, long bytes) throws IOException {
        long written = 0;
        for (int i = 0; written < bytes; i++) {
            String block = """
                    /**
                     * Holds the state of widget %1$d and the rules for changing it.
                     */
                    final class Widget%1$d implements Comparable<Widget%1$d> {
                        private final String name;
                        private int count = %1$d;

                        Widget%1$d(String name) {
                            this.name = java.util.Objects.requireNonNull(name, "name");
                        }

                        int next(int step) {
                            if (step <= 0) {
                                throw new IllegalArgumentException("step must be positive: " + step);
                            }
                            count += step * 3 + %1$d %% 7;
                            return count;
                        }

                        @Override
                        public int compareTo(Widget%1$d other) {
                            return Integer.compare(count, other.count);
                        }
                    }

                    """.formatted(i);
            byte[] b = block.getBytes(StandardCharsets.UTF_8);
            if (written + b.length > bytes) {
                break;
            }
            out.write(b);
            written += b.length;
        }
    }

    private static final String[] LEVELS = {"INFO ", "DEBUG", "WARN ", "INFO ", "ERROR", "INFO "};
    private static final String[] LOGGERS = {"dev.vexelray.gui.core.Gui", "dev.vexelray.demo.editor.View",
            "sibarum.concordance.index.Indexer", "dev.vexelray.framework.shell.Shell"};

    /** Log lines with a timestamp, level, thread, logger and message, until {@code bytes}. */
    private static void log(OutputStream out, long bytes) throws IOException {
        Random rnd = new Random(42);
        long written = 0;
        long ms = 0;
        StringBuilder sb = new StringBuilder(160);
        while (written < bytes) {
            ms += rnd.nextInt(50);
            sb.setLength(0);
            sb.append("2026-10-15T")
                    .append(String.format("%02d:%02d:%02d.%03d", (ms / 3_600_000) % 24, (ms / 60_000) % 60,
                            (ms / 1000) % 60, ms % 1000))
                    .append("Z ").append(LEVELS[rnd.nextInt(LEVELS.length)])
                    .append(" [worker-").append(rnd.nextInt(16)).append("] ")
                    .append(LOGGERS[rnd.nextInt(LOGGERS.length)])
                    .append(" - request ").append(rnd.nextInt(1_000_000))
                    .append(" handled in ").append(rnd.nextInt(500)).append(" ms\n");
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            out.write(b);
            written += b.length;
        }
    }

    /** One line of minified-looking JavaScript, exactly {@code chars} long and with no newline. */
    private static void minified(OutputStream out, int chars) throws IOException {
        Random rnd = new Random(7);
        StringBuilder sb = new StringBuilder(chars + 64);
        for (int i = 0; sb.length() < chars; i++) {
            char a = (char) ('a' + rnd.nextInt(26));
            char b = (char) ('a' + rnd.nextInt(26));
            switch (i % 4) {
                case 0 -> sb.append("var ").append(a).append(i).append('=').append(rnd.nextInt(1000)).append(';');
                case 1 -> sb.append("function ").append(a).append(b).append("(t,e){return t+e*")
                        .append(rnd.nextInt(10)).append("}");
                case 2 -> sb.append(a).append(".push(\"").append(b).append(i).append("\");");
                default -> sb.append("if(").append(a).append('>').append(rnd.nextInt(100)).append("){")
                        .append(b).append("++}");
            }
        }
        sb.setLength(chars);
        put(out, sb.toString());
    }

    /** Lines ending alternately in CRLF and LF, until {@code bytes}. */
    private static void mixedEol(OutputStream out, long bytes) throws IOException {
        long written = 0;
        for (int i = 0; written < bytes; i++) {
            String line = "line " + i + " of a file whose line endings were never agreed on"
                    + (i % 2 == 0 ? "\r\n" : "\n");
            put(out, line);
            written += line.length();
        }
    }

    /** Tab-indented code-like lines and tab-separated columns, until {@code bytes}. */
    private static void tabs(OutputStream out, long bytes) throws IOException {
        long written = 0;
        for (int i = 0; written < bytes; i++) {
            String line = i % 2 == 0
                    ? "\t".repeat(1 + i % 6) + "value" + i + " = compute(\tx,\ty\t);\n"
                    : i + "\tname" + i + "\t" + (i * 7 % 1000) + "\t\t" + (i % 3 == 0 ? "yes" : "no") + "\n";
            put(out, line);
            written += line.length();
        }
    }
}
