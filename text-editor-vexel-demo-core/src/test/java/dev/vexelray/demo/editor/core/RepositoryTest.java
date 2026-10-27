package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitRead;
import dev.vexelray.demo.editor.core.Messages.GitRefresh;
import dev.vexelray.demo.editor.core.Messages.GitStamp;
import dev.vexelray.demo.editor.core.Messages.GitStampAsk;
import dev.vexelray.demo.editor.core.Messages.GitTick;
import dev.vexelray.demo.editor.core.Messages.GitTree;
import dev.vexelray.demo.editor.core.Messages.GitWatch;
import dev.vexelray.demo.editor.core.Messages.Plain;
import dev.vexelray.demo.editor.core.Messages.Relist;
import dev.vexelray.demo.editor.core.Messages.ShowFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The git controller, alone: no window, no lanes, no git. Each test hands the {@link Repository} a message the way its
 * lane would and reads what it sent; the jobs' answers are handed in by hand, so an answer that comes late, or about a
 * root since left, is as easy to test as one that comes at once.
 */
class RepositoryTest {

    private static final Path ROOT = Path.of("C:/work/project").toAbsolutePath();
    private static final Path OTHER = Path.of("C:/work/other").toAbsolutePath();
    private static final Path GIT_DIR = ROOT.resolve(".git");

    private Repository repository;
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        Atchung bus = Atchung.create();
        repository = new Repository(bus);
        bus.subscribe(Messages.GIT_STATUS_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_STAMP_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_HEAD_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_TREE_TOPIC, sent::add);
    }

    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.removeIf(kind::isInstance);
        return out;
    }

    private static GitRead main(long generation, int changed) {
        return answer(generation, "main", 2, 0, changed, "stamp-1");
    }

    private static GitRead answer(long generation, String branch, int ahead, int behind, int changed, String stamp) {
        return new GitRead(generation, true, branch, "origin/" + branch, ahead, behind, 1, changed, changed, ROOT,
                GIT_DIR, stamp, "status-text", "unstaged-text", "staged-text");
    }

    private static GitRead none(long generation) {
        return new GitRead(generation, false, "", "", 0, 0, 0, 0, 0, null, null, "", "", "", "");
    }

    /** Show the root and answer its first question; the answer's generation. */
    private long shown() {
        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();
        repository.read(main(g, 3));
        sent.clear();
        return g;
    }

    @Test
    void aRootIsAskedAboutAndItsAnswerToldToTheView() {
        repository.folder(new ShowFolder(ROOT));
        List<GitAsk> asked = took(GitAsk.class);
        assertEquals(1, asked.size());
        assertEquals(ROOT, asked.getFirst().folder());

        repository.read(main(asked.getFirst().generation(), 3));
        assertEquals(List.of(new GitHead(true, "main ↑2 · 3 changed", "main ↑2", "main", "origin/main", 2, 0, 1, 3)),
                took(GitHead.class));
        assertEquals(List.of(new GitTree(ROOT, "status-text", "unstaged-text", "staged-text")), took(GitTree.class));
    }

    @Test
    void aBurstOfSavesIsOneMoreQuestionNotOneEach() {
        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();

        for (int i = 0; i < 20; i++) {
            repository.changed(new Relist(null));
        }
        assertEquals(List.of(), took(GitAsk.class), "one is out already");

        repository.read(main(g, 0));
        assertEquals(1, took(GitAsk.class).size(), "asked once more, for all twenty");
        repository.read(main(g, 1));
        repository.changed(new Relist(null));
        assertEquals(1, took(GitAsk.class).size(), "nothing out, so asked at once");
    }

    @Test
    void anAnswerAboutARootSinceLeftIsDropped() {
        repository.folder(new ShowFolder(ROOT));
        long old = took(GitAsk.class).getFirst().generation();
        repository.folder(new ShowFolder(OTHER));
        GitAsk now = took(GitAsk.class).getFirst();
        assertEquals(OTHER, now.folder());

        repository.read(main(old, 5));
        assertEquals(List.of(), took(GitHead.class));
        assertEquals(List.of(), took(GitTree.class));
        repository.read(answer(now.generation(), "develop", 0, 1, 0, "s"));
        assertEquals("develop ↓1", took(GitHead.class).getFirst().label());
    }

    @Test
    void outsideARepositoryTheStatusLineSaysNothingAndThereIsNoTree() {
        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();
        repository.read(none(g));
        assertEquals(List.of(Messages.NOT_A_REPOSITORY), took(GitHead.class));
        assertEquals(List.of(), took(GitTree.class));
    }

    @Test
    void withNoRootNothingIsAskedAndTheSlotIsCleared() {
        repository.changed(new Relist(null));
        assertEquals(List.of(), took(GitAsk.class));

        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();
        repository.plain(new Plain());
        assertEquals(List.of(Messages.NOT_A_REPOSITORY), took(GitHead.class));
        repository.read(main(g, 2));
        assertEquals(List.of(), took(GitHead.class), "the answer about the root that went is dropped");
        repository.changed(new Relist(null));
        assertEquals(List.of(), took(GitAsk.class));
    }

    @Test
    void enteringGitModeAndRefreshAskAtOnce() {
        shown();
        repository.watch(new GitWatch(true));
        assertEquals(1, took(GitAsk.class).size());
        repository.refresh(new GitRefresh());
        assertEquals(List.of(), took(GitAsk.class), "one is out; the refresh waits for it");
        repository.watch(new GitWatch(false));
        assertEquals(List.of(), took(GitAsk.class), "leaving asks nothing");
    }

    @Test
    void whileWatchingATickLooksAtTheStampAndAsksOnlyWhenItMoved() {
        long g = shown();
        repository.tick(new GitTick());
        assertEquals(List.of(), took(GitStampAsk.class), "not watching yet");

        repository.watch(new GitWatch(true));
        repository.read(main(took(GitAsk.class).getFirst().generation(), 3));
        sent.clear();

        repository.tick(new GitTick());
        assertEquals(List.of(new GitStampAsk(g, GIT_DIR)), took(GitStampAsk.class));
        repository.tick(new GitTick());
        assertEquals(List.of(), took(GitStampAsk.class), "one look at a time");

        repository.stamped(new GitStamp(g, "stamp-1"));
        assertEquals(List.of(), took(GitAsk.class), "nothing moved, so git is not asked");

        repository.tick(new GitTick());
        took(GitStampAsk.class);
        repository.stamped(new GitStamp(g, "stamp-2"));
        assertEquals(1, took(GitAsk.class).size(), "a commit at a prompt moved the stamp");
        repository.tick(new GitTick());
        assertEquals(List.of(), took(GitStampAsk.class), "no look while the question is out");
    }

    @Test
    void aStampAboutARootSinceLeftIsDropped() {
        long g = shown();
        repository.watch(new GitWatch(true));
        took(GitAsk.class);
        repository.folder(new ShowFolder(OTHER));
        took(GitAsk.class);
        repository.stamped(new GitStamp(g, "moved"));
        assertEquals(List.of(), took(GitAsk.class));
    }

    @Test
    void aCleanBranchInStepWithItsUpstreamIsJustItsName() {
        GitHead h = Repository.head(answer(1, "main", 0, 0, 0, ""));
        assertEquals("main", h.label());
        assertEquals("main", h.chip());
    }
}
