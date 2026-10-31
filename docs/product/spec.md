# Clipra product spec

Clipra is an Android video editor for phones and tablets, competing with Filmora and CapCut. Its promise is in the first-run screen: **no account, no upload, no watermark**. Every AI feature runs on the device with local models. The app is free with two fullscreen ads per export; a one-time 10 PLN purchase (Clipra Pro) removes the ads.

Screens referenced below live in `design/screens/` (see `design/README.md`). The numbers here are the design's numbers; where a value is a product rule it is written as one.

## 1. Principles

1. **On-device only.** Media, transcripts and edits never leave the device. Network is used for three things only: downloading models from their official sources (Wi-Fi only by default), loading ads for free users, and Google Play billing.
2. **Generic layers.** A project is a stack of layers L1 (bottom) to Ln (top). Any content (video, photo, text, caption, audio, adjustment) can go on any layer. A higher layer covers lower ones; every layer contributes sound. Layers only have hide, mute and lock; all other settings belong to clips.
3. **Edit on proxies, export from originals.** Preview uses low-resolution proxies; export always renders the original media.
4. **Nothing hidden behind Pro.** Every editing and AI feature is free. Pro only removes ads.
5. **Phone first, tablet first-class.** Every feature works on a phone. Tablets get a landscape three-pane layout, not a stretched phone UI.

## 2. Projects and formats

Screens: `Main`, `NewProject`, `TabletHome`.

- Home shows: New project, quick actions (Auto captions, Text card, Mosaic layout, tablet adds Import from Gallery), on-device AI status with a link to AI models, recent projects with format badge, duration and last edited, and the crown "Pro" entry.
- Aspect ratios: 9:16 (Reels), 16:9 (YouTube), 1:1 (Square), 4:5 (Feed), 4:3 (Classic), 21:9 (Cinema).
- Layouts ("mosaic" = split screen): Single, Split 2, Split 3, 2×2, 3×3. Each cell is a region clips can be dropped into.
- Resolution: HD 720p, FHD 1080p, 2K 1440p, 4K 2160p. The number is the short side in every ratio; the long side follows the ratio, rounded to an even number. Output size is shown live (for example 9:16 FHD = 1080 × 1920, 16:9 4K = 3840 × 2160, 21:9 FHD = 2520 × 1080).
- Frame rate: 24, 25, 30, 50, 60.
- Background: Black, White, Yellow, Blur. Fills empty space and is the default colour of text cards.
- Projects autosave continuously. The project format must be versioned.

## 3. Editor and timeline

Screens: `Editor`, `Editor--no-clip-selected`, `Editor--after-split`, `TabletEditor`.

- Header: close, project name, format line (`9:16 · FHD · 30 fps · proxies`), Export.
- Preview with selection handles on the selected clip. Transport: timecode, play, undo, redo, add keyframe.
- Timeline: ruler, fixed centre playhead on phone, layers L1 to L6 shown in the design (more can be added), transitions on cuts, linked clips, beat markers on music.
- Main tools (nothing selected): Edit, Audio, Text, Layers, Filters, Transitions, Captions, Transcript, Color, Cutout, Reframe, Masks, Format.
- Clip tools (clip selected): Split, Speed, Audio, Filters, Mask, Layer, Color, Cutout, Stabilize, Linked, Duplicate, Delete, Crop, Freeze, Reverse, Extract, Replace.
- Tablet toolbar: Split, Delete, Duplicate, Speed, Freeze, Stabilize, Detach audio, Linked, Add layer, Snap, Ripple, zoom.
- Stabilize (clip tool): smooths out handheld shake on the device. Clipra tracks the camera motion, smooths it and moves each frame onto the smooth path, zooming in just enough that no edge shows. Strength 0 to 100 (default 50) trades smoothness against crop, and the crop is shown. The analysis runs in the background; the clip plays unstabilized until it is ready.
- Editing rules: snapping on by default; ripple delete on by default; split at playhead; trim with handles; drag between layers; drop above the top layer creates a new layer.
- Linked clips: a caption or a sound can be linked to a source clip so they move and get cut together. Linked partners of the selected clip are outlined.
- Undo and redo for every edit.

## 4. Layers, transform and blending

Screens: `Layers`, `Layers--transform`, `Layers--layer-list`, `TabletEditor` (Clip tab).

- Blend modes (16): Normal, Multiply, Screen, Overlay, Soft light, Hard light, Darken, Lighten, Dodge, Burn, Add, Difference, Exclusion, Hue, Color, Luminosity. Opacity 0 to 100%.
- Transform: position X/Y, scale, rotation, crop, flip, fit/fill, reset. Keyframes for transform, opacity and masks.
- Layer list: order (drag), hide, mute, lock, add layer.
- Picture-in-picture is just a scaled clip on a higher layer.

## 5. Speed and animation

Screens: `TabletEditor--speed-tab`, `TabletEditor--animate-tab`.

- Constant speed 0.25×, 0.5×, 1×, 1.5×, 2×, 4×. Pitch is kept.
- Speed ramps: Montage, Hero, Bullet, Flash in, Flash out, Custom.
- Clip animations: In (Fade, Zoom in, Slide up, Spin), Out (None, Fade, Zoom out, Slide down), Loop (Ken Burns, Shake, Pulse).
- Freeze frame, reverse.

## 6. Filters

Screens: `Filters`, `Filters--looks`, `TabletEffects`.

- 24 filters plus None, with a strength slider (default 80) and hold-to-compare.
- Looks (13): Vivid, Warm, Cool, Cinematic, Matte, Mono, Noir, Sepia, Vintage, Film, Night, Neon, Pastel.
- Stylized (11): Mosaic, Blur, Glitch, VHS, Grain, Vignette, RGB split, Light leak, Halftone, Duotone, Soft glow.
- Effects stack per clip (tablet shows the stack). Apply to all clips.

## 7. Transitions

Screens: `Transitions`, `Transitions--motion`, `TabletEffects--transitions`.

- 24 transitions plus None, duration 0.1 to 2.0 s (default 0.6), easing (Linear, Ease in-out, Ease out), apply to all cuts, remove.
- Basic (8): Dissolve, Fade black, Fade white, Wipe left, Wipe down, Clock, Iris, Split.
- Motion (9): Slide left, Slide up, Push, Zoom in, Zoom out, Whip pan, Spin, Shake, Cube.
- Stylized (7): Glitch, Flash, Blur, Pixelate, Light leak, Film burn, Ink.

## 8. Masks

Screens: `Masks`, `Masks--rectangle-inverted`, `TabletEffects`.

- Shapes: None (whole clip), Rectangle, Oval. Invert, feather (0 to 40 px), corner radius.
- A mask limits an effect (for example Mosaic over a face).
- Follow face: the mask tracks a face on the device frame by frame.
- Manual keyframes for the mask.

## 9. Color

Screens: `Color`, `Color--hsl`, `Color--lut`, `TabletEditor--color-tab`.

- Adjust: Exposure, Contrast, Saturation, Temperature, Tint, Vignette (−100 to +100).
- HSL: 8 hue ranges, each with hue, saturation, luminance.
- LUT: built-in and imported `.cube` files, with strength.
- Before and after split, Auto, Reset, Paste to other clips.
- Adjustment clips: a clip on any layer that grades every layer below it for its duration.

## 10. Text

Screens: `Text`, `TabletText`.

- Text card: a full-frame clip with a background colour (Black, White, Yellow, Red, Blue, Green, custom) and text.
- Text on any layer over video. Font, weight, alignment, size.
- Effects: None, Outline, Shadow, Glow, Neon, Box, Gradient, Extrude, Glitch, Hollow, Retro, Marker.
- Animation in: None, Typewriter, Fade up, Pop, Slide, Bounce, Wave, Glitch in. Animation out.
- Templates: Text card, Title, Lower third, Subtitle, Callout, End credits. Save as template.
- Safe area guides.

## 11. Audio

Screens: `Audio`, `Audio--before`, `TabletAudio`, `TabletAudio--music-selected`.

- Per clip: volume (0 to 200%), fade in, fade out, before and after compare with waveform and loudness readout.
- Loudness normalization per clip: −14 LUFS (Social, YouTube), −16 LUFS (Podcast, voice), −23 LUFS (Broadcast, EBU R128); true peak limited to −1 dBTP.
- Noise reduction: spectral gate for steady hum and hiss, with strength.
- AI voice cleanup on the device: DeepFilterNet 3 (best, about 8 MB) or RNNoise (lite, older devices), with strength.
- Ducking: lower other clips under speech (default −12 dB, range −3 to −24 dB). On a voice it lowers the other clips while that voice speaks; on music it is "Auto-duck under speech", which lowers the music while any other clip speaks.
- Beat markers on music; cuts snap to beats.
- Record voiceover onto the first free layer at the playhead. Detach audio from a video clip to a free layer.
- "Apply to all clips with speech".

## 12. Auto captions

Screens: `CaptionsSetup`, `CaptionsSetup--small-model`, `CaptionsEdit`, `CaptionsEdit--karaoke`, `TabletCaptions`, `TabletCaptions--transcribe-tab`.

- Whisper runs on the device (whisper.cpp). Models: Tiny 78 MB, Base 148 MB (recommended, default), Small 488 MB, Large v3 Turbo (quantized) 574 MB. Choosing a model that is not installed offers "Download N MB and generate".
- Listen to: Main clips, Voiceover, All tracks. Spoken language: auto-detect or pick. Optional translate to English (Whisper translate task).
- Options: highlight each word as it is spoken, remove filler words, put captions on a new top layer.
- Captions are clips on a layer, linked to the source clip they came from.
- Styles: Clean, Bold pop, Boxed, Karaoke, Minimal. Position Top, Middle, Bottom; size; max 2 lines per caption.
- Editing: tap a word to edit, split, merge with next, words to check (low confidence, underlined), search and replace.
- Export and import `.srt`.
- Offline translation into extra caption languages (language chips), using downloadable language pairs.

## 13. Edit by text

Screens: `Transcript`, `Transcript--fillers-kept`, `TabletTranscript`, `TabletTranscript--nothing-cut`.

- Shows the transcript of clips and voiceover with source and time.
- Cut filler words (um, uh, you know and similar) in one tap; shows how many were found.
- Cut pauses longer than a threshold (0.3 to 2.0 s, default 0.5 s).
- Tap any word or pause to cut or restore it.
- Cuts ripple through every layer so everything after stays in sync.
- Shows new length, number of cuts and time saved; removed ranges show as bands on the tablet timeline.

## 14. Cutout and chroma key

Screens: `Cutout`, `Cutout--chroma-key`.

- Remove background (AI): person segmentation on the device frame by frame, edge refine, hair detail.
- Show behind the subject: the layer below, blur, clear (transparent), colour.
- Chroma key: key colour with eyedropper, similarity, smoothness, spill removal.

## 15. Auto-reframe

Screens: `Reframe`, `Reframe--square-face`.

- Creates a copy of a project in a new ratio (9:16, 1:1, 4:5) with a crop window that follows the subject.
- Follow: Subject, Face, Fixed. Camera motion from smooth to quick. Keep titles and captions inside the frame.
- The crop path can be adjusted later with keyframes.

## 16. Export

Screens: `Export`, `Export--4k-hevc-high`, `ExportProgress`, `ExportProgress--paused`, `ExportProgress--finished`, `ExportDone`, `TabletExport`.

- Resolution 720p, 1080p, 2K, 4K. Frame rate 24 to 60. Codec H.264 (plays everywhere), HEVC (smaller), AV1 (smallest, only if the device has an encoder; otherwise shown disabled with "Not on this phone").
- Quality: Smaller, Recommended, High. Captions: Burn in, Separate `.srt`, Off.
- Also save: cover `.jpg`, project `.otio` (phone); captions `.srt`, audio-only `.m4a`, thumbnail `.jpg`, project `.otio` (tablet).
- Estimated size. Base H.264 bitrates at 30 fps: 720p 7.5, 1080p 12, 1440p 24, 2160p 45 Mbps. Multipliers: frame rate above 30 ×1.5; quality ×0.6 / ×1 / ×1.6; HEVC ×0.6; AV1 ×0.5. Size = bitrate × duration / 8 + about 1.3 MB of audio.
- 4K needs a 2160p hardware encoder; the app checks before export.
- Export renders from original media and keeps running in the background with a notification. Pause, resume, cancel.
- When done: Saved to Gallery screen with play, Share, Open Gallery, Back to editing.
- Free users see two ads per export (section 17).

## 17. Ads and Clipra Pro

Screens: `AdStart`, `AdFinish`, `Pro`, `Pro--owned`, `TabletAd`, `TabletPro`; flow `screens/flows/export-with-ads.png`. Full rules in `monetization.md`.

- Two fullscreen interstitial ads per export for free users: one when the export starts, one when it finishes.
- Clipra Pro: a one-time 10 PLN purchase through Google Play that removes the ads on every device signed in to the same Google account. Not a subscription. Restore purchase available.
- Entry points: crown "Pro" on Home (phone) and "Go Pro · 10 PLN" on tablet Home, the notice under the Export button, the card on the Saved screen.
- Ad privacy choices are reachable from the Pro screen (and settings).

## 18. AI models

Screens: `Models`, `Models--tiny-downloaded`, `Onboarding`.

- Lists Whisper models, voice cleanup models and offline translation pairs with size and state (Get, Installed, Built in, Default).
- Shows total storage used by speech and voice models.
- Download on Wi-Fi only (default on). Use the phone's NPU or GPU when it can (default on).
- Face and subject tracking models are built into the app.
- Models come straight from their official sources: Whisper from the whisper.cpp project on Hugging Face, DeepFilterNet 3 from its GitHub repository, translation models from Mozilla's Firefox Translations registry. Each file is checked before use.
- Sizes are real: before a download, the size of the official file; once installed, the size of the files on disk. They are shown in decimal units, as Android's storage settings count them (1 MB = 1,000,000 bytes), so Whisper Base shows 148 MB. The sizes in the design are placeholders.

## 19. First run

Screen: `Onboarding`.

- Promise: No account. No upload. No watermark.
- Three on-device highlights: captions and editing by text offline, voice cleanup, face blur and reframing.
- Choose what to download now (Whisper Base and DeepFilterNet 3 preselected, Whisper Small optional) with the total size; skip is allowed.
- Ad consent (where required by law) is collected with Google's consent form after onboarding, before the first ad request.

## 20. Tablet

Screens: all `Tablet*` boards.

- Landscape layout: tool rail (Media, Audio, Text, Effects, Transitions, Captions), a library panel, the preview, an inspector, and a full-width timeline with layer headers.
- Inspector tabs for a clip: Clip, Color, Audio, Speed, Animate.
- Home combines the project grid with an inline New project panel.

## 21. Out of scope for v1

- Cloud sync, accounts, collaboration, stock media libraries.
- Desktop or iOS versions.
- Subscriptions. The only purchase is Clipra Pro.
