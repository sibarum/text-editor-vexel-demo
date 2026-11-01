package dev.vexelray.demo.editor.core;

import sibarum.probe.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Whole folders at once, for {@link CreateFile}: zipping one into an archive, and taking away what was half made.
 */
final class Trees {

    private static final Log LOG = Log.of("text-editor-vexel-demo.files");

    private Trees() {
    }

    /**
     * Everything under {@code folder} zipped into {@code at}, which is not made if there is no file to put in it. Links
     * are left out rather than followed, so a link to a drive is not an archive of the drive. Says how many files went
     * in.
     */
    static String archive(Path at, Path folder) throws IOException {
        Path self = at.toAbsolutePath().normalize();
        int files = 0;
        try (OutputStream out = Files.newOutputStream(at, StandardOpenOption.CREATE_NEW);
             ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> walk = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.equals(folder) || p.toAbsolutePath().normalize().equals(self)) {
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
                    files++;
                }
            }
            if (files == 0) {
                throw new IOException("nothing to archive in " + folder.getFileName());
            }
        } catch (java.nio.file.FileAlreadyExistsException taken) {
            throw taken;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(at);
            throw e;
        }
        return files == 1 ? "1 file" : files + " files";
    }

    /** Take away what was half made, deepest first. */
    static void delete(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not take away the half-made {}: {}", root, e.toString());
        }
    }
}
