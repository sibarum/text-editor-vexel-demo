package dev.vexelray.demo.editor.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven projects made from nothing: a project of its own, or a module of the project around it. {@link CreateFile}'s,
 * on the job lane.
 *
 * <h2>A pom is read as text</h2>
 *
 * <p>A module needs three of its parent's coordinates and a line added to its {@code <modules>}. Reading those with a
 * pattern rather than a model keeps the parent as the person wrote it: its comments, its order and its line endings
 * are untouched, and the one new line takes the indent of the lines beside it.
 */
final class Poms {

    private Poms() {
    }

    /**
     * The folder of the Maven project {@code folder} is in: the nearest one at or above it with a {@code pom.xml}, or
     * null when there is none, which is when Add offers a project rather than a module.
     */
    static Path projectOf(Path folder) {
        for (Path p = folder == null ? null : folder.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
        }
        return null;
    }

    /** A Maven project with nothing in it yet at {@code at}: its pom, and the two source roots. */
    static String project(Path at, String nl) throws IOException {
        Files.createDirectory(at);
        try {
            Files.writeString(at.resolve("pom.xml"),
                    pom(null, "dev.example", artifactName(at), "0.1.0-SNAPSHOT", nl), StandardCharsets.UTF_8);
            roots(at);
        } catch (IOException | RuntimeException e) {
            Trees.delete(at);
            throw e;
        }
        return "a Maven project, groupId dev.example";
    }

    /**
     * A module at {@code at} of the project around it: its own pom, whose parent is that project's, and a
     * {@code <module>} line in the parent so it builds with it. The parent is written last, so a module that could
     * not be made leaves it as it was.
     */
    static String module(Path at, String nl) throws IOException {
        Path parentDir = projectOf(at.getParent());
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
        String withModule = withModule(parent, parentDir.relativize(at).toString().replace('\\', '/'));
        Files.createDirectory(at);
        try {
            String back = at.relativize(parentDir).toString().replace('\\', '/');
            Parent p = new Parent(group, artifact, version, back.equals("..") ? null : back + "/pom.xml");
            Files.writeString(at.resolve("pom.xml"), pom(p, null, artifactName(at), null, nl), StandardCharsets.UTF_8);
            roots(at);
            Files.writeString(parentPom, withModule, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Trees.delete(at);
            throw e;
        }
        boolean aggregator = Pattern.compile("<packaging>\\s*pom\\s*</packaging>").matcher(parent).find();
        return aggregator ? "a module of " + artifact
                : "a module of " + artifact + ", whose packaging is not pom yet: Maven builds modules only under one";
    }

    private static void roots(Path at) throws IOException {
        Files.createDirectories(at.resolve("src/main/java"));
        Files.createDirectories(at.resolve("src/test/java"));
    }

    private record Parent(String groupId, String artifactId, String version, String relativePath) {
    }

    /** One of the project's own coordinates, not its parent's: the first outside {@code <parent>}; else the parent's. */
    static String coordinate(String pom, String name) {
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

    /** {@code pom} with {@code module} in its {@code <modules>}, which is made before {@code </project>} if it has none. */
    static String withModule(String pom, String module) {
        String nl = pom.contains("\r\n") ? "\r\n" : "\n";
        String line = "<module>" + module + "</module>";
        Matcher modules = Pattern.compile("(?s)([ \\t]*)<modules>(.*?)([ \\t]*)</modules>").matcher(pom);
        if (modules.find()) {
            int at = modules.start(3);
            return pom.substring(0, at) + modules.group(1) + "    " + line + nl + pom.substring(at);
        }
        int end = pom.lastIndexOf("</project>");
        if (end < 0) {
            throw new IllegalArgumentException("not a pom: no </project>");
        }
        return pom.substring(0, end) + nl + "    <modules>" + nl + "        " + line + nl + "    </modules>" + nl
                + pom.substring(end);
    }

    /** A pom with no parent (group and version its own) or with {@code parent} (both inherited), lines ending in {@code nl}. */
    private static String pom(Parent parent, String group, String artifact, String version, String nl) {
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
        b.append("    <packaging>jar</packaging>").append(nl);
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
    static String artifactName(Path at) {
        String s = String.valueOf(at.getFileName()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "project" : s;
    }
}
