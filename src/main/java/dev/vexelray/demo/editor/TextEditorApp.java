package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

/**
 * A deceptively simple text editor on vexelray-gui: a title bar, a multiline {@link TextField}
 * (word wrap, line numbers, caret-follow scroll, selection, cut/copy/paste), and a status line.
 *
 * <p>Run: {@code TextEditorApp} (windowed), {@code TextEditorApp --capture [out.png]} (headless).
 * Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class TextEditorApp {

    /** Window and capture size, in the engine's logical coordinates. */
    private static final int W = 800;
    private static final int H = 560;

    private static final Color BG = Color.rgb(0x11141b);
    private static final Color PANEL = Color.rgb(0x1b2130);
    private static final Color LINE = Color.rgb(0x2b3346);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final Color DIM = Color.rgb(0x93a0b4);

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        Gui gui = new Gui();
        gui.minSize(Length.em(30), Length.em(20));
        buildUi(gui);
        zoomShortcuts(gui);

        if (args.length >= 1 && args[0].equals("--capture")) {
            GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, args.length >= 2 ? args[1] : "text-editor.png");
            System.out.println("captured");
            return;
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        try (Tactroller input = openInput();
             GuiApp app = new GuiApp("Text Editor", W, H);
             Clipboard clipboard = openClipboard(gui)) {
            attachInput(input, app);
            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
            app.run(gui, maxFrames, () -> pump(bridge));
        }
        gui.close();
        System.out.println("clean shutdown");
    }

    private static void zoomShortcuts(Gui gui) {
        gui.zoomRange(0.5f, 3f, 1.25f);
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
    }

    private static Tactroller openInput() {
        try {
            Tactroller t = Tactroller.open();
            System.out.println("input: " + t.backendName());
            return t;
        } catch (BackendException e) {
            System.out.println("input unavailable (" + e.getMessage() + "); running without pointer input");
            return null;
        }
    }

    /** CLIENT space, density left at 1.0 — the engine's canvas is logical; see vexelray-gui-demo's attachInput. */
    private static void attachInput(Tactroller input, GuiApp app) {
        if (input == null) {
            return;
        }
        try {
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.out.println("input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /** OS clipboard for cut/copy/paste; falls back to the in-memory default when no backend is present. */
    private static Clipboard openClipboard(Gui gui) {
        try {
            Clipboard clip = Clipboard.open();
            gui.clipboard(new TextClipboard() {
                @Override
                public String get() {
                    try {
                        return clip.getText().orElse("");
                    } catch (ClipboardException e) {
                        return "";
                    }
                }

                @Override
                public void set(String text) {
                    try {
                        clip.setText(text);
                    } catch (ClipboardException e) {
                        // best effort — a transient clipboard failure just drops the copy
                    }
                }
            });
            return clip;
        } catch (ClipboardException e) {
            System.out.println("clipboard unavailable (" + e.getMessage() + "); cut/copy/paste use in-memory buffer");
            return null;
        }
    }

    private static void pump(TactrollerInputBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.pump();
        } catch (BackendException e) {
            // Transient poll failure — drop this frame's input rather than tear down the loop.
        }
    }

    private static void buildUi(Gui gui) {
        Node title = gui.text("untitled.txt")
                .width(Length.FILL).height(Length.rem(2.75f))
                .background(PANEL).lit(true).elevation(Length.rem(0.375f))
                .padding(Length.dp(12), Length.dp(16))
                .textSize(Length.rem(1.125f)).textColor(INK)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);

        TextField editor = new TextField(gui,
                "Welcome to the deceptively simple text editor.\n\n"
                        + "Word wrap, line numbers, selection, cut/copy/paste, and caret-follow scrolling "
                        + "all come from the multiline TextField widget. Start typing.")
                .multiline(true).wordWrap(true).lineNumbers(true);
        editor.node().width(Length.FILL).height(Length.FILL);

        Node editorCard = gui.column().width(Length.FILL).height(Length.FILL)
                .background(PANEL).corner(Length.rem(0.75f)).border(Length.rem(0.1f), LINE)
                .lit(true).elevation(Length.rem(1))
                .padding(Length.dp(12))
                .children(editor.node());

        Node status = gui.text("Ctrl+= / Ctrl+- zoom - Ctrl+0 reset")
                .width(Length.FILL).height(Length.rem(1.75f))
                .textSize(Length.rem(0.875f)).textColor(DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);

        Node root = gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.dp(16)).gap(Length.rem(0.625f))
                .children(title, editorCard, status);
        gui.root().background(BG).children(root);
    }

    private TextEditorApp() {
    }
}
