# The native build

```
mvn -Pnative-release package -DskipTests   # target/text-editor.exe, about 43 MB, about 40 s: what ships and is signed
mvn -Pnative package -DskipTests           # target/text-editor-debug.exe: for ottermate
```

Two editions of the same code:

- **release** is a Windows GUI subsystem program (no console window ever) built without the automation module, so
  it cannot open a driving socket. `src/edition-release/` (a `TextEditorApp` naming no `AutomationStarter`) is
  compiled instead of `src/edition-debug/`, and `vexelray-framework-automation` and `vexelray-gui-automation` get
  scope `test`, so they are on no runtime classpath and the image never sees them. `--automation=0` is still
  accepted by the launch parser and does nothing. `installer.json` points at this exe.
- **debug** is a console subsystem program with automation: `text-editor-debug.exe --automation=0` prints
  `automation: localhost:<port>` on stdout, which `ottermate --launch` reads. A GUI-subsystem exe has no stdout,
  which is why the two cannot be one binary.

The plain JVM build, tests and `exec:exec` are the debug edition (`automation.scope` compile, `edition.src`
`src/edition-debug/java`; the release profile overrides both properties).

Under the GUI subsystem stdout and stderr are closed, and nothing here minds: logging goes to
`target/logs/text-editor.log` or the user's log directory as before.

Windows only for now. Run it from a Visual Studio developer prompt (or after `vcvars64.bat`), so `link.exe`
and `rc.exe` are MSVC's and on `PATH`; Git Bash's own `link.exe` is not the linker. GraalVM 25 provides
`native-image`. The result is one file with no DLLs beside it (the text atlas is a raw `.rgba` now, so
`java.desktop` is not reached, unlike the earlier editor).

## The switches (pom, `pluginManagement`; profiles `native` and `native-release`)

- `-H:+ForeignAPISupport`: the graphics stack, both input backends, the clipboard and the file dialogs are
  Panama downcalls, and Win32 calls back through an upcall stub.
- `-H:+SharedArenaSupport`: raw input opens an `Arena.ofShared` on one thread and reads it on another.
- `-J-Djava.io.tmpdir=target/nitmp`: Windows Application Control blocks a new unsigned `.exe` under `%TEMP%`,
  and native-image's own probes are exactly that. `Unable to run 'WindowsDirectives.exe'` means this blocked
  a probe: retry.
- `/SUBSYSTEM:WINDOWS` (release; the debug edition passes `/SUBSYSTEM:CONSOLE`) and `/ENTRY:mainCRTStartup`: no
  console window, and the entry is not optional alongside WINDOWS (the linker would look for `WinMain`).
- `src/main/rc/editor.rc` is compiled by `rc.exe` to `target/text-editor.res` and linked in: the file's icon.

## The metadata (`src/main/resources/META-INF/native-image/dev.vexelray.demo/`)

All three files are a stopgap; the framework's ruling is that metadata travels with the backend jars or a starter,
never with an application.

- `text-editor-vexel-demo/reachability-metadata.json` was traced with `native-image-agent` from a JVM run that
  opened a file of each grammar and pressed the screenshot button (the native save dialog). It holds the
  foreign descriptors, `WindowsPlatform`/`Win32Window`/tactroller reflection, ServiceLoader and shader/atlas
  resources, and the `grammars/*` resources and TM4E `Raw*` classes, which are this application's own.
- `text-editor-vexel-demo-signed-jar/reachability-metadata.json`: TM4E ships as a signed jar, the signing
  certificate ends up in the image heap, and every type that represents an X.509 certificate must be
  registered or the build fails with `Type not found during analysis`. Do not delete it because a native image
  has no jars to verify.
- `text-editor-vexel-demo-fonts/reachability-metadata.json` is written by hand, and a re-trace does not touch it:
  `vexelray-text`'s font manifest (`fonts.json`) and every face's metrics and pixels (`*/*.json`, `*/*.rgba`),
  whichever faces it bakes. A trace lists only the files one run opened, so it went stale when the atlas
  became a set of families, and the image died at startup with `font file not found: the manifest`.
  Name `fonts.json` exactly: a top-level `atlas/*.json` glob matched nothing in GraalVM 25.

To re-trace after a dependency change: run the JVM app with
`-agentlib:native-image-agent=config-output-dir=<dir>,config-write-period-secs=2` (the agent's write at exit did not
appear in our runs), drive it with `ottermate`, and copy `<dir>`'s file over
the first one.
