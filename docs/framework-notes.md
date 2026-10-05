# Framework notes

Findings about VexelRay, vexelray-gui, Kronometer, tactroller and atchung that came out of building
**Text Editor** — things the framework does not have, does not document, or does in a way that cost time to
discover.

**Why this file exists.** An application built on a framework is the only place its gaps are visible, and they
are visible exactly once: at the moment they are worked around. A workaround with no note beside it becomes a
piece of application code nobody can tell from a design decision, and the framework never hears about it. So
the rule is to write the note *when the workaround is written*, not in a retrospective, and to write down what
was measured rather than what was assumed.

**Before writing a workaround, ask whether it is a component.** If the answer is "every project on this
framework will write these same four calls" — that is a finding, and the fix belongs upstream. Say so here
with that framing, so the retrospective has a candidate rather than a complaint.

## How to write one

    ## FN-1 · One line saying what is missing 🔬
    
    What was wanted, what the framework offers instead, and what was done about it.
    Then: what it costs, and what the framework could do about it.

The markers are a filter, not decoration:

| | |
| --- | --- |
| 🔬 | a framework gap — something upstream could fix |
| 💡 | an idea, not yet a finding |
| 📋 | carried over: needs re-verifying against a newer build before it is repeated |

Each finding below also says what it means for **freezing v1** (`vexelray-framework/docs/v1.md`): whether the gap
is in a surface v1 freezes, and whether closing it later would be additive or breaking.

## The rebuild, in brief

Rebuilt in October 2026 from `vexel-desktop`, against vexelray-framework `0d3bf8e`, vexelray-gui `8a74b41` and
atchung `12080b8`, every sibling freshly installed. Copied from the deleted editor's history on purpose: the
sixteen TextMate grammars, `TextFile` and its test, the scope and extension tables in `Highlighter`, and the two
highlighting test classes. Everything else is new.

**What took the editor's requirements without modification** — the half worth recording first: the generator
(the project built and passed its tests unedited), phase inference, the one `Settings`, window memory (position,
size and zoom came back on every relaunch), the framework's title bar, `Shell.onClose` as the place a close gate
goes, `Launch.rest()` as the way a file named on the command line arrives, the dialogs being installed for an
application that never mentioned them, `AutomationStarter`, and the logging. An editor with tabs, a navigator, a
close gate and a remembered session is about 2,400 lines including comments, and none of them is application
edge.

## Findings

### FN-1 · A side-effect-only recipe has to invent a type to return 🔬

Two things this application does at `ATTACH` produce nothing: arming the close gate (`shell.onClose(...)`) and
restoring last time's session. Both need the `Shell`, or values that exist only by then, so both belong in a
`@Provides` method — and a provider returning `void` is a compile error ("provides nothing"), and so is one
returning a record ("a record behind `@Provides` is the smell"). `Recipes.restore` returns an empty marker class,
`Recipes.Restored`, which the generated wiring stores in a field and exposes as `wiring.restore()`.

*Costs:* a type per side effect, a generated accessor nobody should call, and a reader wondering what `Restored`
is for. Every application with a close gate writes one. *Could:* accept `void` from `@Provides` and run it for
effect in its inferred phase, or a separate annotation for the same thing. **v1:** annotation surface. Allowing
`void` later is additive (it removes an error), but the record rule's message steers people to "return an
interface", which is the wrong advice for this case.

### FN-2 · An application on a generated wiring cannot wear an icon 🔬

The first finding of the previous port was "the application's mark": the framework never named an icon. That was
fixed with `AppInfo.withIcon` — which only a hand-written wiring can call. A generated wiring builds its `AppInfo`
from `@VexelApp`, which has no icon attribute, and the class is final, so there is nothing to override. This
editor wears the generic icon; the old one wore `prompt` from `vexelray-icons`.

*Could:* an `icon` attribute on `@VexelApp` naming a classpath resource prefix, or `@Provides AppInfo.Icon` handed
back like `Appearance`. **v1:** annotation surface; additive either way, but it is a regression in kind against a
finding already recorded as fixed, so worth closing before the freeze rather than after.

### FN-3 · The automation `key` verb cannot press a chord 🔬

`key <NAME>` publishes one `KeyPressed` with no modifiers. Every command in an editor is a chord — Ctrl+S, Ctrl+W,
Ctrl+Tab, Ctrl+Shift+O — so none of them can be driven through ottermate, and this application's shortcuts were
checked by reading `TextField.onKey` and the claim rules in `keyboard-focus-text.md` rather than by pressing them.
What could be driven: clicks, typing, right-click menus, the title bar's close button and the dialog it raised,
and the navigator walked with the arrow keys.

*Could:* `key ctrl+s`, `key ctrl+shift+tab`. **v1:** the wire protocol is a frozen surface. A new argument form is
additive, but a protocol frozen without chords freezes a driver that cannot test the commonest kind of command,
so it should be decided before.

### FN-4 · `onCommitLatest` makes a committer wait on another thread's listener 🔬

The template's `Model.onChange` uses atchung's `onCommitLatest`, and its Javadoc says why. Neither says what
follows from *how* it serialises: the committing thread blocks on a monitor while another thread's delivery runs.
So a commit made while holding any lock the listener also takes is a deadlock — two threads each waiting on the
other, nothing thrown. This application's first design did exactly that: `Workspace` held its tab lock while
committing `opened(...)`, and the listener (`Ui.show`, retitling the tab headers) wanted the same lock.

Avoided by rule rather than by luck, and the rule is written on `Workspace`: commit only outside the lock, and
nothing reachable from a model listener takes it (headers are found by id through a concurrent map, never by
index under the lock).

A second consequence, found later: the order `onCommitLatest` keeps is among its *own* deliveries. `Session` called
its listener's method directly once at the end of a restore, and a delivery of an older snapshot, still running on
another thread, landed after it — so the session it wrote was missing the last file opened, and the next launch
quietly came back with one tab fewer. The fix is that `Session.remember` reads `Model.doc()` under its own lock
rather than trusting the snapshot it was handed.

*Could:* deliver through a serial executor with the same version drop, so a committer never waits; at the least,
put the warning on `onCommitLatest` and on the template's `Model.onChange`, which is where every application will
meet it. **v1:** a generated project is application code, so the template's guidance is part of what freezes.

### FN-5 · A span change from a worker is not drawn until something else draws 🔬

Syntax colours arrived late. The screenshot after `settle` showed the document uncoloured, the one after a
pointer move showed it coloured, and with no input at all the colours appeared on the next caret blink. The spans
*were* on the document — `EditorTreeTest.openingAFileTakesOverThePristineTabAndHighlightsIt` sees them headless —
so the edit is applied and the frame that would show it is not drawn. Calling `gui.requestFrame()` straight after
`setSpans` did not change it, so it is not only a missing wake. vexelray-gui `8a74b41` ("a frame is drawn when
something changed") is the obvious suspect; it was not bisected.

*Costs:* up to a caret blink of uncoloured text after every edit and every open, and an unfocused document has no
blink, so it waits for input. *Could:* fix upstream; the application does nothing about it. **v1:** not a
framework surface, but `Shell` hands out `Gui`, so its draw-on-change contract bounds v1.

### FN-6 · The dialogs are a process-wide static, and a close request cannot be constructed 🔬

`Modals.show` is static and throws `IllegalStateException` until the framework installs it with the window. A tree
built headless with `VexelApplication.tree` therefore cannot run any path that asks a question — in an editor,
every close and every quit. And `CloseRequest`'s constructor is package-private to gui-core, so a test cannot make
one to hand the gate.

Worked around with two seams in the application: `Actions.ask(Consumer<Modal>)`, defaulting to `Modals::show`, and
`Actions.guardClose(Runnable proceed, Runnable cancel)` beneath the real `guardClose(CloseRequest)`. The close-gate
tests in `EditorTreeTest` go through those.

*Could:* `Shell` hands the dialogs out as a value — a type a recipe can take and a test can supply — and
`CloseRequest` gets a public way to be made from two callbacks. **v1:** `Shell`'s accessors are frozen; the static
`Modals` is how every application reaches dialogs today, so that choice is being made by default.

### FN-7 · The file dialogs are main-thread, block the frame loop, and need the application to quit them 🔬

`vexelray-gui-nfd`'s dialogs are synchronous, modal, and must be called on the window's thread. A request from a
handler is posted to the frame loop (`GuiApp.post`), and the frame loop stops — no paint, no input to the window —
for as long as the user looks at the dialog. `GuiApp`'s stall reporter then warns about the poster after 250 ms,
about something working as designed. And `Nfd.quit()` has to be called by the application, on that thread, at
shutdown ("nothing calls it for you").

Reaching `GuiApp` at all makes the recipe `@MainThread` (T2.2), though what it returns, `NativeDialogs`, is safe
from any thread — it only posts. There is no worker-safe handle to "run this on the frame loop", so a value built
for use from anywhere has to be coloured main-thread to be built.

*Costs:* `NativeDialogs` is code every application with an Open command will write, the same way. *Could:* the
framework owns file dialogs as it owns `Modals` — installed at `ATTACH`, quit at shutdown, answered on a worker —
and/or a worker-safe root for the frame loop's queue (`Shell.post`, or an `Executor` the processor does not colour
main-thread). **v1:** a new `Shell` accessor is additive; the colour of a thread-safe wrapper is a T2.2 semantics
question, and those freeze.

**Update, October 2026: the stall is gone on Windows.** `vexelray-gui-nfd` now asks `Os.dialogLane` where a
dialog runs. On Windows that is one daemon dialog thread, which does `NFD_Init` and quits NFD for itself on the
way out; `FileDialog.openAsync`/`saveAsync`/`pickFolderAsync` answer a `CompletableFuture` off the GUI thread, and
the window keeps drawing (disabled, as a modal's owner) while the dialog is up — measured with a posted task every
250 ms running in 0–2 ms through six seconds of open dialog, against "not run within 200 ms" for the synchronous
call. `NativeDialogs` and the title bar's screenshot button both use it. On macOS AppKit requires the main thread,
so the lane is the frame loop and the stall remains; no Linux build of NFD ships. `Nfd.quit()` now releases only
the calling thread's own initialisation, so the main thread's call at shutdown is a no-op on Windows and still
needed on macOS. Unverified: macOS itself, and `NFD_Quit` actually running at a real JVM exit. Two things learned:
the synchronous calls cannot be routed through the dialog thread with the caller waiting — disabling the owner
window sends the GUI thread a message, and a GUI thread parked in `join()` deadlocks with the dialog — and
`WindowControls.of(window)` (what a popup gets) posts with `Runnable::run` and captures nothing, so on a popup the
macOS lane would run a dialog on whatever thread clicked, and a screenshot would do nothing. The `@MainThread`
colouring of the recipe stands: `NativeDialogs` still takes the `GuiApp` for `post` and the window handle.

### FN-8 · `TextField.onChange` is one slot, and fires when only the spans changed 🔬

A second `onChange` call silently replaces the first, so whoever owns a field has to own its one listener and fan
out (`Buffer` does). And `setSpans` goes through `published()`, which notifies `onChange` with the text unchanged —
so a highlighter that re-runs on change re-runs once for every result it produces, tokenizing the whole document a
second time to find nothing to do. `Buffer` remembers the last text it highlighted and skips.

*Could:* additive listeners, and a notification for text changes only (the document `State` already carries the
edit behind each version). Upstream, vexelray-gui.

### FN-9 · `Tabs`' own Close cannot be stopped 🔬

The bar puts **Close** on every header's menu, and it removes the tab immediately, telling the owner afterwards
through `onRemove`. An editor has to ask about unsaved work first, so this one turns it off (`closable(false)`) and
rebuilds Close, Close others and Close all in its own `onContextMenu`. That works and is how `Tabs` says to do it,
but it means the bar's Close is unusable by exactly the kind of application it was written for.

*Could:* a close request on `Tabs` shaped like `CloseRequest` — asked, answered later. Upstream, vexelray-gui.

### FN-10 · No part is ever inferred into `TREE` 💡

Every part that builds widgets takes the `Gui`, so it is inferred into `GUI`; nothing the framework hands out
first exists in `TREE`, and the generated wiring never overrides `tree()`. `Phase.TREE`'s description ("the
widgets, buildable with no window") is true of what is built in `GUI`. Not a defect — `VexelApplication.tree` runs
both — but a phase that inference cannot produce is a distinction the documentation draws and the mechanism does
not. **v1:** phases are a frozen contract; deciding whether `TREE` means anything is cheaper now than after.

### FN-11 · `History.mark()` marks now, not a version 🔬

Saving runs on a worker, and typing can land between taking the bytes and the write finishing. `mark()` marks
whatever the history is at the call, so marking after the write would call that typing saved. `Buffer` marks
*before* the write, when it takes the bytes, and since there is no un-mark it keeps its own `unsavedWrite` flag
for a write that then fails. *Could:* `mark()` returning a token, or `mark(version)`. Small, upstream.

### FN-12 · Motion is opt-in per widget, so leaving the clock out is silent 🔬

The first rebuild had no motion at all, and nothing said so. The template's `Ui` took the `KronoGui` only to pulse
its counter, so replacing the counter dropped the clock with it — and every widget that could animate (`Tabs` with
a transition, `TreeView.motion`, `Cues`) simply cut instead, because each takes a `Ramp` and does nothing without
one. The old editor had all three. Put back through `Motion`, which makes the ramps from the one clock at two
durations (160 ms for a change, 240 ms for a cue — the old editor's numbers, and its reasons).

*Costs:* a stiffer window, discovered by the user rather than by any check. *Could:* the framework has no default
tempo — every application chooses its own two durations, and the old editor's comment says why one tempo per
application matters. An `Appearance.tempo` (or a `Motion` the shell hands out, like `TitleBar`) would make the
default "it moves" rather than "it cuts", with reduced motion as the one place that hands out none. The
`vexel-desktop` template now builds `Motion` and wires all three, so a generated project starts with it.
**v1:** additive.

### FN-13 · Smaller things, a line each

- 🔬 **`SplitPane` cannot collapse a pane.** Hiding the navigator (Ctrl+B) is `size(Length.ZERO)` with the divider
  still drawn; showing it again restores the remembered width by hand.
- 🔬 **`Tabs` does not handle more tabs than fit.** The bar clips: at the minimum window size four tabs leave the
  front one cut to 35 of its 121 pixels, with no scroll and no shrink. Ctrl+Tab still reaches every tab.
- 🔬 **The dialogs' title bar has minimise and maximise.** A modal question offers to be maximised.
- 💡 **A bare integer on the command line is a frame count.** A file called `120` cannot be opened by naming it;
  `./120` works. The grammar is the framework's and the collision is one application's, so this is a note.
- 🔬 **TM4E's registry is not thread-safe across grammars.** Not the framework's, but the previous editor shipped
  believing a lock per grammar was enough. It is not: the theme's match cache is one `HashMap` for every grammar,
  and `GrammarBundleTest.differentGrammarsFromOneRegistryTokenizeTogether` reproduces the
  `ConcurrentModificationException` it threw when a session restored a Java file and a README together.
  `Highlighter` now takes one lock for all tokenizing.
- 🔬 **A late publication into a closed `Gui` throws.** A highlighting result finishing after a test has torn its
  window down calls `setSpans`, whose change notification goes to a handler pool that has terminated, and
  `RejectedExecutionException` reaches the uncaught handler. Harmless, and a race that was always there; the
  retheme's slightly heavier tree made it show once a run in `EditorTreeTest`. A closed `Gui` could drop it.
- 🔬 **A tab change was once left undrawn until the pointer moved** (FN-5's family). Seen once in four
  `click`→`settle`→`shot` runs, only in the run that did a `find` first; six plain runs, three on each look, all
  drew it.

## The retheme

In October 2026 the editor was rethemed to a design mockup: near-black page, two rounded cards (the navigator, and
the tabs with their document), flat text tabs with an accent mark under the selected one, a breadcrumb over the
document, a quiet status line, a teal accent, and italic comments in a different pair of typefaces. It was done
as a test of whether a look is something an application can bring, before v1 freezes what a look is made of.

**What took the design without modification.** Colour, all of it. Sampling the mockup and converting to Oklab
gave six decisions — page, step, ink, fade, accent, and a derived action/danger — and the ladder lands on the
card, edge, selected-row and thumb colours within the tolerances `LookTest.theConstructionLandsOnTheDesign` now
pins. Three roles are overridden by identity in `Look.THEME` (SELECTION tinted with the accent, a quiet GRIP, a
quiet LINE), which is what `Theme`'s Javadoc says to do. The cards are layout. `Tabs.skin` took the flat tabs, and
the accent mark is a floating child of each header — a text node may carry floats, as a field carries its find
bar — so the bar never learns about it. `Breadcrumb` existed, and `TitleBar.addLeading` took the project name.

**What did not** is below, as FN-14 to FN-17. The pattern is that the framework has a theme for *colour* and
nothing else: shape, density and type are written into each widget, and a widget's look is reachable from outside
in proportion to whether its author happened to add a hook.

### FN-14 · A theme is colour and depth; shape and density are each widget's 🔬

`Theme` is a palette, a shading, a relief and two booleans. Everything else a design changes is a literal in a
widget: corner radii (tab 0.5 rem, tree row 0.4 rem, tree frame 0.5 rem), border widths (0.1 rem everywhere),
padding, row heights, and text sizes (tree rows 1 rem, breadcrumb 0.875 rem, status 0.75 rem, title 0.85 rem).
Some widgets can be restyled after construction because they paint once; others repaint on every state change
and so cannot:

| Widget | What the design wanted | What happened |
| --- | --- | --- |
| `TextField` | no ring round the document | the border is repainted ACCENT/LINE on every focus change (`TextField.java:1153`), so the accent ring stays |
| `TreeView` | tinted row, ink label, smaller text | no skin; selection paints SELECTION + ACCENT label (`TreeView.java:2068`); border repainted on focus; text 1 rem |
| `SplitPane` | a quiet line in the gap between cards | **since fixed upstream:** `gutter(Length)` makes the gap the drag target, `line(Length)` paints a centred line in it, `motion(Ramp)` fades it, and it stays lit for the whole drag |
| `TitleBar` | the application's name bright and bold | the caption is DIM at 0.85 rem with no accessor; the name is the caption |
| `Breadcrumb` | small dim crumbs, `/` separators, the file in the accent | size, separator and the last segment's INK are fixed |
| `StatusBar` | a status dot, a key-cap chip | a slot is a text node; nothing else can go in |
| `Tabs` | a × on the selected tab | the header is one text node; a float could carry it, but the bar's padding is symmetric, so it would overlap the label |

The workaround that covers most of it is a role override, and that has its own cost: a role is global. Making
LINE quiet for the cards' edges makes every line quiet, including the dialogs'; tinting SELECTION also tints the
find bar's match washes. There is no way to say "this widget's selection".

*Costs:* a design is matched only as far as each widget's hooks go, and the gaps are invisible until a screenshot
is laid beside the mockup. *Could:* shape and density tokens on `Theme` (a corner scale, a hairline, a control
height, a type scale) read by every widget the way roles are; and a skin on each widget that paints state, as
`Tabs` has. **v1:** `Theme` is an interface, so new methods with defaults are additive, and `Theme.of` gains an
overload. Changing a widget's literal into a token read is not API-breaking but it does move every application's
pixels, so it is better done before v1 than after.

### FN-15 · An application cannot bring its own typefaces, or a weight, or a slant 🔬

The text atlas is baked into `vexelray-text` at the framework's build, from `NotoSans-Regular` and
`NotoSansMono-Regular`, and `Node.font(int)` picks between those two. The design is set in a different sans and a
different mono, uses bold for the folder and the application's name, italic for comments, and letter-spacing for
the section label. None of that is reachable: there is no third face, no weight, no slant and no tracking, and
shadowing the atlas means re-running the `msdf` plugin over a copy of `vexelray-text`. Of every difference between
the mockup and the screenshot, type is the one you notice first.

*Could:* an application declares its faces (files, and the styles it wants of each) in its build, the starter
bakes them, and a node picks a face *and* a style. **v1:** how an application declares fonts is part of the build
shape, which v1 freezes; and `Node.font(int)` is the one text-styling call, so whether a style is a second
argument or part of a face handle is a decision to make before the freeze.

### FN-16 · A span carries a colour but no style 🔬

`record Span(int start, int end, Color fg, Color bg, boolean underline)`. The design's comments are italic, and
TextMate grammars carry bold and italic as well as colour; a highlighter has nowhere to put either. It depends on
FN-15 — there is no italic face to select — but it is its own decision, because `Span` is a record: adding a
component later changes its canonical constructor and its deconstruction pattern, which breaks every caller.
**v1:** `Span` is reached through `TextField`, which is how every editor-shaped application on this stack colours
text. Widening it now — a style component, or a builder in front of the constructor — is the cheap version.

### FN-17 · The editor's own chrome is not themeable 🔬

The design highlights the caret's line, brightens that line's number, and draws a thin quiet scrollbar. The field
has no current-line highlight at all; the gutter draws every number in one ink, FAINT, read once when the renderer
is built (`TreeRenderer.gutterInk`); the gutter's padding is fixed, so numbers sit tight against the text; and the
scrollbar's width is the renderer's. The thumb's colour could be reached only by overriding GRIP for the whole
application. *Could:* a current-line role and an active-gutter role on the field, read per frame, and the gutter's
padding as a prop. **v1:** additive; not a frozen surface.

### FN-18 · A label cannot be told to stay on one line, and nothing clips its own glyphs 🔬

Seen as the navigator's long file names wrapping to two or three lines inside a fixed-height `TreeView` row and
drawing over the rows below. Three things combined, all in gui-core:

- **`Node.wordWrap(false)` is ignored on a label.** `RetainedNode.wrapsText()` is
  `!editable() || (multiline() && wordWrap())`, so every non-editable text node wraps at its own width whatever the
  prop says. `StatusBar`, `Select`, `Rail`, `Inspector`, `Button`, `Breadcrumb` and `Segment` all call it as if it
  worked, and so does this application (the navigator's folder name, the title bar's project name) — harmless only
  because those happen to fit.
- **`clip(true)` masks a node's children, not its own text.** `TreeRenderer` draws a node's self before pushing
  its clip. So a one-line label cut at its own edge always takes a wrapper box: an AUTO-width label (one line,
  since flex never shrinks) inside a `grow(1)` row with `clip(true)` — `Table`'s body cells already do this, and
  `TreeView` now does it too, with a tooltip carrying the full name when the row is cut.
- **There is no ellipsis anywhere** in vexelray-text or gui-core, so a cut name ends mid-glyph.

The same wrap-in-a-fixed-box bug remains in `ListView` rows (and so in `Select`'s dropdown, whose
`wordWrap(false)` does nothing) and in `Table`'s column titles. *Could:* make `wordWrap(false)` mean one line on
any text node, let a text node clip its own glyphs, and add an ellipsis mode to the text layout. **v1:** a prop that
silently does nothing is a contract; deciding what `wordWrap` means on a label is cheaper before the freeze.

### FN-19 · `TreeView.Source` has one item per row, so a merged row is a workaround 🔬

The navigator now merges a chain of single-child folders into one row (`test/java/dev/vexelray/demo/editor`),
because at 1.2 em of indent per level a Java source tree leaves file names no room. `FolderSource` does it by
keying the row on the deepest folder and labelling it with the chain, and the tree never learns the folders in
between exist. What that costs:

- `revealPath` stops silently at the first step it cannot find, so a reveal chain that still named a merged-away
  folder just stopped at the top of the tree; `chainTo` has to know to leave them out.
- `refresh()` matches rows by item, so when a chain splits (a merged folder gains an entry) the row is rebuilt and
  forgets whether it was expanded.
- There is no per-segment menu: "Make this the root" on a merged row can only mean the deepest folder.
- `label(T)` runs under the tree's lock on whatever thread builds the row, so a label that depends on listing the
  disk has to be worked out in `children()` and cached in the source.

Merging costs one short listing (at most two visible entries) per folder row, plus one per further merged level,
inside `children()` on the tree's own executor; `Navigator.reveal` now walks on the offload lane for the same
reason. *Could:* rows that stand for a chain of items, or a compact-folders mode in `TreeView` itself, and a
`revealPath` that says where it stopped. **v1:** `TreeView` is not a frozen surface; additive.

### FN-20 · A sliding tab indicator is sixty lines every application with tabs will write 🔬

The selected tab's accent mark slides to the newly selected tab, eased (`Workspace.slideMarkTo`). The framework
supplied the parts — an eased `Ramp` from the clock, `translate` for draw-only displacement, floating children on
the header — but not the effect, and assembling it took four things no application should have to rediscover:

- **The distance is the application's to compute.** There is no "move from header A to header B": each header
  owns a mark, and the arriving one is drawn displaced back to where the last one was, then eased home, with the
  displacement read from the two headers' layout rects in pixels.
- **A header added this instant has no layout** until the next frame, so every step re-reads the target's rect
  and the old mark stays where it is until there is one.
- **A slide can be overtaken before it draws.** A restored session opens tabs back to back; the first version
  hid only the mark it was leaving, and left two marks on show. The mark actually drawn has to be tracked apart
  from the tab it is heading for, and an overtaken slide restarts from what is on screen.
- **Its own lock.** The skin runs under the bar's monitor and the steps on the clock, so the mark's state needs a
  lock that takes nothing else, to keep `Workspace`'s deadlock rule (FN-4).

And one conversion: `Node.translate` takes multiples of the node's em, while layout rects are pixels, so the
offset is divided by `rootEmPx × zoom × dpi` by hand — a product the application has to know is the em basis
(`FlexLayout.emBasis`), which `Gui` does not expose as one number.

The conversion is a symptom of the public geometry API speaking three units: an application *writes* `Length`s
(em, rem, dp), but *reads* `NodeLayout` and `DragEvent` in px, animates with `translate` in em, and gets
`SplitPane.sizeDp`/`onResize` in dp (which `SplitPane` itself makes by dividing pointer px by dpi). Px is right
underneath — `NodeLayout` is a transport-serializable read model shared with hit-testing, overlays and devtools,
and input arrives in px — but an application should read in the units it writes.

*Could:* `Tabs.indicator(Ramp)` — one indicator node the bar owns, positioned from its own headers, sliding on
selection, with the skin deciding only its look. And for the units: an em view of the read side (em accessors on
`NodeLayout` and `DragEvent`, or at least one `Gui.emPx()` answering the basis), so a position read back can be
handed to `translate` unconverted and still means the same thing after a zoom. **v1:** the indicator is additive.
The units are not a `Tabs` question: `Node.layout()` is reachable from what `Shell` hands out, so it is inside the
gui surface that bounds v1. Adding an em view later is additive, but deciding what an application reads geometry
in — and whether `rect()` stays px for applications at all — is cheap only before the freeze.
