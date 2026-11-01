package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Picture;
import dev.vexelray.demo.editor.core.Messages.Pictured;
import dev.vexelray.framework.api.Job;

import java.util.Optional;

/**
 * What Open pictures of a folder, read on the offload lane: its {@link Survey}, and for a folder with no build to
 * describe, its {@link Contents} as well, so one answer is the whole picture.
 *
 * <p>Asked for the folder under the pointer, which moves faster than a large build can be read. A job is not stopped
 * part way, so both reads are bounded, and the view keeps one of these running at a time, asking for wherever the
 * pointer has got to when the answer lands.
 */
@Job
public final class SurveyFolder {

    public Pictured run(Picture p) {
        Survey s = Survey.of(p.folder());
        return new Pictured(p.folder(), s, s.listed() ? Optional.of(Contents.of(p.folder())) : Optional.empty());
    }
}
