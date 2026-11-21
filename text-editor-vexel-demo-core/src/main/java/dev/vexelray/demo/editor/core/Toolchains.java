package dev.vexelray.demo.editor.core;

import dev.vexelray.demo.editor.core.Messages.Tool;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding the Java runtimes and Maven installations on this machine, for Welcome's <i>Run with</i>. {@link FindTools}
 * runs it on the offload lane; the environment, the home folder and the folders tools are installed under are
 * handed in, so a test decides what there is.
 *
 * <p>Where it looks, for each kind: the variable that names one ({@code JAVA_HOME}; {@code MAVEN_HOME} or
 * {@code M2_HOME}), the one the {@code PATH} runs, the usual install folders' children, and the tool's own caches
 * ({@code ~/.jdks}, SDKMAN's; Maven wrapper's distributions). Then beside each one found: a folder that holds one JDK
 * often holds several. A folder is a JDK when it has {@code bin/java}, and a Maven when it has {@code bin/mvn}; the
 * version is read from the JDK's {@code release} file and from Maven's {@code maven-core} jar. Each is listed once,
 * newest first.
 */
public final class Toolchains {

    private static final Pattern RELEASE = Pattern.compile("(?m)^(\\w+)=\"?([^\"\\r\\n]*)\"?");
    private static final Pattern MAVEN_CORE = Pattern.compile("maven-core-(\\d[\\w.-]*)\\.jar");

    private Toolchains() {
    }

    /** The JDKs and Java runtimes: under {@code roots}, in {@code home}'s caches, and named by {@code env}. */
    public static List<Tool> javas(Map<String, String> env, Path home, List<Path> roots) {
        List<Path> candidates = new ArrayList<>();
        variable(env, "JAVA_HOME").ifPresent(candidates::add);
        candidates.addAll(onPath(env, "java"));
        for (Path root : roots) {
            candidates.addAll(children(root));
        }
        candidates.addAll(children(home.resolve(".jdks")));
        candidates.addAll(children(home.resolve(".sdkman").resolve("candidates").resolve("java")));
        return found(candidates, Toolchains::java);
    }

    /** The Maven installations: under {@code roots}, in the wrapper's cache in {@code home}, and named by {@code env}. */
    public static List<Tool> mavens(Map<String, String> env, Path home, List<Path> roots) {
        List<Path> candidates = new ArrayList<>();
        variable(env, "MAVEN_HOME").ifPresent(candidates::add);
        variable(env, "M2_HOME").ifPresent(candidates::add);
        candidates.addAll(onPath(env, "mvn"));
        for (Path root : roots) {
            candidates.addAll(children(root));
        }
        // ~/.m2/wrapper/dists/apache-maven-3.9.9-bin/<hash>/apache-maven-3.9.9
        for (Path dist : children(home.resolve(".m2").resolve("wrapper").resolve("dists"))) {
            for (Path hash : children(dist)) {
                candidates.addAll(children(hash));
            }
        }
        return found(candidates, Toolchains::maven);
    }

    /**
     * The folders tools are usually installed under on this platform, read from {@code env} on Windows: each one's
     * children are looked at.
     */
    public static List<Path> roots(Map<String, String> env) {
        List<Path> roots = new ArrayList<>();
        if (File.separatorChar == '\\') {
            for (String pf : List.of("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")) {
                variable(env, pf).ifPresent(p -> {
                    roots.add(p);
                    for (String vendor : List.of("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft",
                            "Amazon Corretto", "GraalVM", "Semeru", "Maven")) {
                        roots.add(p.resolve(vendor));
                    }
                });
            }
            variable(env, "SystemDrive").ifPresent(d -> {
                Path drive = d.resolve(File.separator);
                for (String folder : List.of("JDK", "Java", "tools", "dev")) {
                    roots.add(drive.resolve(folder));
                }
            });
        } else {
            roots.addAll(List.of(Path.of("/usr/lib/jvm"), Path.of("/usr/java"), Path.of("/opt"),
                    Path.of("/usr/share"), Path.of("/usr/local"), Path.of("/Library/Java/JavaVirtualMachines")));
        }
        return roots;
    }

    // ------------------------------------------------------------------ what a folder is

    /** {@code dir} as a JDK, when it has {@code bin/java}: its version and vendor from its {@code release} file. */
    static Optional<Tool> java(Path dir) {
        Path mac = dir.resolve("Contents").resolve("Home");   // a macOS bundle: the JDK is inside
        if (Files.isDirectory(mac)) {
            dir = mac;
        }
        if (!hasProgram(dir, "java")) {
            return Optional.empty();
        }
        Map<String, String> release = release(dir.resolve("release"));
        String version = release.getOrDefault("JAVA_VERSION", "");
        String vendor = release.getOrDefault("IMPLEMENTOR", "");
        return Optional.of(new Tool(dir, version.isBlank() ? "?" : version, vendor));
    }

    /** {@code dir} as a Maven, when it has {@code bin/mvn}: its version from its {@code maven-core} jar. */
    static Optional<Tool> maven(Path dir) {
        if (!hasProgram(dir, "mvn")) {
            return Optional.empty();
        }
        String version = "?";
        for (Path jar : children(dir.resolve("lib"), false)) {
            Matcher m = MAVEN_CORE.matcher(String.valueOf(jar.getFileName()));
            if (m.matches()) {
                version = m.group(1);
            }
        }
        return Optional.of(new Tool(dir, version, "Apache Maven"));
    }

    private static boolean hasProgram(Path dir, String name) {
        Path bin = dir.resolve("bin");
        for (String suffix : List.of("", ".exe", ".cmd")) {
            if (Files.isRegularFile(bin.resolve(name + suffix))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> release(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Matcher m = RELEASE.matcher(Files.readString(file));
            while (m.find()) {
                out.put(m.group(1), m.group(2));
            }
        } catch (IOException | SecurityException e) {
            // No release file: a runtime all the same, of a version nobody wrote down.
        }
        return out;
    }

    // ------------------------------------------------------------------ gathering

    private interface Reader {
        Optional<Tool> read(Path dir);
    }

    /**
     * Each candidate that is the tool, then each folder beside one found that is too; once each by where it really
     * is, newest first, then by where.
     */
    private static List<Tool> found(List<Path> candidates, Reader reader) {
        Map<Path, Tool> byHome = new LinkedHashMap<>();
        List<Path> parents = new ArrayList<>();
        for (Path c : candidates) {
            reader.read(c).ifPresent(t -> {
                if (byHome.putIfAbsent(real(t.home()), new Tool(real(t.home()), t.version(), t.vendor())) == null
                        && t.home().getParent() != null) {
                    parents.add(t.home().getParent());
                }
            });
        }
        for (Path parent : parents) {
            for (Path sibling : children(parent)) {
                reader.read(sibling).ifPresent(t -> byHome.putIfAbsent(real(t.home()),
                        new Tool(real(t.home()), t.version(), t.vendor())));
            }
        }
        List<Tool> out = new ArrayList<>(byHome.values());
        out.sort(Comparator.comparing(Tool::version, Toolchains::compareVersions).reversed()
                .thenComparing(t -> t.home().toString()));
        return out;
    }

    /** {@code name} in {@code env} as a folder, or none when it is unset, blank or not a path. */
    private static Optional<Path> variable(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(v.strip()).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** The install folders of {@code program} as the {@code PATH} finds it: the folder above each {@code bin} holding it. */
    private static List<Path> onPath(Map<String, String> env, String program) {
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        List<Path> out = new ArrayList<>();
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path bin = Path.of(entry.strip());
                for (String suffix : List.of("", ".exe", ".cmd")) {
                    Path p = bin.resolve(program + suffix);
                    if (Files.isRegularFile(p)) {
                        Path parent = real(p).getParent();
                        if (parent != null && parent.getParent() != null) {
                            out.add(parent.getParent());
                        }
                        break;
                    }
                }
            } catch (InvalidPathException e) {
                // Not a folder this platform can name: nothing on it.
            }
        }
        return out;
    }

    private static List<Path> children(Path dir) {
        return children(dir, true);
    }

    /** {@code dir}'s folders (or, with {@code folders} false, its entries), none when it cannot be read. */
    private static List<Path> children(Path dir, boolean folders) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                if (!folders || Files.isDirectory(p)) {
                    out.add(p);
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        return out;
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | SecurityException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** Version strings by their numbers in turn ({@code 25.0.3} after {@code 21.0.9}, {@code 3.9.16} after {@code 3.9.9}). */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\D+");
        String[] y = b.split("\\D+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : -1;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : -1;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return a.compareTo(b);
    }
}
