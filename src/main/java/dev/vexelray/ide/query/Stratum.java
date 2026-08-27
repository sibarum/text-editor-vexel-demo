package dev.vexelray.ide.query;

/**
 * How much a {@link LanguagePack} can see. Ordered by information content, and a pack declares a <em>set</em>
 * rather than a level — see {@link LanguagePack#strata()} for why that is not a ladder.
 *
 * <p>The queries do not change with the stratum. What changes is which of them
 * {@linkplain Answer.Verdict.Abstains abstain}.
 */
public enum Stratum {

    /**
     * Characters, and nothing else. Full-text search, and the only stratum that must answer on a file that does
     * not parse. Every pack supplies it, including one that is nothing but a file reader.
     */
    TEXT,

    /**
     * Named declarations and the calls between them, as a graph with node identity and <b>no duplicate edges</b>.
     * Definition, callers, structure, dead code.
     *
     * <p>The deduplication is not tidiness. It is what makes an edge set diffable between compiles, which is what
     * makes live marks cheap and stable; and it is what lets a query be answered once for a call that appears
     * three times in a body. A pack whose edges are keyed by name rather than by node cannot claim this stratum,
     * because two overloads sharing a name are two nodes and a name cannot tell them apart.
     */
    EDGES,

    /**
     * Sorts on nodes, and on the edges between them. Hover, signature search that understands assignability,
     * anything that needs to know what a thing is rather than only where it is.
     */
    SORTS,

    /**
     * Information-conservation metadata over the edge set: what each call preserves, derives, branches on,
     * translates, leaves untouched or makes opaque.
     *
     * <p>This is the stratum where a query stops being a search and starts being a proof with content — where
     * {@link Query.Expect#NONE} over a conservation predicate is a theorem and its witnesses are a
     * counterexample. At the time of writing, exactly one language supplies it.
     */
    CONSERVATION
}
