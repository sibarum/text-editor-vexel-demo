package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Mark;
import dev.vexelray.demo.editor.core.Messages.Marked;
import dev.vexelray.framework.api.Job;
import dev.vexelray.framework.highlight.Highlighting;

/**
 * A document's colours, worked out on the offload lane: {@link Highlighting#spans} over the text it was sent.
 *
 * <p>A job because it is work over the whole document after every edit, and the view's time is the frame's. The reply
 * names the version of the text it was computed from, and the view applies it only if the field still says that, so a
 * slow answer about old text is dropped rather than drawn over new text. The compiled grammars are a part it takes,
 * not state of its own: the framework's {@code HighlightStarter} builds it, in the editor's colours ({@code -gui}'s
 * {@code Look.CODE}) and with {@link Pontif} among its languages.
 */
@Job
public final class MarkText {

    private final Highlighting highlighting;

    public MarkText(Highlighting highlighting) {
        this.highlighting = highlighting;
    }

    public Marked run(Mark m) {
        return new Marked(m.field(), m.version(), highlighting.spans(m.scope(), m.text()));
    }
}
