package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Check;
import dev.vexelray.demo.editor.core.Messages.Checked;
import dev.vexelray.demo.editor.core.Messages.Problem;
import dev.vexelray.framework.api.Job;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.tooling.PontifProblems;
import sibarum.probe.Log;

import java.nio.file.Path;
import java.util.List;

/**
 * A document's problems, worked out on the job lane: for Pontif, a compile of the text as typed, the same compile
 * {@code pontif run} makes ({@code pontif-compiler}), with the error put where the Pontif Editor puts it
 * ({@code pontif-tooling}'s {@link PontifProblems}). Pontif is the only language checked so far.
 *
 * <p>The text is compiled as the file it came from, by its name and with its folder to find what it
 * {@code requires}, so a sibling module is read from disk while the document itself is what is typed, saved or not.
 *
 * <p>A compile is the whole program each time: well under a millisecond for a small file, 70 ms or so for a large
 * module. The view keeps one check in flight per document, so a burst of typing costs one compile and then one more
 * for where it ended, not one a keystroke. The compiler holds nothing but its rewrite rules, so each check builds its
 * own, and two checks at once share nothing.
 *
 * <p>A compile that fails is an answer, not a throw. So is anything unexpected out of the compiler: it is logged and
 * shown as a problem rather than ending the application, since the person is half-way through typing a program and
 * any text at all is to be expected.
 */
@Job
public final class CheckText {

    private static final Log LOG = Log.of("text-editor-vexel-demo.check");

    /** Whether a file of this name is checked. */
    public static boolean checks(Path path) {
        return path != null && String.valueOf(path.getFileName()).endsWith(".ptf");
    }

    public Checked run(Check check) {
        return new Checked(check.field(), check.version(), problems(check.text(), check.path()));
    }

    static List<Problem> problems(String text, Path path) {
        if (!checks(path)) {
            return List.of();
        }
        Path file = path.toAbsolutePath();
        try {
            return PontifProblems.check(new PontifCompiler(), text, String.valueOf(file.getFileName()),
                            file.getParent())
                    .stream().map(p -> new Problem(p.start(), p.end(), p.message())).toList();
        } catch (RuntimeException | StackOverflowError e) {
            LOG.warn("the Pontif compiler failed on {}: {}", file, e.toString());
            return List.of(new Problem(text.length(), text.length(), "The Pontif compiler failed: " + e));
        }
    }
}
