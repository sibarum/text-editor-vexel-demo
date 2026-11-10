package dev.vexelray.demo.editor.core;

import sibarum.concordance.index.JarCache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Where {@link BuildIndex} keeps what it built: one file per project in {@code dir}, beside the settings
 * ({@code ~/.text-editor-vexel-demo/index}), and each library's index in Concordance's machine-wide {@link JarCache},
 * so a library another project or the Concordance shell already indexed costs a lookup. A part rather than the job's
 * own state, because it is the settings' home that says where, and a test moves that home.
 */
public final class IndexStore {

    private final Path dir;
    private final JarCache jars;

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
        String key = project.toAbsolutePath().normalize().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return dir.resolve(HexFormat.of().formatHex(digest, 0, 12) + ".concordance");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}
