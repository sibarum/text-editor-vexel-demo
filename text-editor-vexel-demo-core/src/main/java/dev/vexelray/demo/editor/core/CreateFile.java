package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Create;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;

/**
 * Make what the path bar named into being: a folder, a file from the template its extension asks for, or what a row's
 * menu put the bar in mind of: a Maven project or a module of the one around it ({@link Poms}), an archive of a folder
 * ({@link Trees}), or a link.
 *
 * <p>Nothing is made over something that is there, and what was half made when something failed is taken away again,
 * so the reply's {@code problem} means nothing changed.
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
        Made what = c.what();
        boolean crlf = CRLF && !(what == Made.FILE && script(at));
        try {
            if (Files.exists(at, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(at.getFileName() + " is already there");
            }
            String note = switch (what) {
                case FILE -> "";
                case FOLDER -> {
                    Files.createDirectory(at);
                    yield "";
                }
                case PROJECT -> Poms.project(at, crlf ? "\r\n" : "\n");
                case MODULE -> Poms.module(at, crlf ? "\r\n" : "\n");
                case ARCHIVE -> Trees.archive(at, from(c));
                case LINK -> {
                    Path target = from(c);
                    Files.createSymbolicLink(at, target);
                    yield "to " + target;
                }
            };
            if (what != Made.FILE) {
                return new Created(at, what, "", crlf, note, "");
            }
            String text = template(at);
            Files.write(at, TextFile.encode(text, crlf), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (script(at) && links()) {
                Set<PosixFilePermission> mode = Files.getPosixFilePermissions(at);
                mode.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(at, mode);
            }
            return new Created(at, what, text, crlf, "", "");
        } catch (FileAlreadyExistsException e) {
            // Somebody named a file that is there: an answer, which the reply carries, so a story rather than a warning.
            LOG.info("not created, already there: {}", at);
            return new Created(at, what, "", crlf, "", String.valueOf(e.getMessage()));
        } catch (IOException | RuntimeException e) {
            // A line, not a trace: the reply says why, and the disk's trouble is not a bug in this program.
            LOG.warn("could not create {}: {}", at, e.toString());
            return new Created(at, what, "", crlf, "", String.valueOf(e.getMessage()));
        }
    }

    /** What an archive holds or a link points at, which only those two are sent with. */
    private static Path from(Create c) throws IOException {
        return c.from().orElseThrow(() -> new IOException("nothing named to make it from"));
    }

    /**
     * Whether a link is something a person can make here: a POSIX file system, where it takes no privilege, and where
     * there is an executable bit to set. On Windows a link needs Developer Mode or an administrator, and a menu line
     * that fails for most people is not offered.
     */
    public static boolean links() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
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
