# Refusing to quit

Status: **implemented** on `text-editor-buildout`.

Closing the main window is quitting, so it goes through `GuiApp.onCloseRequest`. Nothing unsaved closes straight
through; anything unsaved gets a dialog naming it.

## Why it is a request and not a boolean

Close handlers run on worker threads, so there is no answer to return — by the time the application had asked the
user anything, the frame that posed the question would be long over. `CloseRequest` is instead an object with a
deadline of its own: hold it as long as the conversation takes, then `proceed()` or `cancel()`. The window stays
**open, live and drawing** while it is unanswered, which is what lets the answer come from a dialog.

The obligation that comes with that: *every* path must answer exactly once, or the window stays open forever and
the application looks hung. Ours answers on all four — nothing unsaved (`proceed`), Discard (`proceed`), Cancel
(`cancel`), and Save all (`proceed`, or `cancel` if a write did not land). The dialog's Escape key and its own
close button both run the cancel button's action, so dismissing the question without choosing is answered too.

## Dirty tracking is a snapshot, not a flag

Each tab keeps `savedText` — the text as last loaded or saved — and `dirty()` compares against it.

The obvious alternative, a `boolean` set from `TextField.onChange`, loses a race: change handlers run on worker
threads, so the programmatic `editor.text(...)` that *loading a file* performs would set the flag after the load
path had cleared it, and every freshly opened file would claim to be modified. Comparing on demand cannot race
with anything. It also answers more honestly — type something and undo it back, and the document is clean,
because it is.

The cost is one extra copy of each open document in memory, which is bounded by `TextFile` refusing oversized
files anyway.

## Save all

Runs on the GUI thread, from the request queue, because a never-saved document needs the native save dialog and
that is modal and belongs to this thread. It selects each tab before prompting — not cosmetic, since the dialog's
start directory and suggested filename come from the *active* tab, so prompting for a tab the user cannot see
would be labelled from a different one.

Any document that does not land — a cancelled dialog, an unwritable path — **cancels the quit** and says which
one in the status line. The user asked to save everything; quitting anyway would be precisely the loss the
question existed to prevent.

## What was verified

Driving `app.window().requestClose()` from a temporary probe, since a close button cannot be clicked from a test:

| | result |
| --- | --- |
| nothing unsaved | closes at once — exits on the frame the request was made |
| one unsaved document | close held, `Modals.showing()` true, still running 340 frames later |
| load, edit, save | `dirty` false → true → false, and the edit is on disk |

Note the first row of that third case: `dirty` is false immediately after loading a file, which is the
false-positive the flag approach would have produced.

The three buttons themselves were not clicked — there is no way to press a dialog button from a headless test.
Discard and Cancel are one call each (`proceed`/`cancel`), and Discard's is the same call the clean path
exercises.

## Worth watching

`Modals` disables the other windows while a dialog is up, and Save all opens a *native* save dialog immediately
after the modal is asked to close. The request queue puts at least a frame between them, which should be enough
for the main window to be re-enabled first — but a native dialog parented to a still-disabled window is the kind
of thing that misbehaves on one Windows build and not another. If Save-all-with-an-untitled-document ever feels
stuck, that is the first place to look.

## Not done

No dirty marker in the tab bar. The dialog names what is unsaved at the moment it matters, but there is nothing
telling you *before* you try to quit. An asterisk on a changed tab is the obvious next thing: `dirty()` already
answers it, and the per-frame drain is already there to notice a change.
