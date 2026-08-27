package dev.vexelray.ide.query;

import java.util.Set;

/**
 * Everything the editor knows about one language: which files are its, how far it can see, and one method that
 * answers questions.
 *
 * <p>One method, because a search and a proof are the same operation at different cardinalities. There is no
 * {@code definition()}, no {@code hover()}, no {@code diagnostics()} — those are queries, and a pack that
 * implemented them as separate entry points would be free to disagree with itself about what it can see.
 *
 * <h2>The pack keeps the graph; the host keeps the text</h2>
 *
 * A pack is opened against a {@link Sources} and lives as long as the project does, because rebuilding a graph
 * per query is not viable at typing speed. It is told when a unit moves and decides for itself what that
 * invalidates — the host does not know which nodes a keystroke reached, and guessing on its behalf would be the
 * same mistake as parsing in the editor.
 *
 * <h2>Abstaining is an answer</h2>
 *
 * A pack must never answer a question it cannot see. {@link Answer#abstain} exists so that a blind spot and a
 * proof do not render alike: "nothing calls this" and "I cannot see calls" look identical in a results list and
 * mean opposite things. A pack at {@link Stratum#TEXT} alone abstains on every named query and still earns its
 * place, because full-text search over the live buffer is most of what a reader asks for and none of it needs a
 * parse.
 */
public interface LanguagePack {

    /** What to call this pack in the interface. */
    String name();

    /** The file extensions this pack claims, without the dot, lowercased. */
    Set<String> extensions();

    /**
     * What this pack can see. A <b>set</b>, not a level: the strata are ordered by information content but they
     * are not strictly nested, because a language can carry declared types without resolving its calls — type
     * hints in Python being the obvious case. Claiming {@link Stratum#SORTS} would then be true and claiming
     * {@link Stratum#EDGES} a lie, and a ladder cannot say that.
     *
     * <p>Every pack includes {@link Stratum#TEXT}.
     */
    Set<Stratum> strata();

    /** Answer a query, or {@linkplain Answer#abstain abstain} because this pack cannot see that far. */
    Answer ask(Query query);

    /**
     * A unit's text has changed. Called on every edit, so it must be cheap: note the unit and rebuild later, do
     * not rebuild here.
     */
    void changed(String unit);

    /** How a pack is attached to a project. The host calls this once and holds the result. */
    interface Factory {

        /** Open a pack over the host's live text. */
        LanguagePack open(Sources sources);
    }
}
