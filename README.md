# text-editor-vexel-demo

A deceptively simple text editor built on [vexelray-gui](../vexelray-gui): a title bar, a multiline
`TextField` (word wrap, line numbers, selection, cut/copy/paste via the OS clipboard, caret-follow
scrolling), and a status line — rendered as one batched SDF draw.

## Prerequisites

The sibling stack installed to the local Maven repo, in order: `supirvast`, `vexelray`,
`tactroller` (+ `atchung`), `vexelray-gui`, and [`mainframe`](../mainframe) (the terminal window's shell).
Java 25, and a Vulkan-capable GPU to run windowed.

## Run

```bash
mvn compile exec:exec
```

Headless capture to PNG (no GPU window / input backend needed):

```bash
mvn compile exec:exec "-Dapp.args=--capture"
```

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative.

All three windows draw their own title bar, in the app's palette rather than the system's: `TitleBar` from
`vexelray-gui-widget` over a window created with `Decorations.CLIENT`. It is an ordinary row of ordinary
widgets — what makes it a title bar is two declarations, `WindowRegion.DRAG` on the strip and
`WindowRegion.INTERACTIVE` on each button, so dragging, snapping, double-click-to-maximize, Win+arrow and the
system menu all stay the window manager's job. The buttons drive `GuiApp.controls()` on the main window, and
each popup's own `NativeWindow` on the others.

Every window remembers where it was, and whether it was open: position, size, maximized state, which tool
windows were up and what folder the file tree was showing all persist to `~/.text-editor/settings.properties`.
Placement is restored at creation, clamped to a monitor that still exists — shrunk if the saved rectangle no
longer fits, then nudged until it is fully on screen. Launch the app and the terminal and file tree come back if
they were open when you last closed it; a folder that has since gone away is reported rather than reopened. See
[docs/window-placement.md](docs/window-placement.md).

## The terminal window

**Ctrl+`** opens a second window running [MainFrame](../mainframe) — a shell whose piped values are typed
records, sizes, times and media types, not text to be re-parsed. It runs embedded, in this process, so `edit` and
`reveal` are real MainFrame commands with real `help` text:

```
~/Documents/GitHub/text-editor-vexel-demo > find "*Test.java" | first 3 | edit
~/Documents/GitHub/text-editor-vexel-demo > ls | where size > 1mb | sort-by size --reverse
```

`Up`/`Down` walk history, `Ctrl+L` clears, `Ctrl+C` interrupts a running command or copies the selection,
`Ctrl+D` on an empty line (or `exit`) closes the window. The
design, and what the framework cannot do for it yet, is in [docs/mainframe-window.md](docs/mainframe-window.md);
the original from-scratch scope it replaced is in [docs/terminal.md](docs/terminal.md).

Two extra flags:

```bash
mvn compile exec:exec "-Dapp.args=--terminal"           # open the shell window at startup
mvn compile exec:exec "-Dapp.args=--capture-terminal"   # render it headlessly to terminal.png
```
