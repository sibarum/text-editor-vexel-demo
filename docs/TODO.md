# TODO

Work on **Vex** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] Only a launch with no arguments may write **the window's placement**. The session half is done: a launch
      with paths (Vexplore's *Open in Vex* is one) opens only those and never arms `Session`, so it neither
      restores nor writes the tabs. The framework's window memory still writes `window.main.*` (x, y, size) to the
      same `~/.text-editor/settings.properties` from every window, which is upstream: *Settings and the session*
      in the framework TODO.
- [ ] Drive the chords once ottermate can press them (FN-3): Ctrl+S on a dirty file, Ctrl+W through the
      question, Ctrl+Tab round the bar. Today they are checked by reading the claim rules, not by pressing.
- [ ] Notice a file changing on disk under an open, clean tab, and reload it; ask if the tab is dirty.
- [ ] Remember the navigator's width and whether it is shown, beside the session.
- [ ] The window wears the executable's icon (`src/main/rc/vex-window.svg`, from the suite icon canvas) once a
      generated wiring can name an icon (FN-2).

## Later

- [ ] Find across the open folder, with results as a list that opens at the line.
- [ ] Reopen a recently closed tab (Ctrl+Shift+T).
- [ ] The native build (`-Pnative`, [docs/native-build.md](native-build.md)) works and every grammar renders in it,
      but its metadata was traced from a JVM run. Walk the paths nobody drove: Ctrl+O and Ctrl+S (the native
      dialogs), Save As, the clipboard, and the chords `ottermate` cannot press. Most of the metadata belongs
      upstream (framework TODO, *native-image metadata*).
