package dev.vexelray.demo.editor;

import dev.sibarum.suite.tty.TerminalScreen;
import dev.vexelray.canvas.Color;
import dev.vexelray.demo.editor.core.Messages.Ended;
import dev.vexelray.demo.editor.core.Messages.Offer;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.MenuAction;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.text.FontSet;
import dev.vexelray.text.GlyphLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The console: a window of its own with one tab per run, the newest in front. The {@link ConsoleView}'s, on the GUI
 * thread, as every widget is.
 *
 * <p>Each tab is a {@link TerminalScreen}, the terminal MainFrame draws, fed what its run writes. Its first line is
 * what ran and the folder it ran in, and its last says how it ended: <i>Finished</i>, <i>Failed</i> with the exit
 * code, or <i>Stopped</i>. When a run ends its program and pseudo-terminal are gone, but the tab keeps the output, to
 * scroll, select and copy. Several can run at once.
 *
 * <p>A tab's menu has Stop, Run again, Copy output and Close. The console has no new-tab button: tabs come from runs.
 * Finished runs past the last {@link #KEEP} close by themselves, oldest first, never the tab in front.
 *
 * <p>It holds what only the picture needs: which tab is which run, and whether each is still going. What runs, and
 * what it wrote, is the {@code Runner}'s and arrives as messages.
 */
final class Console {

    /** The window's key: {@code WindowCommand.show(KEY)} brings it up, and a script reaches it as {@code window console}. */
    static final String KEY = "console";

    /** How many finished runs are kept before the oldest closes by itself. */
    static final int KEEP = 5;

    /** The tab menu's actions, by the id {@code Tabs.CHOSEN} carries back. */
    static final String STOP = "stop";
    static final String AGAIN = "again";
    static final String COPY = "copy";
    static final String CLOSE = "close-run";

    /** The type size of a run's output. */
    private static final float FONT_PX = 14f;

    /** One run's tab: its terminal, what it ran, and whether it is still going. */
    static final class Entry {
        final long run;
        final TerminalScreen screen;
        Offer offer;
        boolean running = true;

        Entry(long run, TerminalScreen screen) {
            this.run = run;
            this.screen = screen;
        }
    }

    private final Window window;
    private final Gui gui;
    private final GlyphLayout glyphs;
    private final Tabs tabs;
    private final int background;
    private final int foreground;
    private final int faint;
    private final int accent;
    private final int danger;

    /** Parallel to the bar's tabs. */
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Long, Entry> byRun = new HashMap<>();
    private final Map<String, Entry> byAddress = new HashMap<>();

    Console(Window window, FontSet fonts) {
        this.window = window;
        this.gui = window.gui();
        this.glyphs = fonts.layout(fonts.face(Type.MONO));
        this.background = rgb(gui.theme().color(Look.CARD));
        this.foreground = rgb(gui.theme().color(Role.INK));
        this.faint = rgb(gui.theme().color(Role.FAINT));
        this.accent = rgb(gui.theme().color(Role.ACCENT));
        this.danger = rgb(gui.theme().color(Role.DANGER));

        tabs = new Tabs(gui).name("runs").closable(false)
                .action(MenuAction.of(STOP, "Stop"))
                .action(MenuAction.of(AGAIN, "Run again"))
                .action(MenuAction.of(COPY, "Copy output"))
                .action(MenuAction.of(CLOSE, "Close"));
        tabs.node().width(Length.FILL).height(Length.FILL);
        gui.landmark(Landmarks.CONSOLE_TABS, tabs.node());

        // The window's own title bar, which a window places as the main one does, then the tabs on a card.
        window.titleBar().node().background(gui.theme().color(Role.PAGE));
        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), gui.box().width(Length.FILL).height(Length.grow(1f))
                        .padding(Length.ZERO, Type.GAP)
                        .scroll(false, false)
                        .children(gui.box().width(Length.FILL).height(Length.FILL)
                        .padding(Length.dp(4))
                        .background(gui.theme().color(Look.CARD))
                        .corner(Type.CORNER)
                        .border(Type.RULE, gui.theme().color(Look.RIM))
                        .clip(true)
                        .children(tabs.node())),
                        gui.box().width(Length.FILL).height(Type.GAP));
    }

    Window window() {
        return window;
    }

    /** The tab bar's address, which its messages carry. */
    String address() {
        return tabs.address();
    }

    /**
     * Run {@code run}'s tab, made in front if it is not there yet. Its output can come before the console hears that
     * it was launched, since the two travel apart; whichever is first makes the tab, and the launch names it.
     */
    Entry entry(long run) {
        Entry e = byRun.get(run);
        if (e != null) {
            return e;
        }
        // Always as if a Windows pseudoconsole were behind it: whether one is, is the pty platform's to know and it does
        // not say (framework-notes FN-7). Elsewhere the cost is a fit that stops a row short now and then.
        TerminalScreen screen = new TerminalScreen(gui, glyphs, Type.MONO, FONT_PX).name("run-" + run)
                .colors(background, foreground).pseudoconsole();
        e = new Entry(run, screen);
        tabs.add("Run " + run, screen.node());
        entries.add(e);
        byRun.put(run, e);
        byAddress.put(screen.address(), e);
        tabs.select(entries.size() - 1);
        return e;
    }

    /** {@code run} was launched: name its tab, and say what runs and where, above anything it writes. */
    void launched(long run, Offer offer) {
        Entry e = entry(run);
        e.offer = offer;
        tabs.title(entries.indexOf(e), offer.label());
        e.screen.feed(sgr(faint) + "> " + offer.line() + "\r\n  in " + offer.dir() + reset() + "\r\n\r\n");
        gui.focus(e.screen.node());
    }

    /** What {@code run}'s program wrote. */
    void output(long run, String text) {
        entry(run).screen.feed(text);
    }

    /**
     * {@code run} is over: say how as its last line, and close whatever finished runs are past the ones kept. Answers
     * the same in a sentence, for the main window's status line: "Compile finished".
     */
    String ended(Ended m) {
        Entry e = entry(m.run());
        e.running = false;
        String how;
        int ink;
        if (!m.problem().isEmpty()) {
            how = "Could not start: " + m.problem();
            ink = danger;
        } else if (m.stopped()) {
            how = "Stopped";
            ink = faint;
        } else if (m.code() == 0) {
            how = "Finished";
            ink = accent;
        } else {
            how = "Failed with exit code " + m.code();
            ink = danger;
        }
        e.screen.feed("\r\n" + sgr(ink) + how + reset() + "\r\n");
        String label = e.offer == null ? "Run " + m.run() : e.offer.label();
        prune();
        return label + ": " + how.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + how.substring(1);
    }

    /** Whether {@code m} is a run that did not finish well: the status line draws the eye to it. */
    static boolean bad(Ended m) {
        return !m.problem().isEmpty() || (!m.stopped() && m.code() != 0);
    }

    /** Close the oldest finished runs past {@link #KEEP}, never the one in front. */
    private void prune() {
        int finished = 0;
        for (Entry e : entries) {
            if (!e.running) {
                finished++;
            }
        }
        Entry front = at(tabs.selected());
        for (Entry e : List.copyOf(entries)) {
            if (finished <= KEEP) {
                return;
            }
            if (!e.running && e != front) {
                close(e);
                finished--;
            }
        }
    }

    /** The run whose terminal is at {@code address}, or null. */
    Entry byAddress(String address) {
        return byAddress.get(address);
    }

    /** The run in tab {@code index}, or null. */
    Entry at(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    /** Take {@code e}'s tab away. Its run, if still going, is the caller's to stop first. */
    void close(Entry e) {
        int i = entries.indexOf(e);
        if (i < 0) {
            return;
        }
        entries.remove(i);
        byRun.remove(e.run);
        byAddress.remove(e.screen.address());
        tabs.remove(i);
        e.screen.close();
    }

    /** Every run still going: what closing the window would stop. */
    List<Entry> running() {
        return entries.stream().filter(e -> e.running).toList();
    }

    private static int rgb(Color c) {
        return (Math.round(c.r() * 255) << 16) | (Math.round(c.g() * 255) << 8) | Math.round(c.b() * 255);
    }

    /** A truecolour foreground escape: the console's own lines are drawn in the theme's colours. */
    private static String sgr(int rgb) {
        return "\u001b[38;2;" + ((rgb >> 16) & 255) + ";" + ((rgb >> 8) & 255) + ";" + (rgb & 255) + "m";
    }

    private static String reset() {
        return "\u001b[0m";
    }
}
