package dev.vexelray.demo.editor;

import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Ramp;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

/**
 * The application's one tempo, on the framework's clock.
 *
 * <p>The widgets animate only when handed a {@link Ramp} — {@code Tabs.slide}, {@code TreeView.motion},
 * {@code Cues} — and without one they cut, so leaving the clock out costs no error and no warning, only a stiffer
 * window. That is how the first rebuild of this editor lost all three. They are built here, from one clock and
 * two durations, so every change in the window moves at the same pace.
 */
final class Motion {

    /**
     * What a change is worth: a tab arriving, a folder opening. Short enough that Ctrl+Tab held down never waits
     * for it, which is the ceiling on this number.
     */
    static final Dur CHANGE = Dur.ms(160);

    /**
     * What being seen costs: a cue has to be noticed, and once its attack and release are taken out a 160ms cue
     * has too little visible motion left. So the two differ on purpose.
     */
    static final Dur CUE = Dur.ms(240);

    /** A change, linear: a dissolve has no place to arrive at, and {@code Tabs.slide} eases its own travel. */
    final Ramp change;

    /** A change that arrives somewhere — rows opening under a folder — eased into place. */
    final Ramp arrival;

    /** One-shot acknowledgements and refusals painted over a node. */
    final Cues cues;

    Motion(KronoGui krono) {
        this.change = (progress, done) -> krono.ramp(CHANGE, Ease.LINEAR, progress, done);
        this.arrival = (progress, done) -> krono.ramp(CHANGE, Ease.OUT_CUBIC, progress, done);
        this.cues = new Cues((progress, done) -> krono.ramp(CUE, Ease.LINEAR, progress, done));
    }
}
