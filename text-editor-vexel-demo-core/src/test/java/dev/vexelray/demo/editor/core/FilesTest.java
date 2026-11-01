package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Create;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.demo.editor.core.Messages.FileRead;
import dev.vexelray.demo.editor.core.Messages.Find;
import dev.vexelray.demo.editor.core.Messages.Kind;
import dev.vexelray.demo.editor.core.Messages.Read;
import dev.vexelray.demo.editor.core.Messages.Why;
import dev.vexelray.demo.editor.core.Messages.Write;
import dev.vexelray.demo.editor.core.Messages.Written;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jobs, called as the offload lane calls them: a request in, a reply out, against a real folder. What can go wrong
 * is in the reply, never thrown.
 */
class FilesTest {

    @TempDir
    Path dir;

    @Test
    void readingAFileAnswersWithItsTextAndItsLineEndings() throws Exception {
        Path file = Files.write(dir.resolve("crlf.txt"), "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        FileRead r = new ReadFile().run(new Read(file, Why.OPEN, true));
        assertEquals("", r.problem());
        assertEquals("one\ntwo\n", r.text());
        assertTrue(r.crlf());
    }

    @Test
    void aBinaryFileIsAnsweredWithWhyNot() throws Exception {
        Path file = Files.write(dir.resolve("blob.bin"), new byte[] {0, 1, 2, 3});
        FileRead r = new ReadFile().run(new Read(file, Why.OPEN, true));
        assertFalse(r.problem().isEmpty());
    }

    @Test
    void aMissingFileIsAnsweredWithWhyNot() {
        FileRead r = new ReadFile().run(new Read(dir.resolve("gone.txt"), Why.RESTORE_FILE, true));
        assertFalse(r.problem().isEmpty());
        assertEquals(Why.RESTORE_FILE, r.why(), "the reason travels with the request, so the answer can be acted on");
    }

    @Test
    void writingKeepsTheFilesLineEndings() throws Exception {
        Path file = dir.resolve("crlf.txt");
        Written w = new WriteFile().run(new Write(1, file, "one\ntwo\n", true));
        assertEquals("", w.problem());
        assertEquals("one\r\ntwo\r\n", Files.readString(file));
    }

    @Test
    void findingSaysWhatIsThere() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "a");
        FindPath find = new FindPath();
        assertEquals(Kind.FOLDER, find.run(new Find(dir, Why.GO)).kind());
        assertEquals(Kind.FILE, find.run(new Find(file, Why.GO)).kind());
        assertEquals(Kind.NEW, find.run(new Find(dir.resolve("new.txt"), Why.GO)).kind());
        assertEquals(Kind.NOWHERE, find.run(new Find(dir.resolve("no/such/new.txt"), Why.GO)).kind());
    }

    @Test
    void creatingMakesAnEmptyFileAndRefusesOneThatIsThere() throws Exception {
        Path file = dir.resolve("new.txt");
        Created made = new CreateFile().run(new Create(file, false));
        assertEquals("", made.problem());
        assertEquals(0, Files.size(file));
        assertFalse(new CreateFile().run(new Create(file, false)).problem().isEmpty());
    }

    @Test
    void creatingAFolderMakesOneAndRefusesOneThatIsThere() {
        Path folder = dir.resolve("made");
        Created made = new CreateFile().run(new Create(folder, true));
        assertEquals("", made.problem());
        assertTrue(made.folder());
        assertTrue(Files.isDirectory(folder));
        assertFalse(new CreateFile().run(new Create(folder, true)).problem().isEmpty());
    }

    /** A file starts with what its extension asks for, written with the line endings the reply says. */
    @Test
    void aNewFileIsMadeFromTheTemplateItsExtensionAsksFor() throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Created java = new CreateFile().run(new Create(pkg.resolve("Thing.java"), false));
        assertEquals("package com.example;\n\npublic class Thing {\n}\n", java.text());
        assertEquals(new String(TextFile.encode(java.text(), java.crlf()), StandardCharsets.UTF_8),
                Files.readString(pkg.resolve("Thing.java")));

        Created md = new CreateFile().run(new Create(dir.resolve("Notes.md"), false));
        assertEquals("# Notes\n\n", md.text());

        Created sh = new CreateFile().run(new Create(dir.resolve("go.sh"), false));
        assertEquals("#!/usr/bin/env bash\n\n", sh.text());
        assertFalse(sh.crlf(), "a shell reads LF whatever the platform");
        assertEquals("#!/usr/bin/env bash\n\n", Files.readString(dir.resolve("go.sh")));

        assertEquals("", new CreateFile().run(new Create(dir.resolve("plain.txt"), false)).text());
    }

    @Test
    void aJavaPackageIsReadFromTheSourceRootAboveIt() {
        assertEquals("com.example", CreateFile.packageOf(dir.resolve("src/main/java/com/example")));
        assertEquals("a.b", CreateFile.packageOf(dir.resolve("src/test/java/a/b")));
        assertEquals("", CreateFile.packageOf(dir.resolve("src/main/java")));
        assertEquals("", CreateFile.packageOf(dir.resolve("elsewhere")));
    }
}
