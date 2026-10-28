# Third-party components

Keep this list in sync with the build. Every entry needs a license that is compatible with Apache-2.0 distribution (see `docs/architecture.md`, section 12). Check each license again when adding the dependency; this table starts from the plan, not from a build.

| Component | Use | License (to confirm at integration) |
|---|---|---|
| AndroidX (Activity, Navigation, Room, DataStore, WorkManager, Hilt extensions), Jetpack Compose with Material 3, Media3 | UI, navigation, storage, background work, media engine | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Language, async, JSON | Apache-2.0 |
| Hilt / Dagger | Dependency injection | Apache-2.0 |
| whisper.cpp | Speech to text runtime | MIT |
| OpenAI Whisper models (ggml conversions) | Speech to text models | MIT |
| ONNX Runtime Mobile | Runs DeepFilterNet | MIT |
| DeepFilterNet 3 | AI voice cleanup model | MIT or Apache-2.0 (dual) |
| RNNoise | Lite noise suppression | BSD-3-Clause |
| libebur128 (if used) | Loudness measurement | MIT |
| MediaPipe Tasks Vision and models | Face detection, segmentation, object detection | Apache-2.0 |
| OpenCV (core, imgproc, video, calib3d only) | Motion tracking for Stabilize | Apache-2.0 (4.5.0 and later) |
| ML Kit Translation | Offline translation | Google APIs Terms of Service |
| Google Mobile Ads Next-Gen SDK | Interstitial ads | Google terms |
| Google User Messaging Platform SDK | Ad consent | Google terms |
| Play Billing Library | Clipra Pro purchase | Android Software Development Kit License |
| Bricolage Grotesque, Geist, Geist Mono | Fonts | SIL Open Font License 1.1 |

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
| GitHub Actions: `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, `gradle/actions/wrapper-validation` | CI (`.github/workflows/ci.yml`) | MIT |

CI doesn't use `gradle/actions/setup-gradle`: since v6 its caching is a proprietary component under Gradle's terms of use. `actions/setup-java` caches Gradle instead.
