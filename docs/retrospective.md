# Retrospective

What each milestone of **VexelRay IDE** showed when it was read back: the shapes in
[framework-notes.md](framework-notes.md) since the last one, what would stop each, what went wrong in how the work
was done, and what to keep. The notes are written one at a time, as each workaround is written. This file is the pass
over them, and it is where the framework takes its proposals from.

**When.** At the end of every milestone, before the next one starts, and always before a rebuild or an archive. A
milestone with no new findings gets one line saying so. The framework's `docs/developers/retrospective.md` has the
reasons and the full method.

**Never delete a section.** Newest milestone first. A rebuild carries this file over.

## How to write one

    ## Milestone name · 2026-10-22
    
    The count: N findings (FN-a to FN-b), how many of them architectural.
    
    ### The shapes
    | | The promise | What happened | How it looked |
    
    ### What would stop each
    1. One structural change per shape, numbered so a plan can cite it.
    
    ### My own mistakes
    What was trusted, what it turned out to be, and the rule that covers it.
    
    ### What to keep exactly as it is
    What carried the milestone, and why.

## Milestones

## Welcome · 2026-11-20

The count: 1 finding (FN-19), architectural, and one gap fixed upstream instead of noted: a main window that starts
hidden (`@VexelApp(startsHidden)`, vexelray-framework `31cb143`). FN-16 to FN-18 are older and not read back here. Six
slices, each proposed before its messages: Welcome with Recent and the main window hidden until a choice; the home
folder's git repositories (`ScanRepos`, the application's first `@Source`); the JDK and Maven found and chosen
(`Toolchains`); Run's Maven and `java` lines using them (`Runs.withTools`); Welcome… from the View menu; and New file,
an untitled scratch whose first save goes through the native Save dialog, the application's first picker.

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| A script does what a person does | ottermate reaches everything on screen through the trees and the landmarks | The two native parts of a window, the Save dialog and the close box, are in no tree | New file's save and closing Welcome are held only by `EditorTest`; the live checks stop short of both (FN-19, TODO) |
| A background run leaves the desk alone | A run the agent starts is cloaked, never focused, deaf to the real keyboard | A native picker opened by that run is the OS's window, in front, with the keyboard | Ctrl+S could not be pressed in a check at all (FN-19) |
| Waiting on a person inside a chain | A closing holds for the person's answer as long as it takes (`holdForPerson`) | Its saves are held only the tick's default bound, and an untitled save is now a dialog | Save at the close question, with the dialog left open, ends the close as late (TODO) |
| What ran is the console's first line | The line itself carries the environment, so the first line is the whole of it | The line is written wrapped at the terminal's first width, and nothing scrolls back to it | With the chosen JDK and Maven in front, a 960x600 console shows only its tail (TODO) |

### What would stop each

1. Under automation, `PickFile` answered from the driving socket (an ottermate `pick <path>` / `pick cancel` for the
   next request), and a `close` verb for the chosen window's close box (FN-19). Both of this milestone's untested paths
   would then have a script.
2. A background run never opens a native picker: it answers a cancel and logs why (FN-19).
3. A closing's wait for a save that is itself waiting on the person held as the question is, for the person rather
   than the tick's bound. Either the chain learns that a save can be a question, or the editor asks the dialog before
   the closing holds its saves.
4. The console's terminal re-wraps what it holds when it is resized, and keeps a scrollback, so the first line is
   never lost to the first width.

### My own mistakes

- **I ran a check against the person's real settings.** Choosing a folder in Welcome rewrote the saved session, and a
  background-run bug (below) flipped `window.main.maximized`. Rebuilt by hand from what the screenshots showed, and
  confirmed by restoring it. Rule: a check that changes what is open runs in a throwaway home
  (`-Dapp.jvmArgs=-Dtext-editor-vexel-demo.home=...`), kept as a memory.
- **I trusted that maximizing a hidden window kept it hidden.** `SW_MAXIMIZE` puts it on screen behind the platform
  window's back, and the show after it (`SW_SHOWNOACTIVATE` in a background run) restored it to normal size. Tests
  passed; only the live check and the settings file showed it. Rule: a window-state change is checked in a real run,
  and in the settings it leaves.
- **Regex edits on files with backslashes and quotes again**, after the last milestone's rule said not to: a
  `settings.properties` line came out unescaped and a Java string came out broken. Caught each time, at the cost of a
  rerun. The rule stands; I did not keep it.
- **Three slips from not reading the API first**: `EditorTest`'s `took` clears everything sent, so a second `took` saw
  nothing (twice); `ListView.order()` is an `Order`, not a list; a variable named `java` hid the `java.util` package.
- **A `git stash` to compare against the last commit** left the new untracked files behind, so the stashed tree could
  not build and proved nothing. The question (was a warning new?) was answered by an older log's date. Rule: look for
  evidence that exists before rebuilding a past state.
- **A test run failed when another process reinstalled the whole stack under it.** Not a mistake in the code, but I
  read it correctly only because a memory said what a mid-run jar swap looks like. Rule kept: a `NoClassDefFoundError`
  from a stack jar means check the local repository's timestamps, then wait for it to go quiet.

### What to keep exactly as it is

- **The slow, disk-bound logic as plain classes with what they look at handed in** (`Repositories`, `Toolchains`),
  tested against folders made in a temp directory; the job or source around each is a few lines.
- **The editor decides, the view shows.** Which of the session comes back, what closing Welcome means, and whether a
  save needs a dialog are all steps on `Editor.State`; `WelcomeView` only shows and tells.
- **Reusing the scratch machinery for the untitled file.** A real file in a hidden folder gave the tab its title and
  the closed-empty cleanup for nothing, and `Doc` kept its shape.
- **The environment in the Run line**, so the console shows exactly what ran with the chosen JDK and Maven, which is
  also how the live check proved it.
- **Fixing the framework where the gap was** (`startsHidden`, three files and a test) rather than hiding the main
  window a frame late.

## Pontif, milestone 1 · 2026-11-05

The count: 4 findings (FN-12 to FN-15), two of them architectural (FN-12 and FN-15, both about matching an answer to
slow work with the question it answers). FN-1 to FN-11 came from earlier work that has no section here, and are not
read back in this one. Four slices, each proposed and approved before its messages were written: highlighting (Pontif's
own token scan), Run a `.ptf` or its project (`pontif run`), checking as you type (`CheckText`), and Ctrl+Enter's
navigate-or-import (`NavigateText`). On the Pontif side, `pontif-compiler` and `pontif-tooling` were split out so the IDE
carries no Truffle and no toolkit, and the Pontif Editor's error placement became `PontifProblems`, shared.

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| Work as you type | A job is slow work as a declaration: a request in, a reply out | Compiling as you type wants the latest request, one at a time; a job runs every request, stale or not | `Buffer` holds one check in flight by hand and sends the next from the answer (FN-12) |
| Which text an answer is about | A reply names the version it was worked out from, and the view drops a stale one | A field's version moves on every commit, caret moves and spans included, so "stale" was wrong both ways | Answers dropped when the caret moved, a compile per arrow key, and an import refused by Ctrl+Enter's own caret move (FN-15) |
| Marking code | A span carries colour, ground and underline | An underline takes the text's colour, so a problem cannot be red under coloured code | An ink underline on a faint `DANGER` ground, the ground doubling as the tag that tells problem spans from colours (FN-13) |
| A choice at the caret | Menus are declared, and walk from the keyboard | A menu opens only at a pointer click | Several exporters of a name are listed on the status line, and nothing is imported (FN-14) |

### What would stop each

1. A job request that coalesces per key, latest wins and at most one running (FN-12). The buffer would then keep no
   bookkeeping for checks at all.
2. A text version on a field, moving only when the text does (FN-15). With 1, an answer is current by construction.
3. An underline colour and style on `Span` (FN-13): a problem becomes one span laid over the colours, and the buffer's
   "two kinds in one list" partition goes.
4. A menu anchored at a node and an offset in it, opened by a chord (FN-14): the chooser, completion and quick fixes.

### My own mistakes

- **I keyed on `TextField.Changed`'s version as if it named the text**, in slice 3, because `MarkText` already did. It
  is the document's version. A ViewTest moving the caret before Ctrl+Enter showed it in slice 4, and the same fault had
  been costing a compile per arrow key and dropping colours since before this milestone. Rule: before keying on a
  value from the stack, read what bumps it.
- **Slice 3's native check took the shortest happy path.** Its file had no `requires`, so it never loaded the built-in
  modules' sources, which no Pontif native image registered (not the CLI's, not the playground's). Slice 4's check hit
  it, and `CheckText`'s catch missed `ExceptionInInitializerError`, so the IDE stopped. Fixed upstream with
  `pontif-compiler`'s own `reachability-metadata.json`, and the jobs now catch `LinkageError`. Rules: a native check
  goes through every path that loads a resource or initialises a class; a job wrapping another project's code catches
  `LinkageError` too.
- **Positions crossed from disk to a field uncounted.** The navigator counted offsets in source with `\r\n`; the editor
  shows `\n`. Rule: a position is converted where it crosses that boundary (`NavigateText.shown`).
- **Regex edits on CRLF files with `|` as the delimiter** broke the README's `mvn test` line and put a method body at
  the top of `Buffer`. Both were caught before a commit. Rule: the Edit tool for any replacement with punctuation in it.

### What to keep exactly as it is

- **Pontif's editor logic lives in `pontif-tooling`** (`PontifTokens`, `DefinitionNavigator`, `PontifProblems`), shared
  with the Pontif Editor. Each slice was mostly plumbing, and the two editors cannot disagree about where an error or a
  definition is.
- **Messages named for the feature, not the language** (`Check`, `Navigate`), with the language decided in the job.
  Another checker is a new branch in a job, not a new message.
- **Proposing before touching `Messages`.** Both proposals were built as approved; neither needed `Doc`, `Recipes` or a
  lane, because problems and places belong to a version of the text, as the colours do.
- **A reply with kinds hands itself to a sink** (`Navigated.Sink`, after `Picture.Sink`): no switch, and a new kind is a
  compile error in every sink until it is handled.
- **ottermate on the native exe, with the files given on the command line**: it leaves the person's session alone, and
  it is the check that found the missing resources.
