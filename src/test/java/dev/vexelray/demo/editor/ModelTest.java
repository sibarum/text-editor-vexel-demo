package dev.vexelray.demo.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session model: which documents, which in front, which unsaved. No GUI — the shape of the session is a
 * plain value, which is what lets the close gate and the status line read it lock-free.
 */
class ModelTest {

    private static Doc.Entry entry(long id, String path) {
        return new Doc.Entry(id, path == null ? null : Path.of(path), false, "Plain text");
    }

    @Test
    void startsEmpty() {
        Model model = new Model();
        assertTrue(model.doc().tabs().isEmpty());
        assertNull(model.doc().front());
    }

    @Test
    void openingPutsADocumentInFront() {
        Model model = new Model();
        model.opened(entry(1, "a.txt"));
        model.opened(entry(2, "b.txt"));
        assertEquals(2, model.doc().tabs().size());
        assertEquals(2, model.doc().active());
    }

    @Test
    void closingTheFrontDocumentLeavesNoneInFrontUntilTheBarSaysWhich() {
        Model model = new Model();
        model.opened(entry(1, "a.txt"));
        model.opened(entry(2, "b.txt"));
        model.closed(2);
        assertEquals(List.of(1L), model.doc().tabs().stream().map(Doc.Entry::id).toList());
        assertEquals(Doc.NONE, model.doc().active());
        model.front(1);
        assertEquals(1, model.doc().active());
    }

    @Test
    void frontingADocumentThatHasGoneChangesNothing() {
        Model model = new Model();
        model.opened(entry(1, "a.txt"));
        model.front(7);
        assertEquals(1, model.doc().active());
    }

    @Test
    void dirtyShowsInTheTitleAndInTheUnsavedList() {
        Model model = new Model();
        model.opened(entry(1, "dir/a.txt"));
        model.opened(entry(2, null));
        model.dirty(2, true);
        assertEquals("a.txt", model.doc().entry(1).title());
        assertEquals("• Untitled", model.doc().entry(2).title());
        assertEquals(List.of(2L), model.doc().unsaved().stream().map(Doc.Entry::id).toList());
    }

    @Test
    void savingGivesADocumentItsPathAndCleansIt() {
        Model model = new Model();
        model.opened(entry(1, null));
        model.dirty(1, true);
        model.saved(1, Path.of("x.java"), "Java");
        Doc.Entry e = model.doc().entry(1);
        assertFalse(e.dirty());
        assertEquals(Path.of("x.java"), e.path());
        assertEquals("Java", e.language());
        assertEquals(List.of(Path.of("x.java")), model.doc().files());
    }

    /**
     * The one that justifies relative edits: documents going dirty and clean on many workers while tabs open
     * and close. Every open lands, and each document ends in the state its last toggle left it in.
     */
    @Test
    void concurrentChangesAllLand() throws InterruptedException {
        Model model = new Model();
        int threads = 8;
        int each = 50;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int base = t * each;
                pool.execute(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < each; i++) {
                            long id = base + i;
                            model.opened(entry(id, "f" + id));
                            model.dirty(id, true);
                            model.dirty(id, id % 2 == 0);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }
        assertEquals(threads * each, model.doc().tabs().size());
        assertEquals(threads * each / 2, model.doc().unsaved().size());
    }

    @Test
    void theListenerFinishesOnTheNewestDocument() throws InterruptedException {
        Model model = new Model();
        AtomicInteger lastSize = new AtomicInteger();
        model.onChange(doc -> lastSize.set(doc.tabs().size()));
        int threads = 4;
        CountDownLatch done = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int base = t * 100;
                pool.execute(() -> {
                    for (int i = 0; i < 100; i++) {
                        model.opened(entry(base + i, null));
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }
        assertEquals(400, lastSize.get());
    }

    @Test
    void theSessionRemembersOnlyFilesOnDisk() {
        Doc doc = Doc.initial()
                .withTab(entry(1, "a.txt"))
                .withTab(entry(2, null))
                .withActive(1)
                .withFolder(Path.of("proj"));
        Session.Saved saved = Session.Saved.of(doc);
        assertEquals(List.of("a.txt"), saved.files());
        assertEquals("proj", saved.folder());
        assertEquals("a.txt", saved.front());
        // Dirtiness and the status line are not part of what is remembered, so they do not cause a write.
        assertEquals(saved, Session.Saved.of(doc.withEntry(1, e -> e.withDirty(true)).withStatus("hi")));
    }

    @Test
    void aRootLeftBehindIsTheNewestRecentOneAndNeverTheCurrentOne() {
        Path a = Path.of("a");
        Path b = Path.of("b");
        Path c = Path.of("c");
        Doc doc = Doc.initial().withFolder(a);
        assertEquals(List.of(), doc.recent(), "the first folder replaces nothing");
        doc = doc.withFolder(b).withFolder(c);
        assertEquals(List.of(b, a), doc.recent());
        doc = doc.withFolder(a);
        assertEquals(List.of(c, b), doc.recent(), "going back to a takes it out, and c joins at the front");
        assertEquals(doc, doc.withFolder(a), "showing the same folder again changes nothing");
        assertEquals(List.of("c", "b"), Session.Saved.of(doc).recent());
    }

    @Test
    void recentKeepsOnlyTheNewestFew() {
        Doc doc = Doc.initial();
        for (int i = 0; i < Doc.RECENT + 3; i++) {
            doc = doc.withFolder(Path.of("p" + i));
        }
        assertEquals(Doc.RECENT, doc.recent().size());
        assertEquals(Path.of("p" + (Doc.RECENT + 1)), doc.recent().getFirst());
    }
}
