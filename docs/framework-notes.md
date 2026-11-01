# Framework notes

Findings about VexelRay, vexelray-gui, Kronometer, tactroller and atchung that came out of building
**VexelRay IDE** — things the framework does not have, does not document, or does in a way that cost time to
discover.

**Why this file exists.** An application built on a framework is the only place its gaps are visible, and they
are visible exactly once: at the moment they are worked around. A workaround with no note beside it becomes a
piece of application code nobody can tell from a design decision, and the framework never hears about it. So
the rule is to write the note *when the workaround is written*, not in a retrospective, and to write down what
was measured rather than what was assumed.

**Before writing a workaround, ask whether it is a component.** If the answer is "every project on this
framework will write these same four calls" — that is a finding, and the fix belongs upstream. Say so here
with that framing, so the [retrospective](retrospective.md) at the end of the milestone has a candidate rather than a
complaint.

## How to write one

    ## FN-1 · One line saying what is missing 🔬
    
    What was wanted, what the framework offers instead, and what was done about it.
    Then: what it costs, and what the framework could do about it.

The markers are a filter, not decoration:

| | |
| --- | --- |
| 🔬 | a framework gap — something upstream could fix |
| 💡 | an idea, not yet a finding |
| 📋 | carried over: needs re-verifying against a newer build before it is repeated |

## Findings

## FN-1 · A job replies once, so slow work cannot be watched filling in 🔬

Open pictures the folder under the pointer (`SurveyFolder`). In 0.1 the survey ran on a thread of its own, handed the
picture its counts every 120 ms so a large build was seen being read, and gave up between files once the pointer had
moved on. A `@Job` returns one reply and is not stopped part way (components.md §10: cancellation is the caller's).

Done here: the survey is bounded (6000 files, 900 Java files read), and `Preview` keeps one survey running, asking for
wherever the pointer has got to when it lands, so a sweep down twenty rows costs two surveys. What is lost is the
climbing numbers: a large build shows its name, then its whole picture at once. Add's archive (`CreateFile`) is the
same: a big folder says nothing until the zip is done.

What the framework could do: let a job send while it runs (a `Sender` of its own reply type, or a progress topic
beside the reply), and hand it a cancellation token the caller can trip, which components.md already calls additive.
Any job on hover or on typing wants both.

## FN-2 · A Button says nothing about the pointer being on it 🔬

Open pictures a recent folder while the pointer is on its button. `TreeView` publishes `Hovered`; `Button` publishes
only `Pressed` and `Toggled`, and it takes its own node's `NodeEvent.STATE` for its wash, so `gui.hear` on that node is
refused. Done here: each button sits in a cell of its own, and the view hears the cell's state, since a node is hovered
while the pointer is on anything inside it (`Picker.recentCell`). Costs a node per button and a paragraph explaining
it. What the framework could do: a `Button.Hovered` state topic, as `TreeView` has.

