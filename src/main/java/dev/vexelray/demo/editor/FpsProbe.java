package dev.vexelray.demo.editor;

import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the frame loop would have been allowed to skip, measured rather than argued about.
 *
 * <p>The loop in {@code GuiApp.run} presents unconditionally, paced only by FIFO present mode, so it
 * redraws a completely still window at the display rate. Kronometer can already say which of those
 * frames were unnecessary — this samples that answer once per frame and adds it up, so the cost of not
 * asking is a number instead of an assumption.
 *
 * <p>Sampled immediately after {@code krono.tick()} returns: {@code INLINE} returns with the batch
 * complete, and that return is what publishes the kernel's writes to this thread.
 */
final class FpsProbe {

    private final Kron kron;
    private final long startNanos = System.nanoTime();
    private final Map<String, Long> reasons = new LinkedHashMap<>();

    private long frames;
    private long quiescent;     // could have slept until an event arrived
    private long deferrable;    // could have slept for a stated while
    private long wantedNow;     // something is varying; this frame was earned

    private long prevSampleNanos = -1;
    private long prevBudgetNanos;
    private long sleepableNanos;

    FpsProbe(Kron kron) {
        this.kron = kron;
        // This probe is not a host and never sleeps, so a no-op wake is safe here — and it is what makes
        // sleepTimeout() report the real budget rather than the zero it deliberately hands an unwired loop.
        kron.onWork(() -> { });
    }

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
            for (String reason : kron.whyBusy()) {
                reasons.merge(collapse(reason), 1L, Long::sum);
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

    void report(String label) {
        long elapsed = System.nanoTime() - startNanos;
        double seconds = elapsed / 1e9;
        if (frames == 0 || seconds <= 0) {
            System.out.println("frame profile (" + label + "): no frames presented");
            return;
        }
        System.out.println();
        System.out.println("--- frame profile: " + label + " ---");
        System.out.printf("presented        %d frames in %.2fs = %.1f fps%n", frames, seconds, frames / seconds);
        System.out.printf("  quiescent      %6d (%5.1f%%)  nothing to do at all%n", quiescent, pct(quiescent));
        System.out.printf("  deferrable     %6d (%5.1f%%)  a deadline, but not yet%n", deferrable, pct(deferrable));
        System.out.printf("  wanted now     %6d (%5.1f%%)  something is varying%n", wantedNow, pct(wantedNow));
        System.out.printf("could have slept %.2fs of %.2fs (%.1f%% of wall time)%n",
                sleepableNanos / 1e9, seconds, 100.0 * sleepableNanos / elapsed);
        if (!reasons.isEmpty()) {
            System.out.println("why it wanted a frame now:");
            reasons.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue(), e.getKey()));
        }
        System.out.println("--- end frame profile ---");
    }

    private double pct(long n) {
        return 100.0 * n / frames;
    }
}
