# Testing an interaction, not a tree

Status: **harness available** (`vexelray-gui-harness`); no tests in this repo use it yet.

Every test this application could write before now called `Gui.frame()` itself. That proves a tree lays
out, a handler fires, a document loads — and it cannot prove the thing that actually broke here, which
is whether a **frame arrives on its own** after a click.

**Reveal in Navigator** is the case to keep in mind. It did nothing until the mouse moved, and the
telling detail was that the drawer's animation, when it finally came, played *from the beginning* rather
than being found already in progress. That is the signature of an execution problem rather than a
drawing one: the handler had not run at all, because it had queued its work for a frame nobody asked
for. No test that draws its own frames can distinguish those two, and both feel identical to a user.

## What the harness gives you

`HarnessApp` runs this application's **real** frame loop — real `GuiApp`, real window, real swapchain,
real pixels — with the four methods the loop uses to decide whether to draw under the test's control.
The window is created and never shown.

```xml
<dependency>
    <groupId>dev.vexelray.gui</groupId>
    <artifactId>vexelray-gui-harness</artifactId>
    <version>${vexelray-gui.version}</version>
    <scope>test</scope>
</dependency>
```

```java
try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("editor", 1100, 720))) {
    harness.settle();                          // let start-up finish
    long before = harness.frames();

    harness.click(x, y);                       // press + release, no pointer motion
    assertTrue(harness.awaitFrame(before, 3_000), "a click has to produce a frame");
    assertTrue(harness.await(() -> ws.at(0) != null, 2_000));
}
```

**The wait really waits.** `waitEvents` blocks until something calls `postWake` or the budget expires.
That is the design rather than a detail: a harness that returned immediately would let the loop spin,
and a spinning loop draws the next frame whether or not anything asked for one — so every test would
pass, including against the bug it was written for.

## What is worth asserting here

| Interaction | The assertion | What it caught |
|---|---|---|
| A file in the project tree | the tab opens, in one frame | the navigator is its own `Gui`; only the main tree was wired |
| **Reveal in Navigator** | the drawer opens down to the file and selects the row, with no **frame per hop** | a three-queue chain, each step waiting for the next frame |
| Close all, on a tab's menu | the tabs go | the same chain, milder because its mutations kept nudging the loop |
| Ctrl+S with no pointer near the window | the save happens | commands ran through a queue drained mid-frame |

Reveal is the one worth writing first, and it should assert the **frame count**, not just the outcome:

```java
long before = harness.frames();
harness.click(tabX, tabY, Button.RIGHT);       // the tab's context menu
harness.click(revealX, revealY);
assertTrue(harness.await(() -> folder.isOpen(), 2_000));
assertTrue(harness.frames() - before < 5,
        "a reveal is one frame's work, not one frame per step: " + (harness.frames() - before));
```

That upper bound is the regression guard. The bug was never "it does not work" — it was "it takes a
frame per hop", and only a count catches that coming back.

The bound counts hops, not work, and the difference started to matter once reveal learned to *unfold*:
a file deep inside the folder the drawer is already rooted at no longer moves the root, so the tree
opens down to it one level at a time, off the frame loop, taking as many frames as its listings take
to land. Point the test at a file whose folder is already open — nothing left to fetch — and the count
means exactly what it always meant. Point it at a shut subtree and it is measuring the disk.

`window().budgets()` records what the loop parked on each iteration, so a test can assert the editor
**parked** rather than merely looked idle. Note that a focused editor never goes fully quiet: the caret
blinks, which is a real change twice a second and correct.

## Two things that will bite

**Handlers are asynchronous.** They run on the worker executor, so a frame arriving does not mean the
handler that asked for it has finished. Asserting immediately after `awaitFrame` races it — use
`await(condition, timeout)` for anything the application does *because* of the click.

**It needs a Vulkan device.** An integration harness, not a unit fixture. On a machine without a GPU it
fails to start rather than silently proving nothing — right way round, but it does mean these tests
cannot run on a GPU-less CI box without lavapipe.

## Why not `--capture`

`--capture`, `--capture-folder` and `--capture-terminal` render a settled tree to a PNG with no window.
They have no pointer and no loop: they call `Gui.frame()` twice and draw once. So they prove what the
application *looks* like and are structurally blind to input, scheduling, and anything the frame loop is
responsible for.

The two are complements — captures for pixels, `HarnessApp` for behaviour. Neither of them, and no unit
test in this repo, could have caught Reveal.

See [kronometer/docs/render-on-demand.md](../../kronometer/docs/render-on-demand.md) for why the loop
parks at all, and what has to wake it.
