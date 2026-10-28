# Clipra design

This folder is the visual spec for Clipra. Every screen the app needs is here as a picture, as a static HTML snapshot with exact CSS values, and as the interactive source the design was made in.

The source of truth is the Clipra design canvas on claude.ai (private to the owner): https://claude.ai/artifact/BPYfomvHnzKjBB8MLLVt5t. This folder is an export of it. If the canvas changes, re-export (see [Updating this folder](#updating-this-folder)).

## What is in here

| Path | What it is | Use it for |
|---|---|---|
| `screens/phone/<Board>.png` | Phone screens, 390×844 pt at 2x | Looking at a screen |
| `screens/tablet/<Board>.png` | Tablet screens, 1280×800 dp at 1.25x | Looking at a tablet screen |
| `screens/states/<Board>--<state>.png` | The same boards after an interaction (another tab, a toggle, a finished export) | Behaviour that the default screenshot does not show |
| `screens/flows/*.png` | Strips of several screens with arrows | Understanding a whole flow |
| `static/<Board>.html` | Rendered HTML snapshot, no scripts | Exact sizes, colours, spacing, copy (open in a browser, inspect) |
| `canvas/<Board>.dc.html` | Interactive source of each board | Behaviour rules and formulas (read the `renderVals()` script) |
| `canvas/canvas.json` | Board positions and titles on the canvas | Grouping of screens into flows |
| `tokens.json` | Colours, type, radii, spacing, timeline metrics | Building the Compose theme |
| `design-system.md` | Components and rules, in words | Building shared components |

## Reading a `.dc.html` source

Each board is one HTML file in the "Design Component" format used by the canvas:

- Markup is plain HTML with inline styles. Inline styles are the spec.
- `{{name}}` is a value computed in the `renderVals()` method of the `Component` class at the bottom of the file.
- `<sc-for list="{{items}}" as="x">` repeats markup per item; `<sc-if value="{{flag}}">` shows markup conditionally.
- `onClick="{{fn}}"` wires a handler returned by `renderVals()`. State lives in `this.state`.
- `<a href="Other.dc.html">` is a prototype link to another board.

The scripts contain real product rules worth copying, for example the export size estimate (`Export.dc.html`), filler and pause detection (`Transcript.dc.html`), model storage maths (`Models.dc.html`), the timeline geometry (`Editor.dc.html`, `TabletEditor.dc.html`) and the ad countdown (`AdStart.dc.html`).

These files need the canvas runtime (`support.js`) to run, which is not in this repo. Use the PNGs and the static HTML to view them.

## Board index

Phone boards are 390×844, tablet boards 1280×800. "States" lists the extra screenshots in `screens/states/`.

### Start and edit

| Board | Screen | States | Plan area |
|---|---|---|---|
| `Onboarding` | First run: privacy promise, pick models to download | `small-checked` | Onboarding |
| `Main` | Home: new project, quick actions, on-device AI status, recent projects, crown "Pro" entry | | Projects |
| `NewProject` | Aspect ratio, mosaic layout, resolution, frame rate, background, output size | `16x9-2x2-4k` | Projects |
| `Editor` | Preview, transport, layered timeline (L1 to L6), clip tools | `no-clip-selected`, `after-split` | Timeline |
| `Layers` | Blend modes and opacity, transform, layer list with hide, mute, lock | `transform`, `layer-list` | Compositing |

### Effects and look

| Board | Screen | States | Plan area |
|---|---|---|---|
| `Filters` | 24 filters incl. Mosaic, strength, categories | `looks` | Effects |
| `Transitions` | 24 transitions, duration, categories | `motion` | Effects |
| `Text` | Text card: colour, 12 effects, animations | | Text |
| `Masks` | None, rectangle, oval; invert, feather, radius; follow face; keyframe | `rectangle-inverted` | Effects |
| `Color` | Adjust, HSL, LUT; before and after split | `hsl`, `lut` | Effects |

### On-device AI

| Board | Screen | States | Plan area |
|---|---|---|---|
| `CaptionsSetup` | Whisper model choice, language, options | `small-model` | Captions |
| `CaptionsEdit` | Caption styles, segment list, words to check, translate | `karaoke` | Captions |
| `Transcript` | Edit by text: cut fillers and pauses, tap words to cut | `fillers-kept` | Captions |
| `Cutout` | AI background removal and chroma key | `chroma-key` | Vision |
| `Reframe` | Auto-reframe 16:9 to 9:16, 1:1, 4:5 | `square-face` | Vision |

### Export with two ads

| Board | Screen | States | Plan area |
|---|---|---|---|
| `Export` | Resolution, frame rate, codec, quality, captions, extras, size estimate, ad notice | `4k-hevc-high` | Export |
| `AdStart` | Fullscreen ad 1 of 2, shown when export starts. Placeholder for the network's creative | `closable` | Monetization |
| `ExportProgress` | Rendering progress, background notice, files being saved | `paused`, `finished` | Export |
| `AdFinish` | Fullscreen ad 2 of 2, shown when the export finishes | `closable` | Monetization |
| `ExportDone` | Saved to Gallery, share, Pro upsell | | Export |

### Audio, Clipra Pro and AI models

| Board | Screen | States | Plan area |
|---|---|---|---|
| `Audio` | Per-clip volume, fades, loudness, noise reduction, AI voice cleanup, ducking | `before` | Audio |
| `Pro` | Clipra Pro: 10 PLN one-time purchase that removes ads | `owned` | Monetization |
| `Models` | Downloaded models, storage, Wi-Fi only, NPU or GPU | `tiny-downloaded` | Captions |

### Tablet

| Board | Screen | States | Plan area |
|---|---|---|---|
| `TabletHome` | Projects with inline new-project panel, crown "Go Pro" | `9x16-split3` | Tablet |
| `TabletEditor` | Tool rail, media bin, preview, inspector (Clip, Color, Audio, Speed, Animate), full timeline | `color-tab`, `audio-tab`, `speed-tab`, `animate-tab`, `after-split` | Tablet |
| `TabletEffects` | Filter and transition library, effect stack with mask | `transitions` | Tablet |
| `TabletText` | Templates, effects, text inspector | | Tablet |
| `TabletCaptions` | Transcript, language chips, style and transcribe tabs | `transcribe-tab` | Tablet |
| `TabletAudio` | Audio sources, cleanup inspector, ducking and beats for music | `music-selected` | Tablet |
| `TabletTranscript` | Edit by text with cut bands on the timeline | `nothing-cut` | Tablet |
| `TabletExport` | Export settings with preview, extras, ad notice | `720p-h264` | Tablet |
| `TabletAd` | Fullscreen landscape ad, same two slots as phone | `closable` | Monetization |
| `TabletPro` | Clipra Pro dialog | `owned` | Monetization |

## Flows

| Image | Flow |
|---|---|
| `screens/flows/first-run.png` | Onboarding, Home, New project, Editor |
| `screens/flows/export-with-ads.png` | Export, ad 1, rendering, finished, ad 2, saved |
| `screens/flows/clipra-pro.png` | Crown on Home, Pro purchase, owned, export notice |
| `screens/flows/captions.png` | Caption setup, caption editing, edit by text |
| `screens/flows/effects.png` | Clip tools, filters, mask, colour |
| `screens/flows/ai-tools.png` | Cutout, chroma key, reframe, voice cleanup |

## Placeholders and demo-only controls

- Ad boards show bracketed placeholders (`[Ad creative]`, `[Advertiser]`, `[Action]`). The real creative comes from the ad network and the app cannot draw over it. Only the timing and the close behaviour are Clipra's.
- Prices show "10 PLN". In the app, always show the formatted price that Google Play returns for the user's country.
- "Show the purchase screen again (demo)" and "Reset demo" on the Pro boards exist only to replay the prototype. Do not build them.
- The export progress board animates from 62% to 100% to demo the finish. Real progress comes from the export engine.
- Gradients stand in for real video frames and photos.

## Updating this folder

1. Change the design on the canvas.
2. Re-export: screenshots and static snapshots are produced by rendering each board with a headless browser and the canvas runtime, at 2x for phone and 1.25x for tablet. States are captured by clicking the controls listed in the table above.
3. Copy the updated `.dc.html` files and `canvas.json` into `canvas/`.
4. Note what changed in the pull request, and update any affected issues in Project 6.
