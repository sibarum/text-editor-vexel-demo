# Builtin terminal — scope and design

Status: **superseded**, and worth keeping. The terminal window shipped on `text-editor-buildout`, but with
[MainFrame](../../mainframe) embedded as the shell instead of the bash-alike scoped below — see
[mainframe-window.md](mainframe-window.md) for what was built and why.

Which parts of this document still describe the code: §1 (a console, not an emulator), §3 (window and GUI
architecture, the node budget, monospace being atlas face 1), §4 (threading and lifecycle), §8 (key bindings and
the `Ctrl+C` conflict), §9 (ANSI as spans), §11 (safety), §12 (framework gaps), §13 (testing). Which parts are
moot because MainFrame supplies them, differently: §5 (the shell language — MainFrame's pipes carry typed values,
`>` is greater-than and `save` writes files), §6 (builtins), §7 (env, `PATH`, aliases, macros, rc file), §10
(completion), §14 (milestones), §16 (the decisions M1 needed).

A second OS window alongside the editor holding a line-oriented shell: unix/bash-style commands that work
identically on Windows, macOS and Linux, over a fully configurable environment — variables, `PATH`, aliases
and macros — loaded from a user-owned rc file.

---

## 1. What this is, and what it is not

**It is a shell console, not a VT100 emulator.** The distinction decides most of the design, so it comes first.

A terminal emulator proper owns a character grid, a pseudo-terminal device, and the escape-sequence state
machine that lets `vim`, `top` and `less` paint that grid. This stack has none of the three: `vexelray-gui`
renders text through `TextField` and text nodes over an MSDF atlas (no cell grid, no addressable cursor), and
there is no PTY binding anywhere in `vexelray`, `tactroller` or `supirvast`. Building one would mean a PTY
layer over Panama (ConPTY on Windows, `forkpty` elsewhere), a grid widget in the framework, and a full
DEC/xterm parser — each of those is a larger project than this editor.

So the deliverable is the thing that is actually useful inside a text editor: a **prompt, a scrollback, and
commands that run**. Lines in, lines out.

| In scope | Out of scope |
| --- | --- |
| Builtin commands implemented in Java, same behaviour on every OS | PTY / ConPTY, `TERM`, terminfo |
| External programs launched with piped stdio, output streamed into the scrollback | Full-screen/curses apps (`vim`, `top`, `less`) |
| Pipelines, redirection, `&&` / `||` / `;`, quoting, globs, `$VAR` expansion | Interactive prompts from children (`sudo`, `ssh` passwords) |
| Aliases, macros (named parameterised command sequences), rc file, per-session env and `PATH` | Job-control signals, process groups, `Ctrl+Z` / `fg` / `bg` |
| ANSI SGR colour subset in output, mapped onto `Span`s | Cursor addressing, alternate screen, mouse reporting |
| History, history search, tab completion of paths and command names | Shell scripting as a language: arrays, arithmetic, `case`, traps |

A child process that *needs* a TTY will misbehave (no echo suppression, block-buffered output, possibly a hang
on a password prompt). The mitigation is honesty plus a way out: the status line shows a running job, `Ctrl+C`
kills it, and §11 lists the guardrails.

---

## 2. User-visible surface

- **Ctrl+`** (grave) from any window opens — or focuses — the Terminal window. It is a popup owned by the main
  window, exactly like the existing folder window: one taskbar icon, raised and closed with the app.
- The window is a **scrollback** (monospace, colourised, tailing) over a **prompt line** (`~/project $`) over a
  **status line** (cwd, last exit code, running job, `Ctrl+C to interrupt`).
- Typing a line and pressing Enter runs it; output appends. `Up`/`Down` walk history, `Tab` completes,
  `Ctrl+L` clears, `Ctrl+C` interrupts, `Ctrl+D` on an empty line closes the window.
- `edit <file>` opens that file in a tab in the *editor* window. The terminal's starting cwd is the active
  tab's directory, or the folder open in the folder window if there is one.
- `~/.text-editor/rc.sh` is sourced at startup: `export`s, `alias`es, `macro`s, `path` edits. Editing it and
  running `source ~/.text-editor/rc.sh` applies changes without restarting.

---

## 3. Window and GUI architecture

`FolderWindow` in [TextEditorApp.java](../src/main/java/dev/vexelray/demo/editor/TextEditorApp.java) is the
precedent, and the terminal follows it line for line: its own `Gui`, opened through `GuiApp.requestPopup(...)`
with the `onCreated`/`onClosed` seams, its own `Tactroller` attached to its own `HWND` in `onCreated`, its own
`TactrollerInputBridge` pumped once per frame from the app's `beforeFrame` hook, and `zoomShortcuts(gui)` so it
zooms independently. Two OS windows, one frame loop, one thread doing the presenting.

**Scrollback = a column of text nodes with `scrollLock(BOTTOM)`.** The framework already has the exact
primitive: `Node.scrollLock(ScrollLock.BOTTOM)` pins a scrolling container to the bottom as content grows and
detaches when the user scrolls up (architecture §8.5); `vexelray-gui-demo`'s log pane is this, appended to from
a worker thread. One text node per output line, sized to its wrapped content, `font(1)` for monospace, per-line
`spans(...)` for colour.

**Monospace is atlas face 1** — `NotoSansMono-Regular`, baked as the one extra face in `vexelray-text`'s atlas.
Two consequences, both real:

- Its charset is **latin-1 only**; anything outside it falls back to the proportional primary face at runtime.
  Box-drawing (U+2500), block glyphs and arrows live in face 0, not face 1 — so a `tree`-style or table builtin
  drawn with `├──` would render proportionally and misalign. **Builtin output is ASCII-only**: `|`, `+`, `-`,
  spaces. No alignment that depends on a non-ASCII glyph.
- `Node.font(index)` clamps, so asking for a face the atlas lacks degrades to the primary rather than failing.

**Node budget.** A node per line is fine at hundreds and wrong at hundreds of thousands. Policy:

- Scrollback ring capped at **5 000 lines** (`terminal.scrollback`); appending past the cap removes the oldest
  node (`Node.remove()`).
- A single command's output is capped at **20 000 lines / 4 MB**, whichever comes first, then one
  `... output truncated (N more lines)` line. Redirect to a file for the rest.
- Output arriving from the job thread is **coalesced per frame**: the reader fills a buffer and one flush per
  frame appends the batch inside `gui.batch(...)` — one publish and one reconcile instead of one per line. A
  `find /` emitting 50 000 lines/second must not become 50 000 mutations/second.

---

## 4. Threading and lifecycle

The framework's rule is one-way and this design already satisfies it: workers **post** mutations, the GUI thread
drains them once per frame (architecture §4–5). `Node` handles are thread-safe by construction —
`MutationSink.post` accepts from any thread — which is why the demo's worker appends log lines directly.

- **Job thread.** One single-thread executor per session. Commands run there, in submission order, one at a
  time. Nothing about command execution touches the GUI thread; a `grep` over a large tree must never stall
  presentation.
- **Prompt handling.** `TextField.onSubmit` fires on the handler executor (a worker), which enqueues the line
  onto the job thread. Lines submitted while a job runs queue behind it.
- **Output.** Builtins and process readers write into the session's `Stdio` sinks; the GUI-facing sink buffers
  lines and the per-frame flush appends nodes.
- **Cancellation.** `Ctrl+C` sets the session's cancel flag, `interrupt()`s the job thread, and
  `Process.destroy()`s (then `destroyForcibly()` after 2 s) any child. Builtins that loop (`find`, `grep`,
  recursive `rm`) check the flag per item — cooperative cancellation is the only kind available for in-process
  work.
- **Crossing to the editor window.** `edit <file>` rides the same `ConcurrentLinkedQueue<Runnable>` + `drain()`
  path `FileActions` already uses for NFD dialogs, so opening a tab stays ordered with the app's other file I/O.
  One queue, one owner.
- **Shutdown.** Window closed → `onClosed` releases the input backend, kills the running job, shuts the job
  thread, closes the `TextField`s, and persists history and placement through `Settings`. The main window
  closing tears the popup down by the same path it does today.

---

## 5. The shell language

Tiered on purpose: T1 is what "unix/bash style commands" has to mean to be worth the name, T2 is the natural
second pass, T3 is where a shell stops being a feature of a text editor.

**T1 — the core**

- Tokenising with `'single'`, `"double"` and `\` escapes; `#` comments.
- Word expansion in bash's order: tilde (`~`, `~/x`) → parameter (`$VAR`, `${VAR}`, `$?`, `$$`) → splitting on
  unquoted whitespace → globbing (`*`, `?`, `[a-z]`, `**/` via `PathMatcher`), unmatched patterns left literal.
- Lists: `;`, `&&`, `||`, with `$?` from the last command run.
- Pipelines: `a | b | c`, status of the last stage.
- Redirection: `>`, `>>`, `<`, `2>`, `2>>`, `2>&1`, `&>`.
- Aliases (first-word substitution, not recursive on itself).
- Exit status, and no implicit `set -e` — a failing command never aborts the rest of a line silently.

**T2 — the second pass**

- Command substitution `$(...)`, with backticks mapped onto the same code.
- Background jobs `&`, `jobs`, `kill %1`, `wait` — cooperative, nothing beyond destroy.
- Macros (§7) with `$1..$9`, `$@`, `$#`.
- `if` / `for` / `while` over the T1 word machinery, one-line and multi-line-in-rc forms.
- Here-docs.

**T3 — explicitly not building.** Arrays, `$((arithmetic))`, `case`, `trap`, `local`, subshell `( )` semantics,
process substitution, job-control signals, `exec`, POSIX conformance as a goal.

**Errors are messages, not exceptions.** A parse failure prints `syntax error: unexpected '|'` and sets
`$? = 2`; a missing command prints `foo: command not found` and sets `$? = 127`. Nothing thrown reaches the
frame loop — the job thread catches everything and reports it as a line plus a status.

---

## 6. Builtins

Cross-platform is the whole point: `ls`, `grep` and `wc` must work on a stock Windows box, so they are Java, not
delegations. Each is a `Command` (§15) reading and writing `Stdio`, so a builtin and an external program compose
identically in a pipeline.

**T1 — shell.** `cd`, `pwd`, `echo`, `export`, `unset`, `env`, `alias`, `unalias`, `source` / `.`, `history`,
`which` / `type`, `help`, `clear`, `exit`.

**T1 — files.** `ls` (`-l -a -1 -R`), `cat` (`-n`), `head` / `tail` (`-n`), `mkdir` (`-p`), `rm` (`-r -f`),
`cp` (`-r`), `mv`, `touch`, `wc` (`-l -w -c`), `grep` (`-i -n -r -v -E`, Java regex), `find`
(`-name -type -maxdepth`), `sort` (`-r -n -u`), `uniq` (`-c`), `cut` (`-d -f`), `tr`, `basename`, `dirname`,
`realpath`, `date`, `sleep`, `true`, `false`, `printf`.

**T1 — editor integration.** `edit <file>...` (open in tabs), `reveal <dir>` (point the folder window there),
`save` (save the active tab), `tabs` (list what is open).

**T2.** `macro` / `unmacro`, `path` (list/add/remove `PATH` entries without separator guesswork), `jobs`,
`kill`, `diff`, `sed` (substitute-only subset), `xargs`, `tee`, `stat`, `du`, `tree` (ASCII).

**Deliberately absent.** `chmod` / `chown` (meaningless-to-wrong on Windows), `ln`, `ps`, killing arbitrary
PIDs, anything needing elevation, and every network client (`curl`, `ssh`) — external programs cover those for
users who have them.

`ls`, `cat` and friends reuse [TextFile](../src/main/java/dev/vexelray/demo/editor/TextFile.java)'s policy for
reading text: it already refuses binary, oversized and wrong-charset files with a human-readable reason, and
`cat` of a binary file should say so rather than spray replacement characters into the scrollback.

---

## 7. Configuration — environment, PATH, aliases, macros

**Environment.** The session starts from a copy of `System.getenv()` (the JVM cannot mutate its own environment
and nothing here tries), then applies the rc file. `export FOO=bar` writes the session map; children receive
exactly that map via `ProcessBuilder.environment()`. Unexported assignments (`FOO=bar cmd`) are one-command
overrides. `$VAR` reads the same map plus the shell-owned `$?`, `$$`, `$PWD`, `$OLDPWD`, `$HOME`.

**PATH.** Held as an ordered `List<Path>`, not a string, precisely so the `;`-versus-`:` question never reaches
the user's fingers: the rc file says `path add ~/bin`, `path remove ...`, `path`, and the shell serialises with
`File.pathSeparator` only when handing `PATH` to a child. `export PATH=...` still works and splits on the
platform separator. Resolution order for a bare word is **macro → alias → builtin → `PATH` lookup**, with
Windows `PATHEXT` probing (`.com .exe .bat .cmd`) so `git` finds `git.exe`; `./x` and absolute paths bypass
lookup entirely. `which` reports which of the four won, so a shadowed name is diagnosable.

**Aliases** are bash's: first-word textual substitution, expanded once, arguments appended —
`alias ll='ls -la'`, `alias gs='git status'`.

**Macros** are what aliases cannot do: a named, parameterised, multi-command body.

```sh
macro build {
    cd "$1"
    mvn -q compile && echo "built $1"
}
```

`$1..$9`, `$@`, `$#`; the body runs in the session, so a `cd` inside a macro persists — these are conveniences,
not subshells, and pretending otherwise would need scoping we are not building. `return N` sets `$?`. Recursion
depth is capped at 32 with a clear error.

**rc file.** `~/.text-editor/rc.sh`, in the same dot-directory `Settings` already uses
(`Settings.open("text-editor")` gives `~/.text-editor/settings.properties`). It is a *script*, not a config
format: the lines a user could type are the lines the file holds, so there is one language to learn and `source`
is the reload mechanism. A missing file is normal; a failing line prints its error and the rest still runs.

**Persisted through `Settings`** (properties, atomic write, already in the framework): terminal window
placement, font size, `terminal.scrollback`, and the last N history lines — `putList` / `getList` exist for
exactly this. *Not* persisted: environment and cwd. Those come from the rc file and the active tab, which is
what makes a fresh terminal reproducible.

**A per-workspace rc** (`.editor-rc` in the opened folder) is T2 and a decision rather than a default: sourcing
a script found in a directory the user merely *opened* is how an editor becomes an attack surface. If it lands,
it lands behind an explicit per-folder confirmation.

---

## 8. Key bindings

The framework's claim system makes these clean, and one conflict needs care.

| Chord | Where | Action | Mechanism |
| --- | --- | --- | --- |
| Ctrl+` | every window | open / focus terminal | `gui.shortcut(...)` on each `Gui`, as `FileActions.bind` already does |
| `Enter` | prompt | run the line | `TextField.onSubmit` (fires on single-line fields) |
| `Up` / `Down` | prompt | history back / forward | `claimUi(node, UP, FOCUSED, ...)` — preempts the field's line motion |
| `Tab` | prompt | complete path or command | claim on `TAB`, `FOCUSED`; outranks the framework's focus traversal, and `Shift+Tab` is left unclaimed as the way out |
| `Ctrl+R` | prompt | reverse history search | claim, `FOCUSED` |
| `Ctrl+C` | prompt | interrupt the job, else copy | claim, `FOCUSED` — **see below** |
| `Ctrl+L` | prompt | clear scrollback | claim, `FOCUSED` |
| `Ctrl+D` | prompt | close the window when the line is empty | claim, `FOCUSED` |
| `Ctrl+U` / `W` / `A` / `E` / `K` | prompt | readline line editing | claims, `FOCUSED` (T2) |

A claim is preemption declared in advance: when one matches, its command runs and *nothing else* sees the key
(`ClaimScope`). That is what makes `Up` mean history here and line motion everywhere else, with no
`preventDefault` and no widget subclassing.

**The `Ctrl+C` conflict is real.** `TextField` handles `Ctrl+C` as copy inside its own key stage, and a
`FOCUSED` claim preempts that — so claiming it for interrupt silently removes copy from the prompt. The fix
without touching the framework: the claim decides. Job running → interrupt; otherwise copy the selection, which
the app can do itself because `TextField.document()` is public (text, caret, anchor) and `Gui.clipboard()` is
writable. Five lines. §12 lists the upstream change that would make it zero.

---

## 9. ANSI colour handling

External programs emit SGR sequences when they think they have a terminal. Rather than strip everything, handle
the subset that carries meaning and drop the rest:

- Parsed: `ESC[0m` reset, `1m` bold (rendered as brighter ink — the atlas has no bold face), `30–37` / `90–97`
  foreground, `40–47` background, `38;5;N` and `38;2;R;G;B` truecolour.
- Translated into one or more `Span`s on that line's node (`Span.foreground` / `background`).
- Everything else — cursor motion, erase, alternate screen, OSC titles, mouse — is **consumed and discarded**,
  so a program trying to paint a grid produces harmless plain text instead of visible garbage.
- `\r` without `\n` rewrites the current line rather than appending, which is what makes `git clone` and `mvn`
  progress output read correctly.

---

## 10. Completion

Path completion over the cwd for arguments; command completion for the first word (macros + aliases + builtins +
a `PATH` scan cached per `PATH` change). A single match completes inline; multiple matches print the candidates
as an output line and complete the common prefix. No popup widget in v1 — bash's behaviour, and it needs no
framework support.

---

## 11. Safety

This feature turns a text editor into a general code-execution surface. What the design does and does not do
about that:

- **Only what the user typed runs.** No remote input path, no untrusted document content reaching the shell, no
  server. The rc file is user-owned and in the user's home; the per-workspace rc is gated (§7).
- **No shell string, ever.** External programs are launched argv-first through `ProcessBuilder` with no
  `cmd.exe /c` or `sh -c` wrapper, so a quoting bug cannot become command injection. `.bat` / `.cmd` are the
  documented exception — Windows requires the interpreter — and get argv escaping plus a note.
- **No elevation, no privilege helpers**, and no builtin that touches system state (permissions, services,
  registry).
- **Destructive builtins behave like their unix counterparts:** `rm -rf` deletes. One guardrail worth having —
  `rm -r` refuses a path resolving to a filesystem root or the user's home unless `--yes-really` is passed. That
  is not sandboxing; it is the typo-catch `rm -I` exists for.

---

## 12. Framework gaps this exposes

Each is a real limitation found while scoping, with its workaround and its upstream fix:

1. **No read-only selectable text.** A scrollback of text nodes cannot be selected or copied with the mouse;
   `TextField` is the only selectable text and it is always editable. *Workaround:* a context-menu "Copy all" /
   "Copy last output" plus a `copy` builtin that writes the clipboard. *Upstream:* `TextField.readOnly(boolean)`
   — the widget already separates key handling from the document, so this is a guard in `onKey`/`onCodePoint`,
   not a redesign. This is the one gap that meaningfully hurts.
2. **No `TextField.selectedText()` / `copy()`.** Reimplemented from the public `document()` (§8). *Upstream:*
   expose both.
3. **Mono face is latin-1 only** — no box-drawing in monospace (§3). *Upstream:* widen face 1's charset to
   U+2500–257F. Until then, ASCII-only output.
4. **No cheap per-line click target** beyond `gui.onClick(node, ...)` per line. Fine, but it means a handler per
   output line if `file:line` → open-in-editor lands (T2); cap it so only lines a compiler-output matcher
   recognises get one.
5. **`--capture` renders one `Gui`.** The headless capture path takes a single tree, so a screenshot of the
   terminal window needs its own flag (§13).

None of these blocks v1.

---

## 13. Testing

The point of the layering in §15 is that **the shell is a pure library**: lexer, parser, expansion, environment,
`PATH` resolution and every builtin depend on `java.nio` and nothing GUI. That is where the tests go.

- **Unit tests (JUnit 5)** over `Lexer` / `Parser` (quoting, escapes, precedence), expansion order, glob
  matching, `PATH` / `PATHEXT` resolution, alias and macro substitution including the recursion cap, redirection
  wiring, and every builtin against a temp-dir fixture. This repo has no test scaffolding yet — add
  `junit-jupiter` and surefire, mirroring `vexelray-gui`'s root pom (JUnit 5.11.4).
- **`--script <file>` headless mode:** run shell lines against a real filesystem with stdout as the sink, no GPU
  and no window. This doubles as the end-to-end test path and as the way to reproduce a bug report.
- **`--capture-terminal [out.png]`:** `GuiApp.capture` on the terminal `Gui` with a canned scrollback, so the
  window's look is reviewable the way `text-editor.png` already is for the editor.
- **Manual matrix:** Windows (`git`, a `.cmd` on `PATH`, `PATHEXT`), Linux, macOS; a flooding command
  (`find / -name '*.java'`) for the coalescing and cap paths; a TTY-wanting program to confirm it degrades
  rather than hangs.

---

## 14. Milestones

Sizes are rough estimates for new code only.

| # | Deliverable | Acceptance | ~LOC |
| --- | --- | --- | --- |
| M0 | Terminal window: popup, own input backend, monospace scrollback with `scrollLock(BOTTOM)`, prompt, status line, Ctrl+`, history `Up`/`Down`, `echo` / `clear` / `exit` | Window opens beside the editor, both take input, typed lines echo, scrollback tails and caps | ~450 |
| M1 | Shell core: lexer, expansion, `cd` / `pwd` / env / `export`, `$?`, `;` / `&&` / `||`, T1 file builtins, `Stdio` plumbing | Unit tests green; `ls -la \| grep java \| wc -l` correct on all three OSes | ~1200 |
| M2 | External programs: `PATH` + `PATHEXT` resolution, argv exec, streamed output with per-frame coalescing, pipelines and redirection across builtins *and* processes, `Ctrl+C` interrupt, output caps | `git status` and `mvn -q compile` run and stream; a 50k-line producer neither stalls the frame loop nor exhausts nodes; `Ctrl+C` kills mid-run | ~700 |
| M3 | Configuration: rc file + `source`, aliases, macros with parameters, `path` builtin, history and placement through `Settings`, `which` / `type` | An rc file with `export`s, two aliases and a macro survives restart and reports correctly through `which` | ~600 |
| M4 | Polish: ANSI SGR → spans and `\r` rewrite, tab completion, editor integration (`edit` / `reveal` / `save`), copy-out, `--script` and `--capture-terminal` | Colourised `git` output; `edit pom.xml` opens a tab; capture PNG committed | ~700 |

M0–M2 is where the feature becomes genuinely usable; M3 is what "fully configurable" asks for; M4 is what makes
it feel finished.

---

## 15. Proposed file layout

```
src/main/java/dev/vexelray/demo/editor/
  TextEditorApp.java          + Ctrl+`, terminal wiring, request-queue entry for `edit`
  terminal/
    TerminalWindow.java       popup lifecycle, layout, prompt claims, status line   (mirrors FolderWindow)
    Scrollback.java           line ring, node recycling, per-frame batched flush, caps
    Ansi.java                 SGR subset -> Span; \r line rewrite; escape discard
    Session.java              cwd, env, aliases, macros, history, job thread, cancellation
    Shell.java                run one line: lists, pipelines, redirection, status
    Lexer.java                tokens: quoting, escapes, operators, comments
    Parser.java               tokens -> pipeline / list AST
    Expander.java             tilde, parameter, split, glob
    Environment.java          env map, PATH as List<Path>, PATHEXT probing, resolution order
    Rc.java                   rc file load/save, Settings integration
    Exec.java                 ProcessBuilder launch, stream pumps, destroy
    Stdio.java                line-oriented in/out/err so builtins and processes compose
    Command.java              interface Command { int run(List<String> argv, Stdio io, Session s) }
    Builtins.java             name -> Command registry
    builtin/*.java            one per command family (Ls, Grep, Find, ...)
```

The one abstraction that matters is `Command` + `Stdio`: a builtin that reads `io.in()` and writes `io.out()` is
indistinguishable from a child process in a pipeline, which is what keeps `Shell` from growing a special case
per stage kind. Everything under `terminal/` except `TerminalWindow` and `Scrollback` is GUI-free, and therefore
unit-testable without a GPU.

---

## 16. Decisions needed before M1

1. **External programs — in or out for v1?** Everything about `PATH` only earns its keep if `git`, `mvn` and
   `node` can run. Recommendation: **in**, at M2 — it is where most of the risk lives and most of the value.
2. **`sed` / `awk`.** A subset invites "why doesn't `-E` work with `\1`". Recommendation: `sed` substitute-only
   at T2, no `awk`; `grep -E`, `cut` and `tr` cover the common cases.
3. **Per-workspace rc file.** Convenient, and a script sourced from a folder you merely opened. Recommendation:
   T2 behind explicit per-folder confirmation, or skip.
4. **Read-only selectable scrollback.** The small `TextField.readOnly` addition in `vexelray-gui` (right fix,
   touches the framework), or the copy-builtin workaround (contained, worse UX)?
5. **The `rm -r` root/home guardrail** — keep it, or match unix exactly and let the user own the consequences?
