# The MainFrame window — what was built

Status: **implemented**, then **moved**. Supersedes the shell half of
[terminal.md](terminal.md), which scoped a bash-alike written from scratch.

> **Where the code is now.** Everything this document calls `terminal/` was extracted into MainFrame's own
> [`mainframe-vexel-gui`](https://github.com/sibarum/mainframe/tree/main/mainframe-vexel-gui) module and
> generalised, because MainFrame is the program and an editor is one of the things it opens. The renames, class
> for class:
>
> | Here | There |
> | --- | --- |
> | `terminal/TerminalWindow` | `dev.mainframe.gui.console.Console` (+ `ConsoleSpec` for everything an application can disagree about) |
> | `terminal/MainFrameShell` | `dev.mainframe.gui.console.ConsoleShell` (the `edit`/`reveal` builtins split out to `EditorApp` here) |
> | `terminal/Scrollback`, `Ansi`, `LineSink`, `PromptPipe` | `dev.mainframe.gui.console.*`, unchanged |
> | `terminal/Profile`, `ProfileStore`, `ProfileCommands` | `dev.mainframe.gui.profile.*`, behind a `ProfileApp` |
> | `Palettes.PHOSPHOR`, `.HOT`, `.BEZEL` | `dev.mainframe.gui.console.Phosphor` |
> | `ProjectSettings` | `dev.mainframe.gui.app.ProjectScope` (the filename is a parameter now) |
>
> The design below still describes the code. Read `terminal/X` as its right-hand column.

`Ctrl+`` ` opens a second OS window running [MainFrame](../../mainframe) — a shell whose piped values are typed
records, sizes, times and media types rather than text to be re-parsed. That replaced roughly 3 600 lines of
lexer, parser, expansion, builtins and rc-file handling in the original scope with a Maven dependency and about
600 lines of window.

![the terminal window](../terminal.png)

## Why embedding, not launching

MainFrame ships a native binary, so driving it as a subprocess was the obvious option and the wrong one. It is a
**library first**: a `Session` that talks through a `Renderer` over two `PrintStream`s, and an `Interpreter` that
runs a parsed program. Embedding it in-process buys three things a pipe cannot:

- **`edit` and `reveal` are real commands.** They are `Builtin`s registered into MainFrame's own `Registry`, so
  they get argument checking, `help` text generated from their signature, and a "did you mean" when misspelled —
  the same treatment `ls` gets. `find "*Test.java" | first 3 | edit` opens three tabs.
- **One process, one cwd, one lifetime.** `cd` moves a `Path` field the prompt reads each frame. No protocol.
- **Typed values stay typed** right up to the renderer, which is what makes the output a table rather than
  columns of text that happen to line up.

Both projects are Java 25 and MainFrame has zero runtime dependencies, so the dependency costs nothing to add.
Its native-image build is untouched by this: the editor uses the jar.

## Shape

```
terminal/
  TerminalWindow.java   named-window lifecycle, layout, prompt claims, status line  (mirrors FolderWindow)
  Scrollback.java       line ring, per-frame batched flush, node cap
  MainFrameShell.java   the embedded Session/Interpreter, job thread, edit + reveal builtins
  Ansi.java             MainFrame's seven SGR codes -> Span; every other escape discarded
  LineSink.java         PrintStream -> lines, the one place bytes become text
```

The window is a third `Gui`, opened as the **named window** `"terminal"` (`app.window("terminal", …).show()`), so
Ctrl+` means *the* terminal — creating one, or raising the one already there. Input comes from the app-wide
`WindowInput.Factory`; the window's own `TitleBar` over `Decorations.CLIENT` and its own zoom are its business.
The folder window is the same pattern under the name `"folder"`.

**The session outlives the window.** The tree belongs to `TerminalWindow`, not to the OS window, so closing the
terminal releases a window and leaves MainFrame running: reopen it and the scrollback, the history and the
working directory are where you left them. Only application shutdown stops the job thread. Verified by defining
a variable, closing the window, reopening it, and reading the variable back. Two things cross into the editor, both through the request queue `FileActions`
already owns: `edit` opens tabs, `reveal` points the folder window somewhere.

**Threading.** One job thread, commands in submission order. Output is queued from that thread and drained once
per frame inside a single `gui.batch`, so a command emitting fifty thousand lines costs one publish per frame
rather than fifty thousand mutations. The scrollback is a ring of 5 000 nodes; one command's output is capped at
20 000 lines / 4 MB, then a line saying so.

**Monospace is atlas face 1**, whose charset is latin-1. Every byte MainFrame prints is ASCII — its tables are
built from spaces and its clipping marker is `~` — so nothing falls back to the proportional face.

## Decisions worth knowing

**The session is not interactive, on purpose.** MainFrame hands an external program the process's own stdio when
the program is the last stage of an *interactive* line. In a GUI that sends `^git log` to whatever launched the
JVM instead of to the pane. Non-interactive, MainFrame captures the output and shows it here. The same flag makes
a data-losing command refuse with `add --yes once you are sure` rather than stop on a question the pane cannot
answer — the guardrail still fires, it just fires visibly. `rm ./x --dry-run` and `rm ./x --yes` both work.

**`Ctrl+C` is a claim, and the claim decides.** `TextField` handles `Ctrl+C` as copy in its own key stage and a
`FOCUSED` claim preempts it, so the claim does both jobs: a command running → interrupt; otherwise copy the
selection, which this window can do itself because `document()` is public and `Gui.clipboard()` is writable.
Every window is now bound to the OS clipboard, not just the main one.

**`exit` and Ctrl+D close the window.** They go through `NativeWindow.requestClose()` — the same route the
window's own close button takes — so the frame loop tears the popup down on its own terms and `onClosed` still
runs. That became possible when the popup's `NativeWindow` was exposed for
[placement persistence](window-placement.md); before that, `exit` could only print advice.

**Interrupt is honest about its reach.** `Ctrl+C` interrupts the job thread, which stops a child process being
waited on — but MainFrame's own loops do not poll for interruption, so a long `find` or `index-build` runs to the
end. The scrollback says that when you press it instead of implying the command died. Cooperative cancellation
inside MainFrame is the fix, and it belongs in MainFrame.

## What the framework cannot do yet

- **The scrollback cannot be selected with the mouse.** `TextField` is the only selectable text and it is always
  editable. `TextField.readOnly(boolean)` upstream is the fix; until then, output leaves through `save`.
- **`Renderer`'s width is fixed at 100 columns** (it reads `COLUMNS` once at construction, and a JVM cannot set
  its own environment). Tables clip to 100 characters regardless of how wide the window is, and long lines wrap
  rather than reflow. A `Renderer` that accepts a width — or reads one per call — is a small MainFrame change.
- **External programs are captured, not streamed.** `^mvn compile` shows nothing until it finishes. That is
  MainFrame's own documented gap, not this window's.

## Testing

`--capture-terminal [out.png]` starts MainFrame, runs real lines against the real filesystem, lets the per-frame
flush publish them, and writes a PNG — no GPU window, no keyboard. It is both the way the window's look is
reviewable and a smoke test of the entire path from `Parser.parse` to `Span`.

`--terminal` opens the shell window at startup instead of on `Ctrl+`` `, for looking at the two windows together.
