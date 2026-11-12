package dev.vexelray.demo.editor;

import dev.sibarum.suite.git.Status;
import dev.sibarum.suite.git.Status.Change;
import dev.sibarum.suite.git.Status.State;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/** A navigator row's ink, from what git says ({@link Navigator#ink}). */
class GitInkTest {

    @TempDir
    Path root;

    private Status status(Change... changes) {
        return new Status(root, new Status.Branch("main", "0", null, 0, 0), List.of(changes));
    }

    @Test
    void aFileIsInItsOwnStatesInk() {
        Status git = status(new Change("a.txt", null, State.MODIFIED), new Change("b.txt", null, State.UNTRACKED),
                new Change("c.txt", null, State.ADDED), new Change("d.txt", null, State.CONFLICTED),
                new Change("e.log", null, State.IGNORED));
        assertSame(Role.CAUTION, Navigator.ink(git, root.resolve("a.txt"), false));
        assertSame(Role.SUCCESS, Navigator.ink(git, root.resolve("b.txt"), false));
        assertSame(Role.SUCCESS, Navigator.ink(git, root.resolve("c.txt"), false));
        assertSame(Role.DANGER, Navigator.ink(git, root.resolve("d.txt"), false));
        assertSame(Role.FAINT, Navigator.ink(git, root.resolve("e.log"), false));
        assertSame(Role.INK, Navigator.ink(git, root.resolve("clean.txt"), false), "clean is the ink");
    }

    /**
     * Git reports a path as ignored only when it is untracked: a tracked file a {@code .gitignore} also matches comes
     * back as what it is, so it is drawn as that, and a clean one as clean.
     */
    @Test
    void gitsStateWinsOverTheIgnoreFile() {
        Status git = status(new Change("build/kept.txt", null, State.MODIFIED), new Change("build/out.bin", null,
                State.IGNORED));
        assertSame(Role.CAUTION, Navigator.ink(git, root.resolve("build/kept.txt"), false));
        assertSame(Role.FAINT, Navigator.ink(git, root.resolve("build/out.bin"), false));
    }

    @Test
    void whatIsInsideAWholeUntrackedOrIgnoredFolderIsThatFolders() {
        Status git = status(new Change("new/", null, State.UNTRACKED), new Change("out/", null, State.IGNORED));
        assertSame(Role.SUCCESS, Navigator.ink(git, root.resolve("new"), true));
        assertSame(Role.SUCCESS, Navigator.ink(git, root.resolve("new/deep/x.txt"), false));
        assertSame(Role.FAINT, Navigator.ink(git, root.resolve("out/classes"), true));
    }

    @Test
    void aFolderIsTheMostPressingOfWhatIsChangedInsideIt() {
        assertSame(Role.DANGER, Navigator.ink(status(new Change("src/a", null, State.CONFLICTED),
                new Change("src/b", null, State.MODIFIED)), root.resolve("src"), true));
        assertSame(Role.CAUTION, Navigator.ink(status(new Change("src/deep/a", null, State.MODIFIED),
                new Change("src/b", null, State.UNTRACKED)), root.resolve("src"), true));
        assertSame(Role.SUCCESS, Navigator.ink(status(new Change("src/b", null, State.UNTRACKED)),
                root.resolve("src"), true));
        assertSame(Role.INK, Navigator.ink(status(new Change("src/b", null, State.IGNORED)), root.resolve("src"), true),
                "an ignored file inside does not colour its folder");
        assertSame(Role.INK, Navigator.ink(null, root.resolve("src"), true), "no repository, no colour");
    }
}
