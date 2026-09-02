package dev.vexelray.demo.editor;

import sibarum.concordance.index.Index;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The built Concordance index, held where both the shell that builds it and the editor that navigates by it
 * can see it.
 *
 * <p><b>Why this is a class and not a field on {@link ConcordanceApp}.</b> The index started as that app's
 * private state, which was right while the only thing that asked about it was a command being typed. Ctrl+click
 * asks a second question — "what does this word in this document mean" — from a place that has no shell in it
 * at all, and a document that had to reach through a console app to answer it would be the wrong shape. So the
 * index becomes a thing both of them are handed, and neither owns.
 *
 * <p><b>A snapshot, and it says so.</b> What is here is what was on disk when {@code index} last ran. Editing a
 * file does not update it, and nothing here pretends otherwise — {@link SymbolLinks} re-finds a name near the
 * line it was recorded on rather than trusting the number, which is what makes an index that is a few edits old
 * still useful instead of merely wrong.
 *
 * <p>Every field is volatile and the listener list copies on write, because {@code index} runs on the shell's
 * job thread and every reader is on the frame loop or a worker off it.
 */
public final class SourceIndex {

    private volatile Index index;
    private volatile Path root;
    private volatile String summary = "";

    /**
     * Told whenever an index lands, so a document that is already open can light its links up without being
     * touched. Without this, {@code index .} would only take effect on files opened after it.
     */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Record a freshly built index of {@code root}. Replaces whatever was here. */
    void built(Index built, Path from, String describedAs) {
        this.index = built;
        this.root = from;
        this.summary = describedAs == null ? "" : describedAs;
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** The index, or null if none has been built. */
    public Index index() {
        return index;
    }

    /** Where {@link #index()} was built from, or null. */
    public Path root() {
        return root;
    }

    /** Whether there is an index to ask. */
    public boolean present() {
        return index != null;
    }

    /**
     * What a status line can say about this, or empty when there is nothing to say.
     *
     * <p>Moved here from {@code ConcordanceApp.indexStatus()}, which had no caller: the fact is about the
     * index rather than about the app that happens to build it, and now that two things read the index, the
     * one-line summary of it belongs beside it.
     */
    public String status() {
        if (index == null) {
            return "";
        }
        Path name = root.getFileName();
        return (name != null ? name : root) + ": " + summary;
    }

    /** Be told when an index lands. Runs on whichever thread built it — the shell's job thread. */
    public void onBuilt(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Stop listening. Called when a workspace goes away, so a closed window is not still being told. */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }
}
