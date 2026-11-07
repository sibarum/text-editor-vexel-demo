# Framework notes

Findings about VexelRay, vexelray-gui, Kronometer, tactroller and atchung that came out of building
**VexelRay IDE** — things the framework does not have, does not document, or does in a way that cost time to
discover.

**Why this file exists.** An application built on a framework is the only place its gaps are visible, and they
are visible exactly once: at the moment they are worked around. A workaround with no note beside it becomes a
piece of application code nobody can tell from a design decision, and the framework never hears about it. So
the rule is to write the note *when the workaround is written*, not in a retrospective, and to write down what
was measured rather than what was assumed.

**Before writing a workaround, ask whether it is a component.** If the answer is "every project on this
framework will write these same four calls" — that is a finding, and the fix belongs upstream. Say so here
with that framing, so the [retrospective](retrospective.md) at the end of the milestone has a candidate rather than a
complaint.

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

## Findings

## FN-1 · A job replies once, so slow work cannot be watched filling in 🔬

Open pictures the folder under the pointer (`SurveyFolder`). In 0.1 the survey ran on a thread of its own, handed the
picture its counts every 120 ms so a large build was seen being read, and gave up between files once the pointer had
moved on. A `@Job` returns one reply and is not stopped part way (components.md §10: cancellation is the caller's).

Done here: the survey is bounded (6000 files, 900 Java files read), and `Preview` keeps one survey running, asking for
wherever the pointer has got to when it lands, so a sweep down twenty rows costs two surveys. What is lost is the
climbing numbers: a large build shows its name, then its whole picture at once. Add's archive (`CreateFile`) is the
same: a big folder says nothing until the zip is done.

What the framework could do: let a job send while it runs (a `Sender` of its own reply type, or a progress topic
beside the reply), and hand it a cancellation token the caller can trip, which components.md already calls additive.
Any job on hover or on typing wants both.

## FN-2 · A Button says nothing about the pointer being on it 🔬

Open pictures a recent folder while the pointer is on its button. `TreeView` publishes `Hovered`; `Button` publishes
only `Pressed` and `Toggled`, and it takes its own node's `NodeEvent.STATE` for its wash, so `gui.hear` on that node is
refused. Done here: each button sits in a cell of its own, and the view hears the cell's state, since a node is hovered
while the pointer is on anything inside it (`Picker.recentCell`). Costs a node per button and a paragraph explaining
it. What the framework could do: a `Button.Hovered` state topic, as `TreeView` has.


## FN-3 · Overriding three roles takes a whole forwarding theme 🔬 (fixed 2026-11-05)

*Fixed in vexelray-gui: `Theme.map(Role, Role)` keeps every other decision, and `Look.theme` is four `map`s on
`Theme.of(...)`.*

The October design puts a selected row, a scrollbar thumb, every line and every well somewhere other than the
framework's roles do (`Look`). `Theme` has no seam for "these roles, mapped": the only way is an anonymous `Theme`
that forwards `palette`, `shading`, `relief`, `lit`, `letterpress`, `focusRing`, `icons` and both `color`s, and maps
in the two `color`s. Thirteen methods to change four colours, and one forgotten forward is silent: a theme written
before `icons()` existed would quietly drop an application's icon effects. What the framework could do: a
`Theme.map(UnaryOperator<Role>)` (or `Theme.with(Role from, Role to)`) that keeps every other decision, as
`focusRing(FocusRing)` and `icons(IconEffects)` already do for theirs.

## FN-4 · A fill fading to Role.NONE fades through black 🔬 (fixed 2026-11-05)

*Fixed in vexelray-gui, and the diagnosis below was wrong: the fade never went through black. A header born clear,
lifted and cleared again before the fade clock moved was told to fade from the lift at no alpha to transparent black,
two invisible colours, and `Cuts` compared them exactly. Two colours at no alpha now look the same
(`PaintMotion.same`), and `TAB_REST` is gone: a header rests on `Role.NONE`.*

A tab header lifted when selected and clear at rest faded its background from the lifted colour to `Role.NONE`, which
is transparent *black*; `ViewTest` caught it as a change in one frame. Done here: the rest colour is the lifted one at
alpha 0 (`Look.TAB_REST`). Every widget that clears a fill on a state change has the same trap. What the framework
could do: fade an alpha-0 end in the other end's colour, so `NONE` means "none of this", whatever this is.

## FN-5 · TextField and TreeView always bring their own well 🔬 (fixed 2026-11-07)

*Fixed in vexelray-gui: `frame(Framing.FLUSH)` on either paints neither the well nor its hairline and keeps their
room. `Navigator`'s trees and `Buffer`'s field use it, and no longer write resolved colours to undo the well; the
trees sit 0.1 rem further in than when their border was taken to zero.*

Both paint a well, a 0.5rem corner and a `LINE` border on their own node. Set into a card, as the design's navigator and
document are, each instance has the frame taken off again by hand (`Navigator`'s trees, `Buffer`'s field,
`FormattedView` matching it). What the framework could do: a frame style the widget takes (`framed(false)`, or a
`Frame` value with `WELL`/`NONE`), so "flush in its container" is one word.

## FN-6 · A menu line cannot show its shortcut 🔬 (fixed 2026-11-07)

*Fixed in vexelray-gui: `MenuItem.shortcut`, `MenuAction.shortcut` and `Menu.Builder.shortcut` say a chord, drawn dim
at the line's right; it binds nothing. The tab menu says Ctrl+S, Ctrl+W and Ctrl+Shift+V from `Chords`, which the
keyboard binds too, and "Formatted or source" no longer carries its chord in its label. Not filled in by itself: a
tab's line sends a message about that tab, and the chord one about the front tab, so no two are the same message to
match. The Run menu's F5 opens the menu and is no line's, so the README still says it.*

The Run menu's F5 has nowhere to go: `MenuItem` has an icon, a label and an action, and no right-hand column for a
chord. The README says F5 instead. What the framework could do: a `shortcut` on `MenuItem` and `Menu.Builder.item`,
drawn dim at the right, which a declared chord could fill in by itself.

## FN-7 · TerminalScreen asks the application which platform it is on 🔬

`TerminalScreen.pseudoconsole()` works round a conhost flaw, so the console should call it on Windows only. The build's
isolation check (rightly) refuses an `os.name` test outside an OS module, and `PtyPlatform`, which knows, does not say.
Done here: always on; elsewhere a fit stops a row short now and then. What vex-suite-common could do: answer it from
the pty (`PtyPlatform.pseudoconsole()`), or have the widget ask the platform itself.

## FN-8 · A menu cannot be walked from the keyboard 🔬 (fixed 2026-11-04)

*Fixed in vexelray-gui `afd2dbb`: an open menu takes Up, Down, Home, End, Enter, Right and Left, and `TitleBar.open`
starts it on its first line, so F5, Down, Enter runs Compile with no pointer. Kept below as found.*

`ContextMenu` answers Escape and nothing else: no arrows, no Enter, no first-letter jump, and no line under the
keyboard when it opens. So F5 drops the Run menu (`TitleBar.open`) and a click still has to choose. What the framework
could do: Up/Down through the enabled lines, Right/Left into and out of a submenu, Enter to choose, and a menu opened
by a key starting with its first line marked, as every desktop menu does.

## FN-9 · TerminalScreen draws a gap where the colour changes 🔬

Maven's `[INFO]` reads `[ INFO ]` in the console, and every coloured run has the same gap either side: the cells are
right (copy gives `[INFO]`), so it is the drawing of a styled span. In vex-suite-common-tty, not worked round here.

## FN-10 · A record state grows a field in every constructor call 🔬 (fixed 2026-11-04)

*Fixed in vexelray-framework: `@Withers` on `Editor.State`, which implements the generated `EditorStateWithers`, gives
it one `with<Component>` per component, and its twelve withers are now one-liners on them or gone. The run chain still
borrows `Closing`; it can have a component of its own now that one costs a line. Kept below as found.*

The run chain waits on saves exactly as a close does, and wanted a map of its own in `Editor.State`. `State` has eleven
fields and twelve `with…` methods, each a full constructor call; a twelfth field is a change to all of them. Done
here: the run reuses `Closing`, which gained an optional `launch`, so `landed` and `failed` serve both, at the price of
a name that now means "whatever waits on saves". The ledger pattern makes this the normal way to grow: what the
framework could do is generate the withers for a step's state record (the processor already reads it), or let a chain
keep its waiting state on its run rather than in the component's.

## FN-11 · A driven run writes the person's settings 💡

Checking Run… with ottermate wrote `run.command` and its siblings into the person's own settings, since an automated run
uses the real profile unless `text-editor-vexel-demo.home` says otherwise. A launch under `--automation` could default
the home to a scratch one, the way a test's is.

## FN-12 · A job's requests cannot coalesce, so work made stale by the next keystroke still runs 🔬

Checking Pontif as it is typed is a compile of the whole program after an edit: well under a millisecond for a small
file, 70 ms or so warm for a 22 KB module, and more in the native image. A `@Job` request is never dropped: the job
lane runs every one, in any order, two at once if it likes. A mailbox can say `COALESCE_LATEST`, but a job's request
topic cannot, and there is no debounce. So `Buffer` keeps one `Check` in flight per document and sends the next when
the answer comes back, for the text as it is then (`Buffer.check`/`checked`): a burst of typing costs the compile
already running and one for where it ended. `MarkText` has the same shape and has never needed it, being a scan.

Every editor feature that compiles, lints or indexes as you type will write these same few lines. A job whose request
coalesces per key (here the field), latest wins and at most one running, would be the component: `@Job(coalesce = ...)`
or a request type that says so.

## FN-13 · An underline is drawn in the text's colour, so a problem cannot be marked under coloured code 🔬

A problem in code wants the usual mark: a red line under the token, the token keeping its own colour. `Span` has
`underline`, and `TreeRenderer` draws it in the span's `fg` or, without one, the ink. A red `fg` recolours the text
too, so the underline cannot be red without the token going red, and a span with the theme's ink is not the token's
colour either. `Buffer` marks a problem with an ink underline on a faint `DANGER` ground instead, which reads, but is
not what any editor's reader expects, and the ground is also how the buffer tells its problem spans from its colours.

The fix is an underline colour of its own on `Span` (and, as most editors draw one, a wavy style), so that a problem
is one span laid over the colours rather than a second kind of colour.

## FN-14 · A menu cannot be opened at the caret from a key 🔬

Ctrl+Enter on a name that two modules export should offer a choice of the two, as the Pontif Editor's chooser does,
at the name. `ContextMenu.present` takes a `ClickEvent` and floats the menu at the pointer, so a menu has to come from
a click; a key press has no place to give it, and `TextField` does not say where its caret is on screen. So
`NavigateText` names the modules on the status line and imports neither: the person adds the `requires` by hand.

A key-driven menu anchored at a node and an offset in it (a field's caret, a tree's row), opened by a declared chord
as the title bar's menus are by F5, is what completion, quick fixes and choosers in every editor on the stack need.

## FN-15 · A field's change version counts caret moves, so it cannot say which text an answer is about 💡

`TextField.Changed` carries the document's version, and a field announces a change, with a new version, for every
commit: typing, but also a caret move, a selection and spans being set. The IDE's buffers matched a job's answer to
the field by that version, so a caret moved while `MarkText`, `CheckText` or `FormatText` was working made the answer
look stale and dropped it, and `CheckText` compiled the same text again for every arrow key. Found when Ctrl+Enter's
own caret move refused the import it asked for. `Buffer` now remembers the text each question was about and applies an
answer only while the field still says it. Not a gap so much as a name that invites the wrong use: a text version
alongside the document's, bumped only when the text changes, would be the thing to key on.
