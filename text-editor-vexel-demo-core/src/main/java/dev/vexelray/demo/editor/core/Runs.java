package dev.vexelray.demo.editor.core;

import dev.sibarum.suite.tty.PtyPlatform;
import dev.vexelray.demo.editor.core.Messages.Offer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the Run menu offers for a folder and a file, worked out from what is on disk; and the line for a command typed
 * into Run…. Carried from the 0.1 editor, where it was the view's; here it reads the disk, so {@link FindRuns} runs it
 * on the job lane.
 *
 * <p>In the menu's order, each group under a rule: the file in front (its {@code main}, itself when it is a program,
 * or a Pontif file); the Pontif project around them (the folder with a {@code module.toml}); the Maven project around
 * them (Compile, Clean Install, Package, Execute, the most recent build); and the programs in the folder, four folders
 * deep at most and without the folders the navigator leaves out.
 *
 * <p>Pontif runs through its own command line, {@code pontif run}, which takes a file or a project folder. How that
 * command is reached is the caller's ({@link FindRuns} reads it from the settings): {@code pontif} on the
 * {@code PATH} unless something else is set.
 *
 * <p>What is a program, and how to run one, is the platform's ({@link PtyPlatform#programLine}): an {@code .exe},
 * {@code .bat} or {@code .ps1} on Windows; an executable ELF binary or {@code #!} script on Linux.
 */
public final class Runs {

    private static final PtyPlatform PLATFORM = PtyPlatform.current();

    /** How many folders down the programs are looked for. */
    static final int DEPTH = 4;

    /** How many programs the menu lists at most. */
    static final int MAX_PROGRAMS = 12;

    /** How many entries a walk looks at before it gives up, so a vast folder costs a bounded time. */
    private static final int MAX_VISITED = 4_000;

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern MAIN = Pattern.compile(
            "\\bstatic\\s+void\\s+main\\s*\\(\\s*(?:final\\s+)?String\\s*(?:\\[\\s*]|\\.\\.\\.)"
                    + "|(?m)^\\s*(?:public\\s+|protected\\s+)?void\\s+main\\s*\\(\\s*\\)");
    private static final Pattern PACKAGING = Pattern.compile("<packaging>\\s*([\\w-]+)\\s*</packaging>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

    private Runs() {
    }

    /** The command that runs Pontif when nothing else is set: the one on the {@code PATH}. */
    public static final String PONTIF = "pontif";

    /** Everything to offer, running Pontif with {@code pontif} from the {@code PATH}. */
    public static List<Offer> offered(Path project, Path file) {
        return offered(project, file, PONTIF);
    }

    /**
     * Everything to offer for {@code project} (the folder shown) and {@code file} (in front), either of them null;
     * {@code pontif} is the command line that runs Pontif, put in front of {@code run}.
     */
    public static List<Offer> offered(Path project, Path file, String pontif) {
        List<Offer> runs = new ArrayList<>();
        int group = 0;
        if (file != null) {
            Offer main = mainMethod(file, read(file), group);
            if (main != null) {
                runs.add(main);
            }
            Offer script = program(file, file.getParent(), "Run " + file.getFileName(), group);
            if (script != null) {
                runs.add(script);
            }
            Offer ptf = pontifFile(file, pontif, group);
            if (ptf != null) {
                runs.add(ptf);
            }
        }
        Path module = pontifRoot(project, file);
        if (module != null) {
            group++;
            runs.add(pontifProject(module, pontif, group));
        }
        Path pom = mavenRoot(project, file);
        if (pom != null) {
            group++;
            runs.addAll(maven(pom, group));
        }
        if (project != null && Files.isDirectory(project)) {
            group++;
            runs.addAll(programs(project, file, group));
        }
        return runs;
    }

    // ------------------------------------------------------------------ a main method

    /** The file's {@code main}, through Maven when it is in a module (so its dependencies are there), else the JDK's. */
    static Offer mainMethod(Path file, String text, int group) {
        String name = String.valueOf(file.getFileName());
        if (!name.endsWith(".java") || text.isEmpty() || !MAIN.matcher(stripComments(text)).find()) {
            return null;
        }
        String simple = name.substring(0, name.length() - ".java".length());
        Matcher pkg = PACKAGE.matcher(text);
        String fqn = pkg.find() ? pkg.group(1) + "." + simple : simple;
        String label = "Run " + simple + ".main()";
        Path module = nearestPom(file.getParent());
        if (module != null) {
            boolean test = isUnder(file, module.resolve("src").resolve("test"));
            String line = "mvn -q " + (test ? "test-compile" : "compile") + " exec:java "
                    + quote("-Dexec.mainClass=" + fqn) + (test ? " -Dexec.classpathScope=test" : "");
            return new Offer(label, line, module, group);
        }
        return new Offer(label, "java " + quote(file.toAbsolutePath().toString()), file.getParent(), group);
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    // ------------------------------------------------------------------ Pontif

    /** The marker that makes a folder a Pontif project. */
    static final String MODULE_TOML = "module.toml";

    private static final Pattern MODULE_NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*\"([^\"]*)\"");

    /** A {@code .ptf} file, run by itself in its own folder; null for any other file. */
    static Offer pontifFile(Path file, String pontif, int group) {
        String name = String.valueOf(file.getFileName());
        if (!name.endsWith(".ptf")) {
            return null;
        }
        return new Offer("Run " + name, pontif + " run " + quote(file.toAbsolutePath().toString()), file.getParent(),
                group);
    }

    /** The folder shown when it is a Pontif project, else the project the file is in; null for neither. */
    static Path pontifRoot(Path project, Path file) {
        if (project != null && Files.isRegularFile(project.resolve(MODULE_TOML))) {
            return project;
        }
        for (Path at = file == null ? null : file.getParent(); at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve(MODULE_TOML))) {
                return at;
            }
        }
        return null;
    }

    /** The project in {@code root}, run whole, named as its {@code module.toml} names it (else by its folder). */
    static Offer pontifProject(Path root, String pontif, int group) {
        Matcher name = MODULE_NAME.matcher(read(root.resolve(MODULE_TOML)));
        String shown = name.find() && !name.group(1).isBlank() ? name.group(1) : String.valueOf(root.getFileName());
        return new Offer("Run Project (" + shown + ")", pontif + " run " + quote(root.toAbsolutePath().toString()),
                root, group);
    }

    // ------------------------------------------------------------------ Maven

    /** The folder shown when it is a Maven project, else the module the file is in; null for neither. */
    static Path mavenRoot(Path project, Path file) {
        if (project != null && Files.isRegularFile(project.resolve("pom.xml"))) {
            return project;
        }
        return file == null ? null : nearestPom(file.getParent());
    }

    private static Path nearestPom(Path from) {
        for (Path at = from; at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("pom.xml"))) {
                return at;
            }
        }
        return null;
    }

    static List<Offer> maven(Path root, int group) {
        String bare = COMMENT.matcher(read(root.resolve("pom.xml"))).replaceAll("");
        List<Offer> runs = new ArrayList<>();
        runs.add(new Offer("Compile", "mvn compile", root, group));
        runs.add(new Offer("Clean Install", "mvn clean install", root, group));
        Matcher packaging = PACKAGING.matcher(bare);
        if (!packaging.find() || !packaging.group(1).equals("pom")) {
            runs.add(new Offer("Package", "mvn package", root, group));
        }
        if (bare.contains("exec-maven-plugin")
                && (bare.contains("<executable>") || bare.contains("<goal>exec</goal>"))) {
            runs.add(new Offer("Execute", "mvn compile exec:exec", root, group));
        }
        Offer recent = recentBuild(root.resolve("target"), group);
        if (recent != null) {
            runs.add(recent);
        }
        return runs;
    }

    /** The newest program or runnable jar in {@code target}, if any. */
    static Offer recentBuild(Path target, int group) {
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> PLATFORM.programLine(p).isPresent() || isRunnableJar(p))
                    .max(Comparator.comparing(Runs::modified))
                    .map(p -> new Offer("Run Recent Build (" + p.getFileName() + ")", programLine(p),
                            target.getParent(), group))
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isRunnableJar(Path p) {
        String name = p.getFileName().toString();
        if (!name.endsWith(".jar") || name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")
                || name.endsWith("-tests.jar") || name.startsWith("original-")) {
            return false;
        }
        try (JarFile jar = new JarFile(p.toFile())) {
            return jar.getManifest() != null && jar.getManifest().getMainAttributes().getValue("Main-Class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ programs

    static List<Offer> programs(Path project, Path front, int group) {
        List<Path> found = new ArrayList<>();
        walk(project, 0, found, new int[] {0});
        found.sort(Comparator.comparing(p -> project.relativize(p).toString().toLowerCase(Locale.ROOT)));
        List<Offer> runs = new ArrayList<>();
        for (Path p : found) {
            if (runs.size() == MAX_PROGRAMS) {
                break;
            }
            if (front != null && p.toAbsolutePath().normalize().equals(front.toAbsolutePath().normalize())) {
                continue;   // offered already, at the top
            }
            String rel = project.relativize(p).toString().replace('\\', '/');
            runs.add(program(p, p.getParent(), rel, group));
        }
        return runs;
    }

    private static void walk(Path dir, int depth, List<Path> found, int[] visited) {
        List<Path> entries;
        try (Stream<Path> list = Files.list(dir)) {
            entries = list.sorted().toList();
        } catch (IOException | RuntimeException e) {
            return;
        }
        for (Path p : entries) {
            if (++visited[0] > MAX_VISITED) {
                return;
            }
            String name = String.valueOf(p.getFileName());
            if (Files.isDirectory(p)) {
                if (depth + 1 < DEPTH && !ListFolder.HIDDEN.contains(name) && !name.startsWith(".")) {
                    walk(p, depth + 1, found, visited);
                }
            } else if (PLATFORM.programLine(p).isPresent()) {
                found.add(p);
            }
        }
    }

    private static Offer program(Path p, Path dir, String label, int group) {
        if (PLATFORM.programLine(p).isEmpty() && !String.valueOf(p.getFileName()).endsWith(".jar")) {
            return null;
        }
        return new Offer(label, programLine(p), dir, group);
    }

    static String programLine(Path p) {
        if (String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return "java -jar " + quote(p.toAbsolutePath().toString());
        }
        return PLATFORM.programLine(p).orElse(quote(p.toAbsolutePath().toString()));
    }

    // ------------------------------------------------------------------ typed into Run…

    private static final int LABEL_MAX = 40;
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The offer for a command typed into Run…: {@code environment} and {@code path} are set in the line itself
     * ({@code set "NAME=value" && …} for {@code cmd}, {@code export} for {@code sh}), so the console's first line is the
     * whole of what ran and Run again repeats it.
     */
    public static Offer typed(String command, Path dir, Map<String, String> environment, List<String> path) {
        boolean cmd = File.pathSeparatorChar == ';';
        StringBuilder line = new StringBuilder();
        environment.forEach((name, value) -> line.append(cmd ? "set \"" + name + "=" + value + "\""
                : "export " + name + "=" + quote(value)).append(" && "));
        if (!path.isEmpty()) {
            List<String> folders = cmd ? path : path.stream().map(Runs::quote).toList();
            line.append(cmd ? "set \"PATH=" + String.join(";", folders) + ";%PATH%\""
                    : "export PATH=" + String.join(":", folders) + ":\"$PATH\"").append(" && ");
        }
        String what = command.strip();
        line.append(what);
        String label = what.length() > LABEL_MAX ? what.substring(0, LABEL_MAX - 1).strip() + "…" : what;
        return new Offer(label, line.toString(), dir, 0);
    }

    /**
     * Run…'s environment box, one {@code NAME=value} a line; blank lines and {@code #} comments are skipped.
     *
     * @throws IllegalArgumentException naming the first line that is not {@code NAME=value}
     */
    public static Map<String, String> environment(String text) {
        Map<String, String> env = new LinkedHashMap<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            int eq = l.indexOf('=');
            String name = eq < 0 ? l : l.substring(0, eq).strip();
            if (eq < 0 || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Line " + (i + 1) + " of the environment is not NAME=value: " + l);
            }
            env.put(name, l.substring(eq + 1));
        }
        return env;
    }

    /** Run…'s PATH box, one folder a line. */
    public static List<String> folders(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    // ------------------------------------------------------------------ plumbing

    static String quote(String s) {
        return PLATFORM.quote(s);
    }

    /** The platform's line that runs {@code line} in its shell and exits with its status. */
    static String shellLine(String line) {
        return PLATFORM.runLine(line);
    }

    private static boolean isUnder(Path file, Path dir) {
        return file.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize());
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
