package dev.vexelray.demo.editor.terminal;

import dev.vexelray.demo.editor.Palettes;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import dev.vexelray.os.NativeWindow;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * MainFrame as its own OS window on the shared frame loop: a third {@link Gui} holding a tailing scrollback over
 * a prompt over a message line, opened as the named window {@code "terminal"} — so the shortcut means <em>the</em>
 * terminal, whether that has to create one or raise the one already there.
 *
 * <p>This is a <b>shell console, not a terminal emulator</b>: lines in, lines out. There is no character grid, no
 * pseudo-terminal and no escape-sequence state machine here, and MainFrame needs none — it is a Java library that
 * prints, so the window feeds it a line and renders what it prints.
 *
 * <h2>Why it looks like a 5250</h2>
 * MainFrame is a shell whose pipes carry typed records rather than text, which is the one idea it shares with the
 * machine this screen is borrowed from. So the window wears the part: {@link Palettes#PHOSPHOR} for a green tube,
 * {@code Command ===>} over a boxed entry area, and a message line that turns over into reverse video when
 * something failed.
 *
 * <p><b>What is left is what earns its row.</b> A 5250 spent its top three lines on a screen identifier, a
 * centred title and a "Type command, press Enter." that stopped being news the second time anyone read it, and
 * its bottom line on a function-key legend. Those four rows are scrollback now; the working directory and the
 * clock, the only things up there that ever changed, share one. The shadow mask over the glass went the same
 * way, and for the same reason: this is a window you read through, and the costume was charging rent.
 *
 * <p>The chrome is a 5250; the <em>content</em> is not. MainFrame's output keeps its own case and its own
 * spacing, because a shell that upper-cases your paths is a shell that lies about them.
 *
 * <h2>Typing without aiming</h2>
 * The caret lives in the command field and returns there on any click anywhere in the window, so the field never
 * has to be hit to be typed into — see {@link #focusFollowsWindow}.
 *
 * <p><b>The session outlives the window.</b> The tree belongs to this object, not to the OS window, so closing
 * the terminal releases a window and leaves MainFrame running: reopen it and the scrollback, the history and the
 * working directory are where you left them. Only application shutdown stops the shell.
 *
 * <p>All methods run on the main thread except {@link MainFrameShell#submit} (a handler thread) and the job
 * thread behind it.
 */
public final class TerminalWindow implements AutoCloseable {

    /** The title bar's own height, in dp — {@code TitleBar}'s, which is the Windows caption metric. */
    private static final int BAR_H = 32;

    /**
     * The default size, used the first time — after that, whatever the user left it at. The height carries the
     * title bar this window draws itself, plus the three rows around the scrollback: the header, the command
     * line and the message line.
     */
    private static final int DEFAULT_W = 760;
    private static final int DEFAULT_H = 520 + BAR_H;

    /** This window's margin, and so its resize grip — the same bargain the editor's gutter makes. */
    private static final Length GUTTER = Length.dp(12);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM/dd/yy  HH:mm:ss");

    /** What the clock reads before a window has ever been opened — see {@link #stamp()}. */
    private static final String UNSET = "--/--/--  --:--:--";

    private final Consumer<Path> openFile;
    private final Consumer<Path> openDir;
    private final WindowMemory memory;
    private final ProfileStore profiles;
    /** Read on demand: the project is whatever folder the file tree is showing, and that changes. */
    private final Supplier<dev.vexelray.demo.editor.ProjectSettings> project;
    private final Gui gui = new Gui();
    private final Ansi ansi;
    private final Node output;
    private final Node location;
    private final Node badge;
    private final Node clock;
    private final Node message;
    private final TextField prompt;
    private final Scrollback scrollback;
    private final TitleBar titleBar;
    private final Subscription clicks;
    private final List<String> history = new ArrayList<>();

    private MainFrameShell shell;
    /** The framework.s handle on this window, claimed the first time it is shown. */
    private AppWindow handle;
    private int recall;
    private String shownLocation = "";
    /** Characters the location field can show on one line, learned from the layout -- see {@link #fitted}. */
    private int locationRoom = Integer.MAX_VALUE;
    /** The width the budget was learned at, so a resize measures again instead of keeping an old answer. */
    private float locationWidth = -1f;
    private String shownBadge = "";
    private String shownClock = "";
    private String shownMessage = "";
    private boolean shownError;

    public TerminalWindow(Consumer<Path> openFile, Consumer<Path> openDir, WindowMemory memory,
                          ProfileStore profiles,
                          Supplier<dev.vexelray.demo.editor.ProjectSettings> project) {
        this.openFile = openFile;
        this.openDir = openDir;
        this.memory = memory;
        this.profiles = profiles;
        this.project = project;

        // First, and before a single node exists: a role resolves at the moment a widget writes a prop, so a
        // theme installed after the tree is built reaches nothing that is already painted.
        gui.theme(Palettes.PHOSPHOR);
        Theme theme = gui.theme();
        this.ansi = Ansi.of(theme);

        // ---- the display -------------------------------------------------------------
        // One line, because one line is all there was worth keeping: where you are, and when it is. The screen
        // identifier, the centred title and the instruction line underneath them were furniture that told you
        // nothing the second time you read them, and the three rows they cost are scrollback now.
        this.location = glyphs("", theme.color(Palettes.HOT))
                .width(Length.grow(1))
                .scroll(false, false);
        // What a 5250 kept up here was the library list -- which toolchain the next command would find. This is
        // the same fact under a newer name, and the same reason for it being on screen rather than asked for.
        this.badge = glyphs("", theme.color(Role.INK))
                .width(Length.AUTO)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);
        this.clock = glyphs(UNSET, theme.color(Role.DIM))
                .width(Length.AUTO)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);
        Node header = gui.row().width(Length.FILL).height(Length.rem(1.4f)).gap(Length.em(1.5f))
                .children(location, badge, clock);

        Node rule = gui.box().width(Length.FILL).height(Length.dp(1)).background(theme.color(Role.DIM));
        // A tailing log is clipped at its top edge, so the oldest visible line is usually cut through the
        // middle. That is what a scrolling display does; it only looks like a fault when the cut lands against
        // the rule. This is the clearance that keeps the two apart.
        Node clearance = gui.box().width(Length.FILL).height(Length.rem(0.3f));

        this.output = gui.column().width(Length.FILL).height(Length.grow(1))
                .scrollLock(LayoutEnums.ScrollLock.BOTTOM);
        this.scrollback = new Scrollback(gui, output, ansi);

        // ---- the entry field ----------------------------------------------------------
        Node command = glyphs("Command", theme.color(Role.INK)).width(Length.AUTO);
        Node arrow = glyphs("===>", theme.color(Palettes.HOT)).width(Length.AUTO);
        this.prompt = new TextField(gui, "");
        // Square, with the tube showing through it. The widget paints itself a rounded sunken well, which is
        // right on a page and wrong on glass — but its border it re-paints on every focus change, so that one is
        // not ours to take away. Left alone it becomes the thing a 5250 entry field was always drawn as: a box
        // around the input area, bright while the field holds the caret. Which, in this window, is always.
        prompt.node().width(Length.grow(1)).height(Length.FILL)
                .background(theme.color(Role.NONE))
                .corner(Length.ZERO)
                .font(1).textSize(Length.rem(0.8125f)).textColor(theme.color(Palettes.HOT));
        Node commandRow = gui.row().width(Length.FILL).height(Length.rem(1.9f)).gap(Length.em(0.6f))
                .children(command, arrow, prompt.node());

        // ---- the message line ------------------------------------------------------------
        this.message = glyphs("", theme.color(Role.DIM))
                .width(Length.FILL).height(Length.rem(1.5f))
                .padding(Length.ZERO, Length.em(0.4f));
        // ---- the tube ------------------------------------------------------------------
        // Rounded because the glass is, lit because it is glass, and elevated because this palette's depth anchor
        // is the phosphor itself — so what would be a drop shadow under any other theme is the halo the screen
        // throws onto the bezel around it. One anchor; no special case anywhere in the renderer.
        Node tube = gui.column().width(Length.FILL).height(Length.grow(1))
                .background(theme.color(Role.PAGE))
                .corner(Length.rem(1.1f))
                .padding(Length.dp(18))
                .gap(Length.rem(0.35f))
                .lit(theme.lit())
                .elevation(Length.rem(1.25f))
                .children(header, rule, clearance, output, commandRow, message);

        Node frame = gui.column().width(Length.FILL).height(Length.grow(1))
                .padding(GUTTER)
                .children(tube);
        // This window draws its own frame too, so all three match. The bar is bound in onCreated: a popup's
        // window does not exist until the main thread services the request, and it is that window the buttons
        // command — not the main one.
        this.titleBar = new TitleBar(gui, WindowControls.NONE, "Terminal");
        gui.root().background(theme.color(Palettes.BEZEL)).children(titleBar.node(), frame);
        // One row of chrome more than the plain console had, so barely more than it asked for.
        gui.minSize(Length.em(26), Length.em(15));
        // The margin is the grip: dead space around the tube resizes the window, the bar above it still drags it.
        gui.resizeBorder(GUTTER);
        gui.zoomRange(0.5f, 3f, 1.25f);
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);

        prompt.onSubmit(this::onLine);
        this.clicks = focusFollowsWindow();
        gui.onContextMenu(frame, this::settingsMenu);
        claims();
    }

    /** This window's Gui, so the app can bind its shortcuts and its clipboard here too. */
    public Gui gui() {
        return gui;
    }

    /**
     * Open the window on the next frame, starting MainFrame in {@code cwd}.
     *
     * <p>Only the first call opens a window. Asking again raises the one that exists and puts the caret back in
     * the prompt — "open the terminal" has to mean the terminal, not a second terminal, and a window that is
     * already open but behind something else has to come forward or the shortcut looks broken.
     *
     * <p>Its position and size come from {@link WindowMemory}, clamped to a monitor that exists, and go back
     * there as the user moves it.
     */
    public void show(GuiApp app, Path cwd) {
        start(cwd);
        // One call for both cases: show() creates the window if it is closed and raises it if it is not.
        if (handle == null) {
            handle = app.window("terminal", () -> WindowSpec
                    .of(memory.config("terminal", "Terminal", DEFAULT_W, DEFAULT_H)
                            .decorations(Decorations.CLIENT), gui)
                    .onCreated(this::onCreated)
                    .onClosed(this::onClosed));
        }
        handle.show();
        gui.focus(prompt.node());
    }

    /**
     * Start MainFrame in {@code cwd} and greet, without opening a window. Separate from {@link #show} so the
     * headless capture path can render this tree — the window's look is reviewable without a GPU or a keyboard.
     */
    public void start(Path cwd) {
        if (shell != null) {
            return;
        }
        shell = new MainFrameShell(scrollback, cwd, openFile, openDir, this::onShellExit, profiles, project);
        greet(cwd);
        applyDefaultProfile();
    }

    /** Run one line as if it had been typed. The entry point for the capture path and for scripted checks. */
    public void submit(String line) {
        onLine(line);
    }

    /**
     * Render this display to a PNG at its default size — no window, no input backend, no GPU surface beyond the
     * one the capture opens for itself.
     *
     * <p>The clear colour is the bezel, read off this window's own theme rather than repeated as three floats.
     */
    public void capture(String path) throws java.io.IOException {
        tick();
        dev.vexelray.canvas.Color bezel = gui.theme().color(Palettes.BEZEL);
        GuiApp.capture(gui, DEFAULT_W, DEFAULT_H, bezel.r(), bezel.g(), bezel.b(), path);
    }

    /** True while a command is running — the capture path waits on this. */
    public boolean busy() {
        return shell != null && shell.busy();
    }

    /** Whether the window is up right now — polled each frame so it can be reopened next launch. */
    public boolean isOpen() {
        return handle != null && handle.open();
    }

    // ---- the prompt ------------------------------------------------------------------

    /**
     * Handler thread: echo the line, remember it, hand it to the job thread.
     *
     * <p>And send the display back to the tail. Scrolling up through history detaches the scroll lock and hands
     * the reader full control of the view, which is right — output arriving underneath them must not yank the
     * page. But pressing Enter says they are finished reading history: a shell that runs a command and leaves you
     * looking at some older screen has hidden its own answer. So the tail is re-attached here rather than waiting
     * for them to scroll back down, and the jump lands in the same frame as the echo.
     */
    private void onLine(String line) {
        if (shell == null) {
            return;
        }
        prompt.text("");
        output.scrollToEdge();
        // A line is either a command or an answer to a question the shell is holding open -- a form's field, a
        // yes/no. The shell knows which, because it knows whether it is blocked reading; the window only has to
        // ask. An answer is echoed like a command, since that is what the scrollback of a filled-in form is.
        if (shell.asking()) {
            scrollback.post("> " + line, List.of(Span.foreground(0, 1, ansi.hot())));
            shell.answer(line);
            return;
        }
        String label = promptText();
        scrollback.post(label + line, List.of(Span.foreground(0, label.length(), ansi.hot())));
        if (line.isBlank()) {
            return;
        }
        // Written here on a handler thread, read by the Up/Down claims on the GUI thread.
        synchronized (history) {
            history.remove(line);
            history.add(line);
            recall = history.size();
        }
        shell.submit(line);
    }

    /**
     * Any click anywhere in this window puts the caret back in the command field.
     *
     * <p>The click <em>topic</em> rather than a handler on the root, and that is the point: a handler bubbles to
     * the nearest ancestor that has one, so a click on the scrollback would reach the root but a click on the
     * title bar's maximize button would not — and the one that leaves you unable to type afterwards is the
     * second. The topic publishes every click whatever consumed it. Re-focusing a field that already has focus
     * is a no-op in the dispatcher, so the common case costs a comparison.
     *
     * <p>This is what makes the whole tube the input: there is nothing to aim at, because everything is the
     * same target.
     */
    private Subscription focusFollowsWindow() {
        return gui.bus().subscribe(gui.clicks(), event -> gui.focus(prompt.node()));
    }

    /**
     * The prompt's keys, claimed on the field.
     *
     * <p>A claim is preemption declared in advance: while the field has focus these run and nothing else sees the
     * key, which is what lets Up mean "previous command" here and "previous line" in the editor with no
     * subclassing and no {@code preventDefault}. They use the <em>ordered</em> claim, because each one edits the
     * state typing also edits.
     */
    private void claims() {
        Node node = prompt.node();
        gui.claimUi(node, Shortcut.of(Key.UP), ClaimScope.FOCUSED, () -> recall(-1));
        gui.claimUi(node, Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, () -> recall(+1));
        gui.claimUi(node, Shortcut.of(Key.L, Modifier.CONTROL), ClaimScope.FOCUSED, scrollback::clear);
        // Ctrl+C is the one real conflict: the field handles it as copy in its own key stage, and a FOCUSED claim
        // preempts that. So the claim decides — job running, interrupt it; otherwise copy the selection, which
        // this window can do itself because the document is public and the clipboard is writable.
        gui.claimUi(node, Shortcut.of(Key.C, Modifier.CONTROL), ClaimScope.FOCUSED, this::interruptOrCopy);
        // Ctrl+D on an empty line closes the window, as it does in a shell. On a line with something on it, it
        // does nothing rather than deleting forward: this prompt is one line, so there is no "delete the rest".
        gui.claimUi(node, Shortcut.of(Key.D, Modifier.CONTROL), ClaimScope.FOCUSED, () -> {
            if (prompt.text().isEmpty()) {
                onShellExit();
            }
        });
    }

    private void recall(int direction) {
        String line;
        synchronized (history) {
            if (history.isEmpty()) {
                return;
            }
            recall = Math.max(0, Math.min(history.size(), recall + direction));
            line = recall < history.size() ? history.get(recall) : "";
        }
        prompt.text(line);
        prompt.caret(line.length());
    }

    private void greet(Path cwd) {
        scrollback.post("MainFrame in " + cwd, List.of(Span.foreground(0, 9, ansi.hot())));
        scrollback.post("type help to see every command, or try: ls | where kind == \"file\" | sort-by size",
                List.of());
        scrollback.post("", List.of());
    }

    private void interruptOrCopy() {
        if (shell != null && shell.busy()) {
            if (shell.interrupt()) {
                scrollback.post("interrupt sent -- it reaches a program being waited on, but MainFrame's own "
                        + "loops run to the end", List.of());
            }
            return;
        }
        Document document = prompt.document().value();
        if (document.hasSelection()) {
            gui.clipboard().set(document.selectedText());
        }
    }

    /**
     * {@code exit} asked to leave, or Ctrl+D did. It travels the ordinary close route, so the frame loop tears the
     * window down on its own terms and {@link #onClosed} still runs — and MainFrame keeps running behind it, so
     * this is "put the display away", not "throw the session away". Safe from the job thread: it only enqueues.
     */
    private void onShellExit() {
        AppWindow w = handle;
        if (w != null) {
            w.close();
        }
    }

    // ---- per frame -------------------------------------------------------------------

    /**
     * Main thread, once per frame: publish the output that arrived since the last frame and refresh the three
     * fields that track the session. Every one of them is written only when its text actually changed — a
     * display that rewrites a prop per frame dirties the layout per frame.
     */
    public void tick() {
        if (shell == null) {
            return;
        }
        scrollback.flush();
        set(location, fitted(where()), () -> shownLocation, s -> shownLocation = s);
        set(badge, badgeText(), () -> shownBadge, s -> shownBadge = s);
        set(clock, stamp(), () -> shownClock, s -> shownClock = s);
        messageLine();
    }

    /**
     * The message line, and the one place a hue would have earned its keep. A monochrome tube cannot draw a red
     * error, so this does what the machine it is imitating did: turns the line over — the fill becomes the
     * phosphor and the text becomes unlit glass — which is louder than any colour and needs none.
     *
     * <p>Nothing here picks the two colours. {@code Role.DANGER} is the fill and {@code Role.ON_DANGER} is
     * whichever of the palette's extremes lies further from it, which in a monochrome palette resolves to the
     * page. Reverse video falls out of the role rather than being spelled.
     */
    private void messageLine() {
        String error = shell.lastError();
        boolean failed = !error.isEmpty();
        String text = failed ? error.toUpperCase(Locale.ROOT) : statusText();
        if (text.equals(shownMessage) && failed == shownError) {
            return;
        }
        shownMessage = text;
        shownError = failed;
        Theme theme = gui.theme();
        message.text(text)
                .background(theme.color(failed ? Role.DANGER : Role.NONE))
                .textColor(theme.color(failed ? Role.ON_DANGER : Role.DIM));
    }

    /** Write {@code text} onto {@code node} only if it is not already what the node says. */
    private static void set(Node node, String text, Supplier<String> shown, Consumer<String> remember) {
        if (!text.equals(shown.get())) {
            remember.accept(text);
            node.text(text);
        }
    }

    /**
     * The date and time, or {@link #UNSET} while no window has been opened.
     *
     * <p>Not a flourish: the headless capture renders this tree without ever creating a window, and a capture
     * that differs every run is a capture you cannot diff against the last one to see what a change did. Gating
     * the clock on a window makes the PNG a function of the screen alone — and it is the truth besides, since a
     * display with no session on it has no session time to show.
     */
    private String stamp() {
        return handle == null ? UNSET : STAMP.format(LocalDateTime.now());
    }

    /** What an echoed command line is prefixed with — a shell prompt, because the echo is shell output. */
    /**
     * Run {@code line} as if it had been typed, and show that it was.
     *
     * <p>What the settings menu does. It matters that this echoes: a menu that changes the environment silently
     * leaves you guessing at what it did, whereas a menu that puts {@code profile-use jdk-21} in the scrollback
     * has taught you the command. It stays out of the history, though -- Up is for things you typed.
     */
    private void runVisibly(String line) {
        if (shell == null) {
            return;
        }
        output.scrollToEdge();
        String label = promptText();
        scrollback.post(label + line, List.of(Span.foreground(0, label.length(), ansi.hot())));
        shell.submit(line);
    }

    /**
     * The settings menu: profiles, and what can be done with them right now.
     *
     * <p>Built at the moment of the click, which is the point of a sink -- it lists the profiles that exist and
     * greys what does not apply rather than hiding it, so the menu teaches the same shape whatever the state. And
     * every line runs a command through {@link #runVisibly}, so nothing here is a second implementation of
     * anything: the menu is a way of finding {@code profile-new}, not an alternative to it.
     */
    private void settingsMenu(dev.vexelray.gui.core.input.MenuSink menu) {
        dev.vexelray.demo.editor.ProjectSettings here = project.get();
        String active = activeProfile();
        List<String> names = profiles.names();

        menu.item("New profile...", () -> runVisibly("profile-new"));
        menu.item("Edit " + (active.isEmpty() ? "profile" : active) + "...", !active.isEmpty(),
                () -> runVisibly("profile-edit " + quoted(active)));

        menu.separator();
        if (names.isEmpty()) {
            menu.item("No profiles yet", false, null);
        }
        // In use and preferred are different states, and the line has to say which -- a profile that is only the
        // default has not touched the PATH yet. Both the marker and the greying read the same fact, so they
        // cannot disagree: the only line that is greyed is the one there is nothing left to do to.
        String live = shell == null ? "" : shell.appliedProfile();
        String preferred = effectiveDefault();
        for (String name : names) {
            String mark = name.equals(live) ? "  (in use)" : name.equals(preferred) ? "  (default)" : "";
            menu.item("Use " + name + mark, !name.equals(live),
                    () -> runVisibly("profile-use " + quoted(name)));
        }

        menu.separator();
        menu.item(here.present()
                        ? "Default for " + here.name() + ": " + (active.isEmpty() ? "-" : active)
                        : "Default for this project (none open)",
                here.present() && !active.isEmpty(),
                () -> runVisibly("profile-default " + quoted(active) + " --project"));
        menu.item("My default: " + (profiles.defaultName().isEmpty() ? "-" : profiles.defaultName()),
                !active.isEmpty(),
                () -> runVisibly("profile-default " + quoted(active)));

        menu.separator();
        menu.item("List profiles", () -> runVisibly("profile"));
        menu.item("Show the environment", () -> runVisibly("env"));
    }

    /**
     * Which profile this session is on: what was applied if anything has been, otherwise what would be.
     *
     * <p>The two are different questions and the menu needs both, but the one worth showing is this: the profile
     * whose variables the next command will see, or would see once it were applied.
     */
    private String activeProfile() {
        String live = shell == null ? "" : shell.appliedProfile();
        return live.isEmpty() ? effectiveDefault() : live;
    }

    /** The default that applies here: the project's if it names one this machine has, otherwise the user's. */
    private String effectiveDefault() {
        String here = project.get().profile();
        if (profiles.has(here)) {
            return here;
        }
        String mine = profiles.defaultName();
        return profiles.has(mine) ? mine : "";
    }

    /**
     * Apply the default profile as the session opens, by running the command for it.
     *
     * <p>Through {@link #runVisibly} rather than quietly, because a shell whose PATH is not the PATH you would
     * have guessed has to say so somewhere, and the honest place is the first lines of the scrollback. A project
     * naming a profile this machine does not have is said out loud too, and then ignored.
     */
    private void applyDefaultProfile() {
        String here = project.get().profile();
        if (!here.isEmpty() && !profiles.has(here)) {
            scrollback.post("this project asks for the profile " + here
                    + ", which this machine does not have -- carrying on without it", List.of());
        }
        String name = effectiveDefault();
        if (!name.isEmpty()) {
            runVisibly("profile-use " + quoted(name));
        }
    }

    /**
     * A profile name as a MainFrame argument.
     *
     * <p>Quoted, always. MainFrame's language reads {@code -} as an operator, so a bare {@code jdk-21} parses as
     * {@code jdk} minus {@code 21} and the command is handed two arguments instead of one. Hyphens are exactly
     * what people call toolchains, so the name goes in quotes rather than the hyphen being taken away from them.
     */
    private static String quoted(String name) {
        return dev.mainframe.value.Values.quoted(name);
    }

    /**
     * {@code path} shortened to what the header can actually show, keeping the end of it.
     *
     * <p>The header is one row of a fixed height, and a text node that needs two lines does not get clipped to
     * its box -- it draws the second line straight through the rule underneath. So the field is measured rather
     * than guessed at: the layout says where the first visual line ended, which is exactly how many characters
     * fit, and everything before that is dropped behind an ellipsis. The <em>end</em> of a working directory is
     * the part worth keeping, and the whole of it is on every echoed prompt line anyway.
     *
     * <p>The budget is thrown away whenever the field's width changes, so widening the window measures again
     * instead of holding on to an answer from when it was narrow. One frame late, like everything read from the
     * layout, and self-correcting because the next frame measures what this one decided.
     */
    private String fitted(String path) {
        var layout = location.layout();
        float width = layout.rect().w();
        if (width != locationWidth) {
            locationWidth = width;
            locationRoom = Integer.MAX_VALUE;
        }
        var metrics = layout.text();
        if (metrics != null && metrics.lines().size() > 1) {
            locationRoom = Math.max(12, metrics.lines().get(0).end() - 1);
        }
        if (path.length() <= locationRoom) {
            return path;
        }
        return "..." + path.substring(path.length() - Math.max(9, locationRoom - 3));
    }

    private String promptText() {
        return where() + " > ";
    }

    /** The working directory as the field above the screen shows it: no arrow, because a field is not a prompt. */
    private String where() {
        if (shell == null) {
            return "";
        }
        Path cwd = shell.cwd();
        Path home = Path.of(System.getProperty("user.home"));
        return cwd.equals(home) ? "~"
                : cwd.startsWith(home) ? "~/" + home.relativize(cwd).toString().replace('\\', '/')
                : cwd.toString();
    }

    /**
     * The profile the next command will run under, shown up in the header where a 5250 kept its library list.
     *
     * <p>A profile that is set but not yet applied is marked, because those are different states and the
     * difference is the one that catches people out: the PATH is not yet what the header would let you assume.
     */
    private String badgeText() {
        String live = shell == null ? "" : shell.appliedProfile();
        if (!live.isEmpty()) {
            return live;
        }
        String pending = effectiveDefault();
        return pending.isEmpty() ? "" : pending + " (not applied)";
    }

    private String statusText() {
        if (shell.asking()) {
            return "Answering.  !back a field   !clear empties it   !cancel abandons the form";
        }
        if (shell.busy()) {
            return "Running.  Ctrl+C interrupt   Ctrl+L clear   Up/Down history";
        }
        return "Ready.  right-click for profiles   help lists every command   Ctrl+D closes this display";
    }

    // ---- lifecycle -------------------------------------------------------------------

    /**
     * The window exists, and its input is already attached and pumping — the framework did that from the factory
     * the app supplied. What is left is what only this window knows: which window its own title bar commands,
     * where it should be, and that the caret belongs in the prompt.
     */
    private void onCreated(NativeWindow created) {
        titleBar.controls(WindowControls.of(created));
        if (memory.maximized("terminal")) {
            created.maximize();
        } else {
            memory.restoreBounds("terminal", created, DEFAULT_W, DEFAULT_H);
        }
        memory.watch("terminal", created);
        gui.focus(prompt.node());
    }

    /**
     * The window is gone, and <b>MainFrame is not</b>: the shell keeps running and the scrollback stays, so
     * reopening comes back to the same session — same working directory, same history, same output above the
     * prompt — rather than a fresh greeting.
     *
     * <p>That is the whole reason this window is a named window rather than a popup. The tree was never the
     * window's; closing releases an OS window and its input backend, and nothing else.
     */
    private void onClosed() {
        // Stop reading placement off a window that is being destroyed; what was recorded last stands.
        memory.forget("terminal");
        // The window this bar commanded is gone; the tree outlives it and is shown again the next time the
        // terminal is asked for, so the buttons go back to commanding nothing until onCreated rebinds them.
        titleBar.controls(WindowControls.NONE);
    }

    /** Application shutdown: this is where MainFrame's job thread actually stops. */
    @Override
    public void close() {
        clicks.close();
        if (shell != null) {
            shell.close();
            shell = null;
        }
        prompt.close();
    }

    /** One line of screen text in the tube's own face and size. Every label on this display goes through here. */
    private Node glyphs(String text, dev.vexelray.canvas.Color ink) {
        return gui.text(text)
                .height(Length.FILL)
                .font(1)
                .textSize(Length.rem(0.8125f))
                .textColor(ink)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }
}
