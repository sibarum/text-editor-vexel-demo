package dev.vexelray.demo.editor;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.text.TextFaces;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One fixture of {@link LargeFileBench}, in a JVM of its own: open it in the editor's real tree, built headless, and
 * time what a user does to it. Each result goes to stdout as a {@code BENCH key=value} line the moment it is known,
 * so a run that dies or is killed part way still reports how far it got.
 *
 * <p>Measures only; it changes nothing in the editor. Where {@link TextFile} refuses a file, the refusal is reported
 * and the file is opened anyway, normalized the way {@link TextFile#decode} would but without its limits, so the
 * numbers show what the limit is protecting against.
 */
final class LargeFileBenchRun {

    /** Samples per edit operation, after one warm-up; the median is reported. */
    private static final int SAMPLES = 5;
    /** Stop sampling an operation once its samples have taken this long between them. */
    private static final long OP_BUDGET_NANOS = 60_000_000_000L;
    /** Most frames one step may take to settle. */
    private static final int SETTLE_CAP = 30;

    private static final PrintStream OUT = System.out;

    private static Gui gui;
    private static final TextFaces FACES = TextFaces.standard();

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        String step = "start";
        Shell shell = null;
        try {
            step = "build";
            TextEditorAppWiring wiring = new TextEditorAppWiring();
            shell = VexelApplication.tree(wiring, new String[0]);
            gui = shell.gui();
            Workspace ws = wiring.ui().workspace();

            // The empty editor, framed until still: the fonts parsed and the code warm, and the heap it starts with.
            step = "warm-up";
            for (int i = 0; i < 3; i++) {
                settle();
            }
            long baseline = retainedHeap();

            step = "verdict";
            TextFile.Loaded loaded = null;
            long t0 = System.nanoTime();
            try {
                loaded = TextFile.load(file);
                report("verdict", "accepted");
            } catch (TextFile.Unsupported e) {
                report("verdict", "refused: " + e.getMessage());
            }

            step = "open";
            if (loaded == null) {
                t0 = System.nanoTime();
                loaded = unlimited(Files.readAllBytes(file));
            }
            long decoded = System.nanoTime();
            String text = loaded.text();
            report("chars", text.length());
            report("lines", lines(text));
            report("longest", longest(text));
            Buffer b = ws.add(file.toAbsolutePath().normalize(), loaded, true);
            loaded = null;
            long added = System.nanoTime();
            report("open.read", ms(decoded - t0));
            report("open.add", ms(added - decoded));
            report("open", ms(added - t0));

            step = "first frame";
            long f0 = System.nanoTime();
            int frames = settle();
            report("first", ms(System.nanoTime() - f0));
            report("first.frames", frames);

            step = "heap";
            report("heap", mb(retainedHeap() - baseline));

            step = "caret down";
            sample("down", () -> b.field.caret(nextLine(b.field.text(), b.field.caret())));

            step = "insert";
            sample("insert", () -> b.field.insert("x"));

            step = "scroll to end";
            long s0 = System.nanoTime();
            b.field.caret(b.field.text().length());
            frames = settle();
            report("end", ms(System.nanoTime() - s0));
            report("end.frames", frames);
            report("done", "yes");
        } catch (OutOfMemoryError e) {
            report("fail", "OutOfMemoryError during " + step);
        } catch (Throwable e) {
            report("fail", e.getClass().getSimpleName() + " during " + step + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            OUT.flush();
            // The tree's own threads would keep the JVM; the parent has what it needs.
            Runtime.getRuntime().halt(0);
        }
    }

    /** {@link TextFile#decode}'s normalization without its refusals: UTF-8, line endings to LF, tabs to spaces. */
    private static TextFile.Loaded unlimited(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        boolean crlf = text.contains("\r\n");
        if (text.indexOf('\r') >= 0) {
            text = text.replace("\r\n", "\n").replace('\r', '\n');
        }
        if (text.indexOf('\t') >= 0) {
            text = text.replace("\t", "    ");
        }
        return new TextFile.Loaded(text, crlf, List.of("opened past the editor's limits by the benchmark"));
    }

    /** Frame until the tree has nothing pending, at most {@link #SETTLE_CAP} times; returns how many it took. */
    private static int settle() {
        int n = 0;
        do {
            gui.frame(TextEditor.W, TextEditor.H, FACES);
            n++;
        } while (gui.hasPendingWork() && n < SETTLE_CAP);
        return n;
    }

    /**
     * Time {@code action} plus the frames that show it, once to warm up and then up to {@link #SAMPLES} times;
     * report the median and the worst.
     */
    private static void sample(String key, Runnable action) {
        List<Long> times = new ArrayList<>();
        long spent = 0;
        for (int i = 0; i <= SAMPLES && spent < OP_BUDGET_NANOS; i++) {
            long t = System.nanoTime();
            action.run();
            settle();
            long took = System.nanoTime() - t;
            spent += took;
            if (i > 0 || spent >= OP_BUDGET_NANOS) {
                times.add(took);
            }
        }
        long[] sorted = times.stream().mapToLong(Long::longValue).sorted().toArray();
        report(key, ms(sorted[sorted.length / 2]));
        report(key + ".max", ms(sorted[sorted.length - 1]));
        report(key + ".n", sorted.length);
    }

    /** Where Down puts the caret: the start of the next line, or 80 characters on along a line with no next one. */
    private static int nextLine(String text, int caret) {
        int nl = text.indexOf('\n', caret);
        return nl >= 0 ? nl + 1 : Math.min(text.length(), caret + 80);
    }

    private static long lines(String text) {
        return text.chars().filter(c -> c == '\n').count() + 1;
    }

    private static int longest(String text) {
        int best = 0;
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            best = Math.max(best, end - start);
            start = end + 1;
        }
        return best;
    }

    private static long retainedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1e6);
    }

    private static String mb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static void report(String key, Object value) {
        OUT.println("BENCH " + key + "=" + String.valueOf(value).replace('\n', ' '));
        OUT.flush();
    }

    private LargeFileBenchRun() {
    }
}
