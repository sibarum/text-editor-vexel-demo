package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.text.Link;
import org.junit.jupiter.api.Test;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.Reference;
import sibarum.concordance.index.SourceRef;
import sibarum.concordance.index.Symbol;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning an index into links, which is the half of Ctrl+click that can be wrong quietly.
 *
 * <p>The failure this guards against does not throw and does not look broken: a link whose range is off by a
 * few characters underlines the wrong word, and Ctrl+clicking it navigates somewhere real for a name that was
 * never there. So these check the ranges themselves rather than the count — where each link starts, where it
 * ends, and what it says its target is.
 *
 * <p>The interesting cases are the ones where the recorded column is <em>wrong</em>, because that is the
 * ordinary case rather than the exotic one: the index is a snapshot, and a document is typed into after it is
 * taken. What the builder must not do is trust the column and underline whatever now sits at it.
 */
class SymbolLinksTest {

    private static final Path FILE = Path.of("Shape.java").toAbsolutePath().normalize();

    /** A declaration of {@code name} on {@code line}/{@code column}, qualified as {@code qualified}. */
    private static Symbol declaration(String name, String qualified, int line, int column) {
        return new Symbol(Symbol.Kind.METHOD, name, "demo.Shape", qualified,
                new SourceRef(FILE, line, column), List.of());
    }

    /** A use of {@code name} on {@code line}/{@code column}. */
    private static Reference use(String name, int line, int column) {
        return new Reference(Reference.Kind.CALL, name + "/0", name, "demo.Shape#run()",
                new SourceRef(FILE, line, column));
    }

    private static Link linkAt(List<Link> links, int start) {
        return links.stream().filter(l -> l.start() == start).findFirst().orElse(null);
    }

    @Test
    void aDeclarationAndAUseBecomeLinksOverExactlyTheirNames() {
        String text = """
                class Shape {
                    int area() { return 1; }
                    int twice() { return area() * 2; }
                }
                """;
        // "area" is at line 2, column 9 (1-based); the use of it is at line 3, column 25.
        Index index = new Index(List.of(declaration("area", "demo.Shape#area()", 2, 9)),
                List.of(use("area", 3, 25)));

        List<Link> links = SymbolLinks.linksIn(index, FILE, text);

        assertEquals(2, links.size(), "one declaration and one use");
        for (Link link : links) {
            assertEquals("area", text.substring(link.start(), link.end()),
                    "a link must cover exactly the name it stands for");
        }
        // The declaration carries the fully-qualified name, which is the thing a document cannot show.
        Link declaration = links.stream().filter(l -> l.target().startsWith(SymbolLinks.DEF)).findFirst()
                .orElseThrow();
        assertEquals(SymbolLinks.DEF + "demo.Shape#area()", declaration.target());
        // A use carries only the simple name, because that is all Concordance can match on.
        Link usage = links.stream().filter(l -> l.target().startsWith(SymbolLinks.USE)).findFirst()
                .orElseThrow();
        assertEquals(SymbolLinks.USE + "area", usage.target());
    }

    /**
     * The stale-column case, which is the one that matters. The index says the name is at a column it is no
     * longer at, because the line has been indented since; the link must still land on the name.
     */
    @Test
    void aColumnThatHasMovedFallsBackToFindingTheNameOnItsLine() {
        String text = "class Shape {\n            int area() { return 1; }\n}\n";
        // Column 9 was right before the line was indented; "area" now starts at column 17.
        Index index = new Index(List.of(declaration("area", "demo.Shape#area()", 2, 9)), List.of());

        List<Link> links = SymbolLinks.linksIn(index, FILE, text);

        assertEquals(1, links.size());
        assertEquals("area", text.substring(links.getFirst().start(), links.getFirst().end()),
                "a moved column must not underline whatever now sits at it");
    }

    /** A name the index places on a line it is not on at all is dropped, rather than guessed at. */
    @Test
    void aNameThatIsNoLongerOnItsLineIsNotLinked() {
        String text = "class Shape {\n    int perimeter() { return 1; }\n}\n";
        Index index = new Index(List.of(declaration("area", "demo.Shape#area()", 2, 9)), List.of());

        assertTrue(SymbolLinks.linksIn(index, FILE, text).isEmpty(),
                "a name that is gone is not a link somewhere near where it was");
    }

    /**
     * Whole words only. Without this, a use of {@code area} would link the {@code area} inside
     * {@code areaOf}, and Ctrl+clicking half an identifier would navigate.
     */
    @Test
    void aNameInsideALongerIdentifierIsNotLinked() {
        String text = "class Shape {\n    int areaOf() { return 1; }\n}\n";
        Index index = new Index(List.of(), List.of(use("area", 2, 9)));

        assertTrue(SymbolLinks.linksIn(index, FILE, text).isEmpty(),
                "area must not match inside areaOf");
    }

    /** References in other files are not this document's links, however many of them the index holds. */
    @Test
    void referencesInOtherFilesAreIgnored() {
        String text = "class Shape {\n    int area() { return 1; }\n}\n";
        Reference elsewhere = new Reference(Reference.Kind.CALL, "area/0", "area", "demo.Other#run()",
                new SourceRef(Path.of("Other.java").toAbsolutePath().normalize(), 2, 9));
        Index index = new Index(List.of(), List.of(elsewhere));

        assertTrue(SymbolLinks.linksIn(index, FILE, text).isEmpty());
    }

    /**
     * Where a declaration and a use land on the same characters, the declaration is first — because
     * {@code TextField.linkAt} takes the first link covering an offset, and "this is where it is defined" is
     * the more specific claim about a piece of text.
     */
    @Test
    void aDeclarationIsOfferedBeforeAUseOnTheSameCharacters() {
        String text = "class Shape {\n    int area() { return 1; }\n}\n";
        Index index = new Index(List.of(declaration("area", "demo.Shape#area()", 2, 9)),
                List.of(use("area", 2, 9)));

        List<Link> links = SymbolLinks.linksIn(index, FILE, text);

        assertEquals(2, links.size());
        assertTrue(links.getFirst().target().startsWith(SymbolLinks.DEF),
                "the declaration has to be the one linkAt finds");
    }

    @Test
    void lineStartsCountFromZeroAndIncludeTheLineAfterATrailingNewline() {
        int[] starts = SymbolLinks.lineStarts("ab\ncd\n");
        assertEquals(3, starts.length);
        assertEquals(0, starts[0]);
        assertEquals(3, starts[1]);
        assertEquals(6, starts[2]);
    }

    @Test
    void theSimpleNameIsTakenOutOfEveryShapeOfQualifiedName() {
        assertEquals("C", SymbolLinks.simpleNameOf("a.b.C"));
        assertEquals("m", SymbolLinks.simpleNameOf("a.b.C#m(int)"));
        assertEquals("m", SymbolLinks.simpleNameOf("a.b.C#m"));
        assertEquals("C", SymbolLinks.simpleNameOf("C"));
    }

    /**
     * A constructor is qualified {@code #<init>}, which is not a name any file contains. Ctrl+clicking one
     * has to ask about the type — {@code usages <init>} would have found nothing, for every constructor in
     * every project.
     */
    @Test
    void aConstructorAsksAboutItsTypeRatherThanAboutInit() {
        assertEquals("AppIcon", SymbolLinks.simpleNameOf("dev.demo.AppIcon#<init>()"));
        assertEquals("AppIcon", SymbolLinks.simpleNameOf("dev.demo.AppIcon#<init>(int, long)"));
        assertEquals("Shape", SymbolLinks.simpleNameOf("Shape#<init>()"));
    }

    // --- the shared index --------------------------------------------------------------------------

    /**
     * An index that lands has to reach documents that are already open. Without this, {@code index .} would
     * only take effect on files opened afterwards — the tabs you were looking at when you ran it would stay
     * dead, which is exactly when you would run it.
     */
    @Test
    void buildingAnIndexTellsWhoeverIsListening() {
        SourceIndex source = new SourceIndex();
        boolean[] told = {false};
        source.onBuilt(() -> told[0] = true);

        assertFalse(source.present());
        assertEquals("", source.status());

        Index built = new Index(List.of(declaration("area", "demo.Shape#area()", 2, 9)), List.of());
        source.built(built, Path.of("project"), built.summary());

        assertTrue(told[0], "a document already open has to be told");
        assertTrue(source.present());
        assertNotNull(source.index());
        assertTrue(source.status().startsWith("project: "), source.status());
    }

    /** A listener that has gone away stops being called, so a closed workspace is not still being told. */
    @Test
    void aRemovedListenerIsNotTold() {
        SourceIndex source = new SourceIndex();
        int[] calls = {0};
        Runnable listener = () -> calls[0]++;
        source.onBuilt(listener);
        source.removeListener(listener);

        Index built = new Index(List.of(), List.of());
        source.built(built, Path.of("project"), built.summary());

        assertEquals(0, calls[0]);
    }

    @Test
    void noIndexMeansNoLinksRatherThanAFailure() {
        assertNull(new SourceIndex().index());
        assertEquals(List.of(), SymbolLinks.linksIn(new Index(List.of(), List.of()), FILE, "class Shape {}"));
    }
}
