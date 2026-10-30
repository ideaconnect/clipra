# 0001. Media engine: Clipra's layer compositor inside Media3

- Status: Accepted
- Date: 2026-10-28
- Issues: #6 (spike), #14, #15, #56

## Context
Clipra's model is any content on any layer, gaps between clips, 16 blend modes and transitions across cuts. Preview and export must look the same.

Media3 1.11 composites several video sequences, but only with fixed alpha blending. Its export pipeline also hardcodes the compositor, so it can't be replaced for export. Multiply, Screen and the other blend modes need the colour already on the canvas, and Media3 can't cross-fade within one sequence. The spike (`docs/spikes/engine.md`) has the details.

## Decision
Clipra draws every layer of a frame itself, in one GL effect: `LayerCompositor` in `core:engine`.

Media3 does what it does well:
- the clock and preview (`CompositionPlayer`);
- decoding, encoding and muxing (`Transformer`);
- audio mixing.

A composition is a single blank video sequence, as long as the timeline at the project frame rate, carrying `LayerCompositor` as its effect. Each layer's sound is a separate audio sequence. Effects, colour, masks, cutout, chroma key and stabilization run as passes inside the compositor, in a fixed order. Clips are edited on proxies and exported from the originals.

## Consequences
- Preview and export share the same drawing code, so they match. Golden-image tests (`core/engine/src/androidTest`) check the passes.
- Every visual feature is a shader pass in `LayerCompositor` and a field on `RenderClip`, not a Media3 effect per clip.
- Media3 upgrades are lower risk, because Clipra uses only the stable parts (clock, codecs, audio).
- The compositor's speed is Clipra's responsibility. Preview frame rate and export time are budgets (`docs/architecture.md`, section 11) to confirm on real phones (#77).
