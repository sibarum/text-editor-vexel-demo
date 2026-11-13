package dev.vexelray.demo.editor.core;

import sibarum.concordance.index.Index;
import sibarum.concordance.index.JarCache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where {@link BuildIndex} keeps what it built: one file per project in {@code dir}, beside the settings
 * ({@code ~/.text-editor-vexel-demo/index}), and each library's index in Concordance's machine-wide {@link JarCache},
 * so a library another project or the Concordance shell already indexed costs a lookup. A part rather than the job's
 * own state, because it is the settings' home that says where, and a test moves that home.
 *
 * <p><b>And the last one built, in memory.</b> A search asks at every keystroke, and reading a project's index from
 * disk each time would cost more than the search. So each project's latest {@link Index} is held here ({@link #held}),
 * put by {@link BuildIndex} and by {@link FindSymbols} when it had to read one from disk. Only the {@code Index} is
 * held, never the {@code ProjectIndex} it came from: an {@code Index} is a record of lists nobody changes, so the jobs
 * on the offload lane's threads can read one together with no lock, and replacing it is one put.
 */
public final class IndexStore {

    private final Path dir;
    private final JarCache jars;
    private final Map<Path, Index> held = new ConcurrentHashMap<>();

    /** {@code jars}: null to share no library indexes, which is what a test that must leave the home alone passes. */
    public IndexStore(Path dir, JarCache jars) {
        this.dir = dir.toAbsolutePath().normalize();
        this.jars = jars;
    }

    public Path dir() {
        return dir;
    }

    /** The machine's shared library indexes, or null for none. */
    public JarCache jars() {
        return jars;
    }

    /** Where {@code project}'s index is kept: named by a hash of its path, since a path is not a file name. */
    public Path savedAt(Path project) {
        String key = key(project).toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return dir.resolve(HexFormat.of().formatHex(digest, 0, 12) + ".concordance");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /** {@code project}'s index as last built or read in this run, if it has been. */
    public Optional<Index> held(Path project) {
        return Optional.ofNullable(held.get(key(project)));
    }

    /** Hold {@code index}, just built, as {@code project}'s latest, in place of whatever was. */
    public void hold(Path project, Index index) {
        held.put(key(project), index);
    }

    /**
     * Hold {@code index}, read from disk, unless something is held already, and answer what is held. A build that
     * landed while the read was under way is newer than what was read, so it stays.
     */
    public Index adopt(Path project, Index index) {
        Index was = held.putIfAbsent(key(project), index);
        return was != null ? was : index;
    }

    private static Path key(Path project) {
        return project.toAbsolutePath().normalize();
    }
}
