package dev.vexelray.demo.editor;

import sibarum.atchung.Committer;
import sibarum.atchung.State;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one authoritative {@link Doc}, and the only way to change it.
 *
 * <p>Every change is a function applied to whatever the current value turns out to be, committed through
 * {@link State}: a tab closing on one worker and a document going dirty on another both land, in some order,
 * and neither is lost. Handlers run on workers, so this compare-and-set is the serialisation point for the
 * session's shape.
 *
 * <p>One generic committer rather than one per command, as the template started it. The names would be the
 * vocabulary a replicated peer binds to, and nothing replicates this yet; splitting it is a mechanical change
 * when something does.
 */
final class Model {

    private final State<Doc> state;
    private final Committer<Doc, UnaryOperator<Doc>> edit;

    Model() {
        State.Builder<Doc> builder = State.of(Doc.initial());
        this.edit = builder.mutation("doc.edit", (current, change) -> change.apply(current));
        this.state = builder.build();
    }

    /** The latest coherent snapshot. Lock-free, safe from any thread. */
    Doc doc() {
        return state.value();
    }

    /** Apply a change to whatever the session currently is. */
    void change(UnaryOperator<Doc> change) {
        state.commit(edit, change);
    }

    void opened(Doc.Entry entry) {
        change(d -> d.withTab(entry).withActive(entry.id()));
    }

    void closed(long id) {
        change(d -> d.without(id));
    }

    void front(long id) {
        change(d -> d.entry(id) == null ? d : d.withActive(id));
    }

    void dirty(long id, boolean dirty) {
        change(d -> d.withEntry(id, e -> e.withDirty(dirty)));
    }

    void saved(long id, Path path, String language) {
        change(d -> d.withEntry(id, e -> e.withPath(path, language).withDirty(false)));
    }

    void folder(Path folder) {
        change(d -> d.withFolder(folder));
    }

    /** Say something on the status line. */
    void say(String message) {
        change(d -> d.withStatus(message));
    }

    /**
     * React to every change, in order, one at a time, always finishing on the newest — see the template's note on
     * {@code onCommitLatest}: commits come from a pool, and a listener redrawing from each one would otherwise end
     * on a stale one.
     */
    void onChange(Consumer<Doc> listener) {
        state.onCommitLatest(v -> listener.accept(v.value()));
    }

    /** The version counter — what a test waits on to know a change landed. */
    long version() {
        return state.version();
    }
}
