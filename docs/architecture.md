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
core:fonts               The bundled fonts, shared by the interface and the text renderer
core:model               Project, Layer, Clip, Effect, Keyframe, Caption, Transcript (pure Kotlin)
core:data                Project repository, autosave, undo/redo, media store, settings
core:engine              Media3 composition building, preview player, export, GL effects
core:audio               Loudness, gates, ducking, beat detection, waveform extraction
core:models              AI model manifest, downloads (WorkManager), verification and storage
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
- **Video clips** are decoded by a `MediaCodec` per active clip into an external texture in the compositor's GL context. Photos, text and captions are textures from bitmaps. A clip's transform (position, scale, rotation, crop, flip, fit) places its quad: `ClipGeometry` in `core:model` computes the corners, and the editor draws its handles with the same code, so preview, handles and export agree. While a gesture is going, the preview takes the new values from `LiveEdits` and redraws the paused frame instead of rebuilding the composition. Per-clip effects (filters, masks, colour, LUTs, chroma key) are shader passes on the clip's texture before it is placed and blended: the clip is drawn upright into an effect texture no larger than the frame, then each effect reads one texture and writes the other. The 24 filters follow the design's CSS recipes (`FilterRecipes`: the filter functions folded into one colour matrix, a way of sampling such as mosaic blocks, and a gradient or pattern overlay with a blend mode); Blur is a two-pass Gaussian. `StillRenderer` draws single frames offscreen with the same compositor, for the live filter thumbnails. Transitions sit on cuts, centred on them: the compositor draws the outgoing clip (playing on past its end) and the incoming one (starting early, from earlier media when the clip was trimmed, else holding its first frame) into textures of their own, combines them in the transition shader at the eased progress, and blends the result like one clip. Text clips are drawn by `TextRenderer` into bitmaps at the frame's size (the export's size when exporting, so text is sharp at 4K), with the bundled fonts' weight axis; a text card fills the frame with its colour. A mask (rectangle or oval, rotated, feathered, inverted) lives in the clip's picture space and limits one effect: the filter pass fades between the original and the filtered picture by the mask's coverage.
- **Blend modes (16)** are per clip, in the compositor. Normal uses fixed alpha blending; the other modes draw the clip alone and blend it onto the canvas in a shader that reads both (ping-pong). The shader uses the W3C Compositing and Blending formulas (Hue, Color and Luminosity with its luminosity and saturation), and `BlendGoldenTest` checks every mode against a separate CPU implementation.
- **Transitions.** Clips overlap on their layer across the cut, and the incoming clip carries the transition. A dissolve is an opacity ramp; other transitions are shaders that read both clips over the overlap.
- **Audio.** Each layer becomes one audio-only `EditedMediaItemSequence`, with `addGap()` where the layer is silent; Media3 mixes the sequences. Clips that overlap for a transition hand over the sound at the cut.
- **Preview** renders the same compositor at preview size from proxies (720p H.264, generated on import in the background). Export always uses originals at full size.
- **Performance work, measured on phones.** Decode ahead on decoder threads into a small `HardwareBuffer` queue per clip. Keep decoders alive across short gaps and recently active clips. Respect the device's hardware decoder instance limit. Preview audio may come from one pre-mixed track if `CompositionPlayer` with several audio sequences stalls (seen on the emulator).
- **Thumbnails and filmstrips:** frame extraction from proxies, cached on disk.

## 5. Audio

- Mixing: Media3 audio processing for preview and export, all layers summed, muted layers skipped. Each clip's sound goes through `ClipGain` (volume 0 to 200% and fade envelope, kept in place after seeks by the stream position Media3 passes on flush); clips turned all the way down and videos without sound add nothing.
- Waveforms: `AudioDecoder` decodes to PCM; `Waveforms` keeps the peak of every 20 ms in the media cache. Import starts them in the background, and long sounds decode in four slices side by side.
- Loudness: our own BS.1770-4 / EBU R128 meter in `core:audio` (`LoudnessMeter`: K-weighting, 400 ms blocks, -70 LUFS and -10 LU gates, true peak on 4x oversampled sound). The Audio screen measures the clip's stretch of its original media when it opens and stores the result with the clip; a mono source counts as dual mono, as the stereo export plays it. Normalizing to a target (-14, -16, -23 LUFS) adds the difference as gain in `ClipGain`, followed by a peak limiter (instant attack, 80 ms release, samples held at -1.5 dBFS so true peaks stay near -1 dBTP). A normalized clip that was trimmed is measured again the next time the Audio screen opens.
- Noise reduction: spectral gate (our own implementation).
- AI voice cleanup (`ml:voice`, `VoiceCleaner` in `core:engine`). It runs offline per media, not in real time.
  - **The engines** take mono 48 kHz in and give the same length out, sample for sample.
    - DeepFilterNet 3 runs on ONNX Runtime from the official ONNX export (an 8.0 MB download, 8.6 MB unpacked). Its DSP is ported from libDF to Kotlin: the 960-point STFT, ERB and complex features with exponential normalization, the ERB gains, and the five-frame deep filter.
    - The export runs whole sequences, so the sound goes through in 10 s chunks, each with 2 s before it for the recurrent layers to settle. Memory stays flat for any length.
    - RNNoise v0.2 (xiph, BSD-3-Clause) is built in through JNI with its weights; its 960-sample delay is taken out.
  - **The files** live in the media cache. The media's sound is decoded once to mono 48 kHz (`Resampler` when needed), and the engine cleans it once. Each strength is a mix of the two: the attenuation limit is 40 dB times the strength, so 70% keeps noise 28 dB down.
  - **When it runs:** a WorkManager job makes the mix when the Audio screen asks for it. Export makes any mix that isn't ready before it starts, so the noisy sound never ships. The editor asks again for mixes the cache lost.
  - **Playback:** clips play the mix in place of their own sound once it is there. The preview rebuilds when a mix becomes ready. Loudness and the After waveform follow the mix.
- Ducking: speech activity from the transcript or a voice activity detector drives a gain envelope on other clips.
- Beat detection: onset detection (spectral flux) on music clips, cached per media hash.

## 6. On-device AI

| Feature | Runtime and model | Notes |
|---|---|---|
| Captions, transcript | whisper.cpp (MIT) via JNI; Whisper ggml models (MIT): tiny 78 MB, base 148 MB, small 488 MB, large-v3-turbo q5_0 574 MB | Word timestamps, language detection, translate-to-English task. whisper.cpp v1.9.5 is built from its official release (SHA-256 checked) in `ml:whisper`. The spike (`docs/spikes/whisper.md`) recommends Base as the default and Small for Polish, one `whisper_full` call per clip (long media split at silences near 10 minutes), and token timestamps with cuts snapped to silence. Phone measurements are still pending. `Transcriber` (`core:engine`, #44) does that per clip: from the clip's cleaned voice when there is one, at 16 kHz mono. `WordTiming` then moves word edges to the pauses around them (within 100 ms of the speech onsets on the test clip), and fillers are marked per language. `TranscriptionWorker` (`feature:captions`) runs it in the background with progress, cancel and a notification, and stores the transcripts (in each clip's media time) with the project as one undo step. |
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

Downloads run in WorkManager with a Wi-Fi-only constraint by default, resume after interruptions, are verified by SHA-256 before use, are stored in app-specific storage, and are listed on the AI models screen with their size. In `core:models`, `ModelCatalog` is the manifest and `ModelManager` gives each model's state (built in, missing, waiting, downloading, installed) from the files on disk and the download work. `ModelDownloadWorker` runs as a data sync foreground service with a progress notification. It downloads into `<model>.part` and continues it with a range request after a lost connection, a stopped worker or a restart. It checks the size and SHA-256, unpacks archives (DeepFilterNet's tar.gz, read by our own small tar reader), and only then moves the files into `files/models/<model>/` and marks them installed. A failed check deletes the partial file; a source that answers 403, 404 or 410 fails without retrying. If a source moves or a file changes, the download fails with a clear message; the fix ships in an app update, never through another host. Every size the app shows is real: the manifest's byte count before download, the bytes on disk after, formatted with `Formatter.formatShortFileSize` (decimal units from Android 8 on, like the system's storage screens).

### Captions (#45)

- **Caption clips.** `CreateCaptions` turns transcripts into Caption clips: words go from media time to the timeline and are split into captions of about two lines (at sentence ends, pauses of 0.6 s, length, or 5 s). The captions go on a new top layer, linked to their source clip so they move, cut and delete with it. A caption clip's `CaptionSpec` holds its words timed from its own start, its style (Clean, Bold pop, Boxed, Karaoke, Minimal), position, scale and highlight.
- **Rendering.** `CaptionRenderer` draws a caption at frame size, sized from the frame's short side, shrinking long captions to two lines.
  - `ProjectRender` turns a caption into one render clip per spoken word, each a `RenderSource.Drawn` that the compositor draws the first time it shows and keeps only as a texture.
  - The highlight follows the speech in preview and export alike, and only the captions on screen take memory.

### Edit by text (#48)

- **Tokens.** `TextCuts` turns each transcribed clip into tokens on the timeline: words, fillers (from the transcript's word kinds) and pauses (gaps of 0.3 s or more).
- **Choices.** `CutChoices` cuts fillers and pauses over the threshold when their switches are on, unless a tap restored them, plus any word that was tapped. That is the same logic as `design/canvas/Transcript.dc.html`, whose numbers the tests reproduce.
- **Ranges.** Tokens cut one after another make one cut, gaps included.
- **Applying.** The screen previews the result and changes nothing until Done. Done applies `CutTimeRanges`: it removes the ranges from every unlocked layer and closes the gaps.
  - Clips across a cut are split, keeping links and transcripts. Captions lose the cut words, and captions left without words are removed.
  - Everything after a cut, including captions, music and overlays, moves up with it.

## 7. Export

- Transformer renders the composition from original media with the chosen resolution, frame rate, codec and bitrate (formula in `docs/product/spec.md`).
- Encoder capability check with `MediaCodecList` before showing options: HEVC and AV1 availability, 2160p support.
- Runs in a foreground service with a progress notification (media-processing foreground service type on Android 15 and later, data sync before that), supports pause, resume and cancel.
- Writes to MediaStore (Movies/Clipra). Extras: cover JPEG (frame at the playhead), `.srt`, audio-only `.m4a`, `.otio` (OpenTimelineIO JSON written by our own serializer).
- Notifies `monetization` of start, finish, failure and whether the progress screen is visible, so the ad rules in `docs/product/monetization.md` can be applied.

## 8. Monetization

- Ads: GMA Next-Gen SDK interstitials with preloading, gated by `AdGate` (Pro, consent, slot rules, timeouts). Test ad units in debug.
- Consent: UMP SDK on first launch after onboarding and from "Ad privacy choices".
  - `AdConsent` asks free users when the projects first show in a run (so after onboarding): it updates the consent information and shows the form where the law requires it. Pro users are never asked. When the update fails, the consent UMP stored earlier still applies.
  - `AdConsent.canRequestAds` is checked before every ad request; a change in "Ad privacy choices" applies to the next one.
  - "Ad privacy choices" shows only where UMP requires privacy options (EEA, UK and similar) and opens UMP's privacy options form.
  - The AdMob app id is passed to UMP in code (the Next-Gen ads SDK needs no manifest entry): Google's sample app in debug builds, `clipraAdMobAppId` in release builds, which fail without it. Debug builds test as a device in the EEA.
- Billing: Play Billing Library 9.x, product `clipra_pro`, acknowledgement, restore on start, entitlement cached in DataStore.
  - `PlayBillingGateway` wraps one `BillingClient` with automatic service reconnection and pending one-time purchases; tests use a fake gateway.
  - `ProStore` is the entitlement (`Entitlement.isPro`, from the DataStore cache). It counts a purchase once Google Play reports it purchased and `PurchaseVerifier` accepts its signature, grants Pro at once and acknowledges it (retried on every refresh until it succeeds). A pending payment grants nothing until it completes.
  - On every start (`ClipraApplication`), when the Pro screen opens and on Restore, `ProStore.refresh` takes Google Play's list of purchases as the truth, so a refund removes Pro. When Play cannot be reached the cache stays.
  - `PurchaseVerifier` checks the SHA1withRSA signature against the Play public key. The key comes from the Gradle property `clipraPlayLicenseKey` (or `ORG_GRADLE_PROJECT_clipraPlayLicenseKey`) and is stored masked in `BuildConfig`. A release build fails without it; a debug build without it trusts purchases.

## 9. Privacy and permissions

- No analytics SDK by default. If crash reporting is added it must be opt-in and must not send media or transcripts.
- Photo Picker for media import (no broad storage permission). Microphone only when recording a voiceover. Notifications for export and model download progress. `INTERNET` is declared by `core:models` for the downloads (and later by the ad and billing SDKs).
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
