package dev.vexelray.demo.editor;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.text.Link;
import dev.vexelray.gui.widget.TextField;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.Reference;
import sibarum.concordance.index.SourceRef;
import sibarum.concordance.index.Symbol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concordance's index as hyperlinks on a document: hold Ctrl and every name the index knows underlines itself,
 * Ctrl+click follows it.
 *
 * <p>Two things can be under the pointer, and they mean different things:
 *
 * <ul>
 *   <li>A <b>use</b> of a name — following it goes to the declaration.</li>
 *   <li>The <b>declaration</b> itself — there is nowhere to go, so following it asks the other question
 *       instead: who uses this? That runs {@code usages} in the console, where the answer arrives as rows
 *       carrying a {@code path} column and pipes into {@code edit} like every other query in this
 *       application.</li>
 * </ul>
 *
 * <h2>Why the answer is a console line and not a popup</h2>
 * A menu cannot hold the answer. {@code usages} on a name like {@code close} returns hundreds of rows, and
 * Concordance matches names without a resolver, so that is the ordinary case rather than the pathological one.
 * The console already presents exactly this — rows that can be filtered with {@code where}, cut with
 * {@code first}, and opened with {@code edit} — so following a declaration types the query rather than
 * inventing a second, worse list beside the one that already works.
 *
 * <h2>What is and is not recomputed</h2>
 * Links are built when a document is given a file and when an index lands, and at no other time. <b>Not on
 * every keystroke</b>, deliberately: the framework remaps links through edits itself, so they stay attached to
 * their words as the text is typed, and the index behind them is a snapshot that typing does not improve. A
 * rebuild per keystroke would cost the whole file's references to produce the same links slightly later.
 *
 * <p>Threading follows {@link Highlighter} exactly, because the hazard is the same one: the work runs on a
 * {@link Gui#async} worker, a generation counter drops superseded runs, and the result is only committed if the
 * field still holds the text it was computed against.
 */
final class SymbolLinks {

    /**
     * Prefix on a link whose text is a <em>use</em> of a name. The rest is the simple name, which is all
     * Concordance can match on.
     */
    static final String USE = "use:";

    /**
     * Prefix on a link whose text is the <em>declaration</em>. The rest is the fully-qualified name, which is
     * what the declaration has and a use does not — and what the answer leads with.
     */
    static final String DEF = "def:";

    private final Gui gui;
    private final TextField editor;
    private final SourceIndex source;
    private final Host host;

    private final AtomicLong generation = new AtomicLong();
    private volatile Path file;
    private volatile boolean closed;

    /** Held so it can be removed on close: a workspace that has gone away must stop being told about indexes. */
    private final Runnable onIndexBuilt = this::refresh;

    /**
     * What following a link needs from the application, which is more than a document knows.
     *
     * <p>An interface rather than the window itself, for the reason {@code ConsoleContext} is one: this needs
     * three things done and has no business reaching for anything else.
     */
    interface Host {
        /** Open {@code file} and put the caret on {@code name}, which was recorded on {@code line}. */
        void jump(Path file, String name, int line);

        /** Say something on the status line — what happens when a link leads nowhere. */
        void say(String message);

        /**
         * Ask the console to run {@code line}, echoing it. False when there is no console to ask, which is
         * every arrangement of this editor that is not hosted by MainFrame.
         */
        boolean shell(String line);

        /**
         * Write {@code line} into the console without running it.
         *
         * <p>Separate from {@link #say} because the status line is the wrong place for this: it is one line
         * shared with <b>Saved</b>, <b>Opened</b> and every warning, and a hint written there lands on top of
         * whatever the reader actually asked for. Ctrl is held for Ctrl+S as often as for a link.
         */
        void note(String line);
    }

    /** Watches the held modifier, so that arming links on a document with none can say why there are none. */
    private final sibarum.atchung.Subscription modifierSub;

    /** Whether the hint below has already been given for this document; it is worth saying once, not always. */
    private volatile boolean hinted;

    SymbolLinks(Gui gui, TextField editor, SourceIndex source, Host host) {
        this.gui = gui;
        this.editor = editor;
        this.source = source;
        this.host = host;
        editor.onLinkActivate(this::follow);
        source.onBuilt(onIndexBuilt);
        // Holding Ctrl over a document with no links is the one moment we know the reader is looking for them,
        // and it is exactly the moment nothing happens: with no index there are no links, so there is nothing
        // under the pointer to click and `follow` is never reached. Without this the feature's whole failure
        // mode is silence -- no underline, no message, nothing to search for.
        this.modifierSub = gui.modifiers().onCommit(held -> {
            if (held.value().contains(sibarum.tactroller.api.Modifier.CONTROL)) {
                hintIfNothingToFollow();
            }
        });
    }

    /**
     * Say why this document has no links, if it has none and could have had some.
     *
     * <p>Only for a document that is a file: an untitled buffer has nothing to be indexed and saying so would
     * be noise. Once per document, and only while there is still no index — once one lands the links appear
     * and the situation speaks for itself.
     */
    private void hintIfNothingToFollow() {
        if (closed || hinted || file == null || !editor.links().isEmpty()) {
            return;
        }
        hinted = true;
        if (!source.present()) {
            host.note("Ctrl+click needs an index. Run: index " + projectRootOf(file));
        } else {
            host.note(file.getFileName() + " has no indexed names - the index is of "
                    + source.root() + " (" + source.status() + ")");
            host.note("    To index the project this file is in: index " + projectRootOf(file));
        }
    }

    /** This document is {@code file} now — after an open, or a save-as. Null for an unsaved document. */
    void file(Path path) {
        this.file = path == null ? null : path.toAbsolutePath().normalize();
        hinted = false;   // a different document is a different answer to "why are there no links here?"
        refresh();
    }

    /** Stop: in-flight and future refreshes become no-ops, and no further index is announced here. */
    void close() {
        closed = true;
        generation.incrementAndGet();
        source.removeListener(onIndexBuilt);
        modifierSub.close();
    }

    /** Whether {@link #close} has been called. Package-private for the tests, as {@link Highlighter}'s is. */
    boolean closed() {
        return closed;
    }

    /** Rebuild this document's links on a worker, and commit them if the text has not moved under us. */
    void refresh() {
        long gen = generation.incrementAndGet();
        gui.async(() -> {
            if (closed || generation.get() != gen) {
                return;   // superseded before it started, or the tab is gone
            }
            Path of = file;
            Index index = source.index();
            String text = editor.text();
            List<Link> links = of == null || index == null ? List.of() : linksIn(index, of, text);
            if (generation.get() == gen && editor.text().equals(text) && !links.equals(editor.links())) {
                editor.links(links);
            }
        });
    }

    // --- building ----------------------------------------------------------------------------------

    /**
     * Every name in {@code text} that the index places in {@code file}: the declarations made there, and the
     * uses written there.
     *
     * <p>Declarations first, so that where a declaration and a use land on the same characters the declaration
     * wins — {@link TextField#linkAt} takes the first link that covers an offset, and the more specific claim
     * about a piece of text is that it is where the thing is defined.
     */
    static List<Link> linksIn(Index index, Path file, String text) {
        int[] lineStarts = lineStarts(text);
        List<Link> links = new ArrayList<>();
        for (Symbol symbol : index.declaredIn(file)) {
            add(links, text, lineStarts, symbol.at(), symbol.name(), DEF + symbol.qualified());
        }
        for (Reference reference : index.references()) {
            if (sameFile(reference.at().file(), file)) {
                add(links, text, lineStarts, reference.at(), reference.name(), USE + reference.name());
            }
        }
        return links;
    }

    /**
     * Add a link over {@code name} at {@code at}, if the name is really there.
     *
     * <p><b>The recorded column is a hint, not an address.</b> The index is a snapshot of the file as it was
     * when {@code index} ran, and the document may have been typed in since; a file written with hard tabs
     * also counts columns differently from the way this document holds them. So the column is tried first and
     * the line is searched only if that misses, and a name that is on neither is dropped rather than
     * underlining whatever now occupies those characters.
     */
    private static void add(List<Link> links, String text, int[] lineStarts, SourceRef at, String name,
                            String target) {
        int line = at.line() - 1;   // SourceRef counts from one, as an editor does
        if (name == null || name.isEmpty() || line < 0 || line >= lineStarts.length) {
            return;
        }
        int start = lineStarts[line];
        int end = line + 1 < lineStarts.length ? lineStarts[line + 1] : text.length();
        int at0 = start + Math.max(0, at.column() - 1);
        int found = at0 + name.length() <= end && text.startsWith(name, at0) && whole(text, at0, name.length())
                ? at0
                : wholeWordIn(text, name, start, end);
        if (found >= 0) {
            links.add(new Link(found, found + name.length(), target));
        }
    }

    /**
     * The first whole-word occurrence of {@code name} in {@code [from, to)}, or -1.
     *
     * <p>Package-private because {@link Workspace#caretOn} needs exactly this and must not need a second,
     * subtly different, copy of it: a jump that landed on the {@code area} inside {@code areaOf} would be a
     * link that underlined one thing and went to another.
     */
    static int wholeWordIn(String text, String name, int from, int to) {
        for (int i = text.indexOf(name, from); i >= 0 && i + name.length() <= to;
                i = text.indexOf(name, i + 1)) {
            if (whole(text, i, name.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code [at, at+length)} is bounded by non-identifier characters on both sides. */
    private static boolean whole(String text, int at, int length) {
        boolean before = at == 0 || !isIdentifierChar(text.charAt(at - 1));
        boolean after = at + length >= text.length() || !isIdentifierChar(text.charAt(at + length));
        return before && after;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /** The offset each line starts at. Index {@code i} is line {@code i+1} as the index counts them. */
    static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = starts.get(i);
        }
        return out;
    }

    /** Whether two paths name the same file, compared absolutely — the index's roots need not match ours. */
    private static boolean sameFile(Path a, Path b) {
        return a != null && a.toAbsolutePath().normalize().equals(b);
    }

    // --- following ---------------------------------------------------------------------------------

    /**
     * Follow {@code link}: to the declaration if this was a use of one, or to the question of who uses it if
     * this was the declaration.
     *
     * <p>Runs on the handler executor, like every other application callback on the field.
     */
    private void follow(Link link) {
        Index index = source.index();
        if (index == null) {
            host.say("No index yet - run: index .");
            return;
        }
        String target = link.target();
        if (target.startsWith(DEF)) {
            String qualified = target.substring(DEF.length());
            askUsages(qualified, simpleNameOf(qualified));
        } else if (target.startsWith(USE)) {
            goToDefinition(index, target.substring(USE.length()));
        }
    }

    /**
     * Go to where {@code name} is declared.
     *
     * <p>Three outcomes, and the middle one is the reason this is not just a jump. Concordance has no
     * resolver, so a common method name is declared in many places and none of them is more right than the
     * others. Guessing would take the reader somewhere plausible and wrong, which is worse than being asked;
     * so a name with several declarations puts them in the console, where they can be read and opened.
     */
    private void goToDefinition(Index index, String name) {
        List<Symbol> declarations = index.namesContaining(name).stream()
                .filter(symbol -> symbol.name().equals(name))
                .toList();
        if (declarations.isEmpty()) {
            host.say("No declaration of " + name + " in the index");
            return;
        }
        if (declarations.size() == 1) {
            Symbol only = declarations.getFirst();
            host.jump(only.file(), only.name(), only.line());
            host.say(only.qualified());
            return;
        }
        String line = "names \"" + name + "\" | where name == \"" + name + "\"";
        if (host.shell(line)) {
            host.say(declarations.size() + " declarations of " + name + " - listed in the console");
        } else {
            Symbol first = declarations.getFirst();
            host.jump(first.file(), first.name(), first.line());
            host.say(declarations.size() + " declarations of " + name + " - showing the first");
        }
    }

    /**
     * Ask who uses {@code simple}, leading with the fully-qualified name of what was clicked.
     *
     * <p>The FQN is the part the document cannot show: a declaration in a file is a bare name, and which
     * {@code Chapter} it is only becomes visible here.
     */
    private void askUsages(String qualified, String simple) {
        if (host.shell("usages " + simple)) {
            host.say(qualified);
        } else {
            host.say(qualified + " - open the console to list its usages");
        }
    }

    /**
     * The name to search the index for, given a qualified one: {@code a.b.C#m(int)} is {@code m}, and
     * {@code a.b.C} is {@code C}.
     *
     * <p><b>A constructor is the exception, and it is not a corner case.</b> Concordance qualifies one as
     * {@code a.b.C#<init>()}, which is not a name anything is written with: the characters in the file say
     * {@code C}, and a use of it is recorded as a {@code CONSTRUCT} reference to {@code C}. So a member called
     * {@code <init>} resolves to its owner's simple name instead, which is both what the document shows and
     * what {@code usages} will match. Asking for {@code usages <init>} would have found nothing, every time.
     */
    static String simpleNameOf(String qualified) {
        String owner = qualified;
        String member = null;
        int hash = qualified.indexOf('#');
        if (hash >= 0) {
            owner = qualified.substring(0, hash);
            member = qualified.substring(hash + 1);
        }
        if (member != null && !member.startsWith(CONSTRUCTOR)) {
            int paren = member.indexOf('(');
            return paren >= 0 ? member.substring(0, paren) : member;
        }
        // A type, or a constructor: either way the answer is the type's own simple name.
        int paren = owner.indexOf('(');
        String type = paren >= 0 ? owner.substring(0, paren) : owner;
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    /** How the index writes a constructor's member name. Not a name anything in a file is written with. */
    private static final String CONSTRUCTOR = "<init>";

    /**
     * The Maven root {@code file} sits in: the top of the <em>unbroken run</em> of directories above it that
     * hold a {@code pom.xml}, or its own directory if none do.
     *
     * <p>Not the nearest pom, because a module's own pom is not the thing to index: indexing the reactor root
     * indexes the module <em>and everything it is built with</em>, and an index of the module alone could not
     * resolve half of what the reader clicked.
     *
     * <p>But not simply the outermost pom either, and that distinction is load-bearing. Walking all the way up
     * means one stray {@code pom.xml} in any ancestor — a home directory, a temp directory, a folder where two
     * checkouts happen to sit under something that was once a project — silently captures every file beneath
     * it, and the editor indexes an enormous tree that has nothing to do with the file that was opened. A
     * reactor is nested modules, each with a pom, so the chain of poms is continuous from the file up to the
     * root and <b>breaks immediately above it</b>. Stopping at the break is what makes an ancestor's stray pom
     * none of our business.
     */
    static Path projectRootOf(Path file) {
        Path root = null;
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (java.nio.file.Files.isRegularFile(dir.resolve("pom.xml"))) {
                root = dir;
            } else if (root != null) {
                break;   // the run of poms ended: the last one we saw is the top of this reactor
            }
        }
        return root != null ? root : file.getParent();
    }
}
