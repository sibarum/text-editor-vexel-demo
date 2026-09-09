package dev.vexelray.demo.editor;

import dev.vexelray.os.Icon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The mark this application wears: the VexelRay {@code prompt} nib, in coral on its writing rule.
 *
 * <p><b>One mark for every window, and it has to be said twice.</b> The editor, the Navigator and the terminal
 * are three windows of one program, so the mark goes on the process: {@code NativePlatform.setApplicationIcon}
 * is what a window falls back to when it names none. Set before the first window exists, so nothing is ever
 * shown under the generic icon and then corrected.
 *
 * <p>The editor and the Navigator <em>also</em> name it on their own configs, which is redundant for exactly as
 * long as this application is the process. Embedded in MainFrame it is not: the process is MainFrame, so the
 * fallback is MainFrame's mark and a window naming none would be indistinguishable from the shell wherever
 * windows are listed. Saying it in both places is what makes the two hosts agree, and costs one call per window.
 * The terminal is not among them on purpose — it is MainFrame's own window and wears the {@code mainframe} mark.
 *
 * <p><b>Both of those calls are the framework's now</b>, standalone: the mark travels as part of this
 * application's identity on {@code AppInfo}, and {@code VexelApplication} makes them in the one order that
 * works. So the whole of that paragraph, which every application on this stack that wears a mark had a copy
 * of, is one method call at {@link #optional()}. What is left here is reading the artwork.
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

    /** Decoded once. Windows name this on their own config as well as inheriting it, so it is asked for more
     *  than once a session, and six PNGs is not worth decoding twice. */
    private static Icon mark;

    private AppIcon() {
    }

    /**
     * The mark for the framework to wear this application in, or {@code null} if it could not be read.
     *
     * <p>Reports and carries on, which is the same answer {@code install} gave before the framework took over
     * the installing: a missing or unreadable resource costs the application its mark and nothing else, and a
     * window under the OS default icon is still a window. {@link #load()} stays strict, because the test that
     * notices a renamed resource has to have something to fail on.
     */
    static Icon optional() {
        try {
            return load();
        } catch (RuntimeException e) {
            System.err.println("icon not read: " + e);
            return null;
        }
    }

    /**
     * The mark, in every size that ships with it.
     *
     * <p>Named per window as well as installed on the process. Standalone the two say the same thing, but
     * inside MainFrame the process is MainFrame: a window that named no mark of its own would inherit the
     * shell's and be indistinguishable from it wherever windows are listed.
     */
    static synchronized Icon load() {
        if (mark != null) {
            return mark;
        }
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
        return mark = Icon.fromBytes(encoded);
    }
}
