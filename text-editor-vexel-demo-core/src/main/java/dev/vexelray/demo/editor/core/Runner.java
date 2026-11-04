package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.tty.Pty;
import dev.sibarum.suite.tty.Utf8Chunks;
import dev.vexelray.demo.editor.core.Messages.Ended;
import dev.vexelray.demo.editor.core.Messages.Fitted;
import dev.vexelray.demo.editor.core.Messages.Launch;
import dev.vexelray.demo.editor.core.Messages.Output;
import dev.vexelray.demo.editor.core.Messages.StopRun;
import dev.vexelray.demo.editor.core.Messages.Typed;
import dev.vexelray.framework.api.Destination;
import dev.vexelray.framework.api.Source;
import sibarum.atchung.Sender;

/**
 * What the console runs: one program per run, on a pseudo-terminal of its own, read and written.
 *
 * <h2>A source that is a destination too</h2>
 *
 * <p>A run goes both ways, so it is the framework's source-and-destination part (vexelray-framework's
 * {@code docs/components.md}, section 12), keyed by the run's id: {@link #open} starts the program on a
 * pseudo-terminal, which the framework keeps for that run and hands to each half; {@link #run} is the source half,
 * reading what the program writes and sending it as it goes, and returning how the run ended; {@link #write} is the
 * destination half, the keys typed into the console's tab and the size it is laid out at. {@link #close} ends the
 * program and releases the terminal, once, which is also what ends a read blocked on a program that ignores the
 * interrupt. {@link StopRun} stops the run's key.
 *
 * <p>A run that cannot start is not a throw: whatever escapes {@code open} halts the application, so a failure there
 * is kept on the connection and {@link #run} returns it as the run's {@link Ended}.
 */
@Source(stop = StopRun.class)
@Destination
public final class Runner {

    /** How much of the program's output is read at a time, and so the most one {@link Output} carries. */
    private static final int CHUNK = 16 * 1024;

    private final Sender<Output> output;

    public Runner(Sender<Output> output) {
        this.output = output;
    }

    /** One run's pseudo-terminal, or why there is none. */
    public record Connection(Pty pty, String problem) {
    }

    /** Start the program on a pseudo-terminal of the console's size, in the folder it runs in. */
    public Connection open(Launch launch) {
        try {
            String dir = launch.offer().dir() == null ? null : launch.offer().dir().toString();
            // The cursor inherited: on Windows a pseudoconsole would otherwise clear the screen, and the console's first
            // line (what ran, and where) with it. The console answers the cursor question it asks first.
            return new Connection(Pty.start(Runs.shellLine(launch.offer().line()), launch.cols(), launch.rows(), dir,
                    true),
                    "");
        } catch (RuntimeException e) {
            return new Connection(null, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Read what the program writes and send it, until it exits or the run is stopped; then say how it ended. */
    public Ended run(Launch launch, Connection c) {
        if (c.pty() == null) {
            return new Ended(launch.run(), -1, false, c.problem());
        }
        byte[] buf = new byte[CHUNK];
        Utf8Chunks text = new Utf8Chunks();
        for (int n; (n = c.pty().read(buf)) > 0; ) {
            output.send(new Output(launch.run(), text.decode(buf, n)));
        }
        boolean stopped = Thread.currentThread().isInterrupted();
        return new Ended(launch.run(), stopped ? -1 : c.pty().waitFor(), stopped, "");
    }

    /** A key, a paste, or the terminal's own answer to the program. */
    public void write(Connection c, Typed typed) {
        if (c.pty() != null) {
            c.pty().write(typed.text());
        }
    }

    /** The console's tab was laid out at a new size. */
    public void write(Connection c, Fitted fitted) {
        if (c.pty() != null && fitted.cols() > 0 && fitted.rows() > 0) {
            c.pty().resize(fitted.cols(), fitted.rows());
        }
    }

    public void close(Connection c) {
        if (c.pty() != null) {
            c.pty().close();
        }
    }
}
