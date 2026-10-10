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

- No FFM switches: `-H:+ForeignAPISupport`, `-H:+SharedArenaSupport` and `--enable-native-access` come from the
  `native-image.properties` in the library jars that make Panama calls (the graphics stack, both input backends,
  the clipboard and the file dialogs).
- `-J-Djava.io.tmpdir=target/nitmp`: Windows Application Control blocks a new unsigned `.exe` under `%TEMP%`,
  and native-image's own probes are exactly that. `Unable to run 'WindowsDirectives.exe'` means this blocked
  a probe: retry.
- `/SUBSYSTEM:WINDOWS` (release; the debug edition passes `/SUBSYSTEM:CONSOLE`) and `/ENTRY:mainCRTStartup`: no
  console window, and the entry is not optional alongside WINDOWS (the linker would look for `WinMain`).
- `src/main/rc/editor.rc` is compiled by `rc.exe` to `target/text-editor.res` and linked in: the file's icon.

## The metadata (`src/main/resources/META-INF/native-image/dev.vexelray.demo/`)

Metadata travels with the jars that need it. vexelray, supirvast, tactroller, vexelray-gui-nfd and imagelib-wrapper
each carry their own under `META-INF/native-image/`: the FFM build flags (`native-image.properties`), every downcall
shape they declare, their window-procedure upcalls, their services, the shaders and the whole font atlas. This
application lists only what is its own.

- `text-editor-vexel-demo/reachability-metadata.json` was traced with `native-image-agent` from a JVM run that
  opened a file of each grammar and pressed the screenshot button (the native save dialog), then trimmed of
  everything the library jars register. What is left: `TextEditor`, the `grammars/*` resources, the TM4E `Raw*`
  classes, and the JDK entries the trace saw, plus `tables/*.bin`, every one of jcodings' Unicode tables (about
  3 MB). That glob is written by hand, and a re-trace must not replace it: a trace lists only the tables one run
  loaded, so the image once had 13 of them, and highlighting a Markdown file failed on `CR_L.bin`.
- `text-editor-vexel-demo-signed-jar/reachability-metadata.json`: TM4E ships as a signed jar, the signing
  certificate ends up in the image heap, and every type that represents an X.509 certificate must be
  registered or the build fails with `Type not found during analysis`. Do not delete it because a native image
  has no jars to verify.

To re-trace after a dependency change: run the JVM app with
`-agentlib:native-image-agent=config-output-dir=<dir>,config-write-period-secs=2` (the agent's write at exit did not
appear in our runs), drive it with `ottermate`, and merge only the new entries that are this application's into the
first file. Whatever names a library's class, native call, shader or font belongs in that library's metadata.
