package dev.vexelray.demo.editor.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoriesTest {

    @TempDir
    Path home;

    private static final Repositories.Limits WIDE = new Repositories.Limits(6, 10_000, 50, Long.MAX_VALUE / 2);

    /** A repository at {@code relative} under the home: a folder with {@code .git} in it. */
    private Path repo(String relative) throws IOException {
        Path p = home.resolve(relative);
        Files.createDirectories(p.resolve(".git"));
        return p;
    }

    private List<Path> found = new ArrayList<>();

    private Repositories.Walked walk(Repositories.Limits limits) {
        return Repositories.walk(home, limits, (batch, folders) -> found.addAll(batch), () -> false, System::nanoTime);
    }

    @Test
    void nearerRepositoriesComeFirstAndNoneIsLookedForInsideOne() throws IOException {
        Path deep = repo("work/clients/acme/site");
        Path near = repo("zeta");
        repo("zeta/vendor/lib");                      // inside a repository: not looked for
        Path worktree = home.resolve("tree");
        Files.createDirectories(worktree);
        Files.writeString(worktree.resolve(".git"), "gitdir: elsewhere");  // a worktree's .git is a file

        Repositories.Walked w = walk(WIDE);
        assertEquals(List.of(worktree, near, deep), found, "breadth first, by name within a level");
        assertEquals(Repositories.End.DONE, w.end());
        assertEquals(3, w.found());
    }

    @Test
    void hiddenAndToolFoldersAreNotWalked() throws IOException {
        repo(".cache/thing");
        repo("AppData/Local/thing");
        repo("project/node_modules/dep");
        Path real = repo("project/app");
        walk(WIDE);
        assertEquals(List.of(real), found);
    }

    @Test
    void aWalkStopsAtItsLimits() throws IOException {
        repo("a/b/c/d");
        Repositories.Walked shallow = walk(new Repositories.Limits(3, 10_000, 50, Long.MAX_VALUE / 2));
        assertTrue(found.isEmpty(), "deeper than the depth: " + found);
        assertEquals(Repositories.End.DONE, shallow.end(), "every folder within the depth was looked at");

        for (int i = 0; i < 5; i++) {
            repo("many/r" + i);
        }
        found.clear();
        Repositories.Walked few = walk(new Repositories.Limits(6, 10_000, 2, Long.MAX_VALUE / 2));
        assertEquals(2, found.size());
        assertEquals(Repositories.End.LIMIT, few.end());

        found.clear();
        Repositories.Walked one = walk(new Repositories.Limits(6, 1, 50, Long.MAX_VALUE / 2));
        assertEquals(1, one.folders());
        assertEquals(Repositories.End.LIMIT, one.end());
    }

    @Test
    void aWalkAskedToStopStops() throws IOException {
        repo("a");
        Repositories.Walked w = Repositories.walk(home, WIDE, (batch, folders) -> found.addAll(batch), () -> true,
                System::nanoTime);
        assertEquals(Repositories.End.STOPPED, w.end());
        assertTrue(found.isEmpty());
    }

    @Test
    void aWalkOutOfTimeStops() throws IOException {
        repo("a");
        long[] now = {0};
        Repositories.Walked w = Repositories.walk(home, new Repositories.Limits(6, 10_000, 50, 10),
                (batch, folders) -> found.addAll(batch), () -> false, () -> now[0] += 100);
        assertEquals(Repositories.End.LIMIT, w.end());
    }
}
