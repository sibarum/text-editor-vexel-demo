package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The job against a real repository, made fresh for each test. Skipped where git is not installed. */
class ReadStatusTest {

    private final Git git = Git.onPath();

    @TempDir
    Path dir;

    @Test
    void countsEachChangedPathOnceAndEachSideOnItsOwn() throws IOException {
        Path repo = repository();
        Files.writeString(repo.resolve("a.txt"), "changed\n");
        git(repo, "add", "a.txt");
        Files.writeString(repo.resolve("a.txt"), "changed again\n");
        Files.writeString(repo.resolve("new.txt"), "n\n");

        GitRead r = new ReadStatus().run(new GitAsk(7, repo));
        assertEquals(new GitRead(7, true, "main", 0, 0, 1, 2, 2), r);
    }

    @Test
    void aFolderOutsideARepositoryIsNotOne() throws IOException {
        assumeTrue(installed(), "git is not on the PATH");
        Path plain = Files.createDirectories(dir.resolve("plain"));
        assertEquals(new GitRead(3, false, "", 0, 0, 0, 0, 0), new ReadStatus().run(new GitAsk(3, plain)));
    }

    @Test
    void aDetachedHeadIsNamedByItsShortCommit() throws IOException {
        Path repo = repository();
        git(repo, "checkout", "-q", "--detach");
        String oid = git.run(repo, List.of("rev-parse", "HEAD")).text().strip();
        assertEquals(oid.substring(0, 7), new ReadStatus().run(new GitAsk(1, repo)).branch());
    }

    private Path repository() throws IOException {
        assumeTrue(installed(), "git is not on the PATH");
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.writeString(repo.resolve("a.txt"), "a\n");
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        return repo;
    }

    private void git(Path repo, String... args) throws IOException {
        Git.Result r = git.run(repo, List.of(args));
        assumeTrue(r.ok(), () -> "git " + String.join(" ", args) + " failed: " + r.err());
    }

    private boolean installed() {
        try {
            return git.run(Path.of(System.getProperty("java.io.tmpdir")), List.of("--version")).ok();
        } catch (IOException e) {
            return false;
        }
    }
}
