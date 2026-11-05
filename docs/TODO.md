# TODO

Work on **VexelRay IDE** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

The 0.1 editor is being rebuilt on the v0.2 template, one milestone at a time: highlighting (done); editing details
(done, but for word wrap, which comes with preferences); the navigator's Add
menu, Open mode and folder pictures; scratches; preferences; running things and the console; Git mode; go to
declaration. Its code is on `develop`, to copy from as each piece is rebuilt.

- [ ] A native build with highlighting. TM4E reads its grammars by reflection: the 0.1 editor's metadata registered
      the `org.eclipse.tm4e.core.internal.grammar.raw.Raw*` classes, `grammars/*.json` and jcodings' `tables/*.bin`.
      Worse than no colour today: `Grammars.grammar` throws `TMException` outside `spans`' catch, so `MarkText`
      fails and the framework stops the whole application, at start-up when the session restores any TextMate
      document (seen 2026-11-04 with an `.svg` tab). Pontif is not affected: it never touches TM4E.
- [ ] Pontif, milestone 1 (agreed 2026-11-04; one slice each, in order): highlighting (done, `Grammars.PONTIF`);
      Run a `.ptf` and Run Project (done, `Runs.pontifFile`/`pontifProject`; `pontif` on the `PATH`, else the
      `pontif.command` setting, which the preferences window should show); diagnostics as you type (`pontif-compiler` in a job; propose the messages and the
      `Doc` field first); go to definition / navigate-or-import on Ctrl+Enter (`pontif-tooling`'s
      `DefinitionNavigator.builtinsOnly()`). Pontif comes from the sibling `pontif-framework` on branch `v0.2`,
      `mvn install`ed.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that file
      about which authored colours the construction can reproduce and which have to be declared.

## Later

- [ ] Automation coverage: `--automation=0` (or `-Dautomation=0`) opens a driving socket on a free port and prints it, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
