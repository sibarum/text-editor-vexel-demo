# VexelRay IDE

VexelRay IDE: a text editor built on VexelRay.

A VexelRay desktop application, generated from the `vexel-desktop` template: a small text editor with tabs, a file
navigator and Markdown in colour. It is the reference implementation of this stack, so every seam the framework
offers is wired in the tree you are reading — and it is a working editor, so it is also something to change into
whatever you are actually building.

It knows Pontif natively: a `.ptf` file is coloured by the language's own token scan rather than a TextMate grammar
(`Grammars.PONTIF`), through `pontif-tooling` from the sibling `pontif-framework` checkout (branch `v0.2`), which has
to be `mvn install`ed first. The Run menu runs a `.ptf` file or its project through `pontif run` (*Running things*), a `.ptf` is checked as it
is typed (*Spans*), its first error underlined and on the status line, and Ctrl+Enter on a name goes to its definition
or adds its `requires` (*Pontif: Ctrl+Enter*).

## Run it

From this folder, the parent of the two modules:

```
mvn compile exec:exec
```

`exec:exec` rather than `exec:java`, so native access can be enabled for Panama FFM. The window comes up where you
last left it, at 1180x720 the first time, with the folder and the files you last had open. The first time,
the navigator shows the working directory.

```
mvn compile exec:exec "-Dapp.args=C:/notes"               # open a folder (or files) named on the command line
mvn compile exec:exec -Dapp.args=120                      # 120 frames and quit
mvn compile exec:exec -Dautomation=0                      # a driving socket on a free port (see Taking a screenshot)
mvn test                                                  # the editor, the jobs, Markdown, the real tree headless
```

## A native executable

Two builds, made with GraalVM native-image (a GraalVM JDK as `JAVA_HOME`, and Visual Studio with the "Desktop
development with C++" workload):

```
native-build.cmd -Pnative-release package -DskipTests   # text-editor-vexel-demo-gui/target/text-editor-vexel-demo.exe        what ships: no console
native-build.cmd -Pnative package -DskipTests           # text-editor-vexel-demo-gui/target/text-editor-vexel-demo-debug.exe  a console: prints the automation port line
```

Both carry the automation sockets, off until asked for ([Automation](#automation)), so ottermate can drive either.

On Linux there is one build, with no console subsystem or icon step to choose, and plain `mvn`:

```
mvn -Pnative-linux package -DskipTests                  # text-editor-vexel-demo-gui/target-linux/text-editor-vexel-demo
```

`native-build.cmd` is `mvn` inside a Visual Studio developer environment: native-image needs the MSVC `link.exe` and
the icon step needs `rc.exe`, and neither is on `PATH` in a plain shell. Under Git Bash it is worse, since coreutils'
own `link.exe` comes first. The script finds `vcvars64.bat` with `vswhere`, loads it, and runs `mvn` with the
arguments it was given (`./native-build.cmd ...` from Git Bash). Plain `mvn` works too from a developer prompt; from
anywhere else the icon step stops and says to use the script.

This program's own native-image metadata is `text-editor-vexel-demo-gui/src/main/resources/META-INF/native-image/.../reachability-metadata.json`,
and it is short because almost nothing is this program's: the libraries register their own, and the processor
registers the icon. If a native run fails on something the JVM run did not (a `Missing...RegistrationError`), run it
on a JVM under `-agentlib:native-image-agent=config-output-dir=...`, do the thing that failed, and add
only the entries that name this program.

## The icon

`text-editor-vexel-demo-gui/src/main/rc/editor.ico` is the application's mark, and it is one file read three ways: the window and its
taskbar button wear it (`@VexelApp(icon = TextEditor.ICON)`; the pom puts it on the class path), and so does the
title bar the framework draws; the `.exe` links it (`src/main/rc/text-editor-vexel-demo.rc`), and so Explorer, a shortcut and a
pinned button show it too. It starts as the framework's "new app" mark, whose source is `editor.svg` beside it.

A second window — a viewer, a settings window — wears the window mark, on the taskbar and in its title bar, with
nothing to write: declare it in a recipe with `windows.named(...)`, and the framework builds its tree and claims it
wearing `shell.windowMark()`. That mark is this same one unless
a `editor-window.ico` sits beside it, which is then found by name and registered for the native image with
the mark. The VexelRay suite's applications ship one: a band of the application's colour over its glyph, so a
second window reads as its own and is still told apart from the main one.

To make it yours, draw a 64x64 SVG and render it at every size Windows asks for, 16 to 256 px, into that `.ico` —
`vex-suite-common`'s `tools/Ico.java` does exactly this, from each size's own rasterisation rather than shrinking the
largest. Replace the file, and the window and the executable change together. An `.ico` or `.png` that is missing is
a compile error; one that does not decode costs the window its mark, logged, and nothing else.

Run from `mvn exec:exec`, the process is `java.exe`, so the window and its taskbar button wear the mark but Windows
groups the button with other Java programs, and pinning it pins Java. The native executable has neither problem.

## Using it

| Keys | Does |
| --- | --- |
| Ctrl+L | the path bar: type a folder to show it, a file to open it, a new name to create it; Enter goes |
| Ctrl+S | save |
| Ctrl+W | close the tab (asks if it is unsaved) |
| Ctrl+Tab, Ctrl+PageDown / Ctrl+Shift+Tab, Ctrl+PageUp | next / previous tab |
| Ctrl+Shift+E | put the keyboard in the navigator |
| Ctrl+F | find in the file (Enter / Shift+Enter step, Escape closes) |
| Ctrl+Z / Ctrl+Y | undo / redo |
| Ctrl+Enter | in a Pontif file: go to the definition of the name at the caret, or add its `requires` |
| Ctrl+= / Ctrl+- / Ctrl+0 | zoom |
| Alt+Z | word wrap on or off, in every document (on at start; not kept yet); the View menu's line is ticked while it is on |
| F5 | the Run menu (see *Running things*) |

**New**, the first menu in the title bar, makes something in the folder shown, as a navigator row's menu does for its
row: **File…**, then **Project…** (a Maven project), or **Submodule…** (a module of the project) where a `pom.xml` is
at or above the folder. Each puts its name in the path bar, and Enter makes it. Under a rule, inside a project,
**Index this project** builds [Concordance](../concordance-indexer)'s index of it (`BuildIndex`): every declaration
and use of a name in the whole reactor around the folder, and what its libraries export, from their sources jars or
decompiled. It is kept in `~/.text-editor-vexel-demo/index`, one file per project, so indexing again rereads only
what changed, and libraries go through Concordance's shared cache (`~/.concordance/jars`). The status line says what
it holds. Nothing reads it yet: going to a declaration will. To a script the menu is `titlebar-menu.new`, with
`menu.file`, `menu.project`, `menu.submodule` and `menu.index-this-project`.

**View**, after New, has word wrap, the navigator, the formatted view and zoom, each with its chord beside it.

Selecting a file in the navigator opens it — a click, or walking the tree with the arrow keys, which keep the
keyboard in the tree. A tab's menu has Save, Close, Close others, Close all and Reveal in navigator. A bullet in
front of a tab's name means unsaved changes; closing anything unsaved asks first, and so does quitting.

There is no file dialog, by choice: the path bar is the whole of "open", and it works the same in a test as in use. The
framework brings native pickers (a `PickFile` message, answered by `Picked`) for a project that wants them.

### Running things

**Run**, in the title bar after View (or F5), is a menu of what can be run for the folder shown and
the file in front, worked out from what is on disk by the `FindRuns` job (`Runs`), each group under a rule:

- the file in front: **Run *Class*.main()** for a Java file with a `main` (through Maven, `mvn -q compile exec:java`,
  when it is in a module; with the JDK's source launcher otherwise), the file itself when it is a program, or
  **Run *name*.ptf** for a Pontif file (`pontif run <file>`, in its folder);
- the Pontif project, the folder shown or the nearest one above the file with a `module.toml`: **Run Project
  (*name*)**, `pontif run <folder>`, named as its `module.toml` names it. Pontif is run by `pontif` on the `PATH`, or
  by the command line in the settings' `pontif.command` (say `java -jar …/pontif-cli/target/pontif-cli.jar`, where
  there is only the jar), set by hand while the IDE is closed until there is a preferences window;
- the Maven project: **Compile**, **Clean Install**, **Package** unless the packaging is `pom`, **Execute** when the
  pom configures exec-maven-plugin's `exec`, and **Run Recent Build** for the newest program or runnable jar in
  `target/`;
- the programs in the folder, four folders deep at most, as the platform counts them (`PtyPlatform.programLine`).

**Run…**, always first, opens a window (`RunDialog`) for a command of your own: the folder it runs in (the folder
shown, until you give one), environment variables one `NAME=value` a line, and folders to put ahead of the `PATH`,
one a line. Run, or Enter in the command or the folder, runs it; Escape closes. The `TypeRun` job checks it (a line
that is not `NAME=value`, a folder that is not there; the problem stays in the window) and remembers it in the
settings for next time. The variables and the `PATH` are set in the line itself (`set "NAME=value" && …` for `cmd`,
`export` for `sh`), so the console's first line is still the whole of what ran and Run again repeats it.

Before anything runs, every unsaved document is saved, since what
runs is what is on disk; a save that fails runs nothing (the editor's `run` chain).

Runs are shown in the **console**, a window of its own with one tab per run, the newest in front. Each run is a
program on a pseudo-terminal of its own (the `Runner`, the framework's source-and-destination part, keyed by the run),
drawn by `vex-suite-common-tty`'s `TerminalScreen`, so Maven colours its output and a program that asks a question can
be answered by typing. Its first line is what ran and where; its last says how it ended, *Finished*, *Failed with exit
code n*, *Stopped* or *Could not start*, and so does the main window's status line. A tab's menu has Stop, Run again,
Copy output and Close. Finished runs past the last five close by themselves, oldest first, never the one in front.

To a script, the menu is `titlebar-menu.run` and its items `menu.<label>` (`click menu.compile`), the console is
`window console`, and its tabs `console.tabs`; Run… is `menu.run`, and its window `window run`, with `run.command`,
`run.dir`, `run.environment`, `run.path` and the button `run.go`. `await status.message Compile:` waits for a run to
end. F5 opens the menu on its first line; Up and Down move, Enter runs, Escape closes.

## How it is put together

Two modules under this folder, split where the dependency edge falls, and four kinds of part that never hold each
other: **components** (logic, reached only by messages: the editor runs on the framework's logic lane), **views**
(presentation, run on the GUI thread in each frame, `@View`), **jobs** (slow work on the offload lane, a request in
and a reply out), and **recipes** (a part built once and handed to whoever takes it). The parts talk by messages, and every message is in one file. Read these in this order.

**`text-editor-vexel-demo-core`, the controller.** It builds against no widget, so its tests need no window.

| File | What belongs in it |
| --- | --- |
| `Messages.java` | **every message the parts send each other**, grouped by who sends it to whom, each record beside its topic. A record crosses as itself, so it holds only immutable values; a list it holds is copied in its constructor. |
| `Editor.java` | **the controller**: every decision (what a typed path names, whether a file is already open, when closing needs a question, what the status line says, what comes back next time) as a declared step on one immutable state, held by a ledger. Each message it hears runs one step; closing is a chain of them that waits on the person and the disk without anything waiting on a thread. It needs no lock, because only its steps change its state and they run one at a time. |
| `Doc.java` | the shape of the session (which files, which in front, which unsaved, which folder) as one immutable value, part of the editor's state. |
| `ReadFile.java`, `WriteFile.java`, `FindPath.java`, `CreateFile.java`, `ReadSession.java`, `WriteSession.java`, `ListFolder.java`, `MarkText.java`, `CheckText.java`, `NavigateText.java` | the jobs: every touch of the disk, on the offload lane. What can go wrong is part of the reply. |
| `TextFile.java`, `Markdown.java` | the policy between bytes and text, and Markdown as spans: pure functions. |
| `Core.java` | the list of this module's component and jobs. The processor compiling `-gui` sees only `-gui`'s sources, so this list, named among `TextEditorApp`'s starters, is how the wiring hears of them. A new component or job goes on it. |

**`text-editor-vexel-demo-gui`, the view and the framework integration.**

| File | What belongs in it |
| --- | --- |
| `Recipes.java` | **what this module builds.** One `@Provides` method per part; `TextEditorAppWiring`, which builds them and the components and jobs in order, is generated while it compiles. A part's phase is the latest phase of anything it takes, so there is none to declare. |
| `View.java` | **the view**, a `@View`: run on the GUI thread in each frame, hearing the editor, its jobs and the widgets as messages; the editor's messages shown (`AddTab` is a tab) and the person's gestures told (`Open`, `Close`, `Save`). It decides nothing. |
| `Intent.java` | what a key chord asks of the view: the message the gesture was declared to send. |
| `Ui.java` | the window. Holds no state; it shows what the view hands it. |
| `Workspace.java`, `Buffer.java` | the tab bar and one `Buffer` per tab: a `TextField`, its spans, its save bookkeeping. |
| `Navigator.java` | the path bar and the file tree. The tree asks for each level by message, and the view asks `ListFolder`, so the disk is read off the frame. |
| `Motion.java` | the one tempo every widget moves at, on the framework's clock. |

`TextEditor.java` is the entry point and the constants, and nothing else. The application edge — input, the
clipboard, window memory, the clock, the frame loop with its wakes and its pacing, the dialogs, the command line and
the shutdown order — is `vexelray-framework`'s. `Look.java`, `Type.java` and `Landmarks.java` are the three
vocabularies: colour, size, and the names an automation script is allowed to depend on.

### Phases

A phase is a **correctness** constraint rather than a scheduling detail, and having them as a type is what makes the
constraints structural instead of remembered:

| phase | what exists by then |
| --- | --- |
| `CONFIG` | the settings store and the look — values, before there is a `Gui` to apply them to |
| `MODEL` | what the application knows, before there is anything to draw it with |
| `GUI` | the `Gui` and the clock; the theme applied and the zoom range set, before the first widget |
| `TREE` | the widgets: the first phase a part is handed the `Gui`, so anything taking it lands here. Buildable with no window, which is what lets a test build it with no GPU |
| `WINDOW` | the device and the window handle. Main-thread from here on |
| `ATTACH` | anything that needed the handle: chrome controls, the close gate, the driving socket |

Open `text-editor-vexel-demo-gui/target/generated-sources/annotations/.../TextEditorAppWiring.java` after a build to see which part landed where.

### Threading, in one paragraph

The GUI loop runs on the main thread, which owns Vulkan, the window and present, and nothing of this application's
runs there but the view, which is presentation and nothing else (threading.md, T7.4). The editor runs on the
framework's logic lane and the view on the GUI thread, in each frame's widget tick; each handles one message at a
time and owns its state outright, so there is no lock anywhere in the application. The editor's state is changed only
by its declared steps, which the processor reads: a step that touches the disk, waits or reaches outside itself does
not compile. A widget says what it did as a message, which the view hears in the frame the input arrived, and a key
chord or a menu item is declared with the message it sends, so nothing of the application's runs at the click. Every
read and write of a file is a job on the offload lane, so a slow disk is a slow reply and never a frozen window (the
lanes are named in the framework's `docs/threading.md`).

Two things follow from messages, and both are written where they bite. **One sender's messages to one receiver keep
their order**: so the view says a tab went unsaved before it asks to close it, and the editor already knows when the
`Close` arrives. **Jobs are in no order at all**: so the restored session asks for one file at a time. The processor
checks every message it can see: a mailbox's payload is deeply immutable, a topic carries one type, and no cycle of
sends can block.

### Spans

`Markdown.spans` is a pure function from text to `Span`s (foreground, background, underline). After each edit
the view asks `MarkText` for them on the offload lane, and applies the answer only if the field still says the text it
was computed for. The text is never changed; the field draws the colours and moves each span with its text as you type. A `.txt` file gets none.

A Pontif file is also checked as it is typed: `CheckText` compiles the text, saved or not, as the file would be (its
folder is where what it `requires` is found), with `pontif-compiler`, and puts the error where the Pontif Editor puts it
(`pontif-tooling`'s `PontifProblems`). The problem is underlined on a faint `DANGER` ground and said on the status
line (`status.problem`). One check is in flight per document, and its answer sends the next, for the text as it is
then, so a burst of typing costs two compiles, not one a keystroke (FN-12). The colours and the problems are two
answers into the field's one list of spans, so each replaces only its own kind (`Buffer`).

### Pontif: Ctrl+Enter

Ctrl+Enter on a name in a `.ptf` is the Pontif Editor's navigate-or-import, through the same `pontif-tooling`
`DefinitionNavigator` (`NavigateText`). Declared in the file, the caret goes to it; in scope, its declaration opens, in
the file beside this one that has it or, for a built-in module, in that module's source, written to `pontif-builtins`
beside the scratches in the IDE's home and shown read-only; not in scope and exported by one module, the `requires`
is added as one edit (Ctrl+Z takes it out) and Ctrl+Enter again goes to it. Exported by several, they are named on
the status line and nothing is added (a chooser at the caret is FN-14). The answer is one `Navigated` with a `Kind`,
which hands it to the view's `Navigated.Sink`, so nothing switches on it. An answer is applied only while the field
still says the text it was about: a field's change version also counts caret moves (FN-15).

### Motion

The widgets animate only when handed a ramp, and without one they cut — so leaving the clock out costs no error,
only a stiffer window. `Motion` makes the ramps from the framework's clock: the tabs slide, folders open, and a cue
rings a tab you asked to open that was already open, or the status line when something was refused.

## The stack

Everything below is installed locally rather than downloaded, so `mvn install` in each of those projects has to have
happened at least once. If the build cannot resolve them, that is why.

| what | why it is here |
| --- | --- |
| `vexelray-framework-api`, `vexelray-gui-core` | all `-core` builds against: the framework's annotations, and the values (a span, a colour, the settings store) and the bus that are safe from any thread |
| `vexelray-framework-shell`, `-automation`, `-processor` | the application edge, the driving socket, and the processor that checks both modules and writes the wiring |
| `vexelray-gui-widget` | the widgets: `Tabs`, `TreeView`, `TextField`, `SplitPane`, `StatusBar`, `Cues` |
| `vexelray-gui-krono` | the clock: every transition and cue |
| `vexelray-gui-automation` | driving the running app the way a person does — a debugging instrument |
| `tactroller-atchung` | keyboard and pointer input onto an atchung bus (brings `atchung-core`) |
| `tactroller-clipboard` | the OS clipboard, with no input-subsystem coupling |
| `vexelray-os-*`, `tactroller-*` | picked by an OS-activated profile; exactly one activates |

## Automation

VexelRay IDE can be driven and read from outside, by a script, a macro or an agent, through the framework's two loopback
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

Screenshots are `ottermate`'s, and only its: it photographs the running window on the application's own device, so
the picture is right about content as well as chrome. There is no `--capture` flag in this project on purpose.

```
ottermate shot out.png --launch mvn compile exec:exec -Dautomation=0
```

Say what the picture is *of* with options, and `ottermate` sets it first and stops if it cannot. Everything before
`--launch` is ottermate's; everything after it is the application's command line (on Windows, `mvn.cmd`):

```
ottermate --zoom 1.5 --dpi 2 --size 1600x900 shot big.png --launch ...   # pixels
ottermate --size 36emx20em shot smallest.png --launch ...                # the minimum, in em
```

The landmarks in `Landmarks.java` are the names a script uses: `await status.file notes.md` waits for a file to be in
front, and `await path <folder>` for the navigator to be showing one. `ottermate help` lists every verb.

## Logging

The framework configures logging for you, before anything else runs, and the defaults follow what kind of run it is:

| Run | Console | File |
| --- | --- | --- |
| a test | warnings | none |
| from your checkout (`mvn exec:exec`) | info | `text-editor-vexel-demo-gui/target/logs/text-editor-vexel-demo.log`, debug |
| driven by `ottermate` or `-Dautomation` | debug | `…/text-editor-vexel-demo.log`, **trace**, with a probe trace beside it |
| a native image | warnings | `~/.text-editor-vexel-demo/logs/text-editor-vexel-demo.log`, info |

Write to it with a logger, not `System.out` — the jobs do, as `text-editor-vexel-demo.files`:

```java
private static final Log LOG = Log.of("text-editor-vexel-demo.files");   // sibarum.probe.Log
LOG.warn("could not save {}", path, exception);               // the exception goes last; its stack reaches the file
```

`--log=debug` (or `off`) changes the level for one launch, and `-Dlog.level.text-editor-vexel-demo.files=trace` raises one
logger. Levels, settings and the rules for what belongs at which level are in `atchung/docs/logging.md`.

## The three documents

`docs/framework-notes.md` and `docs/TODO.md` start nearly empty and are meant to be filled in as you go. See the note
at the top of the first one — it is the more important of the two. `docs/retrospective.md` is filled in at the end of
each milestone: the pass over the notes that looks for what they have in common, and the one the framework reads
first.
