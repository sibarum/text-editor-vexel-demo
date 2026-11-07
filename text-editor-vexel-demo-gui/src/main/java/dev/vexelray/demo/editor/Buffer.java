package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.demo.editor.core.CheckText;
import dev.vexelray.demo.editor.core.Grammars;
import dev.vexelray.demo.editor.core.Markdown;
import dev.vexelray.demo.editor.core.Messages.Check;
import dev.vexelray.demo.editor.core.Messages.Checked;
import dev.vexelray.demo.editor.core.Messages.Format;
import dev.vexelray.demo.editor.core.Messages.Formatted;
import dev.vexelray.demo.editor.core.Messages.Mark;
import dev.vexelray.demo.editor.core.Messages.Marked;
import dev.vexelray.demo.editor.core.Messages.Problem;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.core.text.Whitespace;
import dev.vexelray.gui.widget.Framing;
import dev.vexelray.gui.widget.TextField;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One open file: its {@link TextField}, its colours, and what a save needs to know. The {@link View}'s, on the GUI
 * thread, so it holds plain fields and no locks.
 *
 * <p>The text is the field's own. Whether it is saved is the field's undo history's answer ({@code History.mark()}
 * and {@code clean()}): the history already knows which state was last saved, so this keeps no flag of its own for
 * that beyond the one case the history cannot express, a save that did not land.
 *
 * <h2>It hears its field, through the view</h2>
 *
 * <p>The field says what it did on its own topics, addressed by its name ({@code TextField.CHANGED},
 * {@code TextField.CARET}), and the view hands each to the buffer it is about. Nothing calls back into this: it is
 * told, and it answers with what to send, so the view does the sending.
 *
 * <h2>Colours, off the GUI thread</h2>
 *
 * <p>A file in a format {@link Grammars} knows gets its colours after every edit. That is a pass over the whole text,
 * so it is a job ({@code MarkText}): {@link #changed} says what to ask it, and {@link #marked} applies the answer only
 * if the field still says the text it was computed for, because colours worked out for one text and attached to
 * another are on the wrong characters. Such a file is code, and is shown in the mono face; anything else is plain text,
 * which reads better in the proportional one.
 *
 * <h2>Problems, one check at a time</h2>
 *
 * <p>A file {@link CheckText} checks (Pontif) is compiled as it is typed, also a job. A compile is the whole program,
 * so this keeps one {@link Check} in flight: a change while one is out only notes that the text moved on, and the
 * answer sends the next check, for the text as it is then. A burst of typing costs the check already running and one
 * for where it ended. A problem is drawn as an underline on a faint {@code DANGER} ground, and its message is on the
 * status line ({@link #problem}).
 *
 * <p>The colours and the problems are two answers to two jobs, and the field has one list of spans. Each answer
 * replaces only its own kind, which the problem spans' ground tells apart, and keeps the other as the field has it
 * now: spans follow the edits made since they were set, so whichever answer is the older is still on the right
 * characters.
 */
final class Buffer implements AutoCloseable {

    final long id;
    /** Where the file is: changed only when the editor says it moved ({@link #moved}). */
    Path path;
    final TextField field;

    private final Gui gui;
    /** What the tab shows: the field, or for Markdown the formatted view in its place. */
    private final Node page;
    /** Built only for Markdown, which is all that has a formatted view, the first time the file is; null before. */
    private FormattedView formatted;
    /** Whether the formatted view is shown in place of the field. */
    private boolean showingFormatted;
    /** The text the formatted view was last asked for. */
    private String formattedText;
    /** The TextMate scope of the file's format, or null for plain text. */
    private String scope;
    /**
     * The version of the field's last change. Not the text's identity: a field announces a change, with a new version,
     * for every commit, a caret move or a selection too. So an answer is matched to its question by the version it was
     * asked at, and applied only while the field still says the text it was asked about.
     */
    private long version = -1;
    /** The text the colours were last asked for, so a change that only moved the colours asks nothing; and when. */
    private String marked;
    private long markedAt = -1;
    /** The text the formatted view was last asked for, and when. */
    private long formattedAt = -1;
    /** A save took the bytes and then failed, so the document is unsaved whatever the history's mark says. */
    private boolean unsavedWrite;
    /** Whether the editor was last told this is unsaved. */
    private boolean said;
    /** Where the caret is, 1-based, for the status line when this comes to the front. */
    private int line = 1;
    private int column = 1;
    /** The ground under a problem: the theme's {@code DANGER}, faint, which also tells a problem span from a colour. */
    private final Color problemGround;
    /** Whether a {@link Check} is out and not yet answered. */
    private boolean checking;
    /** The text the last check was sent for, and when; null when this has not been checked as it is named now. */
    private String checkedText;
    private long checkedAt = -1;
    /** What is wrong, as the last answer for the current text said; empty when nothing is. */
    private String problem = "";

    /** {@code readOnly}: a built-in module's source, to be read; see {@code NavigateText.builtins}. */
    Buffer(Gui gui, long id, Path path, String initial, boolean readOnly) {
        this.id = id;
        this.gui = gui;
        this.path = path;
        this.problemGround = Color.withAlpha(gui.theme().color(Role.DANGER), PROBLEM_GROUND);
        this.scope = Grammars.scopeFor(String.valueOf(path.getFileName()));
        // A document: its past is its own, so Ctrl+Z in one file never reaches into another, and its save mark
        // says whether this file is saved rather than whether the window is. Named, so its messages say which.
        // Multiline is also what gives it Ctrl+F. Whitespace dotted where it means something or hides a mistake:
        // indentation, what trails a line, runs of blanks; and a ¬ at each line's end.
        this.field = new TextField(gui, "").name("buffer-" + id).history(new History()).multiline(true)
                .lineNumbers(true).wordWrap(true).whitespace(Whitespace.BOUNDARY).lineEnds(true)
                .readOnly(readOnly);
        field.node().width(Length.FILL).height(Length.FILL).font(scope != null ? Type.MONO : Type.UI)
                .textSize(Type.CODE);
        // The text sits on the card, not in a well of its own: the card is the document's edge. Flush keeps the well's
        // room, as FormattedView's border does, so switching views moves nothing.
        field.frame(Framing.FLUSH);
        this.formatted = Markdown.handles(name()) ? new FormattedView(gui) : null;
        this.page = gui.column().width(Length.FILL).height(Length.FILL).children(field.node());
        if (formatted != null) {
            page.append(formatted.node());
        }

        // Content this field has no past with, so text() rather than replace(): the history starts here, and the
        // mark says this is what is on disk.
        field.text(initial);
        field.caret(0);
        field.history().mark();
    }

    /** The field's address, which its messages carry. */
    String address() {
        return field.address();
    }

    String name() {
        return String.valueOf(path.getFileName());
    }

    /** The field changed: what to ask {@code MarkText}, or null when the colours are current or it is plain text. */
    Mark changed(String now, long at) {
        version = at;
        if (scope == null || now.equals(marked)) {
            return null;
        }
        marked = now;
        markedAt = at;
        return new Mark(address(), now, at, scope);
    }

    /** What the tab shows: the field, or the formatted view in its place. */
    Node node() {
        return page;
    }

    /** What has the keyboard when this document is in front: the field, or the formatted view in its place. */
    Node keyboard() {
        return showingFormatted ? formatted.node() : field.node();
    }

    /** Whether this document has a formatted view: whether it is Markdown, by its name. */
    boolean formattable() {
        return formatted != null && Markdown.handles(name());
    }

    /**
     * The file is at {@code to} now, moved or renamed: the document stays as it is, unsaved work and all, and takes the
     * format the new name says, its face and its colours with it. What to ask {@code MarkText}, or null when it is plain
     * text now, whose colours are taken off.
     */
    Mark moved(Path to) {
        path = to;
        scope = Grammars.scopeFor(name());
        field.node().font(scope != null ? Type.MONO : Type.UI);
        if (showingFormatted && !Markdown.handles(name())) {
            showFormatted(false);
        }
        if (formatted == null && Markdown.handles(name())) {
            formatted = new FormattedView(gui);
            formatted.node().visible(false);
            page.append(formatted.node());
        }
        marked = null;
        checkedText = null;   // a new name may be checked, or checked differently (what it requires is beside it)
        if (!CheckText.checks(to)) {
            showProblems(List.of());
        }
        if (scope == null) {
            field.setSpans(List.of());
            return null;
        }
        marked = field.text();
        markedAt = version;
        return new Mark(address(), marked, version, scope);
    }

    boolean showingFormatted() {
        return showingFormatted;
    }

    /** The formatted view, or null when this is not Markdown. */
    FormattedView formatted() {
        return formatted;
    }

    /**
     * Show the document formatted, or as its source: what to ask {@code FormatText} for, or null when going back to
     * the source. The view keeps showing the last blocks it had until the answer lands, which on a switch back on is
     * the right ones unless the text changed in between.
     */
    Format showFormatted(boolean on) {
        showingFormatted = on;
        field.node().visible(!on);
        formatted.node().visible(on);
        if (!on) {
            return null;
        }
        formattedText = field.text();
        formattedAt = version;
        return new Format(address(), formattedText, version);
    }

    /**
     * The field changed under the formatted view (nothing is typed while it is shown, but a revert from disk still
     * changes it): what to ask {@code FormatText} for again, or null when the source is what is shown or only the
     * colours changed.
     */
    Format reformat(String now) {
        if (!showingFormatted || now.equals(formattedText)) {
            return null;
        }
        formattedText = now;
        formattedAt = version;
        return new Format(address(), now, version);
    }

    /** {@code FormatText}'s answer: shown only if the field still says the text it was read from. */
    void formatted(Formatted m) {
        if (formatted != null && m.version() == formattedAt && field.text().equals(formattedText)) {
            formatted.show(m.blocks());
        }
    }

    /**
     * {@code MarkText}'s answer: applied only if the field still says the text it was worked out for. The problems
     * the field shows stay as they are.
     */
    void marked(Marked m) {
        if (m.version() != markedAt || !field.text().equals(marked)) {
            return;
        }
        List<Span> next = new ArrayList<>(m.spans());
        next.addAll(spans(true));
        if (!next.equals(field.spans())) {
            field.setSpans(next);
        }
    }

    /** How strongly a problem's ground shows. */
    private static final float PROBLEM_GROUND = 0.22f;

    /**
     * The field changed, or this came to need checking: what to ask {@code CheckText}, or null when this is not a
     * file it checks, the current text has been asked about, or a check is out already (its answer asks again).
     */
    Check check() {
        String now = field.text();
        if (!CheckText.checks(path) || checking || now.equals(checkedText)) {
            return null;
        }
        checking = true;
        checkedText = now;
        checkedAt = version;
        return new Check(address(), now, version, path);
    }

    /**
     * {@code CheckText}'s answer: shown if the field still says the text it was about, else left for the next. Then
     * the next check, if the text moved on while this one was out, or null.
     */
    Check checked(Checked m) {
        checking = false;
        if (m.version() == checkedAt && field.text().equals(checkedText)) {
            showProblems(m.problems());
        }
        return check();
    }

    /** Show {@code problems} in place of the ones shown, keeping the colours as the field has them. */
    private void showProblems(List<Problem> problems) {
        List<Span> next = new ArrayList<>(spans(false));
        int length = field.text().length();
        for (Problem p : problems) {
            if (p.start() < p.end() && p.end() <= length) {
                next.add(new Span(p.start(), p.end(), null, problemGround, true));
            }
        }
        problem = problems.isEmpty() ? "" : firstLine(problems.getFirst().message());
        if (!next.equals(field.spans())) {
            field.setSpans(next);
        }
    }

    /** The field's spans that are problems ({@code true}), or the ones that are not. */
    private List<Span> spans(boolean problems) {
        List<Span> out = new ArrayList<>();
        for (Span s : field.spans()) {
            if (isProblem(s) == problems) {
                out.add(s);
            }
        }
        return out;
    }

    private boolean isProblem(Span s) {
        return s.underline() && problemGround.equals(s.bg());
    }

    private static String firstLine(String message) {
        String s = message.strip();
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl).strip();
    }

    /** What is wrong with the text, for the status line; empty when nothing is, or this is not checked. */
    String problem() {
        return problem;
    }

    /** The version of the field's last change, which an answer about its text has to match. */
    long version() {
        return version;
    }

    /** Whether this is a built-in module's source, shown to be read and never edited. */
    boolean readOnly() {
        return field.readOnly();
    }

    /**
     * Put {@code insert} in place of {@code [start, end)}, as one edit Ctrl+Z takes back, the caret staying on the text
     * it was on. False, and nothing done, if the field no longer says {@code asked}, the text the edit was worked out
     * from, or this is read-only.
     */
    boolean edit(String asked, int start, int end, String insert) {
        if (!field.text().equals(asked) || readOnly() || start < 0 || end < start || end > field.text().length()) {
            return false;
        }
        int caret = field.caret();
        field.select(start, end);
        field.insert(insert);
        int shift = insert.length() - (end - start);
        field.caret(caret < start ? caret : caret >= end ? caret + shift : start + insert.length());
        return true;
    }

    /**
     * Select {@code [start, end)}, a declaration found on disk, and give it the keyboard. When this has unsaved changes
     * the range may have moved, so the {@code name} nearest it is selected instead.
     */
    void reveal(int start, int end, String name) {
        String text = field.text();
        int at = start;
        if (dirty() && !name.isEmpty() && !text.startsWith(name, start)) {
            at = nearest(text, name, start);
            end = at + name.length();
        }
        field.select(Math.min(at, text.length()), Math.min(end, text.length()));
        gui.focus(keyboard());
    }

    /** The whole-word occurrence of {@code name} nearest {@code to}, or {@code to} if there is none. */
    private static int nearest(String text, String name, int to) {
        int best = -1;
        for (int i = text.indexOf(name); i >= 0; i = text.indexOf(name, i + 1)) {
            boolean whole = (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)) && text.charAt(i - 1) != '_')
                    && (i + name.length() == text.length() || !Character.isLetterOrDigit(text.charAt(i + name.length()))
                    && text.charAt(i + name.length()) != '_');
            if (whole && (best < 0 || Math.abs(i - to) < Math.abs(best - to))) {
                best = i;
            }
        }
        return best < 0 ? to : best;
    }

    /** The caret moved. */
    void caret(int atLine, int atColumn) {
        line = atLine;
        column = atColumn;
    }

    /** Where the caret is, for the status line. */
    String position() {
        return "Ln " + line + ", Col " + column;
    }

    /** The file's format, for the status line: "Java", or "Plain text". */
    String language() {
        return Grammars.nameOf(scope);
    }

    /** Whether closing this would lose work. */
    boolean dirty() {
        return unsavedWrite || !field.history().clean();
    }

    /** Whether {@link #dirty} differs from what the editor was last told; it is remembered as told. */
    boolean dirtyChanged() {
        boolean now = dirty();
        if (now == said) {
            return false;
        }
        said = now;
        return true;
    }

    /**
     * The text to write, and the history marked clean at this point, before the write and not after it. Typing that
     * lands while the text is on its way to disk is not in it, so it has to stay unsaved, and {@code mark()} marks
     * whatever the history is when it is called. {@link #saveFailed} undoes the claim if the write does not land. The
     * line endings are the editor's to put back, since it knows how the file had them.
     */
    String takeForSave() {
        unsavedWrite = false;
        field.history().mark();
        return field.text();
    }

    /** The write {@link #takeForSave} was for did not land. */
    void saveFailed() {
        unsavedWrite = true;
    }

    @Override
    public void close() {
        field.close();
        if (formatted != null) {
            formatted.close();
        }
    }
}
