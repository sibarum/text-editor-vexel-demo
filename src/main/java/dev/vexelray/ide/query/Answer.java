package dev.vexelray.ide.query;

import java.util.List;
import java.util.Optional;

/**
 * What a {@link LanguagePack} gives back: the rows, and — if the query asserted a cardinality — the verdict on
 * that assertion.
 *
 * <p>Both, always. A failed proof that only said "failed" would be the string-and-a-span the language-server
 * protocol hands back, and the point of asking a graph is that the counterexample is a set of places you can go
 * to. So a {@link Verdict.Fails} sits beside the rows that witness it rather than replacing them.
 *
 * @param matches the rows, in the pack's own declaration order — stable across rebuilds, because a gutter whose
 *                marks reorder between keystrokes is worse than one that is late
 * @param verdict {@link Verdict.NotAsserted} when the query was a search
 */
public record Answer(List<Match> matches, Verdict verdict) {

    public Answer {
        if (matches == null || verdict == null) {
            throw new IllegalArgumentException("Answer needs matches and a verdict");
        }
        matches = List.copyOf(matches);
    }

    /** The result of a search — no assertion was made, so there is nothing to hold or fail. */
    public static Answer found(List<Match> matches) {
        return new Answer(matches, new Verdict.NotAsserted());
    }

    /**
     * The rows, judged against what the query expected. The single place {@link Query.Expect} is interpreted, so
     * a pack cannot disagree with another pack about what "exactly one" means.
     */
    public static Answer judged(List<Match> matches, Query.Expect expect) {
        Verdict v = switch (expect) {
            case ANY -> new Verdict.NotAsserted();
            case NONE -> matches.isEmpty()
                    ? new Verdict.Holds()
                    : new Verdict.Fails(matches.size() + " where none may be");
            case AT_LEAST_ONE -> matches.isEmpty()
                    ? new Verdict.Fails("none found")
                    : new Verdict.Holds();
            case EXACTLY_ONE -> switch (matches.size()) {
                case 1 -> new Verdict.Holds();
                case 0 -> new Verdict.Fails("none found");
                default -> new Verdict.Fails(matches.size() + " where one is required");
            };
        };
        return new Answer(matches, v);
    }

    /** No rows and no judgement: this pack cannot see what the query asked about. */
    public static Answer abstain(Stratum needed, String because) {
        return new Answer(List.of(), new Verdict.Abstains(needed, because));
    }

    // ------------------------------------------------------------------- match

    /**
     * One row.
     *
     * <p>Carries a {@link Site} because the editor navigates by offset, and a node id because the next query is
     * asked about a node. That pairing is the seam this whole package exists to hold: text on one side, graph on
     * the other, and one row that is in both.
     *
     * @param name   the thing's name; for a {@link Query.Selector.Text} match, the matched text
     * @param site   where to go
     * @param node   the pack's own handle for the graph node, opaque here, absent for a text match — text has no
     *               node, and pretending otherwise would make every text search claim a parse it never did
     * @param reach  how far this declaration carries
     * @param origin how it came to be visible
     * @param detail the one line shown beside the name — a rendered signature, a sort, whatever the pack thinks
     *               identifies it; never parsed by the editor
     */
    public record Match(String name, Site site, Optional<String> node,
                        Query.Reach reach, Query.Origin origin, String detail) {

        public Match {
            if (name == null || site == null || node == null || reach == null || origin == null) {
                throw new IllegalArgumentException("Match needs a name, a site, a node slot and a visibility");
            }
            if (detail == null) {
                detail = "";
            }
        }

        /** A text hit: no node, and nothing known about visibility beyond where it sits. */
        public static Match text(String matched, Site site) {
            return new Match(matched, site, Optional.empty(),
                    Query.Reach.LOCAL, Query.Origin.DECLARED, "");
        }
    }

    /**
     * Where a match is, in the terms the editor already works in: a source unit and a half-open character range
     * over its <em>current</em> text — which is the buffer on screen, not the bytes on disk.
     *
     * @param unit  the source unit, as the pack names it and as {@link Sources} keys it
     * @param start first character offset, inclusive
     * @param end   last character offset, exclusive
     */
    public record Site(String unit, int start, int end) {
        public Site {
            if (unit == null || unit.isEmpty()) {
                throw new IllegalArgumentException("Site needs a unit");
            }
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Site range must be non-negative and ordered: " + start + ".." + end);
            }
        }
    }

    // ----------------------------------------------------------------- verdict

    /**
     * The judgement on a query's assertion. Four cases, and the fourth is the one that makes the interface honest:
     * a pack that cannot see far enough to answer says so, rather than answering no.
     */
    public sealed interface Verdict {

        /** The query asserted nothing. It was a search. */
        record NotAsserted() implements Verdict {}

        /** The expectation was met. */
        record Holds() implements Verdict {}

        /**
         * The expectation was not met. The witnesses are the {@link Answer#matches()} beside this — except for
         * {@link Query.Expect#AT_LEAST_ONE}, where the failure is an absence and there is nothing to show.
         *
         * @param because one line, for the mark's tooltip
         */
        record Fails(String because) implements Verdict {}

        /**
         * Not answered, because this pack does not supply the stratum the query needed. Distinct from
         * {@link Fails} on purpose: "no calls to f from outside" and "I cannot see calls" must not render the
         * same, or a language pack's blind spot reads as a proof.
         *
         * @param needed  the lowest stratum that could have answered
         * @param because one line naming what is missing
         */
        record Abstains(Stratum needed, String because) implements Verdict {}
    }
}
