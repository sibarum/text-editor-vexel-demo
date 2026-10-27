package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Add;
import dev.vexelray.demo.editor.core.Messages.Created;
import dev.vexelray.demo.editor.core.Messages.Made;
import dev.vexelray.demo.editor.core.Messages.Template;
import dev.vexelray.framework.api.Job;
import sibarum.probe.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The navigator's Add, made on the disk: a folder, a file from a template, a link, a Maven project, a module of the
 * project around it, or an archive of the folder. On the offload lane, since every one of them writes and an archive
 * reads a whole tree.
 *
 * <p>The {@link Editor} has already checked the name. What can still go wrong (the name was taken in the meantime, the
 * folder cannot be written) is the reply's {@code problem}; nothing is made in that case, or what was half made is
 * taken away again where that can be done.
 *
 * <p>The questions the navigator asks before it offers each entry are here too, as static functions: whether a folder
 * is inside a Maven project ({@link #projectOf}), whether it holds a file to archive ({@link #anyFile}), and whether
 * the platform makes links a person can ({@link #links}).
 */
@Job(topic = Messages.MAKE, reply = Messages.MADE)
public final class Make {

    private static final Log LOG = Log.of("editor.files");

    /** How many entries {@link #anyFile} looks at before it says no: a menu cannot wait on a walk of a whole drive. */
    private static final int LOOK = 2_000;

    public Created run(Add a) {
        Path at = a.folder().resolve(a.name()).toAbsolutePath().normalize();
        try {
            if (Files.exists(at, LinkOption.NOFOLLOW_LINKS)) {
                return new Created(at, a.what(), "", a.name() + " is already there");
            }
            String note = switch (a.what()) {
                case FOLDER -> {
                    Files.createDirectory(at);
                    yield "";
                }
                case FILE -> file(at, a.template());
                case SYMLINK -> {
                    Files.createSymbolicLink(at, a.target());
                    yield "to " + a.target();
                }
                case PROJECT -> project(at);
                case SUBMODULE -> module(at, a.folder());
                case ARCHIVE -> archive(at, a.folder());
            };
            return new Created(at, a.what(), note, "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not make {} {}", a.what(), at, e);
            return new Created(at, a.what(), "", String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ what the navigator asks before it offers

    /**
     * The folder of the Maven project {@code folder} is in: the nearest folder at or above it with a {@code pom.xml},
     * or null when there is none, which is when Add offers a project rather than a module.
     */
    public static Path projectOf(Path folder) {
        for (Path p = folder == null ? null : folder.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
        }
        return null;
    }

    /** Whether there is a file anywhere under {@code folder} to archive, looking at no more than a couple of thousand entries. */
    public static boolean anyFile(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return false;
        }
        Deque<Path> folders = new ArrayDeque<>();
        folders.add(folder);
        int seen = 0;
        while (!folders.isEmpty() && seen < LOOK) {
            try (Stream<Path> s = Files.list(folders.poll())) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        return true;
                    }
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        folders.add(p);
                    }
                    seen++;
                }
            } catch (IOException | RuntimeException e) {
                // unreadable: counts as nothing there, as the navigator lists it
            }
        }
        return false;
    }

    /**
     * Whether a link is something a person can make here: a POSIX file system, where it takes no privilege. On Windows
     * it needs Developer Mode or an administrator, and a menu line that fails for most people is not offered.
     */
    public static boolean links() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** The name Add offers for {@code what}, before the person changes it: {@code template} for a file. */
    public static String suggested(Made what, Template template, Path folder) {
        return switch (what) {
            case FOLDER -> "New folder";
            case FILE -> switch (template) {
                case BLANK -> "new-file";
                case TEXT -> "new.txt";
                case JAVA -> "NewClass.java";
                case MARKDOWN -> "new.md";
                case POWERSHELL -> "script.ps1";
                case BASH -> "script.sh";
                case ZSH -> "script.zsh";
            };
            case SYMLINK -> "link";
            case PROJECT -> "new-project";
            case SUBMODULE -> "new-module";
            case ARCHIVE -> (folder == null || folder.getFileName() == null ? "archive" : folder.getFileName()) + ".zip";
        };
    }

    // ------------------------------------------------------------------ files

    /** A file from {@code template}, saying what it is if that is not plain from its name. */
    private static String file(Path at, Template template) throws IOException {
        String stem = stem(at);
        // Scripts are read by a shell that wants LF whatever the platform; the rest take the platform's own, as an
        // Untitled document does.
        String nl = template == Template.BASH || template == Template.ZSH ? "\n" : System.lineSeparator();
        String text = switch (template) {
            case BLANK, TEXT, POWERSHELL -> "";
            case MARKDOWN -> "# " + stem + nl + nl;
            case JAVA -> java(at, stem, nl);
            case BASH -> "#!/usr/bin/env bash" + nl + nl;
            case ZSH -> "#!/usr/bin/env zsh" + nl + nl;
        };
        Files.writeString(at, text, StandardCharsets.UTF_8);
        if ((template == Template.BASH || template == Template.ZSH) && links()) {
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(at);
            mode.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(at, mode);
            return "executable";
        }
        return "";
    }

    /** A Java class named for its file, in the package its folder is in under {@code src/main/java} or {@code src/test/java}. */
    private static String java(Path at, String stem, String nl) {
        String pkg = packageOf(at.getParent());
        String head = pkg.isEmpty() ? "" : "package " + pkg + ";" + nl + nl;
        return head + "public class " + stem + " {" + nl + "}" + nl;
    }

    /** The Java package {@code folder} is, from the source root above it; empty for none, or outside one. */
    static String packageOf(Path folder) {
        String path = folder.toAbsolutePath().normalize().toString().replace('\\', '/');
        for (String root : new String[] {"/src/main/java", "/src/test/java", "/src/java", "/src"}) {
            int i = path.lastIndexOf(root + "/");
            if (i >= 0) {
                return path.substring(i + root.length() + 1).replace('/', '.');
            }
        }
        return "";
    }

    private static String stem(Path p) {
        String name = String.valueOf(p.getFileName());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ------------------------------------------------------------------ projects

    /** A Maven project with nothing in it yet: its pom, and the two source roots. */
    private static String project(Path at) throws IOException {
        Files.createDirectory(at);
        try {
            String artifact = artifactName(at);
            Files.writeString(at.resolve("pom.xml"), pom(null, "dev.example", artifact, "0.1.0-SNAPSHOT", "jar"),
                    StandardCharsets.UTF_8);
            Files.createDirectories(at.resolve("src/main/java"));
            Files.createDirectories(at.resolve("src/test/java"));
        } catch (IOException | RuntimeException e) {
            deleteTree(at);
            throw e;
        }
        return "a Maven project, groupId dev.example";
    }

    /**
     * A module of the project around {@code folder}: its own pom, whose parent is that project's, and a
     * {@code <module>} line in the parent so it builds with it.
     */
    private static String module(Path at, Path folder) throws IOException {
        Path parentDir = projectOf(folder);
        if (parentDir == null) {
            throw new IOException("there is no pom.xml here or above to be a module of");
        }
        Path parentPom = parentDir.resolve("pom.xml");
        String parent = Files.readString(parentPom, StandardCharsets.UTF_8);
        String group = coordinate(parent, "groupId");
        String artifact = coordinate(parent, "artifactId");
        String version = coordinate(parent, "version");
        if (artifact == null || group == null || version == null) {
            throw new IOException("could not read the coordinates of " + parentPom);
        }
        String relative = parentDir.relativize(at).toString().replace('\\', '/');
        String withModule = withModule(parent, relative);
        Files.createDirectory(at);
        try {
            Path back = at.relativize(parentDir);
            String relativePath = back.toString().replace('\\', '/');
            Parent p = new Parent(group, artifact, version, relativePath.equals("..") ? null : relativePath + "/pom.xml");
            Files.writeString(at.resolve("pom.xml"), pom(p, null, artifactName(at), null, "jar"),
                    StandardCharsets.UTF_8);
            Files.createDirectories(at.resolve("src/main/java"));
            Files.createDirectories(at.resolve("src/test/java"));
            Files.writeString(parentPom, withModule, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            deleteTree(at);
            throw e;
        }
        boolean aggregator = Pattern.compile("<packaging>\\s*pom\\s*</packaging>").matcher(parent).find();
        return aggregator ? "a module of " + artifact
                : "a module of " + artifact + ", whose packaging is not pom yet: Maven builds modules only under one";
    }

    private record Parent(String groupId, String artifactId, String version, String relativePath) {
    }

    /** One of the project's own coordinates, not its parent's: the first one outside {@code <parent>}; else the parent's. */
    private static String coordinate(String pom, String name) {
        String own = pom.replaceAll("(?s)<parent>.*?</parent>", "")
                .replaceAll("(?s)<dependencies>.*", "")
                .replaceAll("(?s)<build>.*", "");
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<\\s]+)\\s*</" + name + ">").matcher(own);
        if (m.find()) {
            return m.group(1);
        }
        Matcher inherited = Pattern.compile("(?s)<parent>.*?<" + name + ">\\s*([^<\\s]+)\\s*</" + name + ">.*?</parent>")
                .matcher(pom);
        return inherited.find() ? inherited.group(1) : null;
    }

    /** {@code pom} with {@code module} listed in its {@code <modules>}, which is made before {@code </project>} if it has none. */
    static String withModule(String pom, String module) {
        String line = "<module>" + module + "</module>";
        Matcher modules = Pattern.compile("(?s)([ \\t]*)<modules>(.*?)([ \\t]*)</modules>").matcher(pom);
        if (modules.find()) {
            String indent = modules.group(1);
            int at = modules.start(3);
            return pom.substring(0, at) + indent + "    " + line + lineEnd(pom) + pom.substring(at);
        }
        int end = pom.lastIndexOf("</project>");
        if (end < 0) {
            throw new IllegalArgumentException("not a pom: no </project>");
        }
        String nl = lineEnd(pom);
        return pom.substring(0, end) + nl + "    <modules>" + nl + "        " + line + nl + "    </modules>" + nl
                + pom.substring(end);
    }

    private static String lineEnd(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    /** A pom for a project with no parent (group and version its own) or one with {@code parent} (both inherited). */
    private static String pom(Parent parent, String group, String artifact, String version, String packaging) {
        String nl = System.lineSeparator();
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(nl)
                .append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"").append(nl)
                .append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"").append(nl)
                .append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ")
                .append("http://maven.apache.org/xsd/maven-4.0.0.xsd\">").append(nl)
                .append("    <modelVersion>4.0.0</modelVersion>").append(nl).append(nl);
        if (parent != null) {
            b.append("    <parent>").append(nl)
                    .append("        <groupId>").append(parent.groupId()).append("</groupId>").append(nl)
                    .append("        <artifactId>").append(parent.artifactId()).append("</artifactId>").append(nl)
                    .append("        <version>").append(parent.version()).append("</version>").append(nl);
            if (parent.relativePath() != null) {
                b.append("        <relativePath>").append(parent.relativePath()).append("</relativePath>").append(nl);
            }
            b.append("    </parent>").append(nl).append(nl);
        } else {
            b.append("    <groupId>").append(group).append("</groupId>").append(nl);
        }
        b.append("    <artifactId>").append(artifact).append("</artifactId>").append(nl);
        if (parent == null) {
            b.append("    <version>").append(version).append("</version>").append(nl);
        }
        b.append("    <packaging>").append(packaging).append("</packaging>").append(nl);
        if (parent == null) {
            b.append(nl)
                    .append("    <properties>").append(nl)
                    .append("        <maven.compiler.release>25</maven.compiler.release>").append(nl)
                    .append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>").append(nl)
                    .append("    </properties>").append(nl);
        }
        b.append("</project>").append(nl);
        return b.toString();
    }

    /** A folder's name as a Maven artifactId: lower case, a dash for every run of anything else. */
    private static String artifactName(Path at) {
        String s = String.valueOf(at.getFileName()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "project" : s;
    }

    // ------------------------------------------------------------------ archives

    /** Everything under {@code folder} but the archive itself, zipped into {@code at}. */
    private static String archive(Path at, Path folder) throws IOException {
        int[] files = {0};
        try (OutputStream out = Files.newOutputStream(at, java.nio.file.StandardOpenOption.CREATE_NEW);
             ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> walk = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.equals(folder) || p.toAbsolutePath().normalize().equals(at)) {
                    continue;
                }
                String name = folder.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    zip.putNextEntry(new ZipEntry(name + "/"));
                    zip.closeEntry();
                } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(p, zip);
                    zip.closeEntry();
                    files[0]++;
                }
            }
        } catch (FileAlreadyExistsException taken) {
            throw taken;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(at);
            throw e;
        }
        return files[0] == 1 ? "1 file" : files[0] + " files";
    }

    /** Take away what was half made, deepest first. */
    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not take away the half-made {}", root, e);
        }
    }
}
