package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Create;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;

/**
 * Make what the path bar named into being: a folder, or a file from the template its extension asks for.
 *
 * <h2>The extension is the template</h2>
 *
 * <p>A Java file is a class named for it, in the package its folder is in; a Markdown file starts with a heading
 * from its name; a shell script starts with its shebang, has LF line endings whatever the platform, since the shell
 * reads them, and is executable where the platform has the bit. Anything else starts empty.
 */
@Job
public final class CreateFile {

    private static final Log LOG = Log.of("text-editor-vexel-demo.files");

    /** A new file gets this platform's line endings: asked here, so the editor never reads the platform. */
    private static final boolean CRLF = System.lineSeparator().equals("\r\n");

    public Created run(Create c) {
        Path at = c.path();
        boolean crlf = CRLF && !script(at);
        try {
            if (c.folder()) {
                Files.createDirectory(at);
                return new Created(at, true, "", crlf, "");
            }
            String text = template(at);
            Files.write(at, TextFile.encode(text, crlf), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (script(at) && posix()) {
                Set<PosixFilePermission> mode = Files.getPosixFilePermissions(at);
                mode.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(at, mode);
            }
            return new Created(at, false, text, crlf, "");
        } catch (FileAlreadyExistsException e) {
            // Somebody named a file that is there: an answer, which the reply carries, so a story rather than a warning.
            LOG.info("not created, already there: {}", at);
            return new Created(at, c.folder(), "", crlf, String.valueOf(e.getMessage()));
        } catch (IOException e) {
            // A line, not a trace: the reply says why, and the disk's trouble is not a bug in this program.
            LOG.warn("could not create {}: {}", at, e.toString());
            return new Created(at, c.folder(), "", crlf, String.valueOf(e.getMessage()));
        }
    }

    /** The text a file named {@code at} starts with, its lines ending in LF as a tab's text does. */
    static String template(Path at) {
        String stem = stem(at);
        return switch (extension(at)) {
            case "java" -> {
                String pkg = packageOf(at.toAbsolutePath().getParent());
                yield (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n") + "public class " + stem + " {\n}\n";
            }
            case "md" -> "# " + stem + "\n\n";
            case "sh" -> "#!/usr/bin/env bash\n\n";
            case "zsh" -> "#!/usr/bin/env zsh\n\n";
            default -> "";
        };
    }

    /** The Java package {@code folder} is, from the source root above it; empty for none, or outside one. */
    static String packageOf(Path folder) {
        String path = folder.toAbsolutePath().normalize().toString().replace('\\', '/');
        for (String root : new String[] {"/src/main/java", "/src/test/java", "/src/java", "/src"}) {
            if (path.endsWith(root)) {
                return "";
            }
            int i = path.lastIndexOf(root + "/");
            if (i >= 0) {
                return path.substring(i + root.length() + 1).replace('/', '.');
            }
        }
        return "";
    }

    private static boolean script(Path at) {
        String ext = extension(at);
        return ext.equals("sh") || ext.equals("zsh");
    }

    /** Whether the file system has an executable bit to set. */
    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static String extension(Path p) {
        String name = String.valueOf(p.getFileName());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String stem(Path p) {
        String name = String.valueOf(p.getFileName());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
