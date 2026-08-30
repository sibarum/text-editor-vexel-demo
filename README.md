# text-editor-vexel-demo

A deceptively simple text editor built on [vexelray-gui](../vexelray-gui): a title bar, a multiline
`TextField` (word wrap, line numbers, selection, cut/copy/paste via the OS clipboard, caret-follow
scrolling), and a status line — rendered as one batched SDF draw.

![The editor showing pom.xml beside the Navigator window](docs/images/editor-navigator.png)

*The editor and the file tree, each drawing its own title bar, each on its own rotation of the same
nine-number dark palette.*

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

Frame profile, for working on the render-on-demand loop:

```bash
mvn compile exec:exec "-Dapp.args=--profile"
```

That prints what the loop did against what the clock said it was allowed to skip, and every few seconds pokes
one of the wake paths — a timeline post, a node mutated off the frame thread, a handler that changes nothing —
to prove each still produces a frame. Off by default for that second reason: it is an instrument that moves
what it measures, so a shipped run should not be running it on a timer.

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative.

Closing the window asks first if anything is unsaved: a dialog naming each changed document, with **Save all**
(which prompts for a path for anything never saved, and cancels the quit if any write does not land), **Discard**
and **Cancel**. Nothing to save closes straight through. See [docs/close-gate.md](docs/close-gate.md).

Right-click a tab for **Close**, **Close all** and **Reveal in Navigator**. Close is the tab bar's own — the
widget puts it on every header and wires it straight to its own removal, which is why `Workspace` learns about
that route from `Tabs.onRemove` rather than by being asked. The other two are the editor's, because both are
about what a tab *is* here that a tab bar cannot know. Close all leaves one empty tab behind, exactly as `Ctrl+W`
on a last tab does, and goes through the same unsaved-work dialog quitting does: one click that can discard
documents you last looked at an hour ago is not the risk closing the tab in front of you is. Reveal in Navigator
points the file tree at the folder holding that document and selects its row — re-rooting the tree only if the
drawer is somewhere else, so a reveal into a folder you have already opened up does not shut it again. It is
greyed on a document that has never been saved, which is a reason it cannot be taken rather than a different menu.

All three windows draw their own title bar, in the app's palette rather than the system's: `TitleBar` from
`vexelray-gui-widget` over a window created with `Decorations.CLIENT`. It is an ordinary row of ordinary
widgets — what makes it a title bar is two declarations, `WindowRegion.DRAG` on the strip and
`WindowRegion.INTERACTIVE` on each button, so dragging, snapping, double-click-to-maximize, Win+arrow and the
system menu all stay the window manager's job. The buttons drive `GuiApp.controls()` on the main window, and
each popup's own `NativeWindow` on the others.

The margin every window leaves around its page is also its resize grip. A system frame gives you two or three
pixels to aim at; here the whole gutter — 16dp around the editor, 12dp around the terminal and the file tree —
is handed to the window manager with one declaration, `Gui.resizeBorder(GUTTER)`, set from the same `Length` the
root is padded by so the two cannot drift. Cross into the dead space and the pointer is already a resize pointer.
It costs the chrome nothing: the wider band applies only where the tree declares nothing, so inside the title bar
and its buttons the system's own thin band still applies — the bar keeps all but its top few pixels draggable,
and the close button stays a close button all the way to its corner.

**Three windows, three looks.** A theme in vexelray-gui is nine numbers rather than a table of colours, so a
second look is an angle, not a fork. [Palettes.java](src/main/java/dev/vexelray/demo/editor/Palettes.java) rotates
the *neutral family* of `Palette.DARK` — page, ink, and the depth colour every shadow derives from — and leaves
the chromatic anchors where the framework put them: the editor keeps the blue-grey it always had, the file tree
is the same ladders swung 163° to the warm side, and both keep the same blue selection, because a selection
should read as a selection in every window. The terminal is the exception, and deliberately so. Nothing in this
app names a colour any more; the five hex constants it used to carry were all within 2/255 of a rung on the
framework's own ladder, so they are `Role.PAGE`, `Role.PANEL` and `Role.DIM` now.

Every window remembers where it was, and whether it was open: position, size, maximized state, which tool
windows were up and what folder the file tree was showing all persist to `~/.text-editor/settings.properties`.
Placement is restored at creation, clamped to a monitor that still exists — shrunk if the saved rectangle no
longer fits, then nudged until it is fully on screen. Launch the app and the terminal and file tree come back if
they were open when you last closed it; a folder that has since gone away is reported rather than reopened. See
[docs/window-placement.md](docs/window-placement.md).

## The native build

```bash
mvn -Pnative package                                 # target/editor.exe
```

It is one executable and nine JDK DLLs beside it, not one file. `native-image` copies `awt.dll`,
`fontmanager.dll`, `freetype.dll`, `lcms.dll` and five others next to the exe and they are **required**:
the text atlas ships as a PNG and `GuiApp.loadAtlasRgba` decodes it with `ImageIO`, so `java.desktop` is
reachable from the first frame that draws a glyph. Moving them away breaks startup, not some optional path.

The switches are the ones [mainframe-dist](../mainframe/mainframe-dist/README.md) found the hard way and
each is load-bearing: `-H:+ForeignAPISupport` because the graphics stack, both input backends and the native
file dialogs are Panama downcalls and Win32 calls back in through an upcall stub, `-H:+SharedArenaSupport`
because raw input opens an `Arena.ofShared` on one thread and reads it on another,
`-J-Djava.io.tmpdir=target/nitmp` because Windows Application Control blocks a brand-new unsigned `.exe`
under `%TEMP%` and native-image's own probes are exactly that, and `/SUBSYSTEM:WINDOWS` +
`/ENTRY:mainCRTStartup` so no console window appears beside the editor. The last two are the Windows
linker's; a native build elsewhere drops them.

One more Windows-only step sits ahead of the link: `rc.exe` compiles `src/main/rc/editor.rc` into
`target/editor.res`, and that `.res` goes to the linker as an input. It is what puts the icon on the file on
disk — see [The icon](#the-icon) — and it expects the Windows SDK's resource compiler on `PATH`, which is
what a Visual Studio developer prompt does, the same shell that puts `link.exe` there for native-image.

`src/main/resources/META-INF/native-image` holds two files, kept apart on purpose. The first is this
application's traced reflective surface — including the sixteen `grammars/*.tmLanguage.json`, joni's Unicode
tables and TM4E's four `Raw*` grammar classes, which is the whole of the syntax highlighting, and the
bundled `natives/windows-x64/nfd.dll` plus the downcall descriptors `Nfd`'s static initialiser builds, which
is the whole of Ctrl+O. The second, `-signed-jar`, is a workaround: TM4E ships as a **signed** jar, the
Eclipse signing certificate genuinely ends up in the image heap, and every type needed to represent an X.509
certificate has to be registered or the build fails outright with `Type not found during analysis`. Deleting
those because a native image has no jars to verify is the obvious-looking mistake.

**Application Control will lie to you about this build.** `Unable to run 'WindowsDirectives.exe' to compute
offsets in C data structures` is the policy blocking a probe — retry. `UnsatisfiedLinkError: Can't load
library: awt` on the *result* is **not** a missing DLL: `awt.dll` imports from the `java.dll` and `jvm.dll`
shims native-image generates fresh on every build, those are unsigned and hash-unique per build, and a
blocked one leaves `awt.dll` unable to resolve its imports. Rebuild until a set is allowed through.

Verified from the executable, not from the JVM arrangement: `--capture` draws the page, `--capture-terminal`
renders the MainFrame console with this app's own commands in it, and `--capture-folder` renders the file
tree. Highlighting and the file dialogs are not reachable from this executable headlessly — it takes no path
argument — so the proof for those is `mainframe.exe`, which carries the same code and the same metadata.

## The icon

The mark is `prompt` from [vexelray-icons](../vexelray-icons) — a coral pen nib on its writing rule, on the
same 96px grid as the rest of the VexelRay marks. It is the one mark in that sheet that is about writing text
rather than drawing, and it is not `mainframe`, which the terminal window already wears.

It is worn in two places, and they are set two different ways:

- **The window** — title bar, Alt-Tab, taskbar. `AppIcon` reads the six PNGs under
  `src/main/resources/icons` at startup and hands them to `NativePlatform.setApplicationIcon`, which is the
  icon of the *process*: the editor, the Navigator and the terminal are three windows of one program, none of
  them names an icon of its own, so all three inherit it. Set before the first window exists, so nothing is
  ever shown under the generic icon and then corrected. Six sizes rather than one because the window manager
  asks for a size the application never sees, and answers out of what it was given.
- **The executable** — Explorer, a pinned taskbar button, a shortcut. That is a linked resource, not a file
  the program reads, so `src/main/rc/editor.rc` names `editor.ico` beside it and the `native` profile has
  `rc.exe` compile the pair into `target/editor.res` for the linker.

Both come from the same artwork. To change it, rasterise the SVG to the six PNGs, then repack the `.ico`:

```bash
java tools/MakeIco.java
```

## The terminal window

**Ctrl+`** opens a second window running [MainFrame](../mainframe) — a shell whose piped values are typed
records, sizes, times and media types, not text to be re-parsed. It runs embedded, in this process, so `edit` and
`reveal` are real MainFrame commands with real `help` text:

> **The window is not this application's.** It lives in MainFrame's own
> [`mainframe-vexel-gui`](https://github.com/sibarum/mainframe/tree/main/mainframe-vexel-gui) module, because
> MainFrame is the program and an editor is one of the things it opens — not the other way round. What this
> application supplies is a `ConsoleSpec`: the name and title, the project the shell should consider itself in,
> the bottom line's wording, and an `EditorApp` carrying `edit`, `reveal` and a window for `launch "editor"` to
> raise. Everything else — the tube, the scrollback, the prompt, the history, the forms — comes with the
> component. See [`EditorApp.java`](src/main/java/dev/vexelray/demo/editor/EditorApp.java), which is the whole of
> what the shell knows about editing.

```
~/Documents/GitHub/text-editor-vexel-demo > find "*Test.java" | first 3 | edit
~/Documents/GitHub/text-editor-vexel-demo > ls | where size > 1mb | sort-by size --reverse
~/Documents/GitHub/text-editor-vexel-demo > editor
```

`apps` lists what is plugged into the shell, and `editor` brings this window forward — MainFrame names
a command after every app that has a window, so typing a program's name runs it.

`Up`/`Down` walk history, `Ctrl+L` clears, `Ctrl+C` interrupts a running command or copies the selection,
`Ctrl+D` on an empty line (or `exit`) closes the window. The
design, and what the framework cannot do for it yet, is in [docs/mainframe-window.md](docs/mainframe-window.md);
the original from-scratch scope it replaced is in [docs/terminal.md](docs/terminal.md).

### Data entry, and .vtext

**A form, and nobody drew it.** MainFrame's `Form` takes a name plus lists of repeated details, states the
fields once, and its data entry does the rest: `!back`, `!clear`, `!cancel`, `!browse` for a folder chooser, and
the review sheet before it counts. The same definition validates a record that never went near a keyboard, so the
screen and the check cannot drift apart.

That needed one thing from this window. MainFrame's forms are *printed* — write a prompt, read a line back — and
the session had been built on a null reader. It now reads from
[PromptPipe](https://github.com/sibarum/mainframe/blob/main/mainframe-vexel-gui/src/main/java/dev/mainframe/gui/console/PromptPipe.java), a queue with a `Reader` face, so a
question lands in the scrollback and the answer is typed where every other line is typed. Which of the two a typed
line *is* comes from the shell rather than from a mode the window is put into: `asking()` is true exactly while the
job thread is blocked reading, so a form that finishes, cancels or fails hands the prompt back with nothing having
to say so.

**Every menu item runs a command.** Right-click anywhere in the terminal for the menu; every entry on it submits
a real MainFrame command with real `help` text, and echoes the line it submitted. So what the menu did is in the
scrollback, and anything it can do can be scripted:

```
~/src/thing > find "*Test.java" | first 3 | edit
~/src/thing > ls | where ext == "java" | select name size
```

**A project is not a working directory.** A project records settings by *name* in a `.vtext` file in its own
directory — [ProjectScope](https://github.com/sibarum/mainframe/blob/main/mainframe-vexel-gui/src/main/java/dev/mainframe/gui/app/ProjectScope.java),
the same forgiving properties format as everything else, meant to be committed, and holding nothing
machine-specific. The project is the folder the file tree is showing, so the terminal can `cd` anywhere without
changing which project you are in; with no folder open there is no project, and nothing is written anywhere.

### It is a green screen

MainFrame pipes typed records rather than text, which is the one idea it shares with the machine its name comes
from — so the window wears the part: an IBM 5250 data-entry display, with `Command ===>` over a boxed entry area
and a message line under it.

What it does *not* wear is the rest of the costume. A real 5250 spent its top three rows on a screen identifier,
a centred title and a "Type command, press Enter." that stopped being news the second time you read it, and its
bottom row on a function-key legend. Those four rows are scrollback now. The header is one line: the working
directory on the left, the date and time on the right — the only two things up there that ever changed.

The look is a palette, not a set of overrides. `Phosphor.THEME` is one hue at nine lightnesses: accent, action
and danger all collapse onto the same green, because a tube has one colour and a beam that goes up or down. Two
things fall out of that for free. The palette's `depth` anchor is the phosphor itself, so `Node.elevation` stops
being a drop shadow and becomes the halo the glass throws on the bezel — one number, no special case anywhere in
the renderer. And an error turns the message line over into reverse video without anything choosing two colours:
`Role.DANGER` fills it and `Role.ON_DANGER` is *whichever palette extreme lies further from that fill*, which in
a monochrome palette is the unlit page.

What the tube cannot do is draw MainFrame's four ANSI colours, so red, yellow and cyan all become "brighter" —
the same instruction as bold. That loses something real, and the message line is what pays it back.

There was a shadow mask over the glass for a while — scan lines every 3dp, aperture-grille wires every 4dp, so
the lit cell between them came out taller than it was wide. It went for the reason the legend went: a delight to
look at, a tax to read through, and this is a window you read through.

Everything the terminal answers to is a chord, and they all predate the costume: `Up`/`Down` for history, `Ctrl+L`
to clear, `Ctrl+C` to interrupt or copy, `Ctrl+D` to close.

**The screen tails, until you say otherwise.** The scrollback is a `ScrollLock.BOTTOM` container, so it follows
output down as it arrives; scroll away from the bottom and the framework detaches the lock, and nothing that
prints afterwards moves the view. Scrolling back onto the bottom edge re-attaches it — all of that is
`scrollLock` doing its job, and none of it is this app's code.

The one thing the app has to say is what Enter means. Pressing it is a reader announcing they are done with
history: a shell that runs a command and leaves you looking at an older screen has hidden its own answer. So
submitting a line calls `Node.scrollToEdge()`, which re-attaches the tail and lands in the same frame as the
echo. Scrolling stays entirely the reader's; only Enter overrides it.

**The whole window is the input.** Any click anywhere puts the caret back in the command field — subscribed to
the click *topic* rather than handled on the root, because a handler bubbles only to the nearest ancestor that
has one, so a click on the title bar's maximize button would never reach it. Re-focusing a field that already has
focus is a no-op in the dispatcher, so the common case costs a comparison. There is nothing to aim at, because
everything is the same target.

Two extra flags:

```bash
mvn compile exec:exec "-Dapp.args=--terminal"           # open the shell window at startup
mvn compile exec:exec "-Dapp.args=--capture-terminal"   # render it headlessly to terminal.png
mvn compile exec:exec "-Dapp.args=600" "-Dapp.args2=--terminal"   # both windows, 600 frames, then quit
```

(`app.args` and `app.args2` are one token each — a Maven property is not a command line. Blanks are dropped, so
an unused slot costs nothing.)

## Testing an interaction

Headless `--capture` renders a settled tree to a PNG: it has no pointer and no frame loop, so it proves
what the editor *looks* like and nothing about what it *does*. The complement is
[`vexelray-gui-harness`](../vexelray-gui/vexelray-gui-harness), which runs this application's real loop
with a synthetic pointer and asserts the question captures cannot ask — after a click, does a frame
arrive on its own?

That is the question Reveal in Navigator got wrong, invisibly, for weeks. See
[docs/testing-interaction.md](docs/testing-interaction.md).
