package dev.vexelray.demo.editor.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape of the session, in one immutable value: which files are open, in what order, which one is in front,
 * which have unsaved work, which folder the navigator shows, and the folders it showed before, newest first.
 *
 * <h2>Whose it is</h2>
 *
 * <p>The {@link Editor}'s, and nobody else's. It is part of the editor's {@code State}, which only the editor's
 * declared steps change, and the view hears about a change as a message rather than by reading this. So it needs no
 * lock: a step runs on the editor's lane, one at a time, and returns the next value rather than changing this one.
 * What the session is goes here; what the editor is in the middle of (a question, a save, a restore) goes in the
 * {@code State}.
 *
 * <h2>What it does not hold is the text</h2>
 *
 * <p>Each document's text is already one versioned value, the {@code State<Document>} inside its {@code TextField} in
 * the view, so copying it here would be a second place it lives, and the two would disagree every keystroke. What is
 * here is the part nothing else owns: the shape of the session.
 *
 * <p>A tab is named by an {@code id}, not an index. An index is a fact about the tab bar at one instant, and a close
 * landing between a read and a write turns it into the neighbour's.
 */
record Doc(List<Entry> tabs, long active, Path folder, List<Path> recent) {

    /** No tab: none is in front. */
    static final long NONE = Messages.NO_TAB;

    /** How many folders {@link #recent} keeps. The folder shown is never among them. */
    static final int RECENT = 8;

    /**
     * One open file.
     *
     * @param id    stable for the life of the tab
     * @param path  where it is on disk; every document here has one
     * @param dirty whether it differs from what was last loaded or saved
     * @param crlf  whether the file used {@code \r\n}, which a save puts back
     */
    record Entry(long id, Path path, boolean dirty, boolean crlf) {

        String name() {
            return String.valueOf(path.getFileName());
        }

        /** The tab's label: the file name, with a bullet in front while it is unsaved. */
        String title() {
            return dirty ? "• " + name() : name();
        }

        Entry withDirty(boolean value) {
            return new Entry(id, path, value, crlf);
        }

        Entry withPath(Path value) {
            return new Entry(id, value, dirty, crlf);
        }
    }

    Doc {
        tabs = List.copyOf(tabs);
        recent = List.copyOf(recent);
    }

    /** Nothing open. */
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

    /** The open tab for {@code path}, or null. */
    Entry entry(Path path) {
        for (Entry e : tabs) {
            if (e.path().equals(path)) {
                return e;
            }
        }
        return null;
    }

    /** The document in front, or null. */
    Entry front() {
        return entry(active);
    }

    /** Every open file, in tab order: what a session remembers. */
    List<Path> files() {
        return tabs.stream().map(Entry::path).toList();
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

    /** Mark one entry unsaved or saved. A no-op on one that has gone. */
    Doc withDirty(long id, boolean dirty) {
        List<Entry> next = new ArrayList<>(tabs.size());
        for (Entry e : tabs) {
            next.add(e.id() == id ? e.withDirty(dirty) : e);
        }
        return new Doc(next, active, folder, recent);
    }

    /**
     * Whatever was at {@code from}, or under it, is at {@code to} now: the tabs on it, the folder shown and the recent
     * folders follow it there.
     */
    Doc relocated(Path from, Path to) {
        List<Entry> next = new ArrayList<>(tabs.size());
        for (Entry e : tabs) {
            next.add(e.path().startsWith(from) ? e.withPath(moved(e.path(), from, to)) : e);
        }
        List<Path> again = recent.stream().map(p -> p.startsWith(from) ? moved(p, from, to) : p).toList();
        Path shown = folder != null && folder.startsWith(from) ? moved(folder, from, to) : folder;
        return new Doc(next, active, shown, recentWithout(again, shown));
    }

    /** Where {@code path}, at or under {@code from}, is once {@code from} is {@code to}. */
    static Path moved(Path path, Path from, Path to) {
        return to.resolve(from.relativize(path));
    }

    Doc withActive(long id) {
        return new Doc(tabs, id, folder, recent);
    }

    /**
     * Show {@code value} in the navigator. The folder it replaces goes to the front of {@link #recent}, which is how a
     * folder becomes recent: by being left.
     */
    Doc withFolder(Path value) {
        if (folder == null || folder.equals(value)) {
            return new Doc(tabs, active, value, recentWithout(recent, value));
        }
        List<Path> next = new ArrayList<>();
        next.add(folder);
        next.addAll(recent);
        return new Doc(tabs, active, value, recentWithout(next, value));
    }

    /** The recent folders a session brought back, but the folder shown. */
    Doc withRecent(List<Path> value) {
        return new Doc(tabs, active, folder, recentWithout(value, folder));
    }

    /** {@code paths} once each, in order, without {@code shown}, and no more than {@link #RECENT}. */
    private static List<Path> recentWithout(List<Path> paths, Path shown) {
        List<Path> out = new ArrayList<>();
        for (Path p : paths) {
            if (!p.equals(shown) && !out.contains(p) && out.size() < RECENT) {
                out.add(p);
            }
        }
        return out;
    }
}
