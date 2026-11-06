package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Navigate;
import dev.vexelray.demo.editor.core.Messages.Navigated;
import dev.vexelray.demo.editor.core.Messages.Navigated.Kind;
import dev.vexelray.framework.api.Job;
import sibarum.pontif.tooling.DefinitionNavigator;
import sibarum.pontif.tooling.PontifTokens;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Ctrl+Enter on a name in a Pontif document: go to its definition, or add the {@code requires} that brings it in. The
 * Pontif Editor's navigate-or-import, through the same {@code pontif-tooling} {@link DefinitionNavigator}, over the
 * built-in modules and the document's own folder:
 *
 * <ul>
 *   <li>declared in this document: the place in it;</li>
 *   <li>in scope (declared here or already required): the declaration, in the file beside this one that has it, or
 *       in a built-in module's source;</li>
 *   <li>not in scope and exported by one module: the edit that requires it from there;</li>
 *   <li>exported by several: their names, to choose from by hand (a menu at the caret is FN-14);</li>
 *   <li>not importable but declared somewhere it can be found: the declaration;</li>
 *   <li>a primitive, or nothing found: something to say.</li>
 * </ul>
 *
 * <p>A built-in has no file, so its source is written to {@link #builtins}, beside the scratches in the IDE's home,
 * and opened from there. A document in that folder is read-only ({@code Buffer}), and is written afresh each time, so
 * it always says what the compiler the IDE carries says.
 *
 * <p>A job because the search parses the folder's modules and may write a file. Nothing found is an answer.
 */
@Job
public final class NavigateText {

    private static final Log LOG = Log.of("text-editor-vexel-demo.navigate");

    /** The folder, in the IDE's home, that built-in modules' sources are opened from. */
    static final String BUILTINS = "pontif-builtins";

    private final Scratches scratches;

    public NavigateText(Scratches scratches) {
        this.scratches = scratches;
    }

    /** Where built-in modules' sources are written to be read: beside the scratches, in the IDE's home. */
    public static Path builtins(Scratches scratches) {
        return scratches.dir().resolveSibling(BUILTINS);
    }

    public Navigated run(Navigate n) {
        if (!CheckText.checks(n.path())) {
            return Navigated.say(n, "Go to definition is for Pontif files");
        }
        int[] word = wordAt(n.text(), n.caret());
        if (word == null) {
            return Navigated.say(n, "Put the caret on a name to go to its definition");
        }
        String name = n.text().substring(word[0], word[1]);
        try {
            return navigate(n, name);
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
            LOG.warn("going to {} in {} failed: {}", name, n.path(), e.toString());
            return Navigated.say(n, "Could not look up '" + name + "': " + e);
        }
    }

    private Navigated navigate(Navigate n, String name) {
        String text = n.text();
        if (DefinitionNavigator.isPrimitive(name)) {
            return Navigated.say(n, "'" + name + "' is built into the language: it has no source and needs no import");
        }
        Optional<int[]> local = DefinitionNavigator.localDeclaration(text, name);
        if (local.isPresent()) {
            return new Navigated(n.field(), n.version(), Kind.HERE, Optional.empty(), local.get()[0], local.get()[1],
                    "", "");
        }
        Path dir = n.path().toAbsolutePath().getParent();
        DefinitionNavigator navigator = DefinitionNavigator.builtinsOnly();
        if (DefinitionNavigator.inScope(text, name)) {
            return open(n, name, navigator.resolve(text, name, dir), dir);
        }
        List<String> exporters = navigator.exporters(text, name, dir);
        if (exporters.size() == 1) {
            return imported(n, name, exporters.getFirst());
        }
        if (exporters.size() > 1) {
            return Navigated.say(n, "'" + name + "' is exported by " + String.join(" and ", exporters)
                    + ": add the requires for the one you mean");
        }
        return open(n, name, navigator.resolve(text, name, dir), dir);
    }

    /** The declaration {@code found}, in this document, in a file beside it, or in a built-in's source. */
    private Navigated open(Navigate n, String name, Optional<DefinitionNavigator.Target> found, Path dir) {
        if (found.isEmpty()) {
            return Navigated.say(n, "No definition of '" + name + "', and no module that exports it");
        }
        DefinitionNavigator.Target t = found.get();
        String label = t.moduleLabel();
        if (label.equals("(this file)")) {
            return new Navigated(n.field(), n.version(), Kind.HERE, Optional.empty(), t.selStart(), t.selEnd(), "", "");
        }
        Path file;
        if (label.startsWith("builtin ")) {
            String module = label.substring("builtin ".length());
            file = builtins(scratches).resolve(module + ".ptf");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, t.sourceText().replace("\r\n", "\n"));
            } catch (IOException e) {
                return Navigated.say(n, "Could not write the source of " + module + " to " + file + ": " + e);
            }
        } else {
            file = dir.resolve(label);
        }
        // The editor shows a file with \r\n read as \n, so a place in it is counted that way too.
        String source = t.sourceText();
        return new Navigated(n.field(), n.version(), Kind.THERE, Optional.of(file), shown(source, t.selStart()),
                shown(source, t.selEnd()), "", "'" + name + "' is declared in " + label);
    }

    /** {@code offset} in {@code text}, counted as the editor counts it: each {@code \r\n} as one character. */
    static int shown(String text, int offset) {
        int crs = 0;
        for (int i = 0; i + 1 < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\r' && text.charAt(i + 1) == '\n') {
                crs++;
            }
        }
        return offset - crs;
    }

    /** The edit that requires {@code name} from {@code module}: the part of the text that changes, and what with. */
    private static Navigated imported(Navigate n, String name, String module) {
        DefinitionNavigator.RequiresEdit edit = DefinitionNavigator.insertRequires(n.text(), module, name);
        if (!edit.changed()) {
            return Navigated.say(n, edit.message());
        }
        String before = n.text();
        String after = edit.text();
        int start = 0;
        while (start < before.length() && start < after.length() && before.charAt(start) == after.charAt(start)) {
            start++;
        }
        int endBefore = before.length();
        int endAfter = after.length();
        while (endBefore > start && endAfter > start && before.charAt(endBefore - 1) == after.charAt(endAfter - 1)) {
            endBefore--;
            endAfter--;
        }
        return new Navigated(n.field(), n.version(), Kind.IMPORT, Optional.empty(), start, endBefore,
                after.substring(start, endAfter), edit.message() + "; Ctrl+Enter again to go to it");
    }

    /** The identifier at {@code caret}, or just before it (a caret resting at a word's end counts); null for none. */
    public static int[] wordAt(String text, int caret) {
        int at = Math.max(0, Math.min(caret, text.length()));
        int probe = at < text.length() && PontifTokens.isIdentPart(text.charAt(at)) ? at
                : at > 0 && PontifTokens.isIdentPart(text.charAt(at - 1)) ? at - 1 : -1;
        if (probe < 0) {
            return null;
        }
        int start = probe;
        while (start > 0 && PontifTokens.isIdentPart(text.charAt(start - 1))) {
            start--;
        }
        int end = probe + 1;
        while (end < text.length() && PontifTokens.isIdentPart(text.charAt(end))) {
            end++;
        }
        return PontifTokens.isIdentStart(text.charAt(start)) ? new int[] {start, end} : null;
    }
}
