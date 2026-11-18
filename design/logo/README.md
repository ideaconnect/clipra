# Clipra logo

The app icon and logo: a film strip bent into a "C", cut by scissors, with a play button and a playhead. Vectorized from the owner's artwork (`source.jpg`).

| File | What it is | Used for |
|---|---|---|
| `clipra-icon.svg` | The logo: the mark on the accent tile with rounded corners (radius 295 of 1254) | Master for the in-app logo and any place that shows the whole logo |
| `clipra-mark.svg` | The mark alone, in onAccent | Master for the launcher icon and the Play store icon |
| `clipra-icon-1024.png` | `clipra-icon.svg` at 1024 x 1024 | Previews |
| `source.jpg` | The artwork the vectors were traced from | Reference only |

Both SVGs use a 1254 x 1254 box (the artwork's pixels), so the mark sits on the tile exactly as in the artwork. Colours are the design tokens: `accent` #FFD23F for the tile and `onAccent` #141210 for the mark. The artwork itself uses a deeper yellow (#FEC502); the token keeps the logo in line with the rest of the UI.

## Where it is used

- **Launcher icon:** `app/src/main/res/mipmap-anydpi/ic_launcher.xml`, an adaptive icon. The background is `@color/launcher_accent`; the foreground and the monochrome (themed icon) layer are `app/src/main/res/drawable/ic_launcher_foreground.xml`, the mark scaled so its farthest point is 32 dp from the centre, inside the 33 dp safe zone of every launcher mask.
- **In-app logo:** `core/designsystem/src/main/res/drawable/designsystem_ic_logo.xml` (`ClipraIcons.Logo`), shown on Home, the first run and the tablet editor.
- **Play store:** `scripts/store/graphics.py` draws the 512 x 512 icon from `clipra-mark.svg` (the mark on a full-bleed accent square; Play rounds the corners). The feature graphics show the mark next to the name.

## How the vectors were made

The artwork was upscaled 4x, slightly blurred against JPEG noise and split into the mark and the tile. The finger rings, their holes and the pivot dot were redrawn as true circles (the artwork draws them as faint polygons). The mark was traced with potrace (`-t 60 -a 1.2 -O 0.2`) and matches the artwork at 99.3% (intersection over union at 1254 x 1254). The tile is a rounded square fitted to the artwork's corners (mean error 0.5 px). After a change to an SVG, update the two drawables to the same path data.
