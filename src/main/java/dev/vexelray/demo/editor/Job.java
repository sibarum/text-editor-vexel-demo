package dev.vexelray.demo.editor;

import dev.sibarum.suite.tty.ConPty;
import dev.sibarum.suite.tty.Pump;
import dev.sibarum.suite.tty.Terminal;
import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.text.GlyphLayout;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One run in the console: a program started from the Run menu, its output on a {@link TerminalScreen}, and what is
 * left of it once it has ended — the output, still scrollable, selectable and copyable, with the program, its
 * pseudoconsole and its pipes all gone.
 *
 * <p>On Windows the program runs on a pseudoconsole, through {@code cmd.exe}, so it sees a real console: Maven
 * colours its output and draws its progress, and Ctrl+C reaches it as the interrupt. Elsewhere it runs through
 * {@code sh} on pipes, which is a program that sees no terminal but whose output is still shown.
 *
 * <p>While it runs, what is typed goes to it: a program that asks a question can be answered. Ctrl+C copies when
 * something is selected and interrupts otherwise; Ctrl+Shift+C always copies.
 */
final class Job implements AutoCloseable {

    private static final Log LOG = Log.of("editor.run");

    /** How long a program asked to stop with Ctrl+C has before it is ended outright. */
    private static final long STOP_GRACE_MILLIS = 3_000;

    enum State { RUNNING, SUCCEEDED, FAILED, STOPPED }

    final long id;
    final Runs.Run run;
    private final Gui gui;
    private final Terminal term;
    private final TerminalScreen screen;
    private final Runnable changed;
    /** Exactly one of these is the program: the pseudoconsole on Windows, the process elsewhere. */
    private final ConPty pty;
    private final Process process;
    private final OutputStream stdin;
    private final long started = System.nanoTime();
    private volatile long ended;
    private volatile State state = State.RUNNING;
    private volatile int exit;
    /** Whether it was asked to stop, with Ctrl+C or Stop: however it then ends, that is what ended it. */
    private volatile boolean stopping;

    /**
     * Start {@code run} on a terminal of {@code cols} by {@code rows}, and show it on a screen drawn in
     * {@code background} and {@code foreground} ({@code 0xRRGGBB}).
     *
     * @param changed told when the job has ended, and whenever there is something new to draw. Any thread.
     * @throws IOException when the program cannot be started at all
     */
    Job(long id, Runs.Run run, Gui gui, GlyphLayout glyphs, int cols, int rows, float fontPx,
        int background, int foreground, Runnable changed) throws IOException {
        this.id = id;
        this.run = run;
        this.gui = gui;
        this.changed = changed;
        ConPty p = null;
        Process proc = null;
        Terminal.Host host = new Terminal.Host() {
            @Override
            public void reply(String s) {
                send(s);
            }

            @Override
            public void title(String title) {
                // A run is named for what was run, whatever the program calls itself.
            }
        };
        this.term = new Terminal(cols, rows, host);
        // What is about to run, and where, as the first line: what the console shows can be run again by hand.
        term.feed("\u001b[2m> " + run.line() + "\r\n  in " + run.dir() + "\u001b[0m\r\n\r\n");
        if (Runs.WINDOWS) {
            try {
                // /s and the outer quotes: cmd takes the line between them as it is, quotes and all. The cursor is
                // inherited, or the pseudoconsole would clear the screen and take the line above with it.
                p = ConPty.start("cmd.exe /d /s /c \"" + run.line() + "\"", cols, rows, run.dir().toString(), true);
            } catch (RuntimeException e) {
                throw new IOException(e.getMessage(), e);
            }
        } else {
            proc = new ProcessBuilder("/bin/sh", "-c", run.line())
                    .directory(run.dir().toFile()).redirectErrorStream(true).start();
        }
        this.pty = p;
        this.process = proc;
        this.stdin = proc == null ? null : proc.getOutputStream();
        this.screen = new TerminalScreen(gui, glyphs, Type.MONO, term, fontPx, changed)
                .colors(background, foreground);
        if (pty != null) {
            ConPty console = pty;
            screen.onFit(console::resize);
        }
        wire();
        Pump.Source source = pty != null ? Pump.of(pty) : lineEnds(Pump.of(proc.getInputStream()));
        Pump.start(source, term, screen::invalidate, this::finished, "run-" + id);
    }

    Node node() {
        return screen.node();
    }

    State state() {
        return state;
    }

    boolean running() {
        return state == State.RUNNING;
    }

    /** When it ended, in {@link System#nanoTime} terms; meaningless while it runs. */
    long endedAt() {
        return ended;
    }

    /** What the tab says: the run's name, and how it is going. */
    String title() {
        return switch (state) {
            case RUNNING -> run.label() + " · running";
            case SUCCEEDED -> run.label() + " · done";
            case FAILED -> run.label() + " · exit " + exit;
            case STOPPED -> run.label() + " · stopped";
        };
    }

    void focus() {
        gui.focus(screen.node());
    }

    int cols() {
        return screen.cols();
    }

    int rows() {
        return screen.rows();
    }

    void active(boolean on) {
        screen.active(on);
    }

    void fontPx(float px) {
        screen.fontPx(px);
    }

    /** On the frame loop's thread, once a frame. */
    void tick() {
        screen.tick();
    }

    /** Everything it printed, for the clipboard. */
    void copyAll() {
        screen.copyAll();
    }

    /**
     * Ask the program to stop, as Ctrl+C would, and end it outright if it has not stopped a moment later. A build
     * stopped this way says so, and still cleans up after itself where it can.
     */
    void stop() {
        if (!running()) {
            return;
        }
        stopping = true;
        send("\u0003");
        Thread.ofVirtual().name("run-stop-" + id).start(() -> {
            try {
                Thread.sleep(STOP_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (running()) {
                end();
            }
        });
    }

    /** End the program now, and let the screen go. */
    @Override
    public void close() {
        end();
        screen.close();
    }

    private void end() {
        if (pty != null) {
            pty.close();
        }
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    // -- the end ----------------------------------------------------------------------------------------------

    /** The output has ended: the program is gone. Its exit code and time go under its output, and the console closes. */
    private void finished() {
        int code;
        try {
            code = pty != null ? pty.waitFor() : process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        if (pty != null) {
            pty.close();
        }
        if (stdin != null) {
            try {
                stdin.close();
            } catch (IOException ignored) {
                // nothing is listening any more, which is the point
            }
        }
        long now = System.nanoTime();
        String took = duration(now - started);
        State end = stopping ? State.STOPPED : code == 0 ? State.SUCCEEDED : State.FAILED;
        term.feed("\r\n" + switch (end) {
            case SUCCEEDED -> "\u001b[0;32mFinished\u001b[0m \u001b[2min " + took;
            case STOPPED -> "\u001b[0;33mStopped\u001b[0m \u001b[2mafter " + took;
            default -> "\u001b[0;31mFailed\u001b[0m \u001b[2mwith exit code " + code + " after " + took;
        } + "\u001b[0m\r\n\u001b[?25l");
        exit = code;
        ended = now;
        state = end;
        LOG.info("{} ended with {} after {}", run.line(), code, took);
        screen.invalidate();
        changed.run();
    }

    static String duration(long nanos) {
        double s = nanos / 1e9;
        if (s < 60) {
            return String.format(Locale.ROOT, "%.1f s", s);
        }
        long whole = Math.round(s);
        return whole / 60 + " min " + whole % 60 + " s";
    }

    // -- input ------------------------------------------------------------------------------------------------

    private void wire() {
        Node node = screen.node();
        gui.focusable(node, true);
        gui.onCharUi(node, cp -> {
            if (cp >= 0x20 && cp != 0x7f) {
                type(new String(Character.toChars(cp)));
            }
        });
        gui.onKeyUi(node, this::key);
        gui.claimUi(node, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> type("\t"));
        gui.claimUi(node, Shortcut.of(Key.C, Modifier.CONTROL), ClaimScope.FOCUSED, () -> {
            if (screen.hasSelection() || !running()) {
                screen.copySelection();
            } else {
                stopping = true;
                type("\u0003");
            }
        });
        gui.claimUi(node, Shortcut.of(Key.C, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED,
                screen::copySelection);
        gui.claimUi(node, Shortcut.of(Key.V, Modifier.CONTROL), ClaimScope.FOCUSED, this::paste);
        gui.claimUi(node, Shortcut.of(Key.PAGE_UP, Modifier.SHIFT), ClaimScope.FOCUSED, () -> screen.page(1));
        gui.claimUi(node, Shortcut.of(Key.PAGE_DOWN, Modifier.SHIFT), ClaimScope.FOCUSED, () -> screen.page(-1));
        gui.onContextClick(node, c -> {
            if (screen.hasSelection()) {
                screen.copySelection();
            } else {
                paste();
            }
        });
    }

    private void key(KeyEvent e) {
        boolean ctrl = e.has(Modifier.CONTROL), alt = e.has(Modifier.ALT), shift = e.has(Modifier.SHIFT);
        // Ctrl+Tab and the window's own chords are the console's; letting them through would type them.
        if (ctrl && (e.key() == Key.TAB || e.key() == Key.W)) {
            return;
        }
        String out = screen.keySequence(e.key(), ctrl, alt, shift);
        if (out != null) {
            type(out);
        }
    }

    private void paste() {
        String text = gui.clipboard().get();
        if (text != null && !text.isEmpty()) {
            type(screen.pasteText(text));
        }
    }

    /** What the user typed: to the program, while there is one, seen on the live screen. */
    private void type(String s) {
        if (!running()) {
            return;
        }
        screen.snapToBottom();
        send(s);
    }

    private void send(String s) {
        if (!running()) {
            return;
        }
        if (pty != null) {
            pty.write(s);
        } else if (stdin != null) {
            try {
                // A pipe has no line discipline: Enter is a line end, and Ctrl+C is nothing it can carry.
                stdin.write(s.replace('\r', '\n').getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                LOG.debug("{} took no more input", run.line(), e);
            }
        }
    }

    /** Output from a pipe ends its lines with a bare line feed, where a terminal wants a return before it. */
    private static Pump.Source lineEnds(Pump.Source from) {
        return new Pump.Source() {
            private byte[] raw = new byte[0];

            @Override
            public int read(byte[] buf) throws IOException {
                if (raw.length < buf.length / 2) {
                    raw = new byte[buf.length / 2];
                }
                int n = from.read(raw);
                int o = 0;
                for (int i = 0; i < n; i++) {
                    if (raw[i] == '\n') {
                        buf[o++] = '\r';
                    }
                    buf[o++] = raw[i];
                }
                return n <= 0 ? n : o;
            }
        };
    }
}
