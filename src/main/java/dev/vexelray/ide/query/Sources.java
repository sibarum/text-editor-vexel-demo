package dev.vexelray.ide.query;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * The text, as the editor currently holds it. The host implements this; a {@link LanguagePack} reads it.
 *
 * <p>The direction matters. A pack that opened files itself would answer about the last save, and the whole
 * reason to ask a question mid-keystroke is to be told about the line being typed. So the editor stays the one
 * owner of what the text <em>is</em>, and a pack owns only what it has worked out about it.
 *
 * <p>Units are keyed by whatever string the host uses for a document — an absolute path for a file on disk, and
 * something stable but path-shaped for a buffer that has never been saved. A pack must not assume a unit key is
 * openable.
 */
public interface Sources {

    /** Every unit the project currently holds, saved or not. */
    Stream<String> units();

    /** The current text of one unit, or empty if the host has no such unit. */
    Optional<String> text(String unit);

    /**
     * A monotonically increasing stamp for a unit, changing on every edit. A pack caches against this rather than
     * against the text, so deciding whether a rebuild is needed costs a comparison instead of a diff.
     */
    long version(String unit);
}
