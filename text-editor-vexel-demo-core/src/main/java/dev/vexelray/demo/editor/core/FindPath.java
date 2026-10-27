package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Find;
import dev.vexelray.demo.editor.core.Messages.Found;
import dev.vexelray.demo.editor.core.Messages.Kind;
import dev.vexelray.framework.api.Job;

import java.nio.file.Files;
import java.nio.file.Path;

/** What is at a path, as far as opening it goes: a folder, a file, or nothing. */
@Job(topic = Messages.FIND, reply = Messages.FOUND)
public final class FindPath {

    public Found run(Find f) {
        Path p = f.path();
        Kind kind = Files.isDirectory(p) ? Kind.FOLDER : Files.isRegularFile(p) ? Kind.FILE : Kind.NOTHING;
        return new Found(p, f.why(), f.index(), kind);
    }
}
