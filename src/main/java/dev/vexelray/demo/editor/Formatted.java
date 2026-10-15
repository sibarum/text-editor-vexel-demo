package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.FocusRing;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.text.Span;
import sibarum.atchung.Subscription;

import java.util.ArrayList;
import java.util.List;

/**
 * A Markdown document as it reads rather than as it is written: headings set large, emphasis in its faces, lists
 * with their bullets, code on a well of its own. Read-only — what is edited is the source, and this is shown in
 * its place (Ctrl+Shift+V).
 *
 * <p>Built from the framework's ordinary text nodes, which wrap, and their {@link Span}s, which carry weight,
 * slope, colour and underline over a range. The framework's typeset module was the other candidate and is the
 * wrong one: it lays out notation as fixed atoms and does not wrap, which is all prose does.
 *
 * <p>The blocks are rebuilt whole from {@link Markdown#blocks} each time the text is shown. A document in this view
 * is not being typed into, so that is once per toggle, not once per key.
 */
final class Formatted {

    /** Body text: a little larger than the code it replaces, since this is for reading. */
    private static final Length BODY = Length.rem(0.9375f);
    private static final Length[] HEADINGS = {
            Length.rem(1.75f), Length.rem(1.4f), Length.rem(1.175f), Length.rem(1.0f), BODY, BODY};
    /** How far each level of a nested list steps in, in root ems. */
    private static final float INDENT_REM = 1.5f;
    private static final Length MARKER = Length.rem(1.5f);

    private final Gui gui;
    private final Node root;
    private final Subscription ring;
    private Node page;

    Formatted(Gui gui) {
        this.gui = gui;
        this.root = gui.column()
                .width(Length.FILL).height(Length.FILL)
                .scroll(false, true)
                .background(gui.theme().color(Look.CARD))
                .visible(false)
                .focusRing(FocusRing.NONE);
        // It takes the keyboard in the field's place, so it wears the field's quiet ring rather than the accent's.
        gui.focusable(root, true);
        this.ring = Look.ring(gui, root);
    }

    void close() {
        ring.close();
    }

    Node node() {
        return root;
    }

    /** Show {@code markdown}, in place of whatever was shown. */
    void show(String markdown) {
        Node next = gui.column()
                .width(Length.FILL)
                .padding(Type.WIDE, Type.EDGE)
                .gap(Type.GAP);
        for (Markdown.Block b : Markdown.blocks(markdown)) {
            Node n = block(b);
            for (int q = 0; q < b.quote(); q++) {
                n = quoted(n);
            }
            next.append(n);
        }
        Node was = page;
        page = next;
        root.append(next);
        if (was != null) {
            was.remove();
        }
    }

    private Node block(Markdown.Block b) {
        return switch (b.kind()) {
            case HEADING -> heading(b);
            case PARAGRAPH -> prose(b.body(), BODY);
            case ITEM -> item(b);
            case CODE -> code(b.body().text());
            case RULE -> rule(Length.dp(2));
            case TABLE -> table(b.rows());
        };
    }

    private Node heading(Markdown.Block b) {
        Node title = prose(b.body(), HEADINGS[Math.min(b.level(), 6) - 1]).bold();
        if (b.level() > 2) {
            return b.level() == 6 ? title.textColor(color(Role.DIM)) : title;
        }
        // The two top levels are ruled off, as a README is read on the web.
        return gui.column().width(Length.FILL).gap(Length.dp(4))
                .padding(Length.dp(b.level() == 1 ? 6 : 4), Length.ZERO)
                .children(title, rule(Type.RULE));
    }

    private Node item(Markdown.Block b) {
        Node marker = gui.text(b.marker())
                .width(MARKER).textSize(BODY).textColor(color(Role.DIM));
        Node indent = gui.box().width(Length.rem(INDENT_REM * b.level()));
        return gui.row()
                .width(Length.FILL)
                .children(indent, marker, prose(b.body(), BODY));
    }

    private Node code(String text) {
        return gui.text(text)
                .width(Length.FILL)
                .font(Type.MONO).textSize(Type.CODE)
                .textColor(color(Role.INK))
                .wordWrap(true)
                .padding(Type.GAP, Type.WIDE)
                .corner(Type.CORNER)
                .background(color(Role.WELL));
    }

    private Node rule(Length thickness) {
        return gui.box().width(Length.FILL).height(thickness).background(color(Role.LINE));
    }

    /** {@code inner} with a bar down its left, one per level of quoting. */
    private Node quoted(Node inner) {
        Node bar = gui.box().width(Length.dp(3)).height(Length.FILL).corner(Length.dp(1.5f))
                .background(color(Role.EDGE));
        Node body = gui.column().width(Length.FILL).children(inner.textColor(color(Role.DIM)));
        return gui.row().width(Length.FILL).gap(Type.WIDE).alignItems(AlignItems.STRETCH).children(bar, body);
    }

    private Node table(List<List<Markdown.Inline>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        Node grid = gui.column()
                .width(Length.FILL)
                .corner(Type.CORNER)
                .border(Type.RULE, color(Role.LINE))
                .clip(true);
        for (int r = 0; r < rows.size(); r++) {
            List<Markdown.Inline> row = rows.get(r);
            Node line = gui.row().width(Length.FILL);
            if (r == 0) {
                line.background(color(Role.PANEL));
            }
            for (int c = 0; c < columns; c++) {
                Node cell = prose(c < row.size() ? row.get(c) : Markdown.Inline.EMPTY, BODY)
                        .padding(Type.TIGHT, Type.GAP);
                line.append(r == 0 ? cell.bold() : cell);
            }
            grid.append(line);
            if (r < rows.size() - 1) {
                grid.append(rule(Type.RULE));
            }
        }
        return grid;
    }

    /** Wrapped text at {@code size}, its marks drawn as spans. */
    private Node prose(Markdown.Inline text, Length size) {
        List<Span> spans = new ArrayList<>();
        for (Markdown.Style s : text.styles()) {
            // Set in the faces themselves: the editor bakes Noto Sans Bold, Italic and Bold Italic (Recipes.fonts).
            spans.add(switch (s.mark()) {
                case BOLD -> Span.bold(s.start(), s.end());
                case ITALIC -> Span.italic(s.start(), s.end());
                case CODE -> new Span(s.start(), s.end(), color(Role.INK), color(Role.WELL), false);
                case LINK -> new Span(s.start(), s.end(), color(Role.ACCENT), null, true);
                case STRIKE -> Span.foreground(s.start(), s.end(), color(Role.FAINT));
            });
        }
        return gui.text(text.text())
                .width(Length.FILL)
                .textSize(size)
                .textColor(color(Role.INK))
                .wordWrap(true)
                .spans(spans);
    }

    private Color color(Role role) {
        return gui.theme().color(role);
    }
}
