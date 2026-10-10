package dev.vexelray.demo.editor;

import sibarum.concordance.project.MavenModule;
import sibarum.concordance.project.MavenProject;
import sibarum.concordance.project.MavenProjectReader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a folder is, as far as deciding whether it is a project's root goes: a quick look, bounded in files and
 * time, at its build, its languages and the shape of its Java.
 *
 * <p>The clues are the ones a person reads when they open a folder to see whether it is the right one: a
 * {@code pom.xml} and what it calls itself; whether it gathers modules, and how much code each holds; whether it is
 * itself one module of a bigger build, in which case the bigger build is likelier the root; whether it is where a
 * repository starts; and what is in it — which languages, how many classes, records, interfaces and enums, and
 * which annotations its code leans on.
 *
 * <p><b>Bounded, because it runs on hover.</b> At most {@link #MAX_FILES} files are counted and
 * {@link #MAX_JAVA} Java files read, so a home folder answers as fast as a module does; {@link #truncated} says
 * the counts stopped short. {@link #of} also gives up between files once {@code live} says the pointer has
 * moved on, returning null.
 */
record Survey(
        Path folder,
        String artifact,
        String packaging,
        List<Module> modules,
        String partOf,
        boolean repository,
        List<Count> languages,
        int files,
        int folders,
        boolean truncated,
        int classes,
        int interfaces,
        int enums,
        int records,
        List<Count> annotations) {

    /** A module of the build, with how many Java files it holds. */
    record Module(String name, int javaFiles) {
    }

    /** A name and how often it was seen: a language's files, an annotation's uses. */
    record Count(String name, int count) {
    }

    static final int MAX_FILES = 6000;
    static final int MAX_JAVA = 900;
    private static final long MAX_BYTES = 256 * 1024;
    private static final int MAX_DEPTH = 16;
    private static final int TOP_ANNOTATIONS = 10;
    private static final long PROGRESS_NANOS = 120_000_000L;

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"");
    private static final Pattern DECLARATION =
            Pattern.compile("\\b(class|interface|enum|record)\\s+[A-Z][A-Za-z0-9_]*\\s*[<({\\s]");
    private static final Pattern ANNOTATION_TYPE = Pattern.compile("@interface\\s+[A-Z]");
    private static final Pattern ANNOTATION = Pattern.compile("@([A-Z][A-Za-z0-9_]*)");
    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");

    /** The language a file is counted under, by extension or name; null for one not worth naming. */
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("scala", "Scala"),
            Map.entry("groovy", "Groovy"), Map.entry("lean", "Lean"), Map.entry("py", "Python"),
            Map.entry("js", "JavaScript"), Map.entry("mjs", "JavaScript"), Map.entry("cjs", "JavaScript"),
            Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"), Map.entry("rs", "Rust"),
            Map.entry("go", "Go"), Map.entry("c", "C"), Map.entry("h", "C"), Map.entry("cpp", "C++"),
            Map.entry("hpp", "C++"), Map.entry("cs", "C#"), Map.entry("md", "Markdown"), Map.entry("xml", "XML"),
            Map.entry("json", "JSON"), Map.entry("yaml", "YAML"), Map.entry("yml", "YAML"),
            Map.entry("html", "HTML"), Map.entry("css", "CSS"), Map.entry("sh", "Shell"),
            Map.entry("ps1", "PowerShell"), Map.entry("glsl", "GLSL"), Map.entry("wgsl", "WGSL"),
            Map.entry("properties", "Properties"));

    /** Whether this folder builds as a Maven project of its own. */
    boolean maven() {
        return artifact != null;
    }

    /** Whether it gathers modules rather than holding code itself. */
    boolean aggregator() {
        return "pom".equals(packaging);
    }

    /** The Java files counted, all modules together. */
    int javaFiles() {
        for (Count c : languages) {
            if (c.name().equals("Java")) {
                return c.count();
            }
        }
        return 0;
    }

    /**
     * Survey {@code folder}, or return null if {@code live} turns false part way: the answer is no longer wanted.
     * Any one thing that cannot be read is left out rather than failing the rest.
     *
     * <p>{@code progress} is handed the survey so far every {@link #PROGRESS_NANOS}, so that a large build can be
     * watched filling in rather than waited for; the returned survey is the whole of it.
     */
    static Survey of(Path folder, BooleanSupplier live, Consumer<Survey> progress) {
        Tally t = new Tally(folder.toAbsolutePath().normalize());
        long next = System.nanoTime() + PROGRESS_NANOS;

        Deque<Path> pending = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        pending.push(t.dir);
        depths.push(0);
        walk:
        while (!pending.isEmpty()) {
            Path at = pending.pop();
            int depth = depths.pop();
            try (DirectoryStream<Path> s = Files.newDirectoryStream(at)) {
                for (Path p : s) {
                    if (!live.getAsBoolean()) {
                        return null;
                    }
                    if (System.nanoTime() > next) {
                        progress.accept(t.survey());
                        next = System.nanoTime() + PROGRESS_NANOS;
                    }
                    String name = String.valueOf(p.getFileName());
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        if (FolderSource.HIDDEN.contains(name) || name.startsWith(".")) {
                            continue;
                        }
                        t.folders++;
                        if (depth < MAX_DEPTH) {
                            pending.push(p);
                            depths.push(depth + 1);
                        }
                        continue;
                    }
                    if (++t.files > MAX_FILES) {
                        t.files--;
                        t.truncated = true;
                        break walk;
                    }
                    t.file(p, name);
                }
            } catch (IOException | RuntimeException e) {
                // an unreadable folder counts as empty
            }
        }
        return t.survey();
    }

    /** A survey being gathered: the counts so far, and what turns them into a {@link Survey} at any moment. */
    private static final class Tally {

        final Path dir;
        final String artifact;
        final String packaging;
        final List<MavenModule> mavenModules;
        final String partOf;
        final boolean repository;
        final Map<String, Integer> languages = new HashMap<>();
        final Map<String, Integer> annotations = new HashMap<>();
        final Map<Path, Integer> perModule = new LinkedHashMap<>();
        final int[] shape = new int[4];
        int files;
        int folders;
        int javaRead;
        boolean truncated;

        Tally(Path dir) {
            this.dir = dir;
            String a = null;
            String k = null;
            List<MavenModule> ms = List.of();
            if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                try {
                    MavenProject project = MavenProjectReader.read(dir);
                    a = project.root().artifactId();
                    k = project.root().packaging();
                    ms = project.modules().stream().filter(m -> !m.isAggregator()).toList();
                } catch (IOException | RuntimeException e) {
                    a = String.valueOf(dir.getFileName());   // a pom it cannot follow is still a pom
                    k = "?";
                }
            }
            artifact = a;
            packaging = k;
            mavenModules = ms;
            for (MavenModule m : mavenModules) {
                perModule.put(m.baseDir().toAbsolutePath().normalize(), 0);
            }
            partOf = Survey.partOf(dir);
            repository = Files.exists(dir.resolve(".git"));
        }

        void file(Path p, String name) {
            String language = LANGUAGES.get(extension(name));
            if (language == null) {
                return;
            }
            languages.merge(language, 1, Integer::sum);
            if (!language.equals("Java")) {
                return;
            }
            Path module = moduleOf(p, perModule);
            if (module != null) {
                perModule.merge(module, 1, Integer::sum);
            }
            if (javaRead < MAX_JAVA) {
                javaRead++;
                read(p, shape, annotations);
            } else {
                truncated = true;
            }
        }

        Survey survey() {
            List<Module> modules = new ArrayList<>();
            for (MavenModule m : mavenModules) {
                Path base = m.baseDir().toAbsolutePath().normalize();
                modules.add(new Module(m.artifactId(), perModule.getOrDefault(base, 0)));
            }
            if (modules.size() == 1 && !"pom".equals(packaging)) {
                modules = List.of();   // a single jar is the project itself, not a project of one module
            }
            return new Survey(dir, artifact, packaging, List.copyOf(modules), partOf, repository,
                    sorted(languages, Integer.MAX_VALUE), files, folders, truncated,
                    shape[0], shape[1], shape[2], shape[3], sorted(annotations, TOP_ANNOTATIONS));
        }
    }

    /**
     * The outermost build that names {@code dir} as one of its modules, step by step up the tree; null when the
     * folder above does not. Read from the poms' {@code <module>} lines, since the whole reactor is not needed to
     * answer it.
     */
    static String partOf(Path dir) {
        String outermost = null;
        Path child = dir;
        for (Path parent = dir.getParent(); parent != null; parent = parent.getParent()) {
            Path pom = parent.resolve("pom.xml");
            if (!Files.isRegularFile(pom) || !names(pom, String.valueOf(child.getFileName()))) {
                break;
            }
            outermost = String.valueOf(parent.getFileName());
            child = parent;
        }
        return outermost;
    }

    private static boolean names(Path pom, String module) {
        try {
            String text = COMMENT.matcher(Files.readString(pom)).replaceAll(" ");
            Matcher m = MODULE.matcher(text);
            while (m.find()) {
                String declared = m.group(1).replace('\\', '/');
                if (declared.equals(module) || declared.equals("./" + module)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    /** The module whose base folder is the nearest above {@code file}, or null. */
    private static Path moduleOf(Path file, Map<Path, Integer> modules) {
        Path best = null;
        for (Path base : modules.keySet()) {
            if (file.startsWith(base) && (best == null || base.getNameCount() > best.getNameCount())) {
                best = base;
            }
        }
        return best;
    }

    /** Count {@code file}'s top-level kinds of declaration and its annotations, comments and strings aside. */
    private static void read(Path file, int[] shape, Map<String, Integer> annotations) {
        try {
            if (Files.size(file) > MAX_BYTES) {
                return;
            }
            String text = STRING.matcher(COMMENT.matcher(Files.readString(file)).replaceAll(" ")).replaceAll("\"\"");
            Matcher d = DECLARATION.matcher(text);
            while (d.find()) {
                switch (d.group(1)) {
                    case "class" -> shape[0]++;
                    case "interface" -> shape[1]++;
                    case "enum" -> shape[2]++;
                    default -> shape[3]++;
                }
            }
            Matcher at = ANNOTATION_TYPE.matcher(text);
            while (at.find()) {
                shape[1]++;   // an annotation type is an interface by another name
            }
            Matcher a = ANNOTATION.matcher(text);
            while (a.find()) {
                annotations.merge(a.group(1), 1, Integer::sum);
            }
        } catch (IOException | RuntimeException e) {
            // unreadable or not text: counted as a file, not as code
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static List<Count> sorted(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                .limit(limit)
                .map(e -> new Count(e.getKey(), e.getValue()))
                .toList();
    }
}
