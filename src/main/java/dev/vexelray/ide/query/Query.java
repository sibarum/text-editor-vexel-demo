package dev.vexelray.ide.query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One question, asked of a {@link LanguagePack}.
 *
 * <p>Three parts and an optional restriction: <b>what</b> to look for ({@link Selector}), <b>where it may be
 * seen from</b> ({@link Scope}), and <b>how many there should be</b> ({@link Expect}). The last is what turns a
 * search into a proof — see the package documentation.
 *
 * @param selector what to match
 * @param scope    which visibilities count; {@link Scope#EVERYTHING} to not care
 * @param expect   the cardinality this query asserts; {@link Expect#ANY} asserts nothing
 * @param unit     restrict to one source unit (a file path, as the pack names it), or empty for the project
 */
public record Query(Selector selector, Scope scope, Expect expect, Optional<String> unit) {

    public Query {
        if (selector == null || scope == null || expect == null || unit == null) {
            throw new IllegalArgumentException("Query parts must be non-null");
        }
    }

    /** A project-wide search: no assertion, every visibility. */
    public static Query search(Selector selector) {
        return new Query(selector, Scope.EVERYTHING, Expect.ANY, Optional.empty());
    }

    /** The same query, restricted to one source unit. */
    public Query in(String sourceUnit) {
        return new Query(selector, scope, expect, Optional.of(sourceUnit));
    }

    /** The same query, narrowed to a visibility. */
    public Query within(Scope narrower) {
        return new Query(selector, narrower, expect, unit);
    }

    /** The same query, turned into a proof obligation. */
    public Query asserting(Expect cardinality) {
        return new Query(selector, scope, cardinality, unit);
    }

    /** Whether this query asserts anything — {@code false} makes it a search. */
    public boolean isProof() {
        return expect != Expect.ANY;
    }

    // ---------------------------------------------------------------- selector

    /**
     * What a query matches. Three shapes, because there are three kinds of thing a reader looks for: a run of
     * characters, a name, or a shape.
     */
    public sealed interface Selector {

        /**
         * Characters. The stratum below the graph: this is the one selector that must answer on a file that does
         * not parse, which is exactly when a reader is most likely to be searching. No pack may abstain on it.
         *
         * @param needle        the text, or a regular expression if {@code regex}
         * @param regex         treat the needle as a regular expression
         * @param caseSensitive match case
         */
        record Text(String needle, boolean regex, boolean caseSensitive) implements Selector {
            public Text {
                if (needle == null || needle.isEmpty()) {
                    throw new IllegalArgumentException("Text selector needs a needle");
                }
            }

            /** The common case: a case-insensitive literal. */
            public static Text of(String needle) {
                return new Text(needle, false, false);
            }
        }

        /**
         * A name, at one {@link Kind} of declaration.
         *
         * <p>The pattern is a glob over the name: an asterisk matches any run of characters, everything else is
         * literal. One matcher rather than three, because exact and substring both fall out of it and a third
         * spelling of the same idea earns nothing.
         */
        record Named(Kind kind, String pattern) implements Selector {
            public Named {
                if (kind == null || pattern == null || pattern.isEmpty()) {
                    throw new IllegalArgumentException("Named selector needs a kind and a pattern");
                }
            }
        }

        /**
         * A signature, by shape rather than by name — what takes two Ints and gives a Decimal.
         *
         * <p>Each entry is a sort name as the pack spells it, or {@link #ANY} for anything, which is already the
         * language's own wildcard. Matching is <b>structural</b>: an Int entry matches a parameter declared Int
         * and nothing else. Assignability-aware matching — a Num entry finding an Int — is a strictly better
         * answer that only a pack at {@link Stratum#SORTS} could give, so it is a refinement to add there rather
         * than a promise made here that most packs would have to break.
         *
         * @param params one entry per parameter, in order
         * @param result the return sort, {@link #ANY} for anything, or {@code []} for the unit return
         */
        record Signature(List<String> params, String result) implements Selector {

            /** The wildcard entry: matches any sort in any position. */
            public static final String ANY = "_";

            public Signature {
                if (params == null || result == null || result.isEmpty()) {
                    throw new IllegalArgumentException("Signature selector needs params and a result");
                }
                params = List.copyOf(params);
            }
        }
    }

    /**
     * Which sort of declaration a {@link Selector.Named} looks at, outermost first — which is also, not by
     * coincidence, roughly the order they lose reach.
     */
    public enum Kind {
        /** A struct, trait or alias name. */
        TYPE,
        /** A function declaration, by name. For its shape instead, use {@link Selector.Signature}. */
        FUNCTION,
        /**
         * A nullary member referenced bare as a value — a type attribute, an enum case. Called a static because
         * that is what it reads as at the use site; it is a member with no parameters.
         */
        STATIC,
        /** A field or method on a type. */
        MEMBER,
        /** A name bound inside a body, and visible only there. */
        LOCAL
    }

    // ------------------------------------------------------------------- scope

    /**
     * The visibilities a query counts, on two axes.
     *
     * <p>Visibility is not one ladder. {@link Reach} is how far a declaration carries <em>outward</em> from where
     * it is written; {@link Origin} is how it came to be visible <em>here</em>. A name this module exports and a
     * name surfaced here because we imported a type it mentions are both visible, and neither is more visible
     * than the other — so they are two questions, and collapsing them into one ordinal would force a false
     * answer for one of them.
     *
     * <p>Every {@link Answer.Match} carries its own reach and origin, so a result set separates itself: the
     * editor groups the rows it got rather than asking a second question per group.
     */
    public record Scope(Set<Reach> reach, Set<Origin> origin) {

        /** Everything visible, however it got here. */
        public static final Scope EVERYTHING =
                new Scope(Set.of(Reach.values()), Set.of(Origin.values()));

        /** Only what a unit publishes — the API surface. */
        public static final Scope PUBLISHED =
                new Scope(Set.of(Reach.EXPORTED), Set.of(Origin.values()));

        /** Only what was written here: no imports, no associations, no builtins. */
        public static final Scope OURS =
                new Scope(Set.of(Reach.values()), Set.of(Origin.DECLARED));

        public Scope {
            if (reach == null || origin == null || reach.isEmpty() || origin.isEmpty()) {
                throw new IllegalArgumentException("Scope needs at least one reach and one origin");
            }
            reach = Set.copyOf(reach);
            origin = Set.copyOf(origin);
        }

        public boolean admits(Reach r, Origin o) {
            return reach.contains(r) && origin.contains(o);
        }
    }

    /** How far a declaration carries outward from where it is written. */
    public enum Reach {
        /** Named in the unit's exports; other modules may require it. */
        EXPORTED,
        /** Declared at the top of a unit but not exported — visible to the module, and no further. */
        INTERNAL,
        /** Bound inside a body. Visible only within it, which is why a local search is nearly always scoped. */
        LOCAL
    }

    /** How a declaration came to be visible from the asking unit. */
    public enum Origin {
        /** Written here. */
        DECLARED,
        /** Named in a requires, possibly under a different local name than it has at its source. */
        IMPORTED,
        /**
         * Surfaced by importing a type it mentions, rather than by being asked for — import-by-association.
         * Nothing in the asking unit names it, so it is the one origin a reader can be surprised by, and the one
         * most worth being able to filter to on its own.
         */
        ASSOCIATED,
        /** Always there. No unit declares it and no unit can shadow it. */
        BUILTIN
    }

    // ------------------------------------------------------------------ expect

    /**
     * The cardinality a query asserts. {@link #ANY} asserts nothing and the query is a search; every other value
     * makes the same query a proof obligation, and the rows that come back are its witnesses.
     */
    public enum Expect {
        /** No assertion. A search. */
        ANY,
        /** There must be none. The rows, if any, are the counterexample — this is the shape a no-go takes. */
        NONE,
        /** There must be at least one. Absence is the failure, and it has no witness to show. */
        AT_LEAST_ONE,
        /** There must be exactly one. Both nothing and an ambiguity fail, which is what makes it worth having. */
        EXACTLY_ONE
    }
}
