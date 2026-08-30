package dev.vexelray.demo.editor;

import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The mark this application wears: the VexelRay {@code prompt} nib, in coral on its writing rule.
 *
 * <p><b>One mark for every window.</b> The editor, the Navigator and the terminal are three windows of one
 * program, so the icon goes on the process rather than on any of them: {@link NativePlatform#setApplicationIcon}
 * is what a window falls back to when it names none, and none of them names one. Set before the first window
 * exists, so nothing is ever shown under the generic icon and then corrected.
 *
 * <p><b>Six sizes, each drawn at its own size.</b> The window manager asks for a size at moments the application
 * never sees — 16 for the caption, more for Alt-Tab, more again on a scaled display — and answers it out of what
 * it was given, so a set beats a single large image that every other size is a resample of. They are rasterised
 * from {@code vexelray-icons/svg/prompt.svg} on the same 96px grid the rest of the marks use; regenerating them
 * means going back to that SVG, not editing the PNGs.
 *
 * <p>Failure here is not fatal. A missing or unreadable resource costs the application its mark and nothing
 * else, and a window under the OS default icon is still a window — so this reports and carries on rather than
 * taking the process down on the way up.
 */
final class AppIcon {

    private static final String DIR = "/icons/";
    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    private AppIcon() {
    }

    /** Put the mark on this process, for every window it opens. */
    static void install() {
        try {
            NativePlatform.current().setApplicationIcon(load());
        } catch (RuntimeException e) {
            System.err.println("icon not set: " + e);
        }
    }

    /** The mark, in every size that ships with it. */
    static Icon load() {
        byte[][] encoded = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) {
            String resource = DIR + "icon-" + SIZES[i] + ".png";
            try (InputStream in = AppIcon.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new UncheckedIOException(new IOException("no such resource " + resource));
                }
                encoded[i] = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + resource, e);
            }
        }
        return Icon.fromBytes(encoded);
    }
}
