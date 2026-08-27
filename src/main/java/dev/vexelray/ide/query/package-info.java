/**
 * The editor's language interface: <b>one</b> operation, over a graph, at a declared stratum.
 *
 * <h2>Search and proof differ by one field</h2>
 *
 * A {@link dev.vexelray.ide.query.Query} is a {@code Selector}, a {@code Scope} and an
 * {@link dev.vexelray.ide.query.Query.Expect}. Set {@code Expect.ANY} and it is a search: you get rows.
 * Set anything else and the same query is a proof obligation: you get rows <em>and</em> a
 * {@link dev.vexelray.ide.query.Answer.Verdict}. "Where is {@code f} called from" and "is {@code f} ever
 * called from outside this module" are the same selector at different cardinalities, which is why they
 * belong in the same box on screen and behind the same method here.
 *
 * <p>That is the whole reason this package exists instead of a language-server client. LSP hands back a
 * string and a span; a query hands back the rows that witness the failure. A diagnostic is a query whose
 * expectation was not met, and its witnesses are navigable.
 *
 * <h2>Strata, not capabilities</h2>
 *
 * A {@link dev.vexelray.ide.query.LanguagePack} declares which {@link dev.vexelray.ide.query.Stratum}s it
 * can supply. The queries never change; the strata decide which ones
 * {@linkplain dev.vexelray.ide.query.Answer.Verdict.Abstains abstain}. A pack that can only tokenize still
 * answers full-text search. One that resolves calls answers definition and callers. One that carries
 * conservation metadata answers proofs. Java and Python are not crippled dialects of the top stratum —
 * they sit lower in the same structure, and an honest abstention says so.
 *
 * <h2>The seam this package is</h2>
 *
 * The editor speaks spans: a caret is an offset, a mark is a range. A graph speaks nodes. Every
 * {@link dev.vexelray.ide.query.Answer.Match} therefore carries both — a
 * {@link dev.vexelray.ide.query.Answer.Site} the editor can scroll to, and an opaque node id (absent for
 * text matches, which have no node) the next query can be asked about. Mapping between the two is the
 * only genuinely new work on either side of this interface.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><b>Branch quantifiers.</b> {@code conservation-receipts.md} lists assertions over branch merges —
 *       all branches, zero branches, at least one branch. There is no branch selector in this cut, and a
 *       quantifier with nothing to quantify over is an empty feature. They arrive together or not at all.</li>
 *   <li><b>Ranking.</b> Rows come back in whatever order the pack declares things in, and that order must be
 *       stable across rebuilds or the gutter flickers between keystrokes. Sorting is the editor's.</li>
 *   <li><b>Edits.</b> Nothing here renames, moves or rewrites. A query reads.</li>
 * </ul>
 */
package dev.vexelray.ide.query;
