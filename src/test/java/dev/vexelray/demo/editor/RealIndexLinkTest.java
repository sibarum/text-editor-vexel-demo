package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.text.Link;
import org.junit.jupiter.api.Test;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.IndexBuilder;
import sibarum.concordance.index.Symbol;
import sibarum.concordance.project.MavenProject;
import sibarum.concordance.project.MavenProjectReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ctrl+click against a real index of this very project, which is the one thing the unit tests cannot check.
 *
 * <p>Everything else about linking is tested with a hand-built {@link Index}, where the line and column of each
 * symbol are whatever the test said they were. That proves the builder does what it is told; it cannot prove
 * the builder is being told the truth. The assumption underneath the whole feature is that Concordance's
 * {@code SourceRef} column is a one-based <em>character</em> column into the line as the editor holds it — and
 * if that is off by one, or counts bytes, or counts tabs as several, then every link in the editor is subtly
 * misplaced and every unit test still passes.
 *
 * <p>So this indexes this project's own source, builds links over a file read off the disk, and checks that
 * each link covers exactly the identifier it claims to. A single off-by-one shows up immediately.
 */
class RealIndexLinkTest {

    /** This project's root — where the pom is, from wherever the test was run. */
    private static Path root() {
        return Path.of("").toAbsolutePath().normalize();
    }

    @Test
    void everyLinkOverThisProjectCoversExactlyTheNameItStandsFor() throws IOException {
        MavenProject project = MavenProjectReader.read(root());
        Index index = IndexBuilder.build(project);
        assertFalse(index.symbols().isEmpty(), "indexing this project should find declarations");

        int filesChecked = 0;
        int linksChecked = 0;
        for (Path file : project.javaFiles()) {
            Path normalized = file.toAbsolutePath().normalize();
            String text = Files.readString(normalized);
            List<Link> links = SymbolLinks.linksIn(index, normalized, text);
            if (links.isEmpty()) {
                continue;
            }
            filesChecked++;
            for (Link link : links) {
                String covered = text.substring(link.start(), link.end());
                // The target carries the name: a declaration's is the tail of its qualified name, a use's is
                // the name itself. Either way the characters under the link must be exactly that identifier.
                String expected = link.target().startsWith(SymbolLinks.DEF)
                        ? SymbolLinks.simpleNameOf(link.target().substring(SymbolLinks.DEF.length()))
                        : link.target().substring(SymbolLinks.USE.length());
                assertEquals(expected, covered,
                        "link at " + link.start() + " in " + normalized.getFileName()
                                + " covers \"" + covered + "\" but stands for \"" + expected + "\"");
                linksChecked++;
            }
        }
        assertTrue(filesChecked > 5, "expected links across many files, got " + filesChecked);
        assertTrue(linksChecked > 100, "expected a substantial number of links, got " + linksChecked);
    }

    /**
     * The round trip the feature actually performs: take a declaration the index found, ask for its links in
     * its own file, and confirm the declaration is among them and is offered as a declaration.
     *
     * <p>{@link SymbolLinks} is what a Ctrl+click resolves against, so a declaration that does not link in its
     * own file is a name that can never be the start of a "who uses this?".
     */
    @Test
    void aKnownDeclarationLinksInItsOwnFileAsADeclaration() throws IOException {
        Index index = IndexBuilder.build(MavenProjectReader.read(root()));

        // A method this test can name without being brittle about line numbers: it is in this repo, and it is
        // the one the whole feature is built around.
        Symbol target = index.namesContaining("linksIn").stream()
                .filter(s -> s.name().equals("linksIn"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the index did not find SymbolLinks.linksIn"));

        Path file = target.file().toAbsolutePath().normalize();
        String text = Files.readString(file);
        List<Link> links = SymbolLinks.linksIn(index, file, text);

        Link declaration = links.stream()
                .filter(l -> l.target().equals(SymbolLinks.DEF + target.qualified()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("linksIn did not link its own declaration"));

        assertEquals("linksIn", text.substring(declaration.start(), declaration.end()));
    }
}
