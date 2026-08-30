import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pack the icon PNGs into the {@code .ico} the executable wears.
 *
 * <p>Run it from the repository root, with no arguments, whenever the PNGs under
 * {@code src/main/resources/icons} change:
 *
 * <pre>java tools/MakeIco.java</pre>
 *
 * <p>The window icon needs no such step -- the running application reads those PNGs directly. This exists
 * because Windows will not: an executable's icon is a linked resource, and a resource compiler wants one
 * {@code .ico} holding every size rather than a directory of files.
 *
 * <p>Each size goes in as its own PNG rather than as a BMP. An {@code .ico} may hold either, and Windows has
 * read PNG entries at every size since Vista -- which is a decade before the graphics stack this application
 * needs, so nothing that could run the editor could fail to read its icon.
 */
public final class MakeIco {

    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws Exception {
        Path in = Path.of("src", "main", "resources", "icons");
        Path out = Path.of("src", "main", "rc", "editor.ico");

        byte[][] png = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) {
            png[i] = Files.readAllBytes(in.resolve("icon-" + SIZES[i] + ".png"));
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream ico = new DataOutputStream(bytes);
        // ICONDIR: reserved, type 1 (icon), count. Little-endian, so every multi-byte field is written by hand.
        le16(ico, 0);
        le16(ico, 1);
        le16(ico, SIZES.length);
        // The directory is fixed-width, so where the images start is known before any of them is written.
        int offset = 6 + 16 * SIZES.length;
        for (int i = 0; i < SIZES.length; i++) {
            // 256 does not fit in a byte and is written as 0 -- the format's way of saying "the largest size".
            ico.writeByte(SIZES[i] == 256 ? 0 : SIZES[i]);
            ico.writeByte(SIZES[i] == 256 ? 0 : SIZES[i]);
            ico.writeByte(0);            // colours in the palette: none, this is a true-colour image
            ico.writeByte(0);            // reserved
            le16(ico, 1);                // colour planes
            le16(ico, 32);               // bits per pixel
            le32(ico, png[i].length);
            le32(ico, offset);
            offset += png[i].length;
        }
        for (byte[] image : png) {
            ico.write(image);
        }

        Files.createDirectories(out.getParent());
        Files.write(out, bytes.toByteArray());
        System.out.println("wrote " + out + " (" + bytes.size() + " bytes, " + SIZES.length + " sizes)");
    }

    private static void le16(DataOutputStream out, int v) throws Exception {
        out.writeByte(v & 0xFF);
        out.writeByte((v >>> 8) & 0xFF);
    }

    private static void le32(DataOutputStream out, int v) throws Exception {
        le16(out, v & 0xFFFF);
        le16(out, v >>> 16);
    }
}
