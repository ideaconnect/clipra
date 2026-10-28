# Clipra architecture

Status: proposed. Decisions marked **(spike)** must be confirmed by the spikes in milestone M0 before the dependent work starts.

## 1. Platform

| Item | Choice |
|---|---|
| Language | Kotlin 2.x, coroutines and Flow |
| UI | Jetpack Compose, Material 3 components with a custom Clipra theme built from `design/tokens.json` |
| Adaptive layout | Window size classes: compact = phone layout, expanded landscape = tablet three-pane layout |
| DI | Hilt |
| Persistence | Room (project index, media cache index), project documents as versioned JSON (kotlinx.serialization), DataStore for settings and the Pro entitlement |
| Background work | WorkManager (proxies, model downloads); a foreground service for export |
| Min / target SDK | minSdk 26 proposed (GMA Next-Gen needs 24, MediaPipe needs 24); target the latest SDK Play requires. Revisit minSdk with device data before launch |
| ABIs | arm64-v8a first (whisper.cpp, ONNX Runtime and MediaPipe are fastest there); armeabi-v7a only if device data asks for it |

## 2. Modules

```
app                      Navigation, DI wiring, entry points
core:designsystem        Theme, tokens, shared components, icons
core:model               Project, Layer, Clip, Effect, Keyframe, Caption, Transcript (pure Kotlin)
core:data                Project repository, autosave, undo/redo, media store, settings
core:engine              Media3 composition building, preview player, export, GL effects
core:audio               Loudness, gates, ducking, beat detection, waveform extraction
ml:whisper               whisper.cpp JNI, model files, transcription pipeline
ml:voice                 DeepFilterNet 3 (ONNX Runtime), RNNoise (JNI)
ml:vision                MediaPipe face detector, selfie segmenter, subject tracking; motion tracking for Stabilize (OpenCV)
ml:translate             ML Kit on-device translation
feature:home             Home, new project
feature:editor           Timeline, preview, tools, inspector (phone and tablet)
feature:effects          Filters, transitions, masks, color, cutout, reframe
feature:text             Text cards, effects, animations, templates
feature:captions         Caption setup, editing, edit by text
feature:audio            Audio screen
feature:export           Export settings, progress, saved screen
feature:models           AI models screen, onboarding downloads
monetization             Ads (GMA Next-Gen + UMP), billing (Play Billing), entitlement
```

Feature modules depend on `core:*` and `ml:*`, never on each other. `monetization` exposes `Entitlement` (Flow<Boolean>) and `AdGate` (show-or-skip decisions) to `feature:export`.

## 3. Project model

```
Project(id, version, name, format: Format(ratio, layout, resolution, fps, background), layers: List<Layer>, links: List<LinkGroup>)
Layer(id, index /* 1 = bottom */, hidden, muted, locked, clips: List<Clip>)
Clip(id, type: Video|Photo|TextCard|Text|Caption|Audio|Adjustment, start, duration,
     source?: MediaRef(uri, inPoint, outPoint), speed: Speed, transform: Keyframed<Transform>,
     opacity: Keyframed<Float>, blend: BlendMode, effects: List<Effect>, mask: Mask?,
     color: ColorGrade?, audio: AudioSettings?, text: TextSpec?, animation: Animation?,
     stabilize: Stabilization?)
Transition(id, layerId, cutTime, type, duration, easing)
LinkGroup(id, clipIds)       // caption <-> source clip, detached audio <-> video
Transcript(clipId, words: List<Word(text, start, end, confidence, kind: Word|Filler|Pause)>)
```

- All times are microseconds on the project timeline.
- Edits are commands (`SplitClip`, `MoveClip`, `CutRanges`, ...) applied to an immutable `Project`; the undo stack keeps commands and inverses.
- Autosave writes the JSON document after each committed command (debounced).
- The project file never stores absolute paths to app-internal caches; proxies are looked up by media hash.

## 4. Media engine

**Media3** (Transformer for export, CompositionPlayer for preview, the effect module for GL processing). Media3 1.10 is current as of this writing; track the stable channel.

- Each layer maps to one `EditedMediaItemSequence`; the project maps to one `Composition`. Media3 compositions support overlapping sequences for picture-in-picture and mixing audio from several sequences. Gaps between clips on a layer, and which sequence draws on top, must be confirmed **(spike)**.
- Per-clip effects are Media3 `GlEffect`s / custom `GlShaderProgram`s: filters, masks, colour, LUTs, chroma key, text rendering, mosaic.
- Blend modes (16) need a custom compositor shader **(spike)**. If Media3's compositor cannot be extended enough, fall back to rendering each layer to a texture and blending in our own GL pass.
- Transitions: Media3's composition guide says cross-fading is not supported yet. Implement transitions as a GL effect that samples both clips across an overlap **(spike)**.
- Preview uses proxies (720p H.264, generated on import in the background); export always uses originals.
- Thumbnails and filmstrips: frame extraction from proxies, cached on disk.

## 5. Audio

- Mixing: Media3 audio processing for preview and export, all layers summed, muted layers skipped.
- Loudness: integrated loudness (EBU R128 / ITU-R BS.1770) measured per clip, gain to target (−14, −16, −23 LUFS), true-peak limiter at −1 dBTP. Use a permissively licensed implementation (for example libebur128, MIT) or our own.
- Noise reduction: spectral gate (our own implementation).
- AI voice cleanup: DeepFilterNet 3 exported to ONNX (about 8 MB) on ONNX Runtime Mobile; RNNoise as the lite option. Processed offline per clip into a cached file, not in real time.
- Ducking: speech activity from the transcript or a voice activity detector drives a gain envelope on other clips.
- Beat detection: onset detection (spectral flux) on music clips, cached per media hash.

## 6. On-device AI

| Feature | Runtime and model | Notes |
|---|---|---|
| Captions, transcript | whisper.cpp (MIT) via JNI; Whisper ggml models (MIT): tiny 75 MB, base 142 MB, small 466 MB, large-v3-turbo q5 547 MB | Word timestamps, language detection, translate-to-English task. Long audio is chunked; progress and cancel supported **(spike: real-time factor on mid-range devices)** |
| Filler and pause detection | Rules on Whisper word output | Filler list per language, pause threshold from the UI |
| Voice cleanup | DeepFilterNet 3 (MIT or Apache-2.0) on ONNX Runtime (MIT); RNNoise (BSD-3) | See audio |
| Face follow, reframe | MediaPipe Tasks Vision face detector (Apache-2.0) | Smoothed track, keyframes written into the clip |
| Person cutout | MediaPipe image segmenter, selfie model (about 33 ms per frame on a Pixel 6 per Google's docs) | Temporal smoothing, edge refine |
| Subject follow | MediaPipe object detector plus a simple tracker | Fallback to face, then centre |
| Translation | ML Kit on-device translation (Google terms) | Language packs downloaded on demand; non-English pairs pivot through English |
| Stabilization | No model: corner tracking (pyramidal Lucas-Kanade) and per-frame camera motion with RANSAC, using OpenCV (Apache-2.0, trimmed build) | Analysed on the proxy in the background and cached per media hash; the smoothed path is rendered by a per-frame matrix effect with one zoom per clip, in preview and export. No gyro data or learned model in v1 |

Models are downloaded by WorkManager with a Wi-Fi-only constraint by default, verified by SHA-256, stored in app-specific storage, and listed on the AI models screen with their size. Built-in models (RNNoise, MediaPipe) ship in the APK or as an install-time asset pack.

## 7. Export

- Transformer renders the composition from original media with the chosen resolution, frame rate, codec and bitrate (formula in `docs/product/spec.md`).
- Encoder capability check with `MediaCodecList` before showing options: HEVC and AV1 availability, 2160p support.
- Runs in a foreground service with a progress notification (media-processing foreground service type on Android 15 and later, data sync before that), supports pause, resume and cancel.
- Writes to MediaStore (Movies/Clipra). Extras: cover JPEG (frame at the playhead), `.srt`, audio-only `.m4a`, `.otio` (OpenTimelineIO JSON written by our own serializer).
- Notifies `monetization` of start, finish, failure and whether the progress screen is visible, so the ad rules in `docs/product/monetization.md` can be applied.

## 8. Monetization

- Ads: GMA Next-Gen SDK interstitials with preloading, gated by `AdGate` (Pro, consent, slot rules, timeouts). Test ad units in debug.
- Consent: UMP SDK on first launch after onboarding and from "Ad privacy choices".
- Billing: Play Billing Library 9.x, product `clipra_pro`, acknowledgement, restore on start, entitlement cached in DataStore.

## 9. Privacy and permissions

- No analytics SDK by default. If crash reporting is added it must be opt-in and must not send media or transcripts.
- Photo Picker for media import (no broad storage permission). Microphone only when recording a voiceover. Notifications for export progress.
- Network: model downloads, ads (free users), billing.

## 10. Testing

- Unit tests for the model, commands, undo/redo, size estimate, ad rules, entitlement.
- Golden-image tests for GL effects (render a frame, compare with tolerance).
- Instrumented tests for export on at least three device tiers; performance budgets tracked in CI where possible.
- Screenshot tests for key Compose screens against the design PNGs.

## 11. Performance budgets (initial)

| Metric | Target |
|---|---|
| Cold start to Home | under 1.5 s on a mid-range phone |
| Timeline scroll and scrub | 60 fps with 6 layers on proxies |
| Preview playback | real time at 1080p with 3 video layers and 2 effects on a mid-range phone |
| Export 1080p30, 42 s, 3 layers | under 60 s on a mid-range phone |
| Whisper base on 42 s of speech | under 20 s on a mid-range phone **(spike)** |

## 12. Licensing

The app is Apache-2.0. Dependencies must be Apache-2.0, MIT, BSD, ISC, Zlib or similar permissive licenses; fonts OFL; models with permissive licenses (Whisper MIT, DeepFilterNet MIT/Apache-2.0, MediaPipe models Apache-2.0). Closed Google SDKs (ads, UMP, Play Billing, ML Kit) are used under their own terms. Do not add GPL or AGPL code; LGPL only as a separately loaded library and only after review. Record every dependency in `docs/third-party.md`.
