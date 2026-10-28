# Clipra roadmap

The plan that turns the design into a working app. It lives as issues in this repo, grouped by milestone and tracked in GitHub Project 6 (ClipraPlan). This file is the readable overview; the issues are the working copy.

Each milestone has one epic issue with goals, screens, exit criteria and risks, and task or spike issues as its sub-issues. Spikes come first because they decide the architecture.

## Milestones

| Milestone | Goal | Epic | Tasks |
|---|---|---|---|
| M0 · Foundations | A buildable app skeleton with CI, the Clipra theme, and the three risky technical bets (layered compositing, whisper.cpp, export speed) proven on real devices. | Foundations: skeleton, CI, theme and technical spikes | 6 |
| M1 · Projects and timeline | Create a project in any format, import media, arrange it on generic layers, preview it on proxies and edit it with split, trim, move, undo and redo. | Projects, formats and the layered timeline | 8 |
| M2 · Compositing | Clips on higher layers can be moved, scaled, rotated, blended with 16 modes, keyframed, sped up or slowed down, and graded by adjustment clips. | Compositing: transform, blend modes, keyframes and speed | 5 |
| M3 · Effects | All 24 filters (Mosaic included), 24 transitions, rectangle and oval masks, and color grading with LUTs work in preview and export. | Effects: filters, transitions, masks and color | 5 |
| M4 · Text | Text cards and titles with 12 effects, animations and templates. | Text: cards, titles, effects and animations | 4 |
| M5 · Audio | Per-clip volume, fades and loudness, noise reduction, on-device AI voice cleanup, ducking, beat markers and voiceover recording. | Audio: per-clip loudness, cleanup and music tools | 6 |
| M6 · Captions and edit by text | Whisper captions on the device with styles, editing, .srt and offline translation, plus editing the video by deleting words and pauses. | Captions and edit by text with on-device Whisper | 6 |
| M7 · Vision tools | Person cutout, chroma key, face-following masks, auto-reframe and video stabilization, all on the device. | Vision tools: cutout, chroma key, face follow and reframe | 5 |
| M8 · Export | Export in HD to 4K with H.264, HEVC or AV1 from original media, in the background, with extras (.srt, .jpg, .m4a, .otio) and a Saved screen. | Export: from originals, in the background, with extras | 5 |
| M9 · Ads and Clipra Pro | Two compliant interstitial ads per export for free users, consent where required, and a 10 PLN one-time Clipra Pro purchase that removes them. | Monetization: two export ads and Clipra Pro (10 PLN) | 5 |
| M10 · Tablet | A landscape three-pane tablet experience for every feature. | Tablet: landscape three-pane layout | 4 |
| M11 · Launch | Onboarding, settings, accessibility, Polish and English, performance on real devices, and a Google Play release. | Launch: onboarding, settings, accessibility, localization and release | 6 |

## Suggested order

1. M0 first; the spikes gate M1 to M3.
2. M1 (timeline) unblocks almost everything. M6-models and M9-billing can start in parallel with M1.
3. M2 to M5 can run in parallel once M1 preview and edit commands exist.
4. M6 and M7 depend on the spikes and on M1; M8 depends on M1 and the engine decisions.
5. M9 needs M8 (export events) for ads; billing and consent are independent.
6. M10 tablet work follows each phone feature; M11 closes the release.

## M0 · Foundations

A buildable app skeleton with CI, the Clipra theme, and the three risky technical bets (layered compositing, whisper.cpp, export speed) proven on real devices.

- **Epic** `E0` Foundations: skeleton, CI, theme and technical spikes
- **Task** `M0-skeleton` Create the Android project skeleton and module structure (P0)
- **Task** `M0-ci` Set up CI: build, lint, tests and debug APK artifact (P0) - depends on M0-skeleton
- **Task** `M0-theme` Build the Clipra design system in Compose (P0) - depends on M0-skeleton
- **Spike** `M0-spike-engine` Spike: layered compositing, blend modes and transitions on Media3 (P0) - depends on M0-skeleton
- **Spike** `M0-spike-whisper` Spike: whisper.cpp on Android with word timestamps (P0) - depends on M0-skeleton
- **Task** `M0-adr` Record architecture decisions and the dependency license policy (P1) - depends on M0-spike-engine, M0-spike-whisper

## M1 · Projects and timeline

Create a project in any format, import media, arrange it on generic layers, preview it on proxies and edit it with split, trim, move, undo and redo.

- **Epic** `E1` Projects, formats and the layered timeline
- **Task** `M1-model` Project model, persistence and autosave (P0) - depends on M0-skeleton
- **Task** `M1-newproject` New project flow: ratio, layout, resolution, frame rate, background (P0) - depends on M1-model, M0-theme
- **Task** `M1-layers` Generic layer model: anything on any layer, hide, mute, lock (P0) - depends on M1-model
- **Task** `M1-timeline` Phone timeline UI (P0) - depends on M1-layers, M0-theme
- **Task** `M1-preview` Preview player on proxies (P0) - depends on M0-spike-engine, M1-media
- **Task** `M1-edit` Edit commands, snapping, ripple, linked clips, undo and redo (P0) - depends on M1-layers
- **Task** `M1-media` Media import, media bin and proxy generation (P0) - depends on M1-model
- **Task** `M1-mosaic` Mosaic (split-screen) layouts (P1) - depends on M1-preview, M2-transform

## M2 · Compositing

Clips on higher layers can be moved, scaled, rotated, blended with 16 modes, keyframed, sped up or slowed down, and graded by adjustment clips.

- **Epic** `E2` Compositing: transform, blend modes, keyframes and speed
- **Task** `M2-transform` Transform: position, scale, rotation, crop, flip, fit (P0) - depends on M1-preview
- **Task** `M2-blend` Blend modes (16) and opacity (P0) - depends on M0-spike-engine, M2-transform
- **Task** `M2-keyframes` Keyframes and easing for transform, opacity and masks (P1) - depends on M2-transform
- **Task** `M2-speed` Speed, ramps, freeze frame and reverse (P1) - depends on M1-edit
- **Task** `M2-adjustment` Adjustment clips (P2) - depends on M2-blend, M3-color

## M3 · Effects

All 24 filters (Mosaic included), 24 transitions, rectangle and oval masks, and color grading with LUTs work in preview and export.

- **Epic** `E3` Effects: filters, transitions, masks and color
- **Task** `M3-filters` Filter pipeline and 24 filters with strength (P0) - depends on M1-preview
- **Task** `M3-transitions` Transitions (24) with duration and easing (P0) - depends on M0-spike-engine, M1-edit
- **Task** `M3-masks` Masks: rectangle and oval with invert, feather, radius and keyframes (P0) - depends on M3-filters, M2-keyframes
- **Task** `M3-color` Color: adjust, HSL, LUT import, paste to clips (P1) - depends on M3-filters
- **Task** `M3-stack` Effect stack per clip (P1) - depends on M3-filters, M3-masks

## M4 · Text

Text cards and titles with 12 effects, animations and templates.

- **Epic** `E4` Text: cards, titles, effects and animations
- **Task** `M4-card` Text card clip and text rendering (P0) - depends on M1-edit
- **Task** `M4-effects` Text effects (12) (P1) - depends on M4-card
- **Task** `M4-animations` Text and clip animations (P1) - depends on M4-card, M2-keyframes
- **Task** `M4-templates` Text templates and save as template (P2) - depends on M4-effects, M4-animations

## M5 · Audio

Per-clip volume, fades and loudness, noise reduction, on-device AI voice cleanup, ducking, beat markers and voiceover recording.

- **Epic** `E5` Audio: per-clip loudness, cleanup and music tools
- **Task** `M5-graph` Audio mixing, per-clip volume and fades, waveforms (P0) - depends on M1-preview
- **Task** `M5-loudness` Loudness normalization per clip (P0) - depends on M5-graph
- **Task** `M5-nr` Noise reduction (spectral gate) (P1) - depends on M5-graph
- **Task** `M5-ai-cleanup` AI voice cleanup with DeepFilterNet 3 and RNNoise (P0) - depends on M5-graph, M6-models
- **Task** `M5-duck-beats` Ducking under speech and beat markers (P1) - depends on M5-graph
- **Task** `M5-voiceover` Voiceover recording and detach audio (P1) - depends on M5-graph, M1-edit

## M6 · Captions and edit by text

Whisper captions on the device with styles, editing, .srt and offline translation, plus editing the video by deleting words and pauses.

- **Epic** `E6` Captions and edit by text with on-device Whisper
- **Task** `M6-models` AI models manager (P0) - depends on M0-skeleton
- **Task** `M6-transcribe` Transcription pipeline with whisper.cpp (P0) - depends on M0-spike-whisper, M6-models
- **Task** `M6-caption-clips` Caption clips, styles and word highlight (P0) - depends on M6-transcribe, M4-card
- **Task** `M6-caption-edit` Caption editing, words to check, search and replace, .srt (P1) - depends on M6-caption-clips
- **Task** `M6-translate` Offline caption translation (P2) - depends on M6-caption-clips, M6-models
- **Task** `M6-edit-by-text` Edit by text: cut fillers, pauses and words (P0) - depends on M6-transcribe, M1-edit

## M7 · Vision tools

Person cutout, chroma key, face-following masks, auto-reframe and video stabilization, all on the device.

- **Epic** `E7` Vision tools: cutout, chroma key, face follow and reframe
- **Task** `M7-cutout` Person cutout (remove background) (P1) - depends on M0-spike-engine
- **Task** `M7-chroma` Chroma key (P1) - depends on M3-filters
- **Task** `M7-face-follow` Face-following masks (P1) - depends on M3-masks
- **Task** `M7-reframe` Auto-reframe to 9:16, 1:1 and 4:5 (P2) - depends on M7-face-follow, M1-model
- **Task** `M7-stabilize` Video stabilization (P1) - depends on M0-spike-engine, M2-transform

## M8 · Export

Export in HD to 4K with H.264, HEVC or AV1 from original media, in the background, with extras (.srt, .jpg, .m4a, .otio) and a Saved screen.

- **Epic** `E8` Export: from originals, in the background, with extras
- **Task** `M8-settings` Export settings, codec availability and size estimate (P0) - depends on M1-model
- **Task** `M8-render` Render pipeline from original media (P0) - depends on M1-preview, M8-settings
- **Task** `M8-background` Background export with progress, pause and cancel (P0) - depends on M8-render
- **Task** `M8-extras` Export extras: .srt, cover .jpg, audio .m4a, project .otio (P2) - depends on M8-render, M6-caption-edit
- **Task** `M8-saved` Saved to Gallery screen (P0) - depends on M8-background

## M9 · Ads and Clipra Pro

Two compliant interstitial ads per export for free users, consent where required, and a 10 PLN one-time Clipra Pro purchase that removes them.

- **Epic** `E9` Monetization: two export ads and Clipra Pro (10 PLN)
- **Task** `M9-consent` Ad consent with the UMP SDK and "Ad privacy choices" (P0) - depends on M0-skeleton
- **Task** `M9-ads` Ad gate and the two export interstitials (P0) - depends on M8-background, M9-consent, M9-billing
- **Task** `M9-billing` Play Billing: clipra_pro one-time purchase, restore and entitlement (P0) - depends on M0-skeleton
- **Task** `M9-entry` Pro entry points and the ad-free state (P1) - depends on M9-billing, M0-theme
- **Task** `M9-console` Play Console: product, prices, ads declaration and data safety (P0) - depends on M9-billing, M9-ads

## M10 · Tablet

A landscape three-pane tablet experience for every feature.

- **Epic** `E10` Tablet: landscape three-pane layout
- **Task** `M10-adaptive` Adaptive layout infrastructure (P1) - depends on M0-theme
- **Task** `M10-editor` Tablet editor with inspector tabs (P1) - depends on M10-adaptive, M1-timeline
- **Task** `M10-screens` Tablet effects, text, captions, audio, edit by text and export screens (P1) - depends on M10-editor
- **Task** `M10-home` Tablet home with inline new project and Pro (P1) - depends on M10-adaptive, M1-newproject

## M11 · Launch

Onboarding, settings, accessibility, Polish and English, performance on real devices, and a Google Play release.

- **Epic** `E11` Launch: onboarding, settings, accessibility, localization and release
- **Task** `M11-onboarding` Onboarding and permissions (P0) - depends on M6-models, M0-theme
- **Task** `M11-settings` Settings (P1) - depends on M9-consent, M9-billing
- **Task** `M11-a11y` Accessibility pass (P1) - depends on M10-screens
- **Task** `M11-l10n` Localization: English and Polish (P1) - depends on M11-onboarding
- **Task** `M11-perf` Performance budgets and device matrix testing (P0) - depends on M8-render, M6-transcribe
- **Task** `M11-release` Release pipeline and Google Play listing (P0) - depends on M9-console, M11-perf, M11-l10n
