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
| Min / target SDK | minSdk 29: Android 10 and later, so hardware encoding and the on-device AI runtimes are dependable (decided 2026-10-28). targetSdk 36, which Play requires since 31 Aug 2026; compileSdk 37 |
| ABIs | arm64-v8a in release builds (whisper.cpp, ONNX Runtime and MediaPipe are fastest there); debug builds add x86_64 so native code also runs on the emulators. Native code must support 16 KB memory pages, as Play requires. armeabi-v7a only if device data asks for it |
| App id | `tech.idct.clipra`, which can never change once the app is on Google Play |
| Build | Gradle with the Kotlin DSL, the version catalog `gradle/libs.versions.toml` and convention plugins in `build-logic/`; Android Gradle Plugin 9 with its built-in Kotlin support; KSP for Room and Hilt |

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

The editor opens tool screens from other feature modules (Filters, Transitions, Text and the rest) by route, with the project id, the clip and the playhead. They share the editor's open project through `OpenProjects` (`core:data`), which keeps one `ProjectSession` per project until its last user releases it, so every edit lands in the same undo history.

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

**Media3 1.11** around Clipra's own compositor: Transformer encodes and muxes the export, CompositionPlayer plays the preview, and Media3 mixes the audio. Track the stable channel. Decided by the engine spike (#6); see `docs/spikes/engine.md` for the evidence and numbers.

- **Compositor.** `LayerCompositor` (`core:engine`, a Media3 `GlEffect`) draws every layer of a frame. The project maps to one `Composition`. Video is a single sequence: a blank canvas image as long as the timeline, at the project frame rate, carrying the compositor effect. Media3's own multi-sequence compositor only blends with alpha, and Transformer doesn't let an app replace it, so it isn't used.
- **Layer ordering and gaps.** Layers draw from L1 (bottom) up. A layer with no clip at a time draws nothing, so the layers below show through.
- **Video clips** are decoded by a `MediaCodec` per active clip into an external texture in the compositor's GL context. Photos, text and captions are textures from bitmaps. A clip's transform (position, scale, rotation, crop, flip, fit) places its quad: `ClipGeometry` in `core:model` computes the corners, and the editor draws its handles with the same code, so preview, handles and export agree. While a gesture is going, the preview takes the new values from `LiveEdits` and redraws the paused frame instead of rebuilding the composition. Per-clip effects (filters, masks, colour, LUTs, chroma key) are shader passes on the clip's texture before it is placed and blended: the clip is drawn upright into an effect texture no larger than the frame, then each effect reads one texture and writes the other. The 24 filters follow the design's CSS recipes (`FilterRecipes`: the filter functions folded into one colour matrix, a way of sampling such as mosaic blocks, and a gradient or pattern overlay with a blend mode); Blur is a two-pass Gaussian. `StillRenderer` draws single frames offscreen with the same compositor, for the live filter thumbnails.
- **Blend modes (16)** are per clip, in the compositor. Normal uses fixed alpha blending; the other modes draw the clip alone and blend it onto the canvas in a shader that reads both (ping-pong). The shader uses the W3C Compositing and Blending formulas (Hue, Color and Luminosity with its luminosity and saturation), and `BlendGoldenTest` checks every mode against a separate CPU implementation.
- **Transitions.** Clips overlap on their layer across the cut, and the incoming clip carries the transition. A dissolve is an opacity ramp; other transitions are shaders that read both clips over the overlap.
- **Audio.** Each layer becomes one audio-only `EditedMediaItemSequence`, with `addGap()` where the layer is silent; Media3 mixes the sequences. Clips that overlap for a transition hand over the sound at the cut.
- **Preview** renders the same compositor at preview size from proxies (720p H.264, generated on import in the background). Export always uses originals at full size.
- **Performance work, measured on phones.** Decode ahead on decoder threads into a small `HardwareBuffer` queue per clip. Keep decoders alive across short gaps and recently active clips. Respect the device's hardware decoder instance limit. Preview audio may come from one pre-mixed track if `CompositionPlayer` with several audio sequences stalls (seen on the emulator).
- **Thumbnails and filmstrips:** frame extraction from proxies, cached on disk.

## 5. Audio

- Mixing: Media3 audio processing for preview and export, all layers summed, muted layers skipped.
- Loudness: integrated loudness (EBU R128 / ITU-R BS.1770) measured per clip, gain to target (−14, −16, −23 LUFS), true-peak limiter at −1 dBTP. Use a permissively licensed implementation (for example libebur128, MIT) or our own.
- Noise reduction: spectral gate (our own implementation).
- AI voice cleanup: DeepFilterNet 3 exported to ONNX (an 8.0 MB download, 8.6 MB unpacked) on ONNX Runtime Mobile; RNNoise as the lite option. Processed offline per clip into a cached file, not in real time.
- Ducking: speech activity from the transcript or a voice activity detector drives a gain envelope on other clips.
- Beat detection: onset detection (spectral flux) on music clips, cached per media hash.

## 6. On-device AI

| Feature | Runtime and model | Notes |
|---|---|---|
| Captions, transcript | whisper.cpp (MIT) via JNI; Whisper ggml models (MIT): tiny 78 MB, base 148 MB, small 488 MB, large-v3-turbo q5_0 574 MB | Word timestamps, language detection, translate-to-English task. Long audio is chunked; progress and cancel supported **(spike: real-time factor on mid-range devices)** |
| Filler and pause detection | Rules on Whisper word output | Filler list per language, pause threshold from the UI |
| Voice cleanup | DeepFilterNet 3 (MIT or Apache-2.0) on ONNX Runtime (MIT); RNNoise (BSD-3) | See audio |
| Face follow, reframe | MediaPipe Tasks Vision face detector (Apache-2.0) | Smoothed track, keyframes written into the clip |
| Person cutout | MediaPipe image segmenter, selfie model (about 33 ms per frame on a Pixel 6 per Google's docs) | Temporal smoothing, edge refine |
| Subject follow | MediaPipe object detector plus a simple tracker | Fallback to face, then centre |
| Translation | ML Kit on-device translation (Google terms) | Language packs downloaded on demand; non-English pairs pivot through English |
| Stabilization | No model: corner tracking (pyramidal Lucas-Kanade) and per-frame camera motion with RANSAC, using OpenCV (Apache-2.0, trimmed build) | Analysed on the proxy in the background and cached per media hash; the smoothed path is rendered by a per-frame matrix effect with one zoom per clip, in preview and export. No gyro data or learned model in v1 |

Every model runs on the phone and comes from its official source. Clipra never hosts or mirrors model files. The app carries a manifest with each file's URL, pinned to a fixed revision (never `main` or `latest`), its size and its SHA-256.

| Model | Official source | Delivery |
|---|---|---|
| Whisper ggml: tiny, base, small, large-v3-turbo q5_0 | Hugging Face `ggerganov/whisper.cpp`, the repository whisper.cpp's own download script uses | Downloaded, pinned to a commit |
| DeepFilterNet 3 (`DeepFilterNet3_onnx.tar.gz`) | GitHub `Rikorose/DeepFilterNet`, `models/` folder | Downloaded, pinned to a release tag |
| MediaPipe face detector, selfie segmenter, object detector | Google's MediaPipe model storage (`storage.googleapis.com/mediapipe-models`), versioned paths | Built into the APK (or an install-time asset pack), checked at build time |
| RNNoise | Weights compiled in from the official xiph/rnnoise source | Built in |
| ML Kit translation packs | Google, through ML Kit | Managed by ML Kit |

Downloads run in WorkManager with a Wi-Fi-only constraint by default, resume after interruptions, are verified by SHA-256 before use, are stored in app-specific storage, and are listed on the AI models screen with their size. If a source moves or a file changes, the download fails with a clear message; the fix ships in an app update, never through another host. Every size the app shows is real: the manifest's byte count before download, the bytes on disk after, formatted with `Formatter.formatShortFileSize` (decimal units from Android 8 on, like the system's storage screens).

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
- Network: model downloads straight from the official sources (Hugging Face, GitHub, Google), ads (free users), billing. Those hosts see an ordinary download request (IP address, user agent) and nothing about the user's media; the privacy policy names them.

## 10. Testing

- Unit tests for the model, commands, undo/redo, size estimate, ad rules, entitlement.
- Golden-image tests for GL effects (render a frame, compare with tolerance).
- Instrumented tests for export on at least three device tiers; performance budgets tracked in CI where possible.
- Screenshot tests for key Compose screens against the design PNGs.
- Development starts on the emulators (phone and tablet). Emulators show whether things work, not how fast a phone is: real phones join in a later phase, and every speed number (spikes, budgets) is confirmed there.

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
