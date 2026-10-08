# Vex

A tabbed text editor with a file navigator, on the VexelRay stack.

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
| Ctrl+Enter | go to the declaration of the name under the caret (Java); again for the next one |
| Ctrl+F | find in the document (Enter / Shift+Enter step, Escape closes) |
| Ctrl+Z / Ctrl+Y | undo / redo |
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

## How it is put together

| File | What it is |
| --- | --- |
| `Recipes.java` | **what the application builds**, one `@Provides` method per part. `TextEditorWiring` is generated from it at compile time, and each part's phase is inferred from what it takes |
| `Doc.java`, `Model.java` | the shape of the session — which documents, which in front, which unsaved, which folder — as one immutable value changed only by relative edits |
| `Ui.java` | the window: title bar, navigator beside the tabs, status line. `show(Doc)` writes everything derived from the session |
| `Workspace.java` | the tab bar and one `Buffer` per tab, kept in step |
| `Buffer.java` | one document: its `TextField`, its highlighter, its path and line endings |
| `Navigator.java`, `FolderSource.java` | the file tree, over the disk, read lazily |
| `Actions.java` | every command, and the questions some of them ask first; also the close gate |
| `Dialogs.java`, `NativeDialogs.java` | the OS's open/save/folder dialogs, asked from anywhere, run on the frame loop |
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
never to the frame loop; the native dialogs are the one thing that runs there, because they must.

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

`ottermate --size 36emx20em shot smallest.png --launch ...` is the window at its minimum. `ottermate` cannot press
chords yet, so Ctrl-commands cannot be scripted (framework-notes FN-3); clicks, typing and menus can.

## Logging

The framework configures it: info to the console and debug to `target/logs/text-editor.log` from a checkout,
more under automation. This application logs as `editor.files`, `editor.dialogs`, `editor.highlight` and `editor.index`;
`-Dlog.level.editor.files=debug` raises one of them.

## Native build and installer

`mvn -Pnative-release package` builds `target/text-editor.exe` (GUI subsystem, no automation; what ships) and `mvn -Pnative package` builds `target/text-editor-debug.exe` (console, drivable by ottermate) (Windows, GraalVM 25, from a Visual Studio developer
prompt); see [docs/native-build.md](docs/native-build.md). `installer.json` describes the per-user installer
for it (`vexelray-installer`), with `text-editor` added to `PATH`: `text-editor <folder or files...>` opens a new
window, a folder as the navigator's root and files as tabs. It also registers the editor for plain-text and
highlighted file types (`.txt`, `.log`, `.md`, `.json`, `.xml`, `.yaml`, sources, ...): always under **Open with**,
and the default only where no other program has claimed the type. Types Windows runs (`.js`, `.ps1`, `.bat`,
`.cmd`, ...) are highlighted but never associated; the installer refuses them.
