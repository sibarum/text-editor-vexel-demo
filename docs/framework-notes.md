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
