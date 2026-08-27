package dev.vexelray.ide.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The query interface has exactly one piece of logic in it — {@link Answer#judged}, which is where
 * {@link Query.Expect} is interpreted — plus two invariants worth pinning: that a search is a query with the
 * assertion turned off, and that a text match admits it has no graph node.
 */
class QueryApiTest {

    private static Answer.Match hit(String name) {
        return new Answer.Match(name, new Answer.Site("Point.ptf", 0, name.length()),
                Optional.of("node:" + name), Query.Reach.EXPORTED, Query.Origin.DECLARED, "():Decimal");
    }

    // ------------------------------------------------------ a query is a proof

    @Test
    void searchAssertsNothingAndProofDiffersByOneField() {
        Query search = Query.search(Query.Selector.Text.of("magnitude"));
        assertFalse(search.isProof());
        assertInstanceOf(Answer.Verdict.NotAsserted.class,
                Answer.judged(List.of(hit("magnitude")), search.expect()).verdict());

        Query proof = search.asserting(Query.Expect.EXACTLY_ONE);
        assertTrue(proof.isProof());
        assertEquals(search.selector(), proof.selector(), "the selector is untouched: only the cardinality moved");
    }

    // ------------------------------------------------------------- cardinality

    @Test
    void noneHoldsOnEmptyAndFailsWithItsCounterexampleShown() {
        assertInstanceOf(Answer.Verdict.Holds.class,
                Answer.judged(List.of(), Query.Expect.NONE).verdict());

        Answer failed = Answer.judged(List.of(hit("magnitude"), hit("scale")), Query.Expect.NONE);
        assertInstanceOf(Answer.Verdict.Fails.class, failed.verdict());
        assertEquals(2, failed.matches().size(), "a failed no-go keeps its witnesses beside the verdict");
    }

    @Test
    void atLeastOneFailsOnAnAbsenceThatHasNoWitness() {
        Answer failed = Answer.judged(List.of(), Query.Expect.AT_LEAST_ONE);
        assertInstanceOf(Answer.Verdict.Fails.class, failed.verdict());
        assertTrue(failed.matches().isEmpty());

        assertInstanceOf(Answer.Verdict.Holds.class,
                Answer.judged(List.of(hit("magnitude")), Query.Expect.AT_LEAST_ONE).verdict());
    }

    @Test
    void exactlyOneRejectsBothNothingAndAmbiguity() {
        assertInstanceOf(Answer.Verdict.Fails.class,
                Answer.judged(List.of(), Query.Expect.EXACTLY_ONE).verdict());
        assertInstanceOf(Answer.Verdict.Holds.class,
                Answer.judged(List.of(hit("magnitude")), Query.Expect.EXACTLY_ONE).verdict());
        assertInstanceOf(Answer.Verdict.Fails.class,
                Answer.judged(List.of(hit("magnitude"), hit("magnitude")), Query.Expect.EXACTLY_ONE).verdict());
    }

    // ----------------------------------------------- abstaining is not failing

    @Test
    void abstentionIsItsOwnVerdictSoABlindSpotCannotReadAsAProof() {
        Answer.Verdict abstained = Answer.abstain(Stratum.EDGES, "no call resolution").verdict();
        Answer.Verdict failed = Answer.judged(List.of(), Query.Expect.AT_LEAST_ONE).verdict();

        assertInstanceOf(Answer.Verdict.Abstains.class, abstained);
        assertInstanceOf(Answer.Verdict.Fails.class, failed);
        assertEquals(Stratum.EDGES, ((Answer.Verdict.Abstains) abstained).needed());
    }

    // ------------------------------------------------------- visibility axes

    @Test
    void reachAndOriginAreIndependentAxes() {
        Query.Scope associatedOnly = new Query.Scope(
                Set.of(Query.Reach.values()), Set.of(Query.Origin.ASSOCIATED));

        assertTrue(associatedOnly.admits(Query.Reach.EXPORTED, Query.Origin.ASSOCIATED));
        assertFalse(associatedOnly.admits(Query.Reach.EXPORTED, Query.Origin.DECLARED),
                "an exported name written here is not an associated one: the axes do not substitute");
        assertTrue(Query.Scope.EVERYTHING.admits(Query.Reach.LOCAL, Query.Origin.BUILTIN));
    }

    @Test
    void scopeNeedsAtLeastOneValueOnEachAxis() {
        assertThrows(IllegalArgumentException.class,
                () -> new Query.Scope(Set.of(), Set.of(Query.Origin.DECLARED)));
    }

    // ----------------------------------------------------------- the text seam

    @Test
    void aTextMatchCarriesNoNodeBecauseItNeverParsedAnything() {
        Answer.Match m = Answer.Match.text("magnitude", new Answer.Site("Point.ptf", 12, 21));
        assertTrue(m.node().isEmpty());
        assertEquals(9, m.site().end() - m.site().start());
    }

    @Test
    void signatureWildcardIsTheLanguagesOwnUnderscore() {
        Query.Selector.Signature sig =
                new Query.Selector.Signature(List.of("Int", Query.Selector.Signature.ANY), "Decimal");
        assertEquals("_", Query.Selector.Signature.ANY);
        assertEquals(2, sig.params().size());
    }
}
