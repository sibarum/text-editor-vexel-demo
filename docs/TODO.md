# TODO

Work on **VexelRay IDE** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] Only a launch with no arguments may write **the window's placement**. The session half is done: a launch
      with paths (Vexplore's *Open in VexelRay IDE* is one) opens only those and never arms `Session`, so it neither
      restores nor writes the tabs. The framework's window memory still writes `window.main.*` (x, y, size) to the
      same `~/.text-editor/settings.properties` from every window, which is upstream: *Settings and the session*
      in the framework TODO.
- [ ] Drive the chords once ottermate can press them (FN-3): Ctrl+S on a dirty file, Ctrl+W through the
      question, Ctrl+Tab round the bar. Today they are checked by reading the claim rules, not by pressing.
- [ ] Notice a file changing on disk under an open, clean tab, and reload it; ask if the tab is dirty.
- [ ] Remember the navigator's width and whether it is shown, beside the session.
- [ ] Build `-Pnative` and open Open's blocks in it: `vexelray-technique-mesh` and `-engine-embedded` arrived after
      the metadata was traced, and nothing has drawn a mesh in the native image yet.
- [ ] Open's blocks: drag to turn them (the camera is two angles), and a name on hover. Today a block shows only
      its colour and, if it is large enough, its icon.
- [ ] The window wears the executable's icon (`src/main/rc/vex-window.svg`, from the suite icon canvas) once a
      generated wiring can name an icon (FN-2).

## Later

- [ ] Find across the open folder, with results as a list that opens at the line.
- [ ] Reopen a recently closed tab (Ctrl+Shift+T).
- [ ] Switch between Dark and Light without a restart, once the framework can restyle a built tree (vexelray-gui
      todo, *A theme swapped at runtime*). Today the choice is read at launch.
- [ ] Order and choose a name's declarations by nearness: concordance's `Search` over a `Plan` (the open tabs
      first, then the project, then the libraries they import) with `NameGroups`, in place of Ctrl+Enter's fixed
      project-then-libraries walk, which can be hundreds long for a common name.
- [ ] The native build (`-Pnative`, [docs/native-build.md](native-build.md)) works and every grammar renders in it,
      but its metadata was traced from a JVM run. Walk the paths nobody drove: Ctrl+O and Ctrl+S (the native
      dialogs), Save As, the clipboard, and the chords `ottermate` cannot press. Most of the metadata belongs
      upstream (framework TODO, *native-image metadata*).
