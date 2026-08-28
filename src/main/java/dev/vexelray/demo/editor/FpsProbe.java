package dev.vexelray.demo.editor;

import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the frame loop actually did, against what the kernel said it was allowed to skip.
 *
 * <p>Reports from a daemon thread rather than from the frame hook, because the whole point of render on
 * demand is that there are no frames to report from: a loop asleep on {@code sleepTimeout()} would
 * never reach a reporter driven by {@code sample()}, and the run would look dead instead of idle.
 *
 * <p><b>This owns the one call to {@link Kron#onWork}</b>, and takes the real wake as an argument rather
 * than installing a no-op of its own. A second call site would silently replace the first — a probe
 * constructed after the app's wiring would leave the loop with nothing to wake it, which is a frozen
 * window caused by the instrument measuring it.
 */
final class FpsProbe implements AutoCloseable {

    private static final long REPORT_EVERY_NANOS = 3_000_000_000L;

    private final Kron kron;
    private final long startNanos = System.nanoTime();
    private final Map<String, Long> reasons = new LinkedHashMap<>();
    private final Thread reporter;
    private final Runnable mutateFromWorker;
    private final java.util.concurrent.Executor handlers;

    private volatile long frames;
    private volatile long quiescent;     // could have slept until an event arrived
    private volatile long deferrable;    // could have slept for a stated while
    private volatile long wantedNow;     // something is varying; this frame was earned
    private volatile long sleepableNanos;
    private volatile boolean closed;
    private volatile boolean wakeChecked;
    private volatile boolean mutationChecked;
    private volatile boolean handlerChecked;

    private long prevSampleNanos = -1;
    private long prevBudgetNanos;

    FpsProbe(Kron kron, Runnable wake, Runnable mutateFromWorker,
            java.util.concurrent.Executor handlers) {
        this.kron = kron;
        this.mutateFromWorker = mutateFromWorker;
        this.handlers = handlers;
        kron.onWork(wake);
        this.reporter = Thread.ofPlatform().name("fps-probe").daemon(true).start(this::reportLoop);
    }

    /** Called once per presented frame, immediately after the tick that published the kernel's writes. */
    void sample() {
        long at = System.nanoTime();
        Dur budget = kron.sleepTimeout();

        if (prevSampleNanos >= 0) {
            // How much of the interval we just spent rendering could have been spent parked instead.
            sleepableNanos += Math.min(prevBudgetNanos, at - prevSampleNanos);
        }
        prevSampleNanos = at;
        prevBudgetNanos = budget.nanos();

        frames++;
        if (budget.equals(Dur.FOREVER)) {
            quiescent++;
        } else if (budget.nanos() > 0) {
            deferrable++;
        } else {
            wantedNow++;
            synchronized (reasons) {
                for (String reason : kron.whyBusy()) {
                    reasons.merge(collapse(reason), 1L, Long::sum);
                }
            }
        }
    }

    /** Drop the moment from a whyBusy() line, so repeats of the same cause aggregate. */
    private static String collapse(String reason) {
        int until = reason.indexOf(" until ");
        if (until > 0) {
            return reason.substring(0, until) + " until ...";
        }
        int at = reason.indexOf(" at ");
        return at > 0 ? reason.substring(0, at) : reason;
    }

    private void reportLoop() {
        long lastFrames = 0;
        while (!closed) {
            try {
                Thread.sleep(REPORT_EVERY_NANOS / 1_000_000L);
            } catch (InterruptedException e) {
                return;
            }
            long f = frames;
            double window = REPORT_EVERY_NANOS / 1e9;
            System.out.printf("[t=%5.1fs] %4d frames in %.1fs = %5.1f fps | quiescent %5.1f%% "
                            + "| sleepable %5.1f%%%n",
                    (System.nanoTime() - startNanos) / 1e9, f - lastFrames, window,
                    (f - lastFrames) / window, pct(quiescent, f), sleepablePct());
            lastFrames = f;
            if (!wakeChecked && System.nanoTime() - startNanos > 2 * REPORT_EVERY_NANOS) {
                wakeChecked = true;
                checkWakePath();
            }
            if (!mutationChecked && System.nanoTime() - startNanos > 3 * REPORT_EVERY_NANOS) {
                mutationChecked = true;
                checkMutationPath();
            }
            if (!handlerChecked && System.nanoTime() - startNanos > 4 * REPORT_EVERY_NANOS) {
                handlerChecked = true;
                checkHandlerPath();
            }
        }
    }

    /**
     * One end-to-end check of the wake path, from a thread that is neither the timeline nor the loop.
     *
     * <p>Worth a self-test rather than a unit test because its failure mode is the worst kind: the
     * window simply stops responding, the animation that was started runs perfectly on a kernel nobody
     * is ticking, and nothing anywhere says so. This posts a no-op the way a click handler would and
     * asserts that a frame follows.
     */
    /**
     * The reported bug, reproduced: a worker mutates a node and the pointer never moves.
     *
     * <p>Click handlers run off the GUI thread, so a handler publishes its mutation after the frame that
     * dispatched the click has already drained and presented. A loop that asks only its animation clock
     * is told there is nothing to do and parks with the mutation queued — the screen keeps showing what
     * it showed before, and comes right on the next stray OS event. On a touchpad, where a click carries
     * no movement, there is no stray event.
     */
    /**
     * A click handler that changes nothing this layer can see must still earn a frame.
     *
     * <p>The case the other two checks miss, and the one that produced an indefinite hang: a handler
     * whose whole effect is to drop a request on one of the application's own per-frame queues. Nothing
     * is mutated, no clock is touched, and the request is executed by a drain that only runs if a frame
     * runs. An empty task is therefore the strongest form of the test — if even this wakes the loop, so
     * does every handler, whatever it went on to do.
     */
    private void checkHandlerPath() {
        if (handlers == null) {
            return;
        }
        long before = frames;
        handlers.execute(() -> { });
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        System.out.println(frames > before
                ? "handler path OK: a handler that changed nothing still produced a frame"
                : "HANDLER PATH DEAD: a handler ran and no frame followed in 300ms");
    }

    private void checkMutationPath() {
        if (mutateFromWorker == null) {
            return;
        }
        long before = frames;
        mutateFromWorker.run();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        System.out.println(frames > before
                ? "mutation path OK: a worker node mutation woke the loop and produced a frame"
                : "MUTATION PATH DEAD: mutated a node from a worker, no frame in 300ms");
    }

    private void checkWakePath() {
        long before = frames;
        kron.onTimeline(() -> { });
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        System.out.println(frames > before
                ? "wake path OK: a worker post woke the loop and produced a frame"
                : "WAKE PATH DEAD: posted from a worker, no frame followed in 300ms");
    }

    void report(String label) {
        long elapsed = System.nanoTime() - startNanos;
        double seconds = elapsed / 1e9;
        long f = frames;
        if (f == 0 || seconds <= 0) {
            System.out.println("frame profile (" + label + "): no frames presented");
            return;
        }
        System.out.println();
        System.out.println("--- frame profile: " + label + " ---");
        System.out.printf("presented        %d frames in %.2fs = %.1f fps%n", f, seconds, f / seconds);
        System.out.printf("  quiescent      %6d (%5.1f%%)  nothing to do at all%n", quiescent, pct(quiescent, f));
        System.out.printf("  deferrable     %6d (%5.1f%%)  a deadline, but not yet%n", deferrable, pct(deferrable, f));
        System.out.printf("  wanted now     %6d (%5.1f%%)  something is varying%n", wantedNow, pct(wantedNow, f));
        System.out.printf("sleepable        %.2fs of %.2fs (%.1f%% of wall time)%n",
                sleepableNanos / 1e9, seconds, sleepablePct());
        synchronized (reasons) {
            if (!reasons.isEmpty()) {
                System.out.println("why it wanted a frame now:");
                reasons.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .limit(8)
                        .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue(), e.getKey()));
            }
        }
        System.out.println("--- end frame profile ---");
    }

    @Override
    public void close() {
        closed = true;
        reporter.interrupt();
    }

    /**
     * Percentage of wall time the loop could have been parked.
     *
     * <p>Includes the interval still open, which is the whole story once the loop really sleeps: the
     * accumulator only advances on a sample, and a parked loop produces no samples, so a sample-driven
     * reading of a working render-on-demand loop collapses towards zero instead of towards a hundred.
     */
    private double sleepablePct() {
        long elapsed = System.nanoTime() - startNanos;
        long open = prevSampleNanos < 0 ? 0
                : Math.min(prevBudgetNanos, System.nanoTime() - prevSampleNanos);
        return elapsed <= 0 ? 0 : 100.0 * Math.min(sleepableNanos + open, elapsed) / elapsed;
    }

    private static double pct(long n, long total) {
        return total == 0 ? 0 : 100.0 * n / total;
    }
}
