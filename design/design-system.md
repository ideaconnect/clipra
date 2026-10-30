# Clipra design system

Values are in `tokens.json`. This page explains how they are used. Units: phone boards are drawn at 390×844 and map 1:1 to dp; tablet boards are 1280×800 dp.

## Look

- Dark only. Background `#0D0D0F`, surfaces step up `#131316` → `#17171A` → `#1F1F23`.
- One accent: yellow `#FFD23F` with near-black text `#141210` on it. Used for the primary action, the current selection, the playhead and Pro.
- Green `#8FE388` on `#1F2A1F` means "runs on this device" (On-device badge, Installed, Done). It is never used for anything else.
- Clip colours identify content type on the timeline: text `#A8431A`, photo `#5B45C4`, caption `#1F4FC2`, music `#0E4F49` with `#5EE0C8` waveform, voice `#5A2238` with `#FF9CC2` waveform, adjustment clip as a purple diagonal hatch with `#6E5FA8` border. Video clips show their frames with an audio strip along the bottom.
- No gradients in UI chrome, no emoji, no drop shadows except over video (picture-in-picture, captions).

## Type

- Bricolage Grotesque 700/800 for screen titles (22), hero titles (34), the brand (26) and big numbers (export percent 56, edit-by-text length 34, price 32).
- Geist for all UI text. Section labels 13/600 in `#A6A5AD`, body 14/500, captions 12/400.
- Geist Mono for anything that is a measurement: timecodes, resolution, file sizes, LUFS, Mbps, clip file names.
- Fonts are OFL. Bundle them in the app; do not download at runtime.

## Layout

- Phone: 16 to 20 dp side padding, 36 dp status bar area, header 52 dp with a 44 dp back or close button on the left and the primary action on the right.
- Bottom action area: primary button 56 dp, radius 16, 22 to 26 dp bottom padding.
- Sections stack with 10 to 14 dp gaps. Section label sits 6 dp above its control.
- Tablet: 28 dp status area, 52 dp header, 72 dp tool rail on the left, a 300 dp library panel, flexible preview, 320 dp inspector on the right, and a 320 dp tall timeline across the bottom.

## Components

| Component | Spec |
|---|---|
| Primary button | 56 high, radius 16, accent fill, 16/700 text, optional leading icon 20 |
| Secondary button | 48 to 52 high, radius 14, `#1F1F23` fill or 1.5 `#3A3A40` outline |
| Segmented control | Container `#17171A` radius 14 with 4 padding; segments 44 high radius 10; selected segment accent fill (or `#F4F3EF` fill with dark text for tabs) |
| Choice card | 56 to 74 high, radius 14, 1.5 border; selected border accent and fill `#221F12` |
| Chip | 36 to 40 high pill; selected accent fill or `#221F12` fill with accent border. Visible chips may be 32 to 36 high but must keep a 48 dp touch area |
| Switch | Track 48×28 (44×26 compact), knob 22; on = accent track and dark knob; off = `#2A2A30` track and `#B4B3BB` knob. Always `role=switch` semantics |
| Slider | Accent active track, label on the left (60 to 80 wide), value on the right in Geist Mono |
| Card | `#17171A`, radius 16, 12 to 14 padding |
| On-device card | `#131A13` fill, `#22331F` border, green icon tile |
| Pro card | `#1B1910` fill, `#4A3F14` border, crown on an accent circle |
| Bottom nav (phone home) | 3 items, 56 high, active item has a `#2B2716` pill behind the icon |
| Tool rail (tablet) | 72 wide, icon over label, active item `#2B2716` tile |
| Toolbar (phone editor) | Horizontally scrolling 60×60 icon over label tools; a selected clip swaps the toolbar for clip tools with a close button on the left |

## Timeline

- Layers are numbered from the bottom: L1 is the lowest, the highest number is on top. Anything can go on any layer. The layer header shows the number chip plus hide, mute and lock.
- Phone: 32 px per second, playhead fixed at x 195 (centre). Tablet: 48 px per second with a 120 dp layer header column.
- Clip anatomy: label chip top-left (Geist Mono 10), video frames, a 13 dp audio strip at the bottom of video clips, link icon top-right when linked, trim handles 12 dp wide in accent when selected.
- Linked partners of the selected clip get a 1.5 dp dashed accent outline.
- Transitions sit on cuts as a small white tile with the transition icon.
- Music shows beat markers as accent ticks on the top edge when beat detection is on.
- Edit by text shows removed ranges as hatched red bands across all layers.

## Accessibility

- Touch targets at least 48 dp (Material's minimum), on phone and tablet (#75). Visible controls can be smaller only if the touch area is extended. The timeline's 28 dp lanes are the exception, so six layers fit; every clip can also be picked from the Layers list.
- Text contrast at least 4.5:1, including 11 to 12 pt grey labels. The palette above passes on its backgrounds.
- Every icon-only control has a content description. Timeline clips announce type, name, duration, layer and linked or selected state.
- Disabled options (for example AV1 when the device has no encoder) stay visible, are announced as unavailable, and explain why.

## Copy

- Sentence case everywhere. Short, plain verbs ("Export to Gallery", "Cut pauses").
- Name features the same way everywhere: "Edit by text", "Cutout", "Auto captions", "Clipra Pro", "AI models".
- Show measurements with units and Geist Mono: `0:42`, `1080×1920`, `−14 LUFS`, `64.3 MB`.
- Use a regular hyphen, never an em dash.
