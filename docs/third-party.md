# Third-party components

Keep this list in sync with the build. Every entry needs a license that is compatible with Apache-2.0 distribution (see `docs/architecture.md`, section 12). Check each license again when adding the dependency; this table starts from the plan, not from a build.

| Component | Use | License (to confirm at integration) |
|---|---|---|
| AndroidX (Activity, Navigation, Room, DataStore, WorkManager, Hilt extensions), Jetpack Compose with Material 3, Media3 | UI, navigation, storage, background work, media engine | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Language, async, JSON | Apache-2.0 |
| Hilt / Dagger | Dependency injection | Apache-2.0 |
| whisper.cpp | Speech to text runtime | MIT |
| OpenAI Whisper models (ggml conversions) | Speech to text models | MIT |
| ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android` 1.31.0) | Runs DeepFilterNet 3 | MIT |
| DeepFilterNet 3 | AI voice cleanup model, downloaded; Clipra's Kotlin port of its libDF processing (`ml:voice`) | MIT or Apache-2.0 (dual) |
| RNNoise v0.2 | Lite voice cleanup, vendored with its weights in `ml/voice/src/main/cpp/rnnoise` (origin in `SOURCE.md`) | BSD-3-Clause |
| libebur128 (if used) | Loudness measurement | MIT |
| MediaPipe Tasks Vision and models | Face detection, segmentation, object detection | Apache-2.0 |
| OpenCV (core, imgproc, video, calib3d only) | Motion tracking for Stabilize | Apache-2.0 (4.5.0 and later) |
| ML Kit Translation | Offline translation | Google APIs Terms of Service |
| Google Mobile Ads Next-Gen SDK | Interstitial ads | Google terms |
| Google User Messaging Platform SDK | Ad consent | Google terms |
| Play Billing Library | Clipra Pro purchase | Android Software Development Kit License |
| Bricolage Grotesque, Geist, Geist Mono | Fonts | SIL Open Font License 1.1 |

The fonts are the variable TTFs from the google/fonts repository, bundled in `core/fonts/src/main/res/font` (shared by the interface and the text renderer) with their licenses in `core/designsystem/src/main/assets/licenses`:

| File | Source (google/fonts) | SHA-256 |
|---|---|---|
| `geist.ttf` | `ofl/geist/Geist[wght].ttf` at 718e1db | `73894e0448cae90a92b6c2f8732b7bb9acb7b94c418bff559dad4a18e1de9659` |
| `geist_mono.ttf` | `ofl/geistmono/GeistMono[wght].ttf` at 9e25e2b | `d00e590b8eb3a59acc329b2d044fd143ae935090b7da33199ebee27cc7de8196` |
| `bricolage_grotesque.ttf` | `ofl/bricolagegrotesque/BricolageGrotesque[opsz,wdth,wght].ttf` at 6ce172f | `413e7357809ddd12fd80a96a8a396de0e401638d4acd3cb3e37532f0472ac682` |

The icons are drawn from the design's own SVGs (`design/static/`), so they carry no third-party license.

Models come from their official sources only, pinned to a revision and checked with SHA-256; the sources are listed in `docs/architecture.md`, section 6.

## Build and test tools

Used to build and test the app; none of them ship in it.

| Component | Use | License |
|---|---|---|
| Gradle, Android Gradle Plugin, Kotlin Gradle plugins, KSP | Build | Apache-2.0 |
| JUnit 4 | Unit tests | EPL-1.0 (tests only, not distributed) |
| AndroidX Test, Compose UI test, kotlinx-coroutines-test | Tests | Apache-2.0 |
| Spotless Gradle plugin | Runs ktlint over the Kotlin sources | Apache-2.0 |
| ktlint | Kotlin style check | MIT |
| Robolectric | Runs Android code in JVM tests | MIT |
| Roborazzi | Screenshot tests | Apache-2.0 |
| FFmpeg (command line, on the developer's machine) | Generated the synthetic test clips in `core/engine/src/androidTest/assets/spike/` and encoded the voice test set | LGPL/GPL tool; not part of the build, and the generated clips carry no license |
| LibriVox "Grim: The Story of a Pike", chapter 1 (archive.org `storyofapike_1304_librivox`) | 10 s of speech in the voice cleanup test set (`core/engine/src/androidTest/assets/golden/voice/`), with synthetic wind and traffic noise added | Public domain (CC0) |
| GitHub Actions: `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, `gradle/actions/wrapper-validation` | CI (`.github/workflows/ci.yml`) | MIT |

CI doesn't use `gradle/actions/setup-gradle`: since v6 its caching is a proprietary component under Gradle's terms of use. `actions/setup-java` caches Gradle instead.
