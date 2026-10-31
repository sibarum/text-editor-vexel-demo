package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Format;
import dev.vexelray.demo.editor.core.Messages.Formatted;
import dev.vexelray.framework.api.Job;

/**
 * A Markdown document read as blocks for its formatted view, on the offload lane: {@link Markdown#blocks} over the
 * text it was sent.
 *
 * <p>A job for the same reason as {@link MarkText}: it is a pass over the whole document, and the view's time is the
 * frame's. The reply names the version it was read from, and the view shows it only if the field still says that.
 */
@Job
public final class FormatText {

    public Formatted run(Format f) {
        return new Formatted(f.field(), f.version(), Markdown.blocks(f.text()));
    }
}
