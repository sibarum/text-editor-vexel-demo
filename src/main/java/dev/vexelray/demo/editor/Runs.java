package dev.vexelray.demo.editor;

import dev.sibarum.suite.tty.PtyPlatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the Run menu offers: the commands that make sense for the folder the navigator is open on and the document in
 * front, worked out from what is on disk. Reads the disk, so it is asked on the offload lane; holds no state.
 *
 * <ul>
 *   <li>A Java file with a {@code main} method runs it: through Maven when the file is in a Maven module, so its
 *       dependencies are on the class path, and otherwise with the JDK's source launcher, which compiles the file and
 *       whatever else of its source tree it uses.</li>
 *   <li>A Maven project builds: <i>Compile</i> and <i>Clean Install</i> always, <i>Package</i> unless the packaging is
 *       {@code pom} (which packages nothing), <i>Execute</i> when the pom configures exec-maven-plugin's {@code exec}
 *       goal, and <i>Run Recent Build</i> when {@code target/} holds a program: the newest executable, or runnable
 *       jar.</li>
 *   <li>On Windows, the programs in the project: executables that really are ones (they start {@code MZ}),
 *       PowerShell scripts and batch files, as far down as {@value #DEPTH} folders and at most {@value #MAX_PROGRAMS}
 *       of them, the folders the navigator leaves out left out here too.</li>
 * </ul>
 *
 * <p>Every command is a line for the platform's shell ({@link Run#line}), run in a folder ({@link Run#dir}): what
 * runs is what the console shows at its top, so it can be copied and run again by hand.
 */
final class Runs {

    /** One thing to run: the menu's words, the shell line, and the folder it runs in. */
    record Run(String label, String line, Path dir) {
    }

    /** What counts as a program here, how to run one, and how to quote: the platform's to say, not this class's. */
    private static final PtyPlatform PLATFORM = PtyPlatform.current();

    /** How deep the walk for programs goes, and how many it offers. */
    static final int DEPTH = 4;
    static final int MAX_PROGRAMS = 12;
    /** How many entries the walk looks at before it gives up, so a huge folder does not hold the menu up. */
    private static final int MAX_VISITED = 4_000;

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    /** {@code static void main(String[] a)}, {@code (String... a)}, and Java 25's {@code void main()}. */
    private static final Pattern MAIN = Pattern.compile(
            "\\bstatic\\s+void\\s+main\\s*\\(\\s*(?:final\\s+)?String\\s*(?:\\[\\s*]|\\.\\.\\.)"
                    + "|(?m)^\\s*(?:public\\s+|protected\\s+)?void\\s+main\\s*\\(\\s*\\)");
    private static final Pattern PACKAGING = Pattern.compile("<packaging>\\s*([\\w-]+)\\s*</packaging>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

    private Runs() {
    }

    /**
     * Everything to offer, in menu order, with null between groups as the menu's separators.
     *
     * @param project the navigator's folder, or null
     * @param file    the document in front's file, or null for one never saved
     * @param text    the document in front's text as it is in the editor
     */
    static List<Run> offered(Path project, Path file, String text) {
        List<Run> runs = new ArrayList<>();
        Run main = file == null ? null : mainMethod(file, text);
        if (main != null) {
            runs.add(main);
        }
        Run script = file == null ? null : program(file, file.getParent(), "Run " + file.getFileName());
        if (script != null) {
            runs.add(script);
        }
        Path pom = mavenRoot(project, file);
        if (pom != null) {
            separate(runs);
            runs.addAll(maven(pom));
        }
        if (project != null && Files.isDirectory(project)) {
            List<Run> programs = programs(project, file);
            if (!programs.isEmpty()) {
                separate(runs);
                runs.addAll(programs);
            }
        }
        return runs;
    }

    private static void separate(List<Run> runs) {
        if (!runs.isEmpty() && runs.getLast() != null) {
            runs.add(null);
        }
    }

    // -- a main method ----------------------------------------------------------------------------------------

    /** Running {@code file}'s {@code main}, or null when it is not a Java file that has one. */
    static Run mainMethod(Path file, String text) {
        String name = String.valueOf(file.getFileName());
        if (!name.endsWith(".java") || text == null || !MAIN.matcher(stripComments(text)).find()) {
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
            return new Run(label, line, module);
        }
        return new Run(label, "java " + quote(file.toAbsolutePath().toString()), file.getParent());
    }

    /** Java without its comments, so a {@code main} in a comment or a javadoc example does not count. */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    // -- Maven ------------------------------------------------------------------------------------------------

    /** The Maven project to build: the navigator's folder when it has a pom, else the module the file is in. */
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

    static List<Run> maven(Path root) {
        String pom = read(root.resolve("pom.xml"));
        String bare = COMMENT.matcher(pom).replaceAll("");
        List<Run> runs = new ArrayList<>();
        runs.add(new Run("Compile", "mvn compile", root));
        runs.add(new Run("Clean Install", "mvn clean install", root));
        Matcher packaging = PACKAGING.matcher(bare);
        if (!packaging.find() || !packaging.group(1).equals("pom")) {
            runs.add(new Run("Package", "mvn package", root));
        }
        if (bare.contains("exec-maven-plugin")
                && (bare.contains("<executable>") || bare.contains("<goal>exec</goal>"))) {
            runs.add(new Run("Execute", "mvn compile exec:exec", root));
        }
        Run recent = recentBuild(root.resolve("target"));
        if (recent != null) {
            runs.add(recent);
        }
        return runs;
    }

    /** The newest program in {@code target}: one of this platform's programs, or a jar that names its main class. */
    static Run recentBuild(Path target) {
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> PLATFORM.programLine(p).isPresent() || isRunnableJar(p))
                    .max(Comparator.comparing(Runs::modified))
                    .map(p -> new Run("Run Recent Build (" + p.getFileName() + ")", programLine(p), target.getParent()))
                    .orElse(null);
        } catch (IOException e) {
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

    // -- programs ---------------------------------------------------------------------------------------------

    /** The programs under {@code project}, by path from it; {@code front}, which the menu already offers, left out. */
    static List<Run> programs(Path project, Path front) {
        List<Path> found = new ArrayList<>();
        walk(project, 0, found, new int[] {0});
        found.sort(Comparator.comparing(p -> project.relativize(p).toString().toLowerCase(Locale.ROOT)));
        List<Run> runs = new ArrayList<>();
        for (Path p : found) {
            if (runs.size() == MAX_PROGRAMS) {
                break;
            }
            if (front != null && p.toAbsolutePath().normalize().equals(front.toAbsolutePath().normalize())) {
                continue;
            }
            String rel = project.relativize(p).toString().replace('\\', '/');
            runs.add(program(p, p.getParent(), rel));
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
                if (depth + 1 < DEPTH && !FolderSource.HIDDEN.contains(name) && !name.startsWith(".")) {
                    walk(p, depth + 1, found, visited);
                }
            } else if (PLATFORM.programLine(p).isPresent()) {
                found.add(p);
            }
        }
    }

    /** Running program {@code p} as {@code label}, in {@code dir}; null when it is not one this runs. */
    private static Run program(Path p, Path dir, String label) {
        if (PLATFORM.programLine(p).isEmpty() && !String.valueOf(p.getFileName()).endsWith(".jar")) {
            return null;
        }
        return new Run(label, programLine(p), dir);
    }

    /** The shell line that runs program {@code p}: a jar through {@code java}, anything else as the platform runs it. */
    static String programLine(Path p) {
        if (String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return "java -jar " + quote(p.toAbsolutePath().toString());
        }
        return PLATFORM.programLine(p).orElse(quote(p.toAbsolutePath().toString()));
    }

    // -- plumbing ---------------------------------------------------------------------------------------------

    /** {@code s} as one word for the platform's shell. */
    static String quote(String s) {
        return PLATFORM.quote(s);
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
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
