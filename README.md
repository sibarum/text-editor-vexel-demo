# Vex

A tabbed text editor with a file navigator, on the VexelRay stack.

**Install it** on Windows, with the other VexelRay applications, from [sibarum.github.io](https://sibarum.github.io/).

Generated from `vexelray-framework`'s `vexel-desktop` template and built up from there, so the application edge —
input, the clipboard, window memory, the frame loop, the dialogs, the command line and shutdown — is the
framework's, and every file here is about editing text. It replaces an earlier editor in this repository that was
deleted rather than repaired; the grammars, the file-loading policy and the highlighting tables were carried over
from its history, and the rest was written fresh. [docs/framework-notes.md](docs/framework-notes.md) is what
rebuilding it found out about the framework.

## Run it

```
mvn compile exec:exec
```

With paths, to open them (a folder becomes the navigator's root):

```
mvn compile exec:exec "-Dapp.args=C:/work/project C:/work/project/notes.md"
```

The window comes back where you left it, with the folder and the files you had open, the one in front still in
front. Unsaved work is never remembered — closing the window asks about it first.

```
mvn test                                                  # the model, files, grammars, and the real tree headless
mvn compile exec:exec -Dautomation=0                      # with a driving socket (see Taking a screenshot)
mvn -Plarge-file-bench test                               # the large-file benchmark (see below); slow, off by default
```

## Using it

| Keys | Does |
| --- | --- |
| Ctrl+N | new document |
| Ctrl+O / Ctrl+Shift+O | open a file / open a folder in the navigator |
| Ctrl+S / Ctrl+Shift+S | save / save as |
| Ctrl+W | close the tab (asks if it is unsaved) |
| Ctrl+Tab, Ctrl+PageDown / Ctrl+Shift+Tab, Ctrl+PageUp | next / previous tab |
| Ctrl+B | show or hide the navigator |
| Ctrl+Shift+E | put the keyboard in the navigator |
| Alt+Z | word wrap on or off |
| Ctrl+Shift+V | a Markdown document formatted, or back to its source (the eye button over the tabs does the same) |
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
row's menu can copy its path and has *Open in Vexplore*. `.git`, `target`, `node_modules` and a few others are left out of the listing.

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

A tab's menu has Close, Close others, Close all, Reveal in navigator, Open in Vexplore and Copy path. *Open in
Vexplore* starts `vexplore <file>`, a new Vexplore window on the file's folder with the file selected (a folder in
the navigator opens as itself); it is greyed out as *Vexplore not installed* when the install record is missing. A dot in front of a tab's name
means it has unsaved changes. Closing anything with unsaved work asks — Save, Don't save, Cancel — and a save that
fails, or a Save As that is cancelled, cancels whatever was waiting on it, quitting included.

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

| File | What it is |
| --- | --- |
| `Recipes.java` | **what the application builds**, one `@Provides` method per part. `TextEditorWiring` is generated from it at compile time, and each part's phase is inferred from what it takes |
| `Doc.java`, `Model.java` | the shape of the session — which documents, which in front, which unsaved, which folder — as one immutable value changed only by relative edits |
| `Ui.java` | the window: title bar, navigator beside the tabs, status line. `show(Doc)` writes everything derived from the session |
| `Workspace.java` | the Run button, the tab bar and one `Buffer` per tab, kept in step |
| `Buffer.java` | one document: its `TextField`, its highlighter, its path and line endings |
| `Runs.java` | what the Run menu offers for a folder and a document, worked out from what is on disk |
| `Console.java`, `Job.java` | the console window and its tabs, and one run in it: a program on a pseudoconsole, its output kept after it ends |
| `Navigator.java`, `FolderSource.java`, `RootSource.java` | the file tree and, in Open, the folders a root is picked from, over the disk, read lazily |
| `Preview.java`, `Survey.java` | what stands in the editor's place in Open: the folder under the pointer, surveyed for clues that it is a project's root |
| `Actions.java` | every command, and the questions some of them ask first, through the framework's `Dialogs`; also the main window's close gate |
| `Session.java` | what is remembered between runs, and bringing it back |
| `Highlighter.java` | TextMate grammars (TM4E) to colour spans, off the GUI thread |
| `Motion.java` | the one tempo: the ramps the tab bar, the tree and the cues move with, on the framework's clock |
| `TextFile.java` | the policy between bytes on disk and text in a field |
| `Look.java`, `Type.java`, `Landmarks.java` | colour, size, and the names an automation script may use |

The text of each document lives in its `TextField`'s own versioned `State<Document>`; `Doc` holds only what
nothing else owns. Which phase each part lands in is visible in
`target/generated-sources/annotations/.../TextEditorWiring.java` after a build.

### Threads, and the two rules

Handlers run on workers, and the GUI thread never reads the model. Disk reads and writes go to the offload lane,
never to the frame loop. The file dialogs are the framework's: on Windows they run on a dialog thread of their own, and
only on macOS, where AppKit insists, on the frame loop.

Two rules keep the tab bookkeeping deadlock-free, and both are written out on `Workspace`: **nothing commits to
the model while holding the workspace lock**, and **nothing reached from a model listener takes it**. atchung's
`onCommitLatest` makes a committing thread wait for a delivery already running on another thread, so breaking
either rule is a hang with nothing thrown (framework-notes FN-4).

## The stack

Installed locally rather than downloaded, so `mvn install` in each sibling has to have happened at least once.

| What | Why it is here |
| --- | --- |
| `vexelray-framework-shell`, `-automation`, `-processor` | the application edge, the driving socket, and the processor that writes the wiring |
| `vexelray-gui-widget`, `-krono`, `-automation` | the widgets (`Tabs`, `TreeView`, `TextField`, `SplitPane`, `StatusBar`), the clock, the driver |
| `vexelray-gui-nfd` | the OS's file dialogs |
| `vex-suite-common` (its `tty` package; the picture decoder excluded) | the console's terminal, shared with MainFrame |
| `org.netbeans.external:org.eclipse.tm4e.core-0.14.0` + `gson`, `joni`, `snakeyaml-engine` | the TextMate tokenizer and the runtime dependencies it does not declare |
| `tactroller-atchung`, `tactroller-clipboard` | input onto the bus, and the OS clipboard |
| `vexelray-os-*`, `tactroller-*` | picked by an OS-activated profile |

The grammars under `src/main/resources/grammars` are from microsoft/vscode (MIT, `VSCODE-LICENSE.txt` beside
them). CSS and YAML are pinned to vscode 1.96.0 for reasons `Highlighter.loadGrammars` gives, and
`GrammarBundleTest` fails if either is re-synced from main.

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
`target/large-file-bench.md`. The fixtures (~1.2 GB) are generated into `target/large-files` on the first run and
reused after; a file `TextFile` refuses is reported as refused and then opened past the limit anyway, so the table
shows what the limit protects against. Each fixture runs in a JVM of its own, so one that runs out of memory or
times out costs only its row. `-Dbench.only=log-100mb.log,...` picks fixtures, `-Dbench.xmx=8g` sets the heap and
`-Dbench.timeout=10` the minutes per fixture. Plain `mvn test` skips it.

## Logging

The framework configures it: info to the console and debug to `target/logs/text-editor.log` from a checkout,
more under automation. This application logs as `editor.files`, `editor.highlight`, `editor.index` and `editor.suite`;
`-Dlog.level.editor.files=debug` raises one of them.

## Native build and installer

`mvn -Pnative-release package` builds `target/text-editor.exe` (GUI subsystem, no automation; what ships) and `mvn -Pnative package` builds `target/text-editor-debug.exe` (console, drivable by ottermate) (Windows, GraalVM 25, from a Visual Studio developer
prompt); see [docs/native-build.md](docs/native-build.md). `installer.json` describes the per-user installer
for it (`vexelray-installer`), with `text-editor` added to `PATH`: `text-editor <folder or files...>` opens a new
window, a folder as the navigator's root and files as tabs. It also registers the editor for plain-text and
highlighted file types (`.txt`, `.log`, `.md`, `.json`, `.xml`, `.yaml`, sources, ...): always under **Open with**,
and the default only where no other program has claimed the type. Types Windows runs (`.js`, `.ps1`, `.bat`,
`.cmd`, ...) are highlighted but never associated; the installer refuses them.
