package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.git.Git;
import dev.sibarum.suite.git.Numstat;
import dev.sibarum.suite.git.Status;
import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import dev.vexelray.demo.editor.core.Messages.GitStamp;
import dev.vexelray.demo.editor.core.Messages.GitStampAsk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The git jobs against a real repository, made fresh for each test. Skipped where git is not installed. */
class ReadStatusTest {

    private final Git git = Git.onPath();

    @TempDir
    Path dir;

    @Test
    void countsEachChangedPathOnceAndEachSideOnItsOwn() throws IOException {
        Path repo = repository();
        Files.writeString(repo.resolve("a.txt"), "changed\n");
        git(repo, "add", "a.txt");
        Files.writeString(repo.resolve("a.txt"), "changed\nagain\n");
        Files.writeString(repo.resolve("new.txt"), "n\nn\nn\n");

        GitRead r = new ReadStatus().run(new GitAsk(7, repo));
        assertEquals(7, r.generation());
        assertTrue(r.repository());
        assertEquals("main", r.branch());
        assertEquals("", r.upstream());
        assertEquals(1, r.staged());
        assertEquals(2, r.unstaged());
        assertEquals(2, r.changed());
        assertEquals(repo.toRealPath(), r.root().toRealPath());
        assertTrue(Files.isRegularFile(r.gitDir().resolve("index")));
        assertFalse(r.stamp().isEmpty());
    }

    @Test
    void theTextsReadBackToTheRowsGitModeShows() throws IOException {
        Path repo = repository();
        Files.writeString(repo.resolve("a.txt"), "a\nb\n");
        git(repo, "add", "a.txt");
        Files.writeString(repo.resolve("new.txt"), "one\ntwo\n");

        GitRead r = new ReadStatus().run(new GitAsk(1, repo));
        Status s = Status.parse(r.root(), r.status());
        assertEquals(List.of("a.txt"), s.staged().stream().map(Status.Change::path).toList());
        assertEquals(List.of("new.txt"), s.unstaged().stream().map(Status.Change::path).toList());
        assertEquals(new Numstat.Lines(1, 0, false), Numstat.parse(r.stagedLines()).at("a.txt"));
        assertEquals(new Numstat.Lines(2, 0, false), Numstat.parse(r.unstagedLines()).at("new.txt"),
                "an untracked file is counted by reading it");
    }

    @Test
    void aFolderOutsideARepositoryIsNotOne() throws IOException {
        assumeTrue(installed(), "git is not on the PATH");
        Path plain = Files.createDirectories(dir.resolve("plain"));
        GitRead r = new ReadStatus().run(new GitAsk(3, plain));
        assertFalse(r.repository());
        assertEquals(3, r.generation());
        assertEquals(null, r.gitDir());
    }

    @Test
    void aDetachedHeadIsNamedByItsShortCommit() throws IOException {
        Path repo = repository();
        git(repo, "checkout", "-q", "--detach");
        String oid = git.run(repo, List.of("rev-parse", "HEAD")).text().strip();
        assertEquals(oid.substring(0, 7), new ReadStatus().run(new GitAsk(1, repo)).branch());
    }

    @Test
    void theStampMovesWhenGitCommitsAndStaysWhileNothingHappens() throws IOException {
        Path repo = repository();
        GitRead r = new ReadStatus().run(new GitAsk(1, repo));
        GitStamp quiet = new ReadStamp().run(new GitStampAsk(1, r.gitDir()));
        assertEquals(r.stamp(), quiet.stamp());

        Files.writeString(repo.resolve("a.txt"), "changed\n");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-am", "at a prompt");
        assertNotEquals(r.stamp(), new ReadStamp().run(new GitStampAsk(1, r.gitDir())).stamp());
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
