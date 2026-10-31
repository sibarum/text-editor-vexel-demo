package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Mark;
import dev.vexelray.demo.editor.core.Messages.Marked;
import dev.vexelray.framework.api.Job;

/**
 * A document's colours, worked out on the offload lane: {@link Grammars#spans} over the text it was sent.
 *
 * <p>A job because it is work over the whole document after every edit, and the view's time is the frame's. The reply
 * names the version of the text it was computed from, and the view applies it only if the field still says that, so a
 * slow answer about old text is dropped rather than drawn over new text. The compiled grammars are a part it takes,
 * not state of its own: see {@link Grammars}.
 */
@Job
public final class MarkText {

    private final Grammars grammars;

    public MarkText(Grammars grammars) {
        this.grammars = grammars;
    }

    public Marked run(Mark m) {
        return new Marked(m.field(), m.version(), grammars.spans(m.scope(), m.text()));
    }
}
