package dev.vexelray.demo.editor.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Everything the editor knows about its session, in one immutable value: which documents are open, in what order,
 * which one is in front, whether each has unsaved work, which folder the navigator shows and which it showed before.
 *
 * <p><b>What it does not hold is the text.</b> Each document's text is already one versioned value, the
 * {@code State<Document>} inside its {@code TextField} in the view, so copying it here would be a second place it
 * lives, and the two would disagree for a frame every keystroke. This is the part nothing else owns: the shape of the
 * session, which the {@link Editor} keeps in one field of its own.
 *
 * <p>A tab is named by an {@code id} rather than an index. An index is a fact about the tab bar at one instant, and a
 * close landing between a read and a write turns it into the neighbour's index.
 */
record Doc(List<Entry> tabs, long active, Path folder, List<Path> recent) {

    /** No tab is in front. */
    static final long NONE = Messages.NO_TAB;

    /** How many roots {@link #recent} keeps, newest first. The current folder is never among them. */
    static final int RECENT = 4;

    /**
     * One open document.
     *
     * @param id      stable for the life of the tab
     * @param path    where it is on disk, or null for one that has never been saved
     * @param dirty   whether it differs from what was last loaded or saved
     * @param crlf    whether its file's lines end in CR LF, which a save puts back
     * @param library the name a library's read-only source is shown under, which has no path; null for a file
     */
    record Entry(long id, Path path, boolean dirty, boolean crlf, String library) {

        /** The tab's name: the file's, the library source's, or {@code Untitled}. */
        String name() {
            return path != null ? String.valueOf(path.getFileName()) : library != null ? library : "Untitled";
        }

        /**
         * The tab's label: its name, with a bullet in front while it is unsaved. A bullet (U+2022) rather than a
         * heavier dot, because the sans face's atlas has General Punctuation and not Geometric Shapes, and the
         * missing-glyph box is what a tab header would otherwise show.
         */
        String title() {
            return dirty ? "• " + name() : name();
        }

        /** Whether it is an Untitled document: no file, and not a library's source. */
        boolean untitled() {
            return path == null && library == null;
        }

        Entry withDirty(boolean value) {
            return new Entry(id, path, value, crlf, library);
        }

        Entry withPath(Path value) {
            return new Entry(id, value, dirty, crlf, library);
        }
    }

    Doc {
        tabs = List.copyOf(tabs);
        recent = List.copyOf(recent);
    }

    /** No documents, no folder. */
    static Doc initial() {
        return new Doc(List.of(), NONE, null, List.of());
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

    /** The entry open on {@code file}, or null. */
    Entry entry(Path file) {
        for (Entry e : tabs) {
            if (e.path() != null && e.path().equals(file)) {
                return e;
            }
        }
        return null;
    }

    /** The entry showing the library source called {@code name}, or null. */
    Entry library(String name) {
        for (Entry e : tabs) {
            if (name.equals(e.library())) {
                return e;
            }
        }
        return null;
    }

    /** The document in front, or null. */
    Entry front() {
        return entry(active);
    }

    /** Every document that is a file on disk, in tab order: what a session remembers. */
    List<Path> files() {
        return tabs.stream().map(Entry::path).filter(p -> p != null).toList();
    }

    Doc withTab(Entry entry) {
        List<Entry> next = new ArrayList<>(tabs);
        next.add(entry);
        return new Doc(next, active, folder, recent);
    }

    Doc without(long id) {
        List<Entry> next = new ArrayList<>(tabs);
        next.removeIf(e -> e.id() == id);
        return new Doc(next, active == id ? NONE : active, folder, recent);
    }

    /** Change one entry, whatever it currently is. A no-op on an entry that has gone. */
    Doc withEntry(long id, UnaryOperator<Entry> change) {
        List<Entry> next = new ArrayList<>(tabs.size());
        for (Entry e : tabs) {
            next.add(e.id() == id ? change.apply(e) : e);
        }
        return new Doc(next, active, folder, recent);
    }

    Doc withActive(long id) {
        return new Doc(tabs, id, folder, recent);
    }

    /**
     * Show {@code value} in the navigator. The folder it replaces goes to the front of {@link #recent}, which is how a
     * root left behind stays one click away; the first folder of a session replaces nothing and adds nothing.
     */
    Doc withFolder(Path value) {
        if (folder == null || folder.equals(value)) {
            return new Doc(tabs, active, value, recent);
        }
        List<Path> next = new ArrayList<>(RECENT);
        next.add(folder);
        for (Path p : recent) {
            if (next.size() < RECENT && !p.equals(folder) && !p.equals(value)) {
                next.add(p);
            }
        }
        return new Doc(tabs, active, value, next);
    }

    /** What the navigator offered as recent roots last time, as the session brings it back. */
    Doc withRecent(List<Path> value) {
        return new Doc(tabs, active, folder, value.stream().limit(RECENT).toList());
    }
}
