package dev.vexelray.demo.editor.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session as a value: which files, which in front, which unsaved. No thread and no window. */
class DocTest {

    private static Doc.Entry entry(long id, String path) {
        return new Doc.Entry(id, Path.of(path), false, false);
    }

    @Test
    void startsEmpty() {
        Doc doc = Doc.initial();
        assertTrue(doc.tabs().isEmpty());
        assertNull(doc.front());
    }

    @Test
    void closingTheFrontFileLeavesNoneInFront() {
        Doc doc = Doc.initial().withTab(entry(1, "a.txt")).withTab(entry(2, "b.txt")).withActive(2).without(2);
        assertEquals(Doc.NONE, doc.active());
        assertEquals(List.of(Path.of("a.txt")), doc.files());
    }

    @Test
    void unsavedShowsInTheTitle() {
        Doc doc = Doc.initial().withTab(entry(1, "dir/a.txt")).withTab(entry(2, "b.md"))
                .withDirty(2, true);
        assertEquals("a.txt", doc.entry(1).title());
        assertEquals("• b.md", doc.entry(2).title());
    }

    @Test
    void changingAnEntryThatHasGoneChangesNothing() {
        Doc doc = Doc.initial().withTab(entry(1, "a.txt"));
        assertEquals(doc, doc.withDirty(7, true));
    }

    /** A folder becomes recent by being left, newest first, once, never the one shown, and no more than eight. */
    @Test
    void theFolderLeftBecomesRecent() {
        Path a = Path.of("a");
        Path b = Path.of("b");
        Path c = Path.of("c");
        Doc doc = Doc.initial().withFolder(a);
        assertEquals(List.of(), doc.recent(), "showing the first folder leaves none");
        doc = doc.withFolder(b).withFolder(c);
        assertEquals(List.of(b, a), doc.recent());
        doc = doc.withFolder(a);
        assertEquals(List.of(c, b), doc.recent(), "going back to a takes it out and puts c in front");
        assertEquals(doc, doc.withFolder(a), "the same folder again changes nothing");

        for (int i = 0; i < 20; i++) {
            doc = doc.withFolder(Path.of("f" + i));
        }
        assertEquals(Doc.RECENT, doc.recent().size());
        assertEquals(Path.of("f18"), doc.recent().getFirst());
    }

    @Test
    void recentFoldersBroughtBackLeaveOutTheOneShown() {
        Doc doc = Doc.initial().withFolder(Path.of("a")).withRecent(List.of(Path.of("b"), Path.of("a"), Path.of("b")));
        assertEquals(List.of(Path.of("b")), doc.recent());
    }

    @Test
    void anOpenFileIsFoundByItsPath() {
        Doc doc = Doc.initial().withTab(entry(1, "a.txt"));
        assertEquals(1, doc.entry(Path.of("a.txt")).id());
        assertNull(doc.entry(Path.of("b.txt")));
    }
}
