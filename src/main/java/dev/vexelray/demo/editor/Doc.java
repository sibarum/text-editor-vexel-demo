package dev.vexelray.demo.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Everything the editor knows about its session, in one immutable value: which documents are open, in what order,
 * which one is in front, whether each has unsaved work, which folder the navigator shows, and the last thing the
 * status line was told.
 *
 * <p><b>What it does not hold is the text.</b> Each document's text is already one versioned value — the
 * {@code State<Document>} inside its {@code TextField} — so copying it here would be a second place it lives, and
 * the two would disagree for a frame every keystroke. This is the part nothing else owns: the shape of the
 * session. The close gate, the status line, the title bar and the remembered session all read it, lock-free.
 *
 * <p>A tab is named by an {@code id} rather than an index. An index is a fact about the tab bar at one instant,
 * and a close landing between a read and a write turns it into the neighbour's index.
 */
record Doc(List<Entry> tabs, long active, Path folder, String status) {

    /** No tab is in front. Only true for a moment: the workspace never leaves itself with no tabs. */
    static final long NONE = -1;

    /**
     * One open document.
     *
     * @param id       stable for the life of the tab
     * @param path     where it is on disk, or null for one that has never been saved
     * @param dirty    whether it differs from what was last loaded or saved
     * @param language what the highlighter calls it, for the status line
     */
    record Entry(long id, Path path, boolean dirty, String language) {

        /**
         * The tab's label: the file name, or {@code Untitled}; a bullet in front of either while it is unsaved. A bullet
         * (U+2022) rather than a heavier dot, because the sans face's atlas has General Punctuation and not
         * Geometric Shapes, and the missing-glyph box is what a tab header would otherwise show.
         */
        String title() {
            String name = path == null ? "Untitled" : String.valueOf(path.getFileName());
            return dirty ? "• " + name : name;
        }

        Entry withDirty(boolean value) {
            return new Entry(id, path, value, language);
        }

        Entry withPath(Path value, String lang) {
            return new Entry(id, value, dirty, lang);
        }
    }

    Doc {
        tabs = List.copyOf(tabs);
    }

    /** No documents, no folder. */
    static Doc initial() {
        return new Doc(List.of(), NONE, null, "");
    }

    /** The entry with {@code id}, or null if it has gone. */
    Entry entry(long id) {
        for (Entry e : tabs) {
            if (e.id() == id) {
                return e;
            }
        }
        return null;
    }

    /** The document in front, or null. */
    Entry front() {
        return entry(active);
    }

    /** Every document with unsaved work, in tab order. */
    List<Entry> unsaved() {
        return tabs.stream().filter(Entry::dirty).toList();
    }

    /** Every document that is a file on disk, in tab order — what a session remembers. */
    List<Path> files() {
        return tabs.stream().map(Entry::path).filter(p -> p != null).toList();
    }

    Doc withTab(Entry entry) {
        List<Entry> next = new ArrayList<>(tabs);
        next.add(entry);
        return new Doc(next, active, folder, status);
    }

    Doc without(long id) {
        List<Entry> next = new ArrayList<>(tabs);
        next.removeIf(e -> e.id() == id);
        return new Doc(next, active == id ? NONE : active, folder, status);
    }

    /** Change one entry, whatever it currently is. A no-op on an entry that has gone. */
    Doc withEntry(long id, UnaryOperator<Entry> change) {
        List<Entry> next = new ArrayList<>(tabs.size());
        for (Entry e : tabs) {
            next.add(e.id() == id ? change.apply(e) : e);
        }
        return new Doc(next, active, folder, status);
    }

    Doc withActive(long id) {
        return new Doc(tabs, id, folder, status);
    }

    Doc withFolder(Path value) {
        return new Doc(tabs, active, value, status);
    }

    Doc withStatus(String value) {
        return new Doc(tabs, active, folder, value);
    }
}
