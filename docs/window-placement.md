# Window placement that survives a restart

Status: **implemented** on `text-editor-buildout`.

All three windows — editor, files, terminal — come back where they were left, at the size they were left,
maximized if they were maximized, and *open if they were open*. State lives in
`~/.text-editor/settings.properties`, through `Settings`:

```properties
window.main.x=300
window.main.y=180
window.main.width=900
window.main.height=620
window.main.maximized=false
window.terminal.x=60
window.terminal.y=700
window.terminal.width=640
window.terminal.height=380
window.terminal.open=true
window.folder.x=1520
window.folder.y=540
window.folder.width=400
window.folder.height=600
window.folder.open=true
window.folder.path=C\:\\Users\\User\\Documents\\GitHub\\text-editor-vexel-demo
```

Java properties, one window per key prefix, hand-editable — and forgiving of nonsense, which matters more than
it sounds like it should (see *Clamping* below).

## The four rules

**1. Restore at creation, never after.** Saved bounds go into the `WindowConfig` the window is *constructed*
with, so it appears where it belongs instead of appearing somewhere else and jumping. `WindowConfig` sizes the
*outer* rect and `NativeWindow.screenX()/outerWidth()` report the outer rect, so a saved rectangle round-trips
exactly — verified: a sane placement comes back byte-identical across runs, with no drift. That stayed true when
the windows moved to `Decorations.CLIENT` and started drawing their own title bars: the frame is still there and
still sized, it is only painted by the application, so the same rectangle means the same thing either way.

**2. Saved bounds are a claim about a desktop, not a fact about this one.** Monitors get unplugged, resolutions
drop, a laptop comes back undocked. Every restore is clamped through `WorkArea.fit`, against the work area of
the monitor nearest the saved position — its rectangle minus the taskbar or dock.

**3. Shrink, then nudge.** That order cannot fail. Nudging first leaves an oversized window with its top-left in
bounds and its bottom-right outside, and no further nudging fixes it; shrinking first means the nudge always has
somewhere to go. A window that already fits is untouched — the common case is a no-op. Measured on a
1920×1140 work area:

| saved | restored | why |
| --- | --- | --- |
| `-3000,-3000 5000×5000` | `0,0 1920×1140` | larger than the screen: shrunk to it, then nudged to the corner |
| `1700,1000 900×700` | `1020,440 900×700` | fits, but hangs off two edges: nudged, size kept |
| `1800,1100 400×600` | `1520,540 400×600` | same, the folder window |
| `300,180 900×620` | `300,180 900×620` | already fits: untouched |

**3b. A reopened window is corrected after creation, not at it.** The tool windows are *named* windows
(`app.window("terminal", …)`), and a named window's `WindowSpec` — so its `WindowConfig` — is built once, when
the name is first claimed. The bounds worth restoring may be newer than that: move the terminal, close it,
reopen it in the same session. So `onCreated` calls `WindowMemory.restoreBounds`, which re-reads, re-clamps and
applies with `NativeWindow.setBounds` before the window's first frame. Verified by moving a live terminal to
`500,400 640×420`, closing it, and reopening it: `500,400 640×420`.

**4. What the window actually became is what gets written down.** A clamped restore is recorded at its real
bounds, not the ones the file asked for — otherwise an impossible rectangle survives every restart, silently
re-clamped each time and never corrected. Same rule on a first run: the placement the OS chose is worth keeping,
so it is written immediately rather than waiting for the user to move something.

## Which windows were open

A launch reopens the tool windows that were up when the application last closed: the terminal, and the file tree
pointed back at the same folder. Restoring goes through the same request queue everything else uses, one window
per frame, so it is ordered with the modal dialogs rather than racing them.

**The one hard part is telling "you quit with this open" from "you closed this".** Both end in the same callback
— the frame loop runs every popup's `onClosed` as it tears the application down — so a flag written there reads
"closed" in both cases and nothing ever reopens. So the flag is not written from the open and close paths at all:
`WindowMemory.open(key, isOpen)` is called **every frame** from the window's own live state. The last poll before
the loop exits still sees the window open, which is the truth worth saving; a window closed by hand is recorded
shut on the very next frame, long before shutdown. Verified both ways — quitting with the terminal up records
`open=true`, and closing it with `exit` first records `open=false` while still remembering its geometry.

**A folder that has gone away is not reopened.** Deleted, renamed, on an unmounted drive, or an unparseable path
in a hand-edited settings file: the status line says the last folder is no longer there rather than opening an
empty tree, and the next frame records the window as closed, so it stops being asked for. The path itself is
left in the file. `window.folder.open` is the only thing forgotten.

## Two details worth knowing

**Maximized is a state, not a rectangle.** While a window is maximized its reported bounds are the screen's, so
saving them would lose the size to restore *down* to. The flag is recorded and the bounds from before are left
alone, which is what makes un-maximizing after a restart land where it used to. A minimized window is skipped
entirely — it has no meaningful bounds to read.

**Writing is debounced.** `WindowMemory.poll()` runs once per frame from the app's `beforeFrame` hook and
notices every move immediately, but the file is only written once the movement has held still for 700 ms, and
again at shutdown. A window drag would otherwise be a few hundred disk writes. `Settings.save()` is atomic
(temp file, then move), so a crash mid-write loses the save and never the file.

## What this needed from the framework

Three additions, in the layer that owns each fact.

**`vexelray-os`** — the platform had no way to say what fits on a screen. Now:
`NativePlatform.workArea(x, y)` returns `Optional<WorkArea>` for the monitor nearest a screen point, its
rectangle minus the taskbar. Windows implements it with `MonitorFromRect` + `GetMonitorInfoW`
(`MONITOR_DEFAULTTONEAREST`, so bounds saved on a monitor since unplugged resolve to the screen that replaced
it); the Linux and macOS skeletons inherit a default `Optional.empty()`, which means "cannot say" and degrades
to letting the OS place the window rather than to a wrong answer. `WorkArea.fit` carries the shrink-then-nudge
rule, since it is geometry rather than policy. Coordinates are physical screen pixels throughout — the same
space `screenX()` reports — so no DPI conversion enters anywhere.

**`vexelray-gui`** — `requestPopup` took a title and a size, and handed back only a raw `HWND`. A popup could
therefore neither be created at a saved position nor read back. Now
`requestPopup(WindowConfig, Gui, Consumer<NativeWindow>, Runnable)` takes the full window request and hands over
the popup's own `NativeWindow`. The old overloads delegate to it unchanged. `GuiApp.workArea(x, y)` is a static
pass-through, static because the *first* window's bounds must be clamped before there is a `GuiApp` to ask —
they go into the config it is constructed with.

Also `NativeWindow.setBounds(x, y, w, h)` — one `SetWindowPos` rather than a move and then a resize, because two
would be two `WM_WINDOWPOSCHANGED` rounds and the intermediate rectangle is a visible flicker. `WindowConfig`
covers placement at creation; this covers the window whose right bounds are only known after it exists, which is
every window an application reopens.

That second change also closed two unrelated gaps, both from the same fact — the application now holds the
popup's `NativeWindow`. `requestClose()` is how the terminal's `exit` and `Ctrl+D` close their window. And
`minimize`/`maximize`/`restore`/`isMaximized` are what a popup's own `TitleBar` needs to command, which is what
lets the folder and terminal windows draw their chrome in the app's palette rather than the system's. Both demos
briefly hand-rolled that four-method adapter privately; it is now `WindowControls.of(window)` in the framework,
which is where it belonged.

`NativeWindow.focus()` arrived alongside those, and closes the last gap in this area: pressing `Ctrl+`` ` (or
`Ctrl+Shift+O`) when the window is already open now raises it and puts the caret back, instead of quietly
focusing something behind the editor. A window manager may still refuse a foreground steal from a background
process — the platform raises and flashes rather than pretending it worked.

## Where the code is

- [WindowMemory.java](../src/main/java/dev/vexelray/demo/editor/WindowMemory.java) — the whole policy: read into
  a `WindowConfig`, watch, debounce, write.
- [TextEditorApp.java](../src/main/java/dev/vexelray/demo/editor/TextEditorApp.java) — `memory.config("main", …)`
  into the `GuiApp` constructor, `memory.watch` after each window exists, `memory.poll()` per frame,
  `memory.save()` at shutdown. `FolderWindow.onCreated` and `TerminalWindow.onCreated` do the popup half.
