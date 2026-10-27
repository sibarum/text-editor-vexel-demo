# VexelRay IDE

A tabbed text editor with a file navigator, on the VexelRay stack.

**Install it** on Windows, with the other VexelRay applications, from [sibarum.github.io](https://sibarum.github.io/).

Generated from `vexelray-framework`'s `vexel-desktop` template and built up from there, so the application edge —
input, the clipboard, window memory, the frame loop, the dialogs, the command line and shutdown — is the
framework's, and every file here is about editing text. It replaces an earlier editor in this repository that was
deleted rather than repaired; the grammars, the file-loading policy and the highlighting tables were carried over
from its history, and the rest was written fresh. [docs/framework-notes.md](docs/framework-notes.md) is what
rebuilding it found out about the framework, and [docs/retrospective.md](docs/retrospective.md) is the pass over those
notes at the end of each milestone.

## Run it

```
mvn compile exec:exec
```

With paths, to open them (a folder becomes the navigator's root):

```
mvn compile exec:exec "-Dapp.args=C:/work/project C:/work/project/notes.md"
```

Files alone, with no folder among them, make a **plain window**: the tabs and nothing beside them, the navigator
gone and the editor the whole width. Opening more files keeps it plain. A folder opened in it — the folder dialog
(Ctrl+Shift+O) — brings the navigator back on that folder, at the width it had, with the files still open beside
it; so does Ctrl+B, or Alt+N for the scratches.

Started with no paths, the window comes back where you left it, with the folder and the files you had open, the one
in front still in front. One started with paths opens just those and remembers nothing, a folder opened in it later
included, so it never overwrites that session. Unsaved work is never remembered — closing the window asks about it
first.

```
mvn test                                                  # the editor alone, the jobs, grammars, and the real tree headless
mvn compile exec:exec -Dautomation=0                      # with a driving socket (see Taking a screenshot)
mvn -Plarge-file-bench test                               # the large-file benchmark (see below); slow, off by default
```

## Using it

| Keys | Does |
| --- | --- |
| Ctrl+N | new document |
| Ctrl+Shift+N / Alt+N | new scratch / the scratches over the navigator, or back (see Scratches) |
| Ctrl+O / Ctrl+Shift+O | open a file / open a folder in the navigator |
| Ctrl+S / Ctrl+Shift+S | save / save as |
| Ctrl+W | close the tab (asks if it is unsaved) |
| Ctrl+Tab, Ctrl+PageDown / Ctrl+Shift+Tab, Ctrl+PageUp | next / previous tab |
| Ctrl+B | show or hide the navigator (in a plain window, show it) |
| Ctrl+Shift+E | put the keyboard in the navigator |
| Ctrl+Shift+G | Git mode, inside a repository, or back (see Git) |
| Alt+Z | word wrap on or off |
| Ctrl+Shift+V | a Markdown document formatted, or back to its source (the font button over the tabs, there only for Markdown, does the same) |
| Ctrl+Enter | go to the declaration of the name under the caret (Java), the project's or a library's (shown read-only, decompiled if it has no sources); again for the next one |
| Ctrl+F | find in the document (Enter / Shift+Enter step, Escape closes) |
| Ctrl+Z / Ctrl+Y | undo / redo |
| F5 | the Run menu (see Running things) |
| Ctrl+= / Ctrl+- / Ctrl+0 | zoom |

In the text, the editing is the framework's `TextField` and works the way editors do: double-click selects a
word and triple-click a line, and holding the button after either and dragging extends by whole words or whole
lines; Shift+click extends the selection. Home goes to where a line's indentation ends and then to the margin,
Enter keeps the indentation, and Tab over several lines indents them. Ctrl+Insert, Shift+Insert and Shift+Delete
copy, paste and cut alongside Ctrl+C, V and X.

Whitespace that matters shows as a faint dot: indentation, what trails a line, and any run of two or more blanks —
not the single space between words. A file whose newlines are not this system's (LF on Windows, CRLF elsewhere)
ends each line with a faint `¬`, since that is how it will be saved.

Selecting a file in the navigator opens it — a click, or walking the tree with the arrow keys, which keep the
keyboard in the tree so the walk carries on. Enter opens a folder. A folder's menu can make it the root; every
row's menu can copy its path and has *Open in Vexplore*, and a folder's also has *Open in MainFrame*, which starts
`mainframe --dir=<folder>`, a MainFrame window with a shell there (greyed out as *MainFrame not installed* without
its install record). The root's name at the top has the same folder menu for the root, and so does every folder in
Open. `.git`, `target`, `node_modules` and a few others are left out of the listing.

Every row's menu, and the root's name, has **Add**, which makes something in that folder (a file's row: beside it):

| Add | Makes |
| --- | --- |
| Folder | an empty folder |
| File › Blank, Text, Java, Markdown, PowerShell, Bash, Zsh | a file of that type, opened in a tab: Java is a class named for the file, in the package its folder is under `src/main/java`; Markdown starts with a heading; Bash and Zsh with their `#!` line, and executable where the platform has the bit |
| Symlink | a link to a file picked in the platform's dialog (where links need no privilege: Linux and macOS; greyed out on Windows) |
| Project | a Maven project, its `pom.xml` opened (offered where there is no `pom.xml` at the folder or above it) |
| Submodule | a module of the project around the folder: its own `pom.xml` naming that project as its parent, and a `<module>` line in the parent's (offered where there is one; the status line says so when the parent is not packaged as `pom` yet) |
| Archive | a `.zip` of everything in the folder (offered once there is a file in it) |

Each asks for a name first, in a field over the tree, starting from one it suggests with all but the extension
selected; Enter makes it, Escape asks nothing. What is made is listed and shown in the tree.

The navigator has two modes. The root's name at its top is a toggle: pressed, the navigator is in **Open**, for
picking a root; released, it is in **Edit**, the files under the root, as above. A window with nothing to show
starts in Open, and a root arriving from the folder dialog or the command line goes back to Edit.

Open is a different place and looks it: the panel is tinted and edged in the accent, and a line sweeps down it on
the way in. It shows folders only: the last few roots under *Recent*, then the folder above the root with the root
selected among its siblings. Over that list a breadcrumb climbs: its last segment is the folder the list starts
from, and a click on the one before it (or on `…`, two up) lists from there instead, as far as the top of the
drive, with the root still unfolded and selected underneath. A folder under the pointer lights strongly and takes
the hand; a click on it (or Enter) makes it the root and goes back to Edit. The arrow keys only move, so walking
the folders does not change the root as it goes. The root you leave becomes the first recent one, and Recent is
remembered between runs.

While Open is up the editor steps aside for a picture of the folder under the pointer (or, with the pointer
elsewhere, of the selected one), drawn as it is read so a large build fills in rather than keeping you waiting: its
Maven packaging and artifactId, how many modules it gathers, whether it is itself a module of a bigger build (`in
vexelray-gui`: that one is the likelier root), whether a git repository starts there, a bar of its languages by
file count, how many Java files, classes, records, interfaces and enums it holds, and its modules and most-used
annotations as bars against each other. The look is bounded (`Survey`: 6,000 files, 900 Java files read), so a
home folder answers as quickly as a module.

A folder with no pom and fewer than a dozen Java files has no build to describe, so it is pictured as **blocks**
instead, in 3D: every file a cube whose volume is its size (its side is the cube root of its bytes), every folder a
square plate its contents stand on, so the folders inside it rise from it a level at a time. Files are coloured by
their family, as the suite's file icons are, and the larger blocks whose tops can be seen carry their icon. The
view is a long lens from above at three quarters, close to orthographic, so sizes compare across the picture. It
is drawn with the engine's polygon meshes (`BlockView`, through `vexelray-technique-mesh`), only when the folder
or the size of the panel changes. The walk is bounded too (`Blocks`: 1,500 files, eight folders deep).

**Scratches** are files that belong to no project: a note, a pasted log, a snippet being worked out, not to be
deleted and with nowhere in particular to save it. They live in the editor's own folder, beside its settings
(`~/.text-editor/scratches`, or under `-Dtext-editor.home`). Ctrl+Shift+N makes one, named for the minute
(`2026-10-19-1432.md`; Markdown, so it has the Formatted view) and open in front; it is an ordinary file from
then on and Ctrl+S saves it. One closed with nothing ever written to it is taken away again. Closing an Untitled
document with unsaved work offers *Keep as scratch* beside Save and Don't save.

Alt+N lays the scratches over the navigator without leaving the root: the panel is edged in the accent and its
heading says *Scratches*, a file picked there opens in a tab as any other does, and Alt+N again (or the heading)
puts the root's files back. Over the tree is *New scratch*; a row's menu has *New scratch here*, *New folder
here* and *Rename…*, and a folder's has *Open in Vexplore* too. The scratches are organised in that tree: drag a
scratch or a folder onto a folder, or between the rows of one, to move it there (or Ctrl+X, then Ctrl+V on the
folder), and *Rename…* opens a name field above the tree, Enter to rename and Escape to leave it. A move or a
rename is refused before it happens when the name is taken, and Ctrl+Z puts it back. A tab open on the file, or
on anything in a moved folder, follows it, unsaved work and all, and a new extension gives it its new language.
Once there is at least one scratch, Open lists **Scratches** under Recent, to make the folder the root like any
project.

Inside a git repository the status line starts with where the root stands, `develop ↑8 · 10 changed`, and a chip
beside the root's name says the branch. Pressing it (or Ctrl+Shift+G) puts the navigator in **Git mode**: the
branch and what it tracks, then *Unstaged* (changed, deleted, untracked, conflicted) and *Staged*, each row with
its letter, its folder and the lines it adds and removes. A row picked, by a click or the arrows, stands its diff
where the editor was: the old and new line numbers, added lines on green and removed on red, under each hunk's
header. Enter or a double click on a line opens the file there (a removed line, where it was); on a row, it opens
the file. *Close* or Escape brings the editor back, as leaving Git mode does. While Git mode is up a commit or a
checkout made at a prompt shows within two seconds, the diff on show with it; a file changed by another program
shows after *Refresh*.

A row's **+** stages it and **−** unstages it; *Stage all* and *Unstage all* sit beside the lists' headings, and the
diff has *Stage* or *Unstage* at its top, the diff following the file to its new side. Staging saves the tabs it
covers first, as Run does, so what is staged is what is on the screen; a save that fails stages nothing. A
conflicted file has no **+**: staging it would mark it resolved, markers and all, so that is done at a prompt. What
git refuses (an `index.lock` left by another git, say) is said on the status line in git's words. Committing and
history are the next slices of [docs/git-mode.md](docs/git-mode.md).

A tab's menu has Close, Close others, Close all, Reveal in navigator, Open in Vexplore and Copy path. *Open in
Vexplore* starts `vexplore <file>`, a new Vexplore window on the file's folder with the file selected (a folder in
the navigator opens as itself); it is greyed out as *Vexplore not installed* when the install record is missing. A dot in front of a tab's name
means it has unsaved changes. Closing anything with unsaved work asks — Save, Don't save, Cancel — and a save that
fails, or a Save As that is cancelled, cancels whatever was waiting on it, quitting included.

**Preferences** are behind the gear in the title bar, beside the project's name:

- **Theme**: Dark (the default) or Light, a soft off-white page with darker code colours, for bright or artificial
  light. VexelRay IDE is the only application in the suite with a light theme. The look is chosen when the window
  is built, so a change takes effect at the next start, and the menu says so until then.
- **Word wrap** (also Alt+Z) and **Index Maven projects**. With indexing off, opening a Maven project builds no
  index, and Ctrl+Enter looks only in the file in front.
- **Keep for this project, in .vex**: the root's word wrap and indexing are kept in `<root>/.vex/preferences.properties`,
  started from the values in force, and used whenever that folder is the root; every other folder keeps the
  editor's own (in `~/.text-editor/settings.properties`). Unticking it takes the file away again. The theme is
  always the editor's.
- **Add .vex to .gitignore** (on by default): making a `.vex` folder in a git working tree adds `.vex/` to the root's
  `.gitignore`, creating it if need be, unless a line already ignores it.

To a script, the gear is the landmark `preferences`.

Sixteen formats are highlighted, by extension or by name (`Dockerfile`, `.bashrc`): Java, Python, JavaScript,
JSON and JSONC, Markdown, HTML, CSS, XML, YAML, INI and `.properties`, shell, PowerShell, batch, diff and
Dockerfile. A recognised format is shown in the mono face; anything else is plain text in the UI face.

Files are opened defensively (`TextFile`): UTF-8, or UTF-16 behind its BOM, up to 8 MB with no line over 100,000
characters, and no control characters — anything else is refused with the reason rather than opened and
corrupted on save. Line endings are kept: a CRLF file saves as CRLF. Tabs become four spaces, because the field's
document is soft-tab only, and the status line says so when it happens. A save writes beside the file and moves
over it, so a failure leaves the old file whole.

## Running things

The ▶ button over the tabs (or F5) opens a menu of what can be run for the folder in the navigator and the
document in front (`Runs`):

- **Run *Class*.main()**, when the document is a Java file with a `main` method: through Maven
  (`mvn -q compile exec:java -Dexec.mainClass=…`, on the test class path for a file under `src/test`) when the file
  is in a Maven module, so its dependencies are there, and with the JDK's source launcher (`java File.java`)
  otherwise.
- For a Maven project (the folder's `pom.xml`, or else the module the document is in): **Compile**, **Clean
  Install**, **Package** unless the packaging is `pom`, **Execute** (`mvn compile exec:exec`) when the pom configures
  exec-maven-plugin's `exec` goal, and **Run Recent Build** when `target/` holds the newest executable or runnable
  jar.
- On Windows, the programs in the project, four folders deep at most and without the folders the navigator leaves
  out: executables that really are ones, PowerShell scripts and batch files. A script in front is offered first.

Before anything runs, every document with a file and unsaved work is saved, since what runs is what is on disk.

Runs are shown in the **console**, a window of its own in a neutral grey with one tab per run, the newest in front;
several can run at once. Its first line is the command and the folder it ran in, and its last says how it ended:
*Finished*, *Failed* with the exit code, or *Stopped*. On Windows a run has a real console (a pseudoconsole, the
same terminal MainFrame draws, from vex-suite-common's `tty`), so Maven colours its output and a program that asks a
question can be answered by typing. Ctrl+C copies a selection and otherwise interrupts the run; a tab's menu has
Stop, Run again, Copy output and Close. When a run ends its program and console are gone, but the tab keeps the
output, to scroll, select and copy.

The console has no new-tab button: tabs come from the Run menu. Finished runs close by themselves according to the
settings button in its title bar: keep the last 1, 3, 5 (the default), 10 or all of them, and close each after 5
minutes, 30 (the default), 2 hours or never. The tab in front is never closed under you, nor a run still going.
Closing the console window asks before stopping anything still running, and clears it.

To a script, the button is the landmark `run`, the console is `ottermate --window console` (or `window console`),
its tabs `console.tabs` and its settings button `console.settings`.

## How it is put together

Two modules under this folder, split where the dependency edge falls, as the `vexel-desktop` template is: a
**controller** that decides and builds against no widget, and a **view** that shows. They talk by messages and nothing
else, and every message is in one file. Three kinds of part, none of which holds another: **components** (a class
with a lane of its own and mailboxes, reached only by messages), **jobs** (slow work on the offload lane, a request
in and a reply out), and **recipes** (a part built once and handed to whoever takes it).

**`text-editor-vexel-demo-core`, the controller.** Its tests need no window.

| File | What it is |
| --- | --- |
| `Messages.java` | **every message the parts send each other**, grouped by who sends it to whom, each record beside its topic. A record crosses as itself, so it holds only immutable values and no lists |
| `Editor.java` | **the controller**, a component on the `editor` lane: every decision. What is open and which tab is in front, when closing, quitting, picking a root or running needs a question first, where an Untitled document is saved, which tabs each root keeps, what the status line and the title bar say, what comes back next time. It owns the session and needs no lock, because nothing else can reach it |
| `Doc.java` | the shape of the session — which documents, which in front, which unsaved, which folder — as one immutable value in one field of the editor's |
| `Repository.java` | **the controller for git**, a component on the `git` lane: follows the root the editor shows, asks git where it stands when the root changes and after the editor writes or makes something, one question at a time, and tells the view what the status line's Git slot says (`develop ↑8 · 10 changed`) and every changed path, as git's own text. While Git mode is up it looks every 2 s whether git did anything, and asks only when it did ([docs/git-mode.md](docs/git-mode.md)) |
| `ReadFile.java`, `WriteFile.java`, `FindPath.java`, `MakeScratch.java`, `NameScratch.java`, `DropScratch.java`, `Make.java`, `ReadStatus.java`, `ReadStamp.java`, `ReadDiff.java`, `RunGit.java` | the jobs: every touch of the disk, and every run of git, the controllers ask for, on the offload lane. What can go wrong is part of the reply |
| `Core.java` | the list of this module's components and jobs. The processor compiling `-gui` sees only `-gui`'s sources, so this list, named among `TextEditorApp`'s starters, is how the wiring hears of them. A new component or job goes on it |
| `TextFile.java` | the policy between bytes on disk and text in a field |
| `Markdown.java` | Markdown as blocks and inlines, for the formatted view: a pure function |
| `Scratches.java` | the scratches' folder, as a value: making a scratch, moving and renaming one, and taking one away |
| `Preferences.java` | the theme, word wrap and indexing: the editor's own, or a root's own in its `.vex` |
| `ProjectIndex.java` | Concordance's index of the open folder and its libraries, on a thread of its own |

**`text-editor-vexel-demo-gui`, the view and the framework integration.**

| File | What it is |
| --- | --- |
| `Recipes.java` | **what this module builds**, one `@Provides` method per part. `TextEditorAppWiring` is generated from it at compile time, and each part's phase is inferred from what it takes |
| `View.java` | **the view**, a component on the `view` lane: the editor's messages shown (`AddTab` is a tab, `Ask` is a dialog) and the person's gestures told (`Open`, `Closing`, `Save`). What only the window knows stays here: the preferences menu, the Run menu, the formatted view, going to a declaration |
| `Intent.java` | what a chord or a menu item asks of the view, sent from the thread it arrived on to the view's lane |
| `Gates.java` | the window's close gate and the navigator's "may I go to Open", as messages: their two callbacks kept under a ticket, the ticket sent |
| `Ui.java` | the window: title bar, navigator beside the tabs, status line. Holds no state; it shows what the view hands it |
| `Workspace.java` | the Run button, the tab bar and one `Buffer` per tab, kept in step |
| `Buffer.java` | one document: its `TextField`, its highlighter, its path |
| `Runs.java` | what the Run menu offers for a folder and a document, worked out from what is on disk |
| `Console.java`, `Job.java` | the console window and its tabs, and one run in it: a program on a pseudoconsole, its output kept after it ends |
| `Navigator.java`, `FolderSource.java`, `RootSource.java` | the file tree and, in Open, the folders a root is picked from, over the disk, read lazily; Add, on a folder's menu |
| `Preview.java`, `Survey.java` | what stands in the editor's place in Open: the folder under the pointer, surveyed for clues that it is a project's root |
| `GitPane.java` | Git mode's card: the branch, then Unstaged and Staged, read from the git text the repository sends |
| `GitStage.java` | what stands where the editor does while a Git mode row is picked: its diff, a line of which opens the file there |
| `Highlighter.java` | TextMate grammars (TM4E) to colour spans, off the GUI thread |
| `Motion.java` | the one tempo: the ramps the tab bar, the tree and the cues move with, on the framework's clock |
| `Look.java`, `Type.java`, `Landmarks.java` | colour, size, and the names an automation script may use |

The text of each document lives in its `TextField`'s own versioned `State<Document>`; `Doc` holds only what
nothing else owns. Which phase each part lands in is visible in
`text-editor-vexel-demo-gui/target/generated-sources/annotations/.../TextEditorAppWiring.java` after a build.

### Threads, in one paragraph

The GUI loop runs on the main thread, which owns Vulkan, the window and present, and nothing of the editor's runs
there. The editor has a lane of its own and so does the view; each handles one message at a time, and each owns its
state outright, so the session needs no lock. Input arrives on the handler lane and is only told: a key or a menu item
becomes an `Intent` for the view, or a message straight to the editor. Every read and write the editor asks for is a
job on the offload lane, so a slow disk is a slow reply and never a frozen window or a stalled lane. **Each mailbox is
in order, and two mailboxes are not in order with each other**: so a set of tabs being closed (or quit, or settled
before Open, or switched away from with a root, or saved before a run) is one message per tab, each saying how many
make the set and whether its tab is unsaved, and the editor decides when the last one arrives. **Jobs are in no order
at all**: so a restored session, and the tabs a root gets back, ask for one file at a time. The file dialogs are the
framework's: on Windows they run on a dialog thread of their own, and only on macOS, where AppKit insists, on the frame
loop.

The tab bar keeps one rule from before the split, written out on `Workspace`: its order and its buffers change
together under one lock, and the buffers' own listeners (dirty, caret) never take it.

## The stack

Installed locally rather than downloaded, so `mvn install` in each sibling has to have happened at least once.

| What | Why it is here |
| --- | --- |
| `vexelray-framework-shell`, `-automation`, `-processor` | the application edge, the driving socket, and the processor that writes the wiring |
| `vexelray-gui-widget`, `-krono`, `-automation` | the widgets (`Tabs`, `TreeView`, `TextField`, `SplitPane`, `StatusBar`), the clock, the driver |
| `vexelray-gui-nfd` | the OS's file dialogs |
| `vexelray-gui-file-icons` | the file-type family icons and their colours, on the navigator's rows and Open's blocks |
| `vexelray-technique-mesh`, `vexelray-engine-embedded` (repo `vexelray`) | Open's blocks: polygon meshes through the rasteriser, composited into a viewport the GUI samples |
| `vex-suite-common` (its `tty` package; the picture decoder excluded) | the console's terminal, shared with MainFrame |
| `vex-suite-common-git` (in `-core`) | git, run as a process, and its answers read into records; its own module, with no widget, so `-core` still builds against none |
| `org.netbeans.external:org.eclipse.tm4e.core-0.14.0` + `gson`, `joni`, `snakeyaml-engine` | the TextMate tokenizer and the runtime dependencies it does not declare |
| `tactroller-atchung`, `tactroller-clipboard` | input onto the bus, and the OS clipboard |
| `vexelray-os-*`, `tactroller-*` | picked by an OS-activated profile |

The grammars under `text-editor-vexel-demo-gui/src/main/resources/grammars` are from microsoft/vscode (MIT, `VSCODE-LICENSE.txt` beside
them). CSS and YAML are pinned to vscode 1.96.0 for reasons `Highlighter.loadGrammars` gives, and
`GrammarBundleTest` fails if either is re-synced from main.

## Automation

The editor can be driven and read from outside, by a script, a macro or an agent, through the framework's two loopback
sockets: **remote control** (the keys, clicks and reads a person has) and **diagnostics** (the flight recorder, the
log, the Inspector). Both are in every build, the release one too, and both start off.

- **Switched on by hand**: the plug in the title bar opens the switches, one per socket, each with a port (blank for
  a free one).
- **Switched on at launch**, for an agent that means to: `--automation=0` (both, on a free port),
  `--automation.control=<port>`, `--automation.diagnostics=<port>`, or `VEXELRAY_AUTOMATION=0` in the environment.
- **Not there at all**: `--automation.available=none` (or `control`, or `diagnostics`), or
  `VEXELRAY_AUTOMATION_AVAILABLE` in the environment. A socket that is not available cannot be switched on by
  anything, and with neither available the plug is gone.

Whatever turned it on, **the plug's lamps say what each socket is doing**, remote control's (red) left of the plug and
diagnostics' (blue) right:

| Lamp | Means |
| --- | --- |
| none | off, and nothing has connected to it this session |
| ring | open, and nothing has connected yet |
| filled ring | open, and something is connected now |
| ring with a grey dot | open; something was connected and has gone |
| grey dot | off, after something had connected to it this session |

The plug's tooltip and the switches' window say the same in words, with the port and how many are connected.

## Taking a screenshot

Screenshots are `ottermate`'s: it photographs the running window on the application's own device.

```
ottermate shot out.png --launch mvn.cmd compile exec:exec -Dautomation=0
```

`ottermate --size 36emx20em shot smallest.png --launch ...` is the window at its minimum. Clicks, typing, menus and
chords (`key CTRL+S`) can all be scripted; `key` learned chords after framework-notes FN-3 was written.

## Large-file benchmark

```
mvn -Plarge-file-bench test
```

How the editor copes with large and pathological files: 8 MB of source, 100 MB and 1 GB of log lines, 10 M empty
lines, one line of 1 M characters, mixed CRLF/LF, and many tabs. For each it times the open, the first frame, a caret
move, a typed character and a jump to the end, and the heap the open file keeps, and writes the table to
`text-editor-vexel-demo-gui/target/large-file-bench.md`. The fixtures (~1.2 GB) are generated into `target/large-files` on the first run and
reused after; a file `TextFile` refuses is reported as refused and then opened past the limit anyway, so the table
shows what the limit protects against. Each fixture runs in a JVM of its own, so one that runs out of memory or
times out costs only its row. `-Dbench.only=log-100mb.log,...` picks fixtures, `-Dbench.xmx=8g` sets the heap and
`-Dbench.timeout=10` the minutes per fixture. Plain `mvn test` skips it.

Those times are layout only: the frames are headless, so they build no glyph quads and draw nothing. A second
table, *Drawn frame*, has the rest. For each step it times the CPU half of drawing, the settled frame's tree
emitted into a canvas as the window's renderer emits it, and counts the vertices that produced. Then it draws one
real frame on the GPU through `GuiApp.capture`'s offscreen path, beside the same capture of the empty editor, and
leaves the picture at `target/large-files/<fixture>.png`. The same command runs both tables, and the GPU frame needs
a Vulkan device; `-Dbench.gpu=false` skips it on a machine without one.

## Logging

The framework configures it: info to the console and debug to `text-editor-vexel-demo-gui/target/logs/text-editor.log` from a checkout,
more under automation. This application logs as `editor.files`, `editor.highlight`, `editor.index` and `editor.suite`;
`-Dlog.level.editor.files=debug` raises one of them.

## Native build and installer

`mvn -Pnative-release package` builds `text-editor-vexel-demo-gui/target/text-editor.exe` (GUI subsystem; what ships) and `mvn -Pnative package` builds `text-editor-vexel-demo-gui/target/text-editor-debug.exe` (console, drivable by ottermate) (Windows, GraalVM 25, from a Visual Studio developer
prompt); see [docs/native-build.md](docs/native-build.md). `installer.json` describes the per-user installer
for it (`vexelray-installer`), with `text-editor` added to `PATH`: `text-editor <folder or files...>` opens a new
window, a folder as the navigator's root and files as tabs (files alone, a plain window). It also registers the editor for plain-text and
highlighted file types (`.txt`, `.log`, `.md`, `.json`, `.xml`, `.yaml`, sources, ...): always under **Open with**,
and the default only where no other program has claimed the type. Types Windows runs (`.js`, `.ps1`, `.bat`,
`.cmd`, ...) are highlighted but never associated; the installer refuses them.
