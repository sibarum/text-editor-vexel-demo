package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.GitAsk;
import dev.vexelray.demo.editor.core.Messages.GitHead;
import dev.vexelray.demo.editor.core.Messages.GitRead;
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
 * lane would and reads what it sent; {@link ReadStatus}'s answers are handed in by hand, so an answer that comes late,
 * or about a root since left, is as easy to test as one that comes at once.
 */
class RepositoryTest {

    private static final Path ROOT = Path.of("C:/work/project").toAbsolutePath();
    private static final Path OTHER = Path.of("C:/work/other").toAbsolutePath();

    private Repository repository;
    private final List<Object> sent = new ArrayList<>();

    @BeforeEach
    void build() {
        Atchung bus = Atchung.create();
        repository = new Repository(bus);
        bus.subscribe(Messages.GIT_STATUS_TOPIC, sent::add);
        bus.subscribe(Messages.GIT_HEAD_TOPIC, sent::add);
    }

    private <T> List<T> took(Class<T> kind) {
        List<T> out = sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        sent.clear();
        return out;
    }

    private static GitRead main(long generation, int changed) {
        return new GitRead(generation, true, "main", 2, 0, 1, changed, changed);
    }

    @Test
    void aRootIsAskedAboutAndItsAnswerToldToTheView() {
        repository.folder(new ShowFolder(ROOT));
        List<GitAsk> asked = took(GitAsk.class);
        assertEquals(1, asked.size());
        assertEquals(ROOT, asked.getFirst().folder());

        repository.read(main(asked.getFirst().generation(), 3));
        assertEquals(List.of(new GitHead(true, "main ↑2 · 3 changed", "main", 2, 0, 1, 3)), took(GitHead.class));
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
        repository.read(new GitRead(now.generation(), true, "develop", 0, 1, 0, 0, 0));
        assertEquals("develop ↓1", took(GitHead.class).getFirst().label());
    }

    @Test
    void outsideARepositoryTheStatusLineSaysNothing() {
        repository.folder(new ShowFolder(ROOT));
        long g = took(GitAsk.class).getFirst().generation();
        repository.read(new GitRead(g, false, "", 0, 0, 0, 0, 0));
        assertEquals(List.of(Messages.NOT_A_REPOSITORY), took(GitHead.class));
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
    void aCleanBranchInStepWithItsUpstreamIsJustItsName() {
        assertEquals("main", Repository.head(new GitRead(1, true, "main", 0, 0, 0, 0, 0)).label());
    }
}
