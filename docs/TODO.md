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

- [ ] Welcome, after slice 1 (Recent, the main window hidden until a choice; done 2026-11-20): the home folder
      scanned breadth-first for git repositories, listed as found (a `@Source`, bounded by depth, folders visited,
      repositories found and time); Java runtime and Maven auto-detected and chosen there, kept in the settings; the
      Run menu's Maven and `java` lines run with them (`JAVA_HOME` and the `PATH` set in the line, as Run… does); a
      Welcome… item to bring it back. Closing Welcome before choosing quits, which only `EditorTest` covers: ottermate
      has no verb for a window's close box.
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
