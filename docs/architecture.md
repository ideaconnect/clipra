# Clipra architecture

Status: proposed. Decisions marked **(spike)** must be confirmed by the spikes in milestone M0 before the dependent work starts.

## 1. Platform

| Item | Choice |
|---|---|
| Language | Kotlin 2.x, coroutines and Flow |
| UI | Jetpack Compose, Material 3 components with a custom Clipra theme built from `design/tokens.json` |
| Adaptive layout | `WindowLayout` in `core:designsystem` (#68): a window at least 840 dp wide (the expanded width class) and 560 dp high, in landscape, gets the tablet layout; every other window the phone layout. The activity handles size changes itself, so split screen, foldables and rotation switch layouts in place and keep what is on screen; phone and tablet screens share their view models. The tablet editor (#69) reuses the phone timeline with layer headers and the Speed and Animate controls in its inspector |
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
ml:vision                MediaPipe models on LiteRT: selfie segmenter, face detector, subject tracking; motion tracking for Stabilize (Kotlin, no library)
ml:translate             ML Kit on-device translation
feature:home             Home, new project
feature:editor           Timeline, preview, tools, inspector (phone and tablet)
feature:effects          Filters, transitions, masks, color, cutout, reframe
feature:text             Text cards, effects, animations, templates
feature:captions         Caption setup, editing, edit by text
feature:audio            Audio screen
feature:export           Export settings, progress, saved screen
feature:models           AI models screen, onboarding downloads
feature:settings         Settings: Clipra Pro and Restore, export defaults, storage, privacy, licenses
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
- **Video clips** are decoded by a `MediaCodec` per active clip into an external texture in the compositor's GL context. Photos, text and captions are textures from bitmaps. A clip's transform (position, scale, rotation, crop, flip, fit) places its quad: `ClipGeometry` in `core:model` computes the corners, and the editor draws its handles with the same code, so preview, handles and export agree. While a gesture is going, the preview takes the new values from `LiveEdits` and redraws the paused frame instead of rebuilding the composition (a seek a millisecond either way, since `CompositionPlayer` draws nothing new for a seek to where it is). The preview draws on a `TextureView`, which keeps its picture while a picker or the background covers the app. Per-clip effects (filters, masks, colour, LUTs, chroma key) are shader passes on the clip's texture before it is placed and blended: the clip is drawn upright into an effect texture no larger than the frame, then each effect reads one texture and writes the other. The 24 filters follow the design's CSS recipes (`FilterRecipes`: the filter functions folded into one colour matrix, a way of sampling such as mosaic blocks, and a gradient or pattern overlay with a blend mode); Blur is a two-pass Gaussian. `StillRenderer` draws single frames offscreen with the same compositor, for the live filter thumbnails. Transitions sit on cuts, centred on them: the compositor draws the outgoing clip (playing on past its end) and the incoming one (starting early, from earlier media when the clip was trimmed, else holding its first frame) into textures of their own, combines them in the transition shader at the eased progress, and blends the result like one clip. Text clips are drawn by `TextRenderer` into bitmaps at the frame's size (the export's size when exporting, so text is sharp at 4K), with the bundled fonts' weight axis; a text card fills the frame with its colour. The 12 text effects (#32) follow the design's CSS (`TextEffectPainter`): text shadows as moved and blurred copies of the letters, the last listed first, in ems of the text so they scale with it; strokes drawn twice as wide under the letters (or cut out of a layer, for Hollow) so the font's overlapping outlines don't show; boxes and marker bands behind each line; a gradient across the text. The Text board's effect tiles are drawn by the same renderer. A mask (rectangle or oval, rotated, feathered, inverted) lives in the clip's picture space and limits one effect: the filter pass fades between the original and the filtered picture by the mask's coverage.
- **Keyframes (#21)** animate a clip's transform, opacity and mask. They are in clip time (`Keyframed`, plus `Clip.maskKeyframes` so older projects still load). Between two keyframes the value moves linearly or with ease in-out, by the earlier keyframe's easing; before the first and after the last it holds (`Keyframes`, `at`). `RenderClip` carries the keyframes and the compositor evaluates them every frame, so preview and export match (`KeyframeGoldenTest`). The keyframe button keys the transform and the opacity, or the mask, at the playhead, or deletes the keyframe there. Edits at the playhead on an animated clip set or add the keyframe there. Markers on the selected clip move keyframes. Trims and splits keep every keyframe and shift its time, so the animation stays where it was on the timeline.
- **Mosaic layouts (#17)** split the frame into cells with `Cells` (`core:model`): Split 2 and Split 3 stack in a portrait frame and sit side by side otherwise, 2×2 and 3×3 are grids, numbered row by row. A clip's `cell` puts it in one. `ClipGeometry` then lays it out with the cell as its frame, so Fill covers the cell, and the compositor scissors the clip's own draw to the cell, so nothing spills into the next. Blend modes and transitions are unaffected, because they draw the clip alone first. A clip keeps its cell number when the layout changes (`SetLayout`): cells the new layout lacks fold back into it, Single ignores them, and changing back restores every clip. Imports into a mosaic project (`AddToMosaic`) fill the first empty cell from the start, on a free layer, then follow the first cell. The transform and mask handles work in the clip's cell, and the Layer tool's Transform tab picks the cell.
- **Animations (#33)** are functions of time in `Animations` (`core:model`), so preview and export, which draw every frame from the same values, show them identically (`AnimationGoldenTest`). Clip animations (in: Fade, Zoom in, Slide up, Spin; out: Fade, Zoom out, Slide down; loop: Ken Burns, Shake, Pulse) move, scale, turn and fade the whole clip: `RenderClip.motionAt` applies them over the keyframed transform and opacity at each frame. Text animations move only the letters, not a text card's colour, so the text renderer draws them (`TextMotionPainter`): whole-text moves (Fade up, Pop, Slide, Bounce and the ways out), Typewriter by clipping each line to the letters typed, Wave and Glitch by moving slices of the text, so any text effect moves with it. `ProjectRender` splits an animating text clip into one piece per frame of the timeline's grid while it comes in or goes out, each drawn when it shows, with the still text between. In and out last 0.6 s (half a shorter clip); a split keeps the way in on the left part and the way out on the right.
- **Effect stack (#29).** A clip's `effects` are a stack that applies in order, each of the 24 types once, each with its own strength and settings, and an `enabled` switch that leaves it in place but draws nothing (`AddEffect`, `MoveEffect`, `SetEffectEnabled`, `SetEffectStrength`, `RemoveEffect`). The Filters board picks the first filter in its place in the stack; the stack board (the tablet's Effects panel, a board on the phone from the Effects clip tool) orders, turns off and removes the rest, and copies and pastes a whole stack between clips (`PasteEffects`, an in-memory clipboard). A mask still limits the effect it names. The timeline shows "FX n" on clips with effects. `EffectStackGoldenTest` checks that order matters as the colour matrices say.
- **Colour (#28)** is a grade pass before the clip's filters (`Shaders.GRADE`), a line-by-line port of `ColorMath` (`core:model`), which the golden test holds it to: exposure in stops of light, white balance gains, contrast, saturation, eight HSL ranges (each colour takes the shifts of the two range centres either side of its hue, greys barely move), a LUT at its strength, and the vignette. LUTs are 3D tables (`CubeLut`, read from Adobe's `.cube` format, 2 to 129 points a side); on the GPU their slices sit in a grid of tiles in one RGBA texture, blended between two slices. Built-in looks are made from formulas (`Luts`), and imported files are checked and kept in the app's own storage, named by their content (`LutLibrary`), so they work in every project and offline. Auto looks at the frame at the playhead (`ColorStats`) and sets exposure, contrast, saturation and half-corrected casts (`AutoColor`). Paste gives every picture clip on an unlocked layer the same grade in one undo step. The Color board shows the clip split into before and after, the split drawn by the grade shader.
- **Speed (#22)** maps a clip's timeline time to its media time in `SpeedCurve` (`core:model`), the one mapping that the compositor (frames), Media3 (sound), the filmstrip, waveforms, edit by text and captions all use (`Clip.mediaTimeAt`, `timelineAt`). A constant speed plays `rate` seconds of media per second. A ramp joins its preset's speeds (or Custom's three) with straight lines and plays them in steps of about a tenth of a second of media (`rampSteps`), each at one speed. Sound goes through `EditedMediaItem.setSpeed` with the pitch kept (Sonic restarts at every change, so steps shorter than its ~30 ms window would garble it; `SpeedAudioTest`). Steps sit at fixed places in the whole ramp and a clip keeps its window of it (`rampFrom`, `rampTo`), so split, trim, cut and freeze parts play every moment as before. Reverse plays the media from its out point, without sound. Freeze frame holds the frame on screen at the playhead. `SetSpeed` sets the clip's length from its media and moves the later clips on its layer, with their linked partners.
- **Adjustment clips (#23)** have no picture of their own (`RenderSource.Adjustment`). When the compositor reaches one, it runs the clip's grade, filters and mask over the canvas so far (every layer below), and draws the result back over it at the clip's opacity and in its cell, so a fade eases the look in. Layers above it are drawn after and keep their own look (`AdjustmentRenderTest`).
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
- **Noise reduction (#38)**: a spectral gate of our own in `core:audio` (`SpectralGate`, `NoiseProfile`, a radix-2 `Fft`). It cuts the sound into 2048-sample windows a quarter apart, learns each frequency's noise floor as the 30th percentile of its levels over the whole clip (steady hum and hiss sit there; speech and music rise above), and lowers each frequency that stays within 3x of its floor by up to 36 dB times the strength (14 dB at the default 40%), opening at once and closing smoothly, with gains smoothed over neighbouring frequencies. `VoiceCleaner` runs it like voice cleanup, offline per media and strength and cached, on the recorded sound or on the cleanup's mix when both are on; the resolver plays the gated file once it is ready, and export makes it first. The result is mono, as voice cleanup's is.
- **Ducking and beats (#40)** come from `SoundAnalyzer` (`core:engine`), once per media and cached (`analysis/<media>.speech`, `.beats`), on the sound voice cleanup decodes (mono 48 kHz). `SpeechDetector` (`core:audio`) measures 10 ms frames in the speech band (300 Hz to 3.4 kHz) against a floor from the quietest tenth, with a 250 ms hangover between words; `BeatDetector` takes the spectral flux in six bands (the two lowest, for kicks, counting double), the tempo from its autocorrelation with a lean towards 120 BPM, then the beats by dynamic programming (Ellis, 2007). A clip set to duck (the phone's "Duck music under this voice", 3 to 24 dB) has its speech mapped onto the timeline; every other clip with sound, except its own media, gets it as `RenderDuck`s, and `ClipGain` lowers it there (`Ducking`: down 50 ms before the speech over 60 ms, so fully down 10 ms in, back over 300 ms after). Export finds any speech it needs first. Beat markers draw a tick at each beat on clips that show them, and cuts snap to them (`Snapping.targets`).
- **Voiceover and detached sound (#41)**: `VoiceRecorder` (`core:engine`) records the microphone with `MediaRecorder` (AAC in an .m4a, mono, 48 kHz) into `files/recordings`, app storage the cache clearing leaves alone; RECORD_AUDIO is asked for only when Record is pressed. `MediaImporter.importRecording` makes it a `MediaAsset` with `recorded = true` (the timeline draws it in the voice colours), and `AddSound` puts it where recording started, on the first unlocked layer above the main one that is free for its whole length, with AI voice cleanup on (DeepFilterNet 3 when downloaded, else RNNoise), which runs in the background. The preview plays along muted while recording, so the speaker stays out of the take. Deleting a project deletes its recordings unless another project (a duplicate) uses them. Detached sound (`ExtractAudio`) stays linked to its video: moves, splits and ripple deletes take it along; trims leave it alone, which keeps it in sync and allows J and L cuts; a speed change or a freeze frame on either applies to both while they are still in step (`inStep`: same media, start, length and speed).
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
| Face follow, reframe | MediaPipe face detector model (Apache-2.0) on LiteRT | Smoothed track, keyframes written into the clip |
| Person cutout | MediaPipe selfie segmenter model (Apache-2.0, 256 × 256) on LiteRT (Apache-2.0) | Masks per frame, steadied and cached; see below |
| Subject follow | MediaPipe object detector plus a simple tracker | Fallback to face, then centre |
| Translation | ML Kit on-device translation (Google terms) | Language packs downloaded on demand; non-English pairs pivot through English |
| Stabilization | No model and no library: Shi-Tomasi corners, pyramidal Lucas-Kanade tracking and a RANSAC similarity fit per frame, written in Kotlin (`ml:vision` `MotionEstimator`) on 320 px wide grey frames. OpenCV was planned; the few routines needed are small, so the download stays the same and there is no native build to maintain | Analysed from the proxy in the background (WorkManager, one thread per core) and cached per media; the steps are kept in fractions of the picture keyed by media time, so trims, speed and reverse keep working. `Stabilizing` smooths the path with a Gaussian (wider with strength) and picks one zoom per clip; the compositor reads each frame through the correction matrix in its first draw, so preview and export match, and cutout masks and followed faces go through the same transform. No gyro data or learned model in v1 |

**MediaPipe models run on LiteRT, not MediaPipe Tasks (#50).** The Tasks libraries send usage events (task name, app version) through Google's datatransport to Clearcut, with no switch to turn them off; that is network traffic the product doesn't allow. LiteRT (`com.google.ai.edge.litert:litert`, the TensorFlow Lite runtime) has no telemetry and runs the same official model files, so `ml:vision` does the small pre- and post-processing itself (for the selfie segmenter: the picture squeezed to 256 × 256, RGB in 0 to 1, a confidence per pixel out).

**Person cutout (#50).**
- **Masks.** `Cutouts` (`core:engine`) runs `SelfieSegmenter` (`ml:vision`) over every frame of a media's proxy (one mask for a photo), upright, in a WorkManager job, with progress. Each mask is steadied by the last (`CutoutMath.smooth`): small changes mostly keep the last value, changes over 0.35 (someone moving) are taken at once. The masks go into `cutout/<media>.masks` in the cache: 8-bit confidences on the 256 × 256 grid, deflated per frame, indexed by media time, with each frame's person coverage (for "Person found"). Export makes masks that aren't ready first.
- **Drawing.** The compositor uploads the clip's mask for its media time as a luminance texture and runs `Shaders.CUTOUT` after the grade and the filters. It keeps a pixel as `CutoutMath.alpha` says: a firm edge a little inside the model's 50 % line, narrower as Edge refine goes up, and the unsure fringe (hair) partly kept by Hair detail. Behind the person go the layers under the clip (Layer below, Clear), a strong blur of the clip itself (3 % of the short side), or a colour. Preview and export draw it the same way; masks don't depend on the output size.
- **Chroma key (#51).** The same board and pass, with `Cutout.key` instead of masks: no AI and no background work, drawn in real time. A pixel's BT.709 chroma (its blue and red difference, brightness left out, so a screen's shadows and highlights stay near its colour) is compared with the key's: within Similarity × 0.4 it goes, past a soft edge of 0.01 + Smoothness × 0.25 it stays (`CutoutMath.keyAlpha`). Spill removal moves what stays towards its own grey, most where it is nearest the key and up to 0.12 past the edge (`CutoutMath.despill`), which takes green light off hair and skin. Switching to Chroma key guesses the key from the commonest strong colour round the picture's edges (`CutoutMath.guessKey`); the eyedropper picks it from the clip's own frame at the playhead (the board's Original), averaged over 7 × 7 pixels.

**Face follow (#52).** `FaceDetector` (`ml:vision`) runs MediaPipe's BlazeFace short-range model on LiteRT: the picture fitted into 128 × 128 (keeping its shape), RGB in -1 to 1, its 896 anchors (16 × 16 cells with 2, then 8 × 8 with 6) decoded and overlapping boxes merged by score-weighted non-maximum suppression, as MediaPipe's graph does. `FaceTracks` (`core:engine`) looks at the clip ten times a second (decoding the proxy in order, a few frames at a time, which is far quicker than seeking each; any speed or direction) and follows the face nearest the last one. `FaceTracking` (`core:model`) fills the frames without a face (straight between faces, held at the ends), smooths the path with a Gaussian over ±3 samples, and puts the mask over the whole head (1.5 × as wide and 1.9 × as tall as the face's box, a little higher), keeping its shape, feather and effect. `FollowFace` writes it as linear mask keyframes five times a second, one undo step; they stay editable, and switching it off leaves the mask where it is. The Masks board's Follow face switch shows the tracking's progress.

Every model runs on the phone and comes from its official source. Clipra never hosts or mirrors model files. The app carries a manifest with each file's URL, pinned to a fixed revision (never `main` or `latest`), its size and its SHA-256.

| Model | Official source | Delivery |
|---|---|---|
| Whisper ggml: tiny, base, small, large-v3-turbo q5_0 | Hugging Face `ggerganov/whisper.cpp`, the repository whisper.cpp's own download script uses | Downloaded, pinned to a commit |
| DeepFilterNet 3 (`DeepFilterNet3_onnx.tar.gz`) | GitHub `Rikorose/DeepFilterNet`, `models/` folder | Downloaded, pinned to a release tag |
| MediaPipe face detector, selfie segmenter, object detector | Google's MediaPipe model storage (`storage.googleapis.com/mediapipe-models`), versioned paths | Built into the APK, downloaded and checked with SHA-256 at build time (`ml:vision`'s `downloadVisionModels`; the selfie segmenter `float16/1`, 249,537 bytes) |
| RNNoise | Weights compiled in from the official xiph/rnnoise source | Built in |
| ML Kit translation packs | Google, through ML Kit | Managed by ML Kit |

Downloads run in WorkManager with a Wi-Fi-only constraint by default, resume after interruptions, are verified by SHA-256 before use, are stored in app-specific storage, and are listed on the AI models screen with their size. In `core:models`, `ModelCatalog` is the manifest and `ModelManager` gives each model's state (built in, missing, waiting, downloading, installed) from the files on disk and the download work. `ModelDownloadWorker` runs as a data sync foreground service with a progress notification. It downloads into `<model>.part` and continues it with a range request after a lost connection, a stopped worker or a restart. It checks the size and SHA-256, unpacks archives (DeepFilterNet's tar.gz, read by our own small tar reader), and only then moves the files into `files/models/<model>/` and marks them installed. A failed check deletes the partial file; a source that answers 403, 404 or 410 fails without retrying. If a source moves or a file changes, the download fails with a clear message; the fix ships in an app update, never through another host. Every size the app shows is real: the manifest's byte count before download, the bytes on disk after, formatted with `Formatter.formatShortFileSize` (decimal units from Android 8 on, like the system's storage screens).

### Captions (#45)

- **Caption clips.** `CreateCaptions` turns transcripts into Caption clips: words go from media time to the timeline and are split into captions of about two lines (at sentence ends, pauses of 0.6 s, length, or 5 s). The captions go on a new top layer, linked to their source clip so they move, cut and delete with it. A caption clip's `CaptionSpec` holds its words timed from its own start, its style (Clean, Bold pop, Boxed, Karaoke, Minimal), position, scale and highlight.
- **Rendering.** `CaptionRenderer` draws a caption at frame size, sized from the frame's short side, shrinking long captions to two lines.
  - `ProjectRender` turns a caption into one render clip per spoken word, each a `RenderSource.Drawn` that the compositor draws the first time it shows and keeps only as a texture.
  - The highlight follows the speech in preview and export alike, and only the captions on screen take memory.
- **Editing (#46).** `CaptionText` (`core:model`) changes a caption's words with their timing kept: a change spans whole words, and its new words share their time by length and count as checked. Tap a word to retype it (`EditCaptionWord`; empty takes it out, and a caption left empty goes), split the caption where it starts (`SplitCaption`, the new part joins the same link groups) or merge it with the next caption on its layer (`MergeCaptions`). `ReplaceInCaptions` replaces text in every caption, ignoring case, across word boundaries, as one undo step. "Words to check" opens the first word under the low-confidence line (0.5).
- **.srt (#46).** `Srt` writes the caption clips as SubRip cues in time order, with the words as typed, and reads what players accept (a byte order mark, Windows line ends, a full stop before the milliseconds, missing numbers, `<i>` and `{n8}` tags). `ImportCaptions` puts a file's cues in place of the project's captions, one undo step, with their look, on the layer that held them or a new top layer; overlapping cues end where the next begins, and each cue's words share its time by length. Files go through the system file picker (Save .srt, Import .srt on the Captions screen, and "Import .srt instead" on Auto captions); nothing else is read or written.

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
- Writes to MediaStore (Movies/Clipra). Captions are drawn into the video only when they are burned in.
- Extras (#59, `ExportExtras`) are named like the video and saved where Android keeps each kind. They are saved after the video, each on its own, so one that fails costs nothing else:
  - the cover JPEG: the frame at the editor's playhead, drawn by `StillRenderer` at the export size, in Pictures/Clipra;
  - the audio-only `.m4a`: the video's AAC track copied without re-encoding, in Music/Clipra;
  - the `.srt` and the `.otio`, in Download/Clipra. The `.otio` is OpenTimelineIO JSON written by our own serializer (`Otio`). It has a video track per layer and an audio track for a layer's sound, with L1 at the bottom, gaps, dissolves and other transitions, and speed as `LinearTimeWarp`. Media are referenced by their URIs; text, captions and adjustment clips have no file and keep their words in metadata.
- Notifies `monetization` of start, finish, failure and whether the progress screen is visible, so the ad rules in `docs/product/monetization.md` can be applied.

## 8. Monetization

- Ads: GMA Next-Gen SDK interstitials with preloading, gated by `AdGate` (Pro, consent, slot rules, timeouts). Test ad units in debug.
  - `AdGate` holds the rules of `docs/product/monetization.md`, section 1, for one export: Pro, consent, one chance per slot, the progress screen in front, success, 30 s between Ad 1 closing and the export finishing, online. `ExportAds` asks it, waits up to 2 s for a preloaded ad and shows it through `AdsGateway` (`NextGenAdsGateway`).
  - The SDK starts only when a free user with consent opens Export, which also preloads Ad 1; the preloader refills for Ad 2. Buying Pro stops it. Nothing starts it on app start.
  - `feature:export` drives it. The progress screen shows Ad 1 once the render has started and the screen is in front; the render never waits. When the export finishes with the progress screen in front and Ad 2 allowed, the finished state's See your video plays Ad 2, then opens the saved video. Otherwise the saved video opens at once, as from the notification.
  - Debug builds use Google's sample app id and test interstitial unit. Release builds take `clipraAdMobAppId` and `clipraAdUnitInterstitial`.
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
- Each main screen also has a Polish screenshot (`…InPolish`, `pl` qualifier and locale) of its design state, to check that every label fits; where Polish did not fit, the Polish string is shorter (#76). Seconds and decibels follow the user's number format; timecodes keep the point.
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
