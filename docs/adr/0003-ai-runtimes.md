# 0003. AI runtimes: everything on the device, official models only

- Status: Accepted
- Date: 2026-10-28, updated 2026-10-30 (#50, #54)
- Issues: #7 (spike), #39, #43, #44, #50, #52, #54

## Context
Clipra promises no account and no upload. User media, transcripts and edits never leave the device. The network is only for model downloads, ads for free users and Play billing.

## Decision
| Feature | Runtime | Model and source |
|---|---|---|
| Captions, edit by text | whisper.cpp v1.9.5 via JNI (`ml:whisper`) | Whisper ggml models from Hugging Face `ggerganov/whisper.cpp`, pinned to a commit. Base is the default (`docs/spikes/whisper.md`) |
| AI voice cleanup | ONNX Runtime Mobile | DeepFilterNet 3 from GitHub `Rikorose/DeepFilterNet`, pinned to a release tag |
| Lite voice cleanup | RNNoise, compiled in | Weights from the official xiph/rnnoise source |
| Person cutout, face follow | LiteRT (`ml:vision`) | MediaPipe models from Google's model storage, versioned paths, built into the APK at build time |
| Stabilization | Kotlin, no library (`MotionEstimator`) | No model |
| Translation | ML Kit on-device translation | Language packs managed by ML Kit |

Rules for every model:
- It runs on the phone.
- It is downloaded straight from its official source, pinned to a fixed revision (never `main` or `latest`), and checked with SHA-256 before use. Clipra never hosts or mirrors model files.
- Downloads use WorkManager, on Wi-Fi only by default.
- The AI models screen shows real file sizes in decimal units.

Two choices made during the build:
- **MediaPipe Tasks is not used (#50).** It sends usage events to Google's Clearcut through datatransport, with no off switch. LiteRT runs the same official model files and has no telemetry.
- **Stabilization uses no OpenCV (#54).** The few routines needed (Shi-Tomasi corners, pyramidal Lucas-Kanade, a RANSAC similarity fit) are written in Kotlin. They add nothing to the download and need no native build.

## Consequences
- A new AI feature brings its runtime and model only after checking: the license (0006), that the model's source is official, that it works offline, and that the runtime sends nothing over the network.
- Native runtimes ship for arm64-v8a, plus x86_64 in debug builds for the emulators, and must support 16 KB memory pages.
- Every speed number is confirmed on real phones (#77); emulator figures are only a first look.
