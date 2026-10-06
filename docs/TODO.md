# TODO

Work on **Text Editor** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] Only a launch with no arguments may restore or write the saved session **and the window's placement**.
      One started with paths — as vexplore or mainframe will do, one new window per spawn — starts clean and
      saves nothing, or two windows overwrite each other's state and a single-file window drags the last
      project's tabs in. Today a launch with paths *still restores the saved tabs first and also writes the
      session*. There are two seams: `Session` reads `session.folder`, `session.files` and `session.front` once in
      `restore()`, before the command-line paths are opened (`Recipes.restore`), and writes them from
      `remember()`, called by the model listener and at the end of `restore`; and the framework's window memory
      writes `window.main.*` (x, y, size) to the same `~/.text-editor/settings.properties`, which is upstream, see
      *Settings and the session* in the framework TODO. The editor's half: skip both `restore` and `remember`
      when `launch.rest()` is non-empty.
- [ ] Drive the chords once ottermate can press them (FN-3): Ctrl+S on a dirty file, Ctrl+W through the
      question, Ctrl+Tab round the bar. Today they are checked by reading the claim rules, not by pressing.
- [ ] Notice a file changing on disk under an open, clean tab, and reload it; ask if the tab is dirty.
- [ ] Remember the navigator's width and whether it is shown, beside the session.
- [ ] Wear the `prompt` mark from `vexelray-icons` once a generated wiring can name an icon (FN-2).

## Later

- [ ] Find across the open folder, with results as a list that opens at the line.
- [ ] Reopen a recently closed tab (Ctrl+Shift+T).
- [ ] The native build (`-Pnative`, [docs/native-build.md](native-build.md)) works and every grammar renders in it,
      but its metadata was traced from a JVM run. Walk the paths nobody drove: Ctrl+O and Ctrl+S (the native
      dialogs), Save As, the clipboard, and the chords `ottermate` cannot press. Most of the metadata belongs
      upstream (framework TODO, *native-image metadata*).
