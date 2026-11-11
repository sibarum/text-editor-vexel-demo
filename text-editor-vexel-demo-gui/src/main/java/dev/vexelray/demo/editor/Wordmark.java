package dev.vexelray.demo.editor;

import dev.vexelray.fields.sdg.SdgFile;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageSdg;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.probe.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * The name as the title bar's logo: "Pixel textura", D on the suite canvas's "IDE, gothic" board. IDE in Jacquard 24,
 * a blackletter built on a pixel grid, spaced .05 em, at the board's title-bar cut (an em of 22 px).
 *
 * <p>The letters are not the font: they are its outlines traced once into {@code src/main/rc/wordmark.svg}, and the
 * icon plugin's pair goal makes that drawing an SDG, {@code /wordmark.sdgb}, so the word is sharp at any scale and no
 * typeface is carried for three letters. Without it the framework would put the build's name there,
 * {@code text-editor-vexel-demo}, in the bar's own type.
 *
 * <p><b>DE follows the bar; the I keeps its teal.</b> D and E are the drawing's layer {@code face}, in the role
 * {@code tint}, the node's own text colour, which the bar hands over as a paint ({@link TitleBar.Wordmark#ink}):
 * resolved every frame, so the pointer lifts it into the accent as it does a caption glyph. The I is the icon's
 * #5fd4e6, as the board draws it on a dark bar, and this application has only a dark theme ({@link Look}).
 *
 * <p>Sized in {@code rem}, as the bar's mark is, so the word zooms with the mark beside it.
 */
final class Wordmark {

    private static final Log LOG = Log.of("text-editor-vexel-demo.wordmark");

    /** The drawing's size, in its own units: a hundred to the em. */
    static final float W = 170.16f;
    static final float H = 58.14f;

    /** One unit on the screen: the board's title-bar cut, an em of 22 px, which is 1.375 rem. */
    static final float REM = 1.375f / 100f;

    private Wordmark() {
    }

    /** The drawing, its D and E in the role {@code tint}; null, said once, if it is not on the class path. */
    static ImageSdg face() {
        try (InputStream in = Wordmark.class.getResourceAsStream("/wordmark.sdgb")) {
            if (in == null) {
                LOG.warn("no /wordmark.sdgb on the class path (the icon plugin's pair goal makes it); no wordmark");
                return null;
            }
            return ImageSdg.of(SdgFile.read(in.readAllBytes()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("the wordmark /wordmark.sdgb could not be read; no wordmark", e);
            return null;
        }
    }

    /**
     * The wordmark for the title bar's lockup: the drawing, its D and E lit in whatever ink the bar hands it. The ink
     * is the node's text colour, a paint the renderer resolves in each frame's theme and fades as it fades text.
     */
    static TitleBar.Wordmark bar(Gui gui) {
        Node node = gui.box().role("wordmark").size(Length.rem(W * REM), Length.rem(H * REM)).textColor(Role.INK);
        ImageSdg face = face();
        if (face != null) {
            node.sdg(face);
        }
        return new TitleBar.Wordmark() {
            @Override
            public Node node() {
                return node;
            }

            @Override
            public void ink(Paint ink) {
                node.textColor(ink);
            }
        };
    }
}
