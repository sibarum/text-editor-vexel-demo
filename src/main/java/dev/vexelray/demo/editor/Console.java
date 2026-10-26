package dev.vexelray.demo.editor;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.api.BeforeFrame;
import dev.vexelray.framework.core.WakeSource;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.widget.ContextMenu;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.GlyphLayout;
import dev.vexelray.text.TextLayout;
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The console: a window of its own, in a neutral grey, where what the Run menu starts is shown. One tab per run
 * ({@link Job}), the newest in front; several can run at once.
 *
 * <p>It is not a terminal to type commands into, and so has no new-tab button: a tab comes from the Run menu, and
 * goes when it is closed (its header's ×, Ctrl+W, or its menu) or when it is old. A run that has ended keeps its tab,
 * with its output, until one of two settings closes it ({@link #KEEP}, {@link #MINUTES}): finished runs beyond the
 * newest few, and finished runs older than some minutes. Neither ever closes the tab in front of the user while the
 * console is open, nor a run that is still going. Both are chosen from the settings button in the title bar.
 *
 * <p>Closing the window ends whatever is still running (after asking) and clears it; the next run opens it again.
 *
 * <h2>The frame</h2>
 *
 * <p>{@link #tick} is the per-frame work: draw what a program wrote, retitle a tab whose run has ended, and close
 * what has grown old. The loop parks while nothing is due, so this is a {@link WakeSource}: a program writing into an
 * idle console wakes it.
 */
final class Console implements WakeSource, AutoCloseable {

    private static final Log LOG = Log.of("editor.run");

    static final NamedWindow DECLARED = NamedWindow.of("console", "VexelRay IDE Console", 980, 560).theme(Look.CONSOLE);

    /** Settings: how many finished runs keep their tabs (0 is all of them), and for how many minutes (0 is forever). */
    static final String KEEP = "console.keep";
    static final String MINUTES = "console.minutes";
    static final int DEFAULT_KEEP = 5;
    static final int DEFAULT_MINUTES = 30;
    static final int[] KEEP_CHOICES = {1, 3, 5, 10, 0};
    static final int[] MINUTE_CHOICES = {5, 30, 120, 0};

    /**
     * The terminal's size before its tab has been laid out: what a program sees until the first fit. Small, so the
     * fit grows it: a terminal that shrinks cuts its lines short, and the first line is the command, which is long.
     */
    private static final int COLS = 80, ROWS = 24;

    private final Window window;
    private final Gui gui;
    private final Settings settings;
    private final Dialogs dialogs;
    private final Tabs tabs;
    private final GlyphLayout glyphs;
    private final int background;
    private final int foreground;
    private final AtomicLong ids = new AtomicLong();
    /** Parallel to the bar's tabs. Guarded by {@code this}; never held while calling into the bar. */
    private final List<Job> jobs = new ArrayList<>();
    /** The jobs whose end the bar has been told of, by id. Touched only by {@link #tick}. */
    private final java.util.Set<Long> told = new java.util.HashSet<>();
    private volatile Runnable wake = () -> { };
    private volatile Runnable onEnded = () -> { };
    private volatile boolean shown;

    Console(Window window, Settings settings, Dialogs dialogs) {
        this.window = window;
        this.gui = window.gui();
        this.settings = settings;
        this.dialogs = dialogs;
        this.background = rgb(gui.theme().color(Look.CARD));
        this.foreground = rgb(gui.theme().color(Role.INK));
        this.glyphs = new TextLayout(AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json")
                .face(Type.MONO)).glyphLayout();
        ContextMenu.presentOn(gui);

        this.tabs = new Tabs(gui).onRemove(this::removed).onSelect(this::selected)
                .onContextMenu((index, menu) -> {
                    Job j = at(index);
                    if (j != null) {
                        menu.item("Stop", j.running(), j::stop)
                                .item("Run again", () -> run(j.run))
                                .item("Copy output", j::copyAll)
                                .separator()
                                .item("Close", () -> close(j))
                                .item("Close finished", this::closeFinished);
                    }
                });
        tabs.node().width(Length.FILL).height(Length.grow(1f));
        gui.landmark(Landmarks.CONSOLE_TABS, tabs.node());
        gui.claimUi(tabs.node(), Shortcut.of(Key.W, Modifier.CONTROL), ClaimScope.VISIBLE, () -> {
            Job j = current();
            if (j != null) {
                close(j);
            }
        });
        gui.claimUi(tabs.node(), Shortcut.of(Key.TAB, Modifier.CONTROL), ClaimScope.VISIBLE, () -> cycle(1));
        gui.claimUi(tabs.node(), Shortcut.of(Key.TAB, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.VISIBLE,
                () -> cycle(-1));

        window.titleBar().addLeading(settingsButton());
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), tabs.node());

        window.onClose(this::askClose);
        window.onOpened(() -> shown = true);
        window.onClosed(() -> shown = false);

        // Old runs close on the clock as well as when a run ends, since nothing else happens in an idle console.
        Thread.ofVirtual().name("console-age").start(() -> {
            try {
                while (true) {
                    TimeUnit.SECONDS.sleep(20);
                    prune();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Start {@code run} in a new tab in front, and bring the console forward. A program that cannot be started at
     * all is said rather than thrown, and opens no tab.
     */
    void run(Runs.Run run) {
        Job current = current();
        int cols = current == null ? COLS : Math.max(current.cols(), 20);
        int rows = current == null ? ROWS : Math.max(current.rows(), 5);
        Job job;
        try {
            job = new Job(ids.incrementAndGet(), run, gui, glyphs, cols, rows, fontPx(), background, foreground,
                    this::wake);
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not start {}", run.line(), e);
            dialogs.info("Could not run " + run.label(), run.line() + ": " + e.getMessage());
            return;
        }
        int index;
        synchronized (this) {
            jobs.add(job);
            index = jobs.size() - 1;
        }
        tabs.add(job.title(), job.node());
        tabs.select(index);
        window.show();
        job.focus();
        prune();
    }

    /** Be told when a run has ended, once per run, so what it wrote to the disk can be looked for. On the main thread. */
    void onEnded(Runnable handler) {
        this.onEnded = handler == null ? () -> { } : handler;
    }

    @Override
    public void onWake(Runnable wake) {
        this.wake = wake;
    }

    private void wake() {
        wake.run();
    }

    /** Once a frame, on the main thread: draw, retitle the runs that have ended, and close what is old. */
    @BeforeFrame
    void tick() {
        List<Job> snapshot = snapshot();
        float px = fontPx();
        boolean ended = false;
        for (Job j : snapshot) {
            j.fontPx(px);
            j.tick();
            if (!j.running() && told.add(j.id)) {
                retitle(j);
                ended = true;
            }
        }
        if (ended) {
            prune();
            onEnded.run();
        }
    }

    @Override
    public void close() {
        List<Job> all;
        synchronized (this) {
            all = List.copyOf(jobs);
            jobs.clear();
        }
        all.forEach(Job::close);
    }

    // -- closing ----------------------------------------------------------------------------------------------

    /** Close the finished runs the settings say are old. Any thread. */
    void prune() {
        int keep = settings.getInt(KEEP, DEFAULT_KEEP);
        int minutes = settings.getInt(MINUTES, DEFAULT_MINUTES);
        Job front = shown ? current() : null;
        long now = System.nanoTime();
        List<Job> finished = snapshot().stream().filter(j -> !j.running()).toList();
        List<Job> old = new ArrayList<>();
        for (int i = 0; i < finished.size(); i++) {
            Job j = finished.get(i);
            boolean beyond = keep > 0 && i < finished.size() - keep;
            boolean aged = minutes > 0 && now - j.endedAt() > TimeUnit.MINUTES.toNanos(minutes);
            if ((beyond || aged) && j != front) {
                old.add(j);
            }
        }
        old.forEach(this::close);
    }

    private void closeFinished() {
        snapshot().stream().filter(j -> !j.running()).forEach(this::close);
    }

    private void close(Job job) {
        int i;
        synchronized (this) {
            i = jobs.indexOf(job);
        }
        if (i >= 0) {
            tabs.remove(i);
        }
    }

    /** Runs inline inside {@link Tabs#remove}, with the bar already short one tab. */
    private void removed(int index) {
        Job gone;
        synchronized (this) {
            gone = index < jobs.size() ? jobs.remove(index) : null;
        }
        if (gone != null) {
            gone.close();
        }
    }

    /** The window's close: ends what is still running, after asking, and clears the window for the next run. */
    private void askClose(Runnable proceed, Runnable cancel) {
        long running = snapshot().stream().filter(Job::running).count();
        Runnable clear = () -> {
            for (int n = tabs.count(); n > 0; n--) {
                tabs.remove(n - 1);
            }
            proceed.run();
        };
        if (running == 0) {
            clear.run();
        } else {
            dialogs.confirm(running == 1 ? "Stop the run?" : "Stop " + running + " runs?",
                    "Closing the console ends what is still running in it.", "Stop", clear, cancel);
        }
    }

    // -- the bar ----------------------------------------------------------------------------------------------

    private void selected(int index) {
        List<Job> snapshot = snapshot();
        for (int i = 0; i < snapshot.size(); i++) {
            snapshot.get(i).active(i == index);
        }
        if (index >= 0 && index < snapshot.size()) {
            snapshot.get(index).focus();
        }
    }

    private void cycle(int by) {
        int n = tabs.count();
        if (n > 1) {
            tabs.select(Math.floorMod(tabs.selected() + by, n));
        }
    }

    private void retitle(Job j) {
        int i;
        synchronized (this) {
            i = jobs.indexOf(j);
        }
        if (i >= 0) {
            tabs.title(i, j.title());
        }
    }

    private synchronized Job at(int index) {
        return index >= 0 && index < jobs.size() ? jobs.get(index) : null;
    }

    private Job current() {
        return at(tabs.selected());
    }

    private synchronized List<Job> snapshot() {
        return List.copyOf(jobs);
    }

    /** The output's type size in the picture's pixels: the editor's code size, at this window's zoom and density. */
    private float fontPx() {
        return Type.CODE_REM * gui.rootEmPx() * gui.zoom().value() * gui.dpi().value();
    }

    // -- settings ---------------------------------------------------------------------------------------------

    /** The title bar's settings button: a menu of when finished runs close. */
    private Node settingsButton() {
        Node b = gui.row().role("settings").width(Length.dp(36)).height(Length.dp(32))
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.NONE)).scroll(false, false)
                .windowRegion(WindowRegion.INTERACTIVE)
                .children(IconView.of(gui, Icon.SETTINGS).size(Length.dp(16)).tint(Role.INK).node());
        gui.onClick(b, (ClickEvent e) -> gui.menus().present(e, settingsMenu()));
        gui.onState(b, state -> b.background(
                gui.theme().color(state == InteractionState.NORMAL ? Role.NONE : Role.RAISED)));
        new Tooltip(gui).attach(b, "When finished runs close");
        gui.landmark(Landmarks.CONSOLE_SETTINGS, b);
        return b;
    }

    List<MenuItem> settingsMenu() {
        int keep = settings.getInt(KEEP, DEFAULT_KEEP);
        int minutes = settings.getInt(MINUTES, DEFAULT_MINUTES);
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.disabled("Keep finished runs"));
        for (int k : KEEP_CHOICES) {
            items.add(choice(k == keep, k == 0 ? "All of them" : "The last " + k, () -> choose(KEEP, k)));
        }
        items.add(MenuItem.SEPARATOR);
        items.add(MenuItem.disabled("Close a finished run after"));
        for (int m : MINUTE_CHOICES) {
            items.add(choice(m == minutes, m == 0 ? "Never" : m < 60 ? m + " minutes" : m / 60 + " hours",
                    () -> choose(MINUTES, m)));
        }
        return items;
    }

    private static MenuItem choice(boolean chosen, String label, Runnable action) {
        return new MenuItem(null, label, action, true, chosen ? Icon.CHECK : null);
    }

    private void choose(String key, int value) {
        settings.putInt(key, value);
        settings.save();
        prune();
    }

    private static int rgb(Color c) {
        return (Math.round(c.r() * 255) << 16) | (Math.round(c.g() * 255) << 8) | Math.round(c.b() * 255);
    }
}
