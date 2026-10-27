package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Markdown.Block;
import dev.vexelray.demo.editor.core.Markdown.Inline;
import dev.vexelray.demo.editor.core.Markdown.Kind;
import dev.vexelray.demo.editor.core.Markdown.Mark;
import dev.vexelray.demo.editor.core.Markdown.Style;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Markdown as the formatted view reads it: blocks, and the marks in their text. */
class MarkdownTest {

    @Test
    void emphasisStrongCodeAndLinksLoseTheirMarkupAndKeepTheirRanges() {
        Inline in = Markdown.inline("a *b* **c** `d` [e](http://x) ~~f~~");
        assertEquals("a b c d e f", in.text());
        assertEquals(List.of(
                new Style(2, 3, Mark.ITALIC),
                new Style(4, 5, Mark.BOLD),
                new Style(6, 7, Mark.CODE),
                new Style(8, 9, Mark.LINK),
                new Style(10, 11, Mark.STRIKE)), in.styles());
    }

    @Test
    void aDelimiterWithNoPartnerIsJustACharacter() {
        assertEquals("2 * 3", Markdown.inline("2 * 3").text());
        assertEquals("snake_case_name", Markdown.inline("snake_case_name").text());
        assertTrue(Markdown.inline("snake_case_name").styles().isEmpty());
        assertEquals("*", Markdown.inline("\\*").text());
    }

    @Test
    void codeIsLiteral() {
        Inline in = Markdown.inline("`**not bold**`");
        assertEquals("**not bold**", in.text());
        assertEquals(List.of(new Style(0, 12, Mark.CODE)), in.styles());
    }

    @Test
    void blocksAreReadInOrder() {
        List<Block> blocks = Markdown.blocks("""
                # Title

                Some text
                that carries on.

                - one
                  - nested
                1. first

                > quoted

                ```java
                int x;
                ```

                ---

                | a | b |
                |---|---|
                | 1 | 2 |
                """);
        assertEquals(List.of(Kind.HEADING, Kind.PARAGRAPH, Kind.ITEM, Kind.ITEM, Kind.ITEM, Kind.PARAGRAPH,
                Kind.CODE, Kind.RULE, Kind.TABLE), blocks.stream().map(Block::kind).toList());
        assertEquals("Some text that carries on.", blocks.get(1).body().text());
        assertEquals(1, blocks.get(3).level());
        assertEquals("1.", blocks.get(4).marker());
        assertEquals(1, blocks.get(5).quote());
        assertEquals("int x;", blocks.get(6).body().text());
        assertEquals(2, blocks.get(8).rows().size());
        assertEquals("2", blocks.get(8).rows().get(1).get(1).text());
    }

    @Test
    void setextHeadingsAndHardBreaks() {
        List<Block> blocks = Markdown.blocks("Title\n=====\n\nline one  \nline two\n");
        assertEquals(Kind.HEADING, blocks.get(0).kind());
        assertEquals(1, blocks.get(0).level());
        assertEquals("line one\nline two", blocks.get(1).body().text());
    }

    @Test
    void htmlCommentsAreNotShown() {
        List<Block> blocks = Markdown.blocks("<!-- a\nnote -->\ntext\n");
        assertEquals(1, blocks.size());
        assertEquals("text", blocks.getFirst().body().text());
    }

    @Test
    void onlyMarkdownFilesAreFormattable() {
        assertTrue(Markdown.handles("README.md"));
        assertTrue(Markdown.handles("notes.MARKDOWN"));
        assertEquals(false, Markdown.handles("pom.xml"));
        assertEquals(false, Markdown.handles(null));
    }
}
