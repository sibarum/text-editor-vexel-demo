# TODO

Work on **VexelRay IDE** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

The 0.1 editor is being rebuilt on the v0.2 template, one milestone at a time: highlighting (done); editing details
(done; word wrap is toggled, and is kept once there are preferences); the navigator's Add
menu, Open mode and folder pictures; scratches; preferences; running things and the console; Git mode; go to
declaration (the index is built when a project is shown and on each save, and Ctrl+T's Symbols finds declarations in
it by name).
Its code is on `develop`, to copy from as each piece is rebuilt.

- [ ] Reveal in navigator selects a row deep in a folder it just opened, but leaves it offscreen: the tree scrolls only
      as far as that folder (seen 2026-11-13 with `vexelray-gui-widget`'s `TreeView.java`, before and after Reveal
      learned to show the file's project). Revealing the same row again does not scroll, since `TreeView.select`
      returns early on the row already selected. Whether the fault is the walk's or `scrollIntoView`'s is not known.
- [ ] Reading the index further: uses of a name (`Query.usages`), Ctrl+click on a name, and a library's declarations
      in a read-only tab, which Symbols leaves out today.

- [ ] A native build with highlighting. TM4E reads its grammars by reflection: the 0.1 editor's metadata registered
      the `org.eclipse.tm4e.core.internal.grammar.raw.Raw*` classes, `grammars/*.json` and jcodings' `tables/*.bin`.
      Worse than no colour today: `Grammars.grammar` throws `TMException` outside `spans`' catch, so `MarkText`
      fails and the framework stops the whole application, at start-up when the session restores any TextMate
      document (seen 2026-11-04 with an `.svg` tab). Pontif is not affected: it never touches TM4E.
- [ ] Pontif, after milestone 1 (highlighting, Run, checking as you type and Ctrl+Enter, done 2026-11-05; see README):
      a chooser when several modules export a name (FN-14); Ctrl+click; going back after a jump; errors past the
      first (the compiler stops there) and checking a project whole (`compileProjectDir`); `pontif.gui`'s modules,
      which the IDE does not carry; and the `pontif.command` setting in the preferences window, when there is one.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that file
      about which authored colours the construction can reproduce and which have to be declared.

## Later

- [ ] Automation coverage: `--automation=0` (or `-Dautomation=0`) opens a driving socket on a free port and prints it, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
