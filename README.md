# text-editor-vexel-demo

A deceptively simple text editor built on [vexelray-gui](../vexelray-gui): a title bar, a multiline
`TextField` (word wrap, line numbers, selection, cut/copy/paste via the OS clipboard, caret-follow
scrolling), and a status line — rendered as one batched SDF draw.

## Prerequisites

The sibling stack installed to the local Maven repo, in order: `supirvast`, `vexelray`,
`tactroller` (+ `atchung`), `vexelray-gui`. Java 25, and a Vulkan-capable GPU to run windowed.

## Run

```bash
mvn compile exec:exec
```

Headless capture to PNG (no GPU window / input backend needed):

```bash
mvn compile exec:exec "-Dapp.args=--capture"
```

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative.
