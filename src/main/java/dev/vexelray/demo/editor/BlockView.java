package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.engine.embedded.SampledTechniqueHost;
import dev.vexelray.framework.api.BeforeFrame;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.LayeredIcon;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.fileicons.FileIconView;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.Lens;
import dev.vexelray.shader.Shadings;
import dev.vexelray.technique.mesh.Mesh;
import dev.vexelray.technique.mesh.MeshTechnique;
import dev.vexelray.technique.mesh.Placement;
import dev.vexelray.vulkan.present.SampledColorTarget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link Blocks} on screen: the folder's plates and its files' cubes as polygon meshes, seen from above at three
 * quarters through a long lens, with the file-type icon on the top of each block large enough to carry one.
 *
 * <p><b>Two halves on two threads.</b> The node and the icons are built with the tree and written from anywhere,
 * like every other node. The drawing is the mesh technique ({@code vexelray-technique-mesh}) composited by a
 * {@link SampledTechniqueHost} into a target the GUI samples as this node's image, and that is GPU work: it is done
 * in {@link Binding#frame}, a {@code @BeforeFrame} hook on the main thread, and only when something changed — a new
 * scene, or a box of another size. Nothing animates, so an unchanged picture costs nothing per frame.
 *
 * <p><b>Two meshes, uploaded once.</b> The technique packs its meshes into one vertex buffer when it is realised and
 * refuses a new mesh after that, so every folder is the same unit plate and every file the same unit cube, placed
 * and scaled per block; a different folder is the same instances moved, retinted, shown or hidden. That is also why
 * {@link Blocks} keeps every block a uniformly scaled square.
 *
 * <p><b>Nearly orthographic.</b> The engine's {@link Lens} is a pinhole, so the view is a long lens far away: a
 * field of view under five degrees, at which the far edge of a plate is drawn about a tenth smaller than the near
 * one, so sizes can still be compared across the picture. The scene is always one unit across, so the lens, which
 * the shaders are built for, never has to change; only the distance does, to fit the box.
 *
 * <p><b>The icons are nodes over the picture, placed by the shader's own projection</b> ({@link #project}, the
 * mesh technique's vertex stage written out in Java), so an icon sits on the top of its block by construction. A
 * node drawn over the image is not occluded by it, so a block whose top is hidden behind a taller one gets no icon:
 * {@link #hidden} casts the ray from the eye to the top and asks every other block whether it is in the way.
 */
final class BlockView {

    /** The most icons shown at once: the largest blocks whose tops are in view. */
    private static final int ICONS = 36;
    /** Smaller than this, in pixels, an icon is not legible and is left off. */
    private static final float ICON_MIN = 12;
    private static final float ICON_MAX = 88;

    /** The lens: under five degrees across the height. */
    private static final double FOCAL = 24;
    private static final ClipDepth DEPTH = new ClipDepth(0.5, 200);
    /** Turned so both floor axes read, tilted enough that height reads as height. */
    private static final double YAW = Math.toRadians(36);
    private static final double PITCH = Math.toRadians(34);
    /** How much of the box the scene's furthest corner may reach, from the centre to the edge. */
    private static final double FILL = 0.92;

    private final Gui gui;
    private final Node node;
    private final Icon[] icons = new Icon[ICONS];
    private final boolean dark;
    private final Color background;
    private final double hue;

    private volatile Blocks.Scene scene;

    BlockView(Gui gui) {
        this.gui = gui;
        Theme theme = gui.theme();
        this.dark = FileIconView.dark(theme);
        this.background = theme.color(Look.PICKING);
        this.hue = theme.palette().accent().hueDegrees();
        node = gui.box()
                .width(Length.FILL)
                .height(Length.grow(1))
                .corner(Type.CORNER)
                .clip(true)
                .scroll(false, false)
                .visible(false);
        for (int i = 0; i < ICONS; i++) {
            icons[i] = new Icon(gui);
            node.append(icons[i].view.node());
        }
        gui.landmark(Landmarks.BLOCKS, node);
    }

    Node node() {
        return node;
    }

    /** Show {@code s}, or nothing for null. From any thread; drawn by the next frame. */
    void show(Blocks.Scene s) {
        scene = s;
        node.visible(s != null);
        if (s == null) {
            for (Icon icon : icons) {
                icon.hide();
            }
        }
        gui.requestFrame();
    }

    /** What is shown, or null. */
    Blocks.Scene scene() {
        return scene;
    }

    /** The drawing's half, given the device. Main thread only, from a {@code @MainThread} recipe. */
    Binding bind(GuiApp app) {
        return new Binding(app);
    }

    /**
     * Where the eye is for {@code s} in a box of {@code aspect}: looking at the middle of what the blocks cover on
     * screen, back along the line the camera looks along just far enough that every corner of every block is inside
     * {@link #FILL} of the box. Fitted to the blocks rather than to the box round them, whose top corners over the
     * far edge are empty air and would leave the picture small.
     */
    static double[] eye(Blocks.Scene s, double aspect) {
        double cy = Math.cos(YAW);
        double sy = Math.sin(YAW);
        double cp = Math.cos(PITCH);
        double sp = Math.sin(PITCH);
        // Every corner in the camera's frame, from the origin: across, up, and towards.
        int n = s.blocks().size() * 8;
        double[] ax = new double[n];
        double[] vy = new double[n];
        double[] vz = new double[n];
        int k = 0;
        double minA = Double.MAX_VALUE;
        double maxA = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (Blocks.Block b : s.blocks()) {
            double h = b.side() / 2;
            for (double x : new double[] {b.x() - h, b.x() + h}) {
                for (double y : new double[] {b.y(), b.top()}) {
                    for (double z : new double[] {b.z() - h, b.z() + h}) {
                        double az = x * sy + z * cy;
                        ax[k] = x * cy - z * sy;
                        vy[k] = y * cp + az * sp;
                        vz[k] = az * cp - y * sp;
                        minA = Math.min(minA, ax[k]);
                        maxA = Math.max(maxA, ax[k]);
                        minV = Math.min(minV, vy[k]);
                        maxV = Math.max(maxV, vy[k]);
                        k++;
                    }
                }
            }
        }
        double midA = (minA + maxA) / 2;
        double midV = (minV + maxV) / 2;
        // At distance d a corner is at depth d + vz; it fits when its projection is inside FILL.
        double distance = 0;
        for (int i = 0; i < k; i++) {
            distance = Math.max(distance, FOCAL * Math.abs(ax[i] - midA) / (aspect * FILL) - vz[i]);
            distance = Math.max(distance, FOCAL * Math.abs(vy[i] - midV) / FILL - vz[i]);
        }
        // That fit is exact for the corner that decides it but centres the picture as if there were no
        // perspective, and near corners are drawn larger than far ones: a picture that sits low and loose. So it is
        // refined against the projection itself, a few times, which settles because the lens is long: centred on
        // what the corners cover, then moved along the line of sight until the wider half-extent is FILL.
        double look = midA;
        double up = midV;
        for (int pass = 0; pass < 6; pass++) {
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int i = 0; i < k; i++) {
                double depth = distance + vz[i];
                double x = FOCAL * (ax[i] - look) / (aspect * depth);
                double y = FOCAL * (vy[i] - up) / depth;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            look += (minX + maxX) / 2 * aspect * distance / FOCAL;
            up += (minY + maxY) / 2 * distance / FOCAL;
            distance *= Math.max((maxX - minX) / 2, (maxY - minY) / 2) / FILL;
        }
        // The point looked at, as a world point on the camera's own right and up, and the eye back from it.
        double tx = cy * look + sy * sp * up;
        double ty = cp * up;
        double tz = -sy * look + cy * sp * up;
        return new double[] {tx - sy * cp * distance, ty + sp * distance, tz - cy * cp * distance};
    }

    /**
     * Where a world point lands in a {@code w} by {@code h} box, in pixels from its top left, and how deep it is:
     * {@code MeshShader}'s vertex stage, the world turned into the camera's frame (yaw undone, then pitch) and run
     * through the pinhole. Null behind the eye.
     */
    static double[] project(double[] eye, double x, double y, double z, double w, double h) {
        double rx = x - eye[0];
        double ry = y - eye[1];
        double rz = z - eye[2];
        double cy = Math.cos(YAW);
        double sy = Math.sin(YAW);
        double cp = Math.cos(PITCH);
        double sp = Math.sin(PITCH);
        double ax = rx * cy - rz * sy;
        double az = rx * sy + rz * cy;
        double vy = ry * cp + az * sp;
        double vz = az * cp - ry * sp;
        if (vz <= 1e-6) {
            return null;
        }
        double aspect = w / h;
        double ndcX = FOCAL * ax / (aspect * vz);
        double ndcY = -FOCAL * vy / vz;
        return new double[] {(ndcX + 1) / 2 * w, (ndcY + 1) / 2 * h, vz};
    }

    /** Whether something stands between the eye and the middle of {@code b}'s top. */
    static boolean hidden(double[] eye, Blocks.Block b, List<Blocks.Block> blocks) {
        double px = b.x();
        double py = b.top();
        double pz = b.z();
        double dx = px - eye[0];
        double dy = py - eye[1];
        double dz = pz - eye[2];
        for (Blocks.Block o : blocks) {
            if (o == b) {
                continue;
            }
            double h = o.side() / 2;
            if (enters(eye, dx, dy, dz, o.x() - h, o.y(), o.z() - h, o.x() + h, o.top(), o.z() + h)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the segment from {@code eye} along {@code d} (to {@code t = 1}) passes through the box, short of its end. */
    private static boolean enters(double[] eye, double dx, double dy, double dz,
                                  double x0, double y0, double z0, double x1, double y1, double z1) {
        double near = 0;
        double far = 1 - 1e-6;
        double[] o = eye;
        double[] d = {dx, dy, dz};
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) {
                    return false;
                }
                continue;
            }
            double t0 = (lo[a] - o[a]) / d[a];
            double t1 = (hi[a] - o[a]) / d[a];
            if (t0 > t1) {
                double t = t0;
                t0 = t1;
                t1 = t;
            }
            near = Math.max(near, t0);
            far = Math.min(far, t1);
            if (near > far) {
                return false;
            }
        }
        return true;
    }

    /** A plate's colour: the accent's hue, low in chroma, a step lighter a level up so the levels part. */
    private Color plate(int depth) {
        double lightness = (dark ? 0.30 : 0.82) + (dark ? 0.055 : -0.04) * Math.min(depth, 6);
        return Oklab.polar(lightness, 0.025, hue).toColor();
    }

    /**
     * One icon over the picture, re-pointed per block: the icon the navigator gives the file, so a pom or a Java file
     * wears its own here too. A soft shadow lifts it off the top it lies on.
     */
    private final class Icon {

        final IconView view;

        Icon(Gui gui) {
            view = IconView.of(gui, null).size(Length.dp(16)).shadow(0.06f);
            view.node().hitInert(true).visible(false).floatAt(Length.ZERO, Length.ZERO);
        }

        void show(Blocks.Block b, float xDp, float yDp, float sideDp) {
            IconSource icon = FolderSource.byName(b.name());
            view.icon(icon instanceof LayeredIcon layered ? new OnTop(layered, b.family().hue(dark)) : icon)
                    .size(Length.dp(sideDp));
            view.node().floatAt(Length.dp(xDp - sideDp / 2), Length.dp(yDp - sideDp / 2)).visible(true);
        }

        void hide() {
            view.node().visible(false);
        }
    }

    /**
     * {@code icon} on the top of a block coloured {@code face}, in whichever of its two rows of colours stands out
     * from it. A block is its family's hue in the theme's row, the same colour as that row's sheet, so the theme's own
     * row would leave only the emblem showing; the other row is drawn for the other ground, and is usually the one.
     * Each row's back layer is weighed against the face by lightness, so an icon whose own rows run the other way (a
     * Java file's page is dark in the dark row) keeps the row that stands out.
     */
    private record OnTop(LayeredIcon icon, Color face) implements LayeredIcon {

        @Override
        public ImageField field(float px, Color tint) {
            return icon.field(px, tint);
        }

        @Override
        public List<ImageField> layers(float px, boolean dark) {
            List<ImageField> inDark = icon.layers(px, true);
            List<ImageField> inLight = icon.layers(px, false);
            return apart(inDark) >= apart(inLight) ? inDark : inLight;
        }

        /** How far the back layer of {@code row} is from the face in lightness. */
        private float apart(List<ImageField> row) {
            return row.isEmpty() ? 0f : Math.abs(lightness(row.getFirst().tint()) - lightness(face));
        }

        private static float lightness(Color c) {
            return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
        }
    }

    /**
     * The GPU half: the technique, the host and the target, and the hook that draws. Built on the main thread at
     * the window phase; closed at shutdown, before the device.
     */
    final class Binding implements AutoCloseable {

        private final GuiApp app;
        private final MeshTechnique meshes;
        private final Mesh cube = Mesh.box(1, 1, 1);
        private final Mesh slab = Mesh.box(1, Blocks.PLATE, 1);
        private final List<MeshTechnique.Instance> cubes = new ArrayList<>();
        private final List<MeshTechnique.Instance> plates = new ArrayList<>();
        private SampledColorTarget target;
        private SampledTechniqueHost host;
        private Blocks.Scene drawn;
        private int drawnW;
        private int drawnH;

        private Binding(GuiApp app) {
            this.app = app;
            // Light from over the viewer's shoulder, so the three faces seen read as three tones.
            meshes = new MeshTechnique(new Lens(FOCAL, DEPTH), Shadings.lambert(-0.45, 0.85, -0.35, 0.75, 0.32));
            // One instance of each mesh before the first frame, hidden, so both are in the buffer it uploads.
            plates.add(meshes.add(slab).visible(false));
            cubes.add(meshes.add(cube).visible(false));
        }

        /** Draw, if the scene or the box changed since the last drawing. */
        @BeforeFrame
        void frame() {
            Blocks.Scene s = scene;
            if (s == null) {
                drawn = null;
                return;
            }
            NodeLayout layout = node.layout();
            if (!layout.present()) {
                gui.requestFrame();   // laid out by the coming frame; drawn by the one after
                return;
            }
            int w = Math.round(layout.content().w());
            int h = Math.round(layout.content().h());
            if (w < 8 || h < 8) {
                return;
            }
            boolean fresh = ensureTarget(w, h);
            if (!fresh && s == drawn && w == drawnW && h == drawnH) {
                return;
            }
            double aspect = (double) w / h;
            double[] eye = eye(s, aspect);
            place(s);
            meshes.camera(eye[0], eye[1], eye[2], YAW, PITCH, aspect);
            host.render(background.r(), background.g(), background.b(), 1f);
            label(s, eye, w, h);
            drawn = s;
            drawnW = w;
            drawnH = h;
        }

        /**
         * A target at least as large as the box. A larger one is kept rather than re-made smaller, since the
         * picture is drawn for the box's shape and only sampled down; one re-made at every pixel of a drag would be
         * a new image per frame. The ones replaced are the application's to close, as it closes every one it made.
         */
        private boolean ensureTarget(int w, int h) {
            if (target != null && w <= target.width() && h <= target.height()) {
                return false;
            }
            int tw = target == null ? w : Math.max(w, target.width());
            int th = target == null ? h : Math.max(h, target.height());
            target = app.viewport(tw, th, true);
            if (host == null) {
                host = new SampledTechniqueHost(target, List.of(meshes));
            } else {
                host.retarget(target);
            }
            node.image(target);
            return true;
        }

        /** Every block an instance: the plates and the cubes, as many as are needed, the rest hidden. */
        private void place(Blocks.Scene s) {
            int p = 0;
            int c = 0;
            for (Blocks.Block b : s.blocks()) {
                MeshTechnique.Instance at;
                if (b.folder()) {
                    at = p < plates.size() ? plates.get(p) : add(plates, slab);
                    p++;
                    Color tint = plate(b.depth());
                    at.tint(tint.r(), tint.g(), tint.b());
                } else {
                    at = c < cubes.size() ? cubes.get(c) : add(cubes, cube);
                    c++;
                    Color tint = b.family().hue(dark);
                    at.tint(tint.r(), tint.g(), tint.b());
                }
                at.place(new Placement(b.x(), b.y() + b.height() / 2, b.z(), 0, 0, 0, b.side())).visible(true);
            }
            for (int i = p; i < plates.size(); i++) {
                plates.get(i).visible(false);
            }
            for (int i = c; i < cubes.size(); i++) {
                cubes.get(i).visible(false);
            }
        }

        private MeshTechnique.Instance add(List<MeshTechnique.Instance> pool, Mesh mesh) {
            MeshTechnique.Instance i = meshes.add(mesh);
            pool.add(i);
            return i;
        }

        /** The icons: on the largest files whose tops can be seen, as many as fit the pool. */
        private void label(Blocks.Scene s, double[] eye, int w, int h) {
            float dpi = Math.max(0.0001f, gui.dpi().value());
            List<Blocks.Block> files = new ArrayList<>();
            for (Blocks.Block b : s.blocks()) {
                if (!b.folder()) {
                    files.add(b);
                }
            }
            files.sort(Comparator.comparingDouble(Blocks.Block::side).reversed());
            int shown = 0;
            for (Blocks.Block b : files) {
                if (shown == ICONS) {
                    break;
                }
                double[] at = project(eye, b.x(), b.top(), b.z(), w, h);
                if (at == null) {
                    continue;
                }
                // Pixels per scene unit at that depth, the same factor the pinhole applies.
                double perUnit = FOCAL * h / (2 * at[2]);
                float side = (float) Math.min(ICON_MAX, b.side() * perUnit * 0.6);
                if (side < ICON_MIN) {
                    break;   // sorted by size: every block after this one is smaller still
                }
                if (hidden(eye, b, s.blocks())) {
                    continue;
                }
                icons[shown++].show(b, (float) at[0] / dpi, (float) at[1] / dpi, side / dpi);
            }
            for (int i = shown; i < ICONS; i++) {
                icons[i].hide();
            }
        }

        /** Close the technique. Registered after the application, so it runs before the device goes. */
        @Override
        public void close() {
            if (host != null) {
                host.close();
            }
        }
    }
}
