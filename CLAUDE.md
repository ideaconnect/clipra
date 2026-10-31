# CLAUDE.md

Guidance for Claude (and any contributor) working in this repository.

## What Clipra is

Clipra is an Android video editor for phones and tablets that competes with Filmora and CapCut. Promise: **no account, no upload, no watermark**. All AI runs on the device (Whisper captions, voice cleanup, cutout, face tracking, reframe). The app is free with **two fullscreen ads per export**; **Clipra Pro** is a one-time 10 PLN Google Play purchase that removes them. Nothing else is behind Pro.

Status: design complete, implementation not started. The plan is a set of issues in this repo, tracked in GitHub Project 6 "ClipraPlan" (https://github.com/orgs/ideaconnect/projects/6).

## Repository map

| Path | Contents |
|---|---|
| `design/` | The visual spec: screenshots, flows, static HTML snapshots, canvas sources, tokens. Start with `design/README.md` |
| `docs/product/spec.md` | What every feature does, with the design's numbers |
| `docs/product/monetization.md` | Ad slots, ad rules, Clipra Pro purchase flow, SDKs, Play Console checklist |
| `docs/architecture.md` | Stack, modules, project model, media engine, AI runtimes, export, testing, budgets, licensing |
| `docs/adr/` | Architecture decisions: media engine, project format, AI runtimes, ads and billing, Android 10+, license policy. Start with `docs/adr/README.md` |
| `docs/third-party.md` | Dependencies and their licenses |
| `docs/plan/ROADMAP.md` | Milestones M0 to M11 and every issue, in order |
| `docs/release/` | Play Console setup, publishing a release, the performance runbook, privacy policy drafts |
| `scripts/plan/` | `issues.json` (the plan as data) and `publish.py` (creates the issues and adds them to Project 6) |
| `app/` | The Android app: application class, main activity and the navigation that connects the feature modules |
| `core/`, `ml/`, `feature/`, `monetization/` | The modules of `docs/architecture.md`, section 2 |
| `build-logic/` | Gradle convention plugins (`clipra.android.library`, `clipra.android.feature`, `clipra.android.screenshots`, `clipra.hilt` and others) |
| `core/designsystem/` | The theme (`ClipraTheme.colors`, `.typography`, `.shapes`), components, the icon set (`ClipraIcons`) and the debug gallery |
| `gradle/libs.versions.toml` | The version of every library and Gradle plugin |
| `.github/workflows/ci.yml` | CI: Kotlin style, debug build, unit tests and lint on every pull request and on `main` |
| `.github/workflows/release.yml` | The signed release bundle, built when a `v1.2.3` tag is pushed (`docs/release/publishing.md`) |
| `app/src/main/play/` | The Play store listing, release notes and graphics (EN, PL) |

## How to use the design

1. **Find the screen.** `design/README.md` has a board index: board name, what it is, extra states, plan area. Every issue links its screenshots.
2. **Look at it.** `design/screens/phone/<Board>.png`, `design/screens/tablet/<Board>.png`, and states in `design/screens/states/<Board>--<state>.png`. Whole flows are in `design/screens/flows/`.
3. **Measure it.** Open `design/static/<Board>.html` in a browser and inspect: inline styles are the spec (sizes, colours, radii, copy). Phone boards are 390×844 and map 1:1 to dp; tablet boards are 1280×800 dp.
4. **Read the behaviour.** `design/canvas/<Board>.dc.html` contains a `renderVals()` script with real rules: the export size formula (`Export`), filler and pause cutting (`Transcript`), model storage maths (`Models`), timeline geometry (`Editor`, `TabletEditor`), the ad countdown (`AdStart`). Port the logic, not the HTML.
5. **Build from the design system.** Use `design/tokens.json` and `design/design-system.md` through the Compose theme in `core:designsystem`. Never hardcode a hex colour, font size or radius in a screen.

Placeholders are not features: bracketed ad text (`[Ad creative]`), "10 PLN" (always show the price from Play Billing), model sizes such as "142 MB" (always show the real file sizes, in decimal units), "Reset demo" buttons and the auto-advancing export progress exist only in the prototype.

If the implementation has to differ from the design (platform limits, accessibility), keep the intent, note the difference in the pull request, and update the issue.

## Product rules that must not break

- **On-device only.** User media, transcripts and edits never leave the device. Network is allowed only for model downloads (Wi-Fi only by default), ads for free users, and Play billing. AI models run on the phone and are downloaded straight from their official sources, pinned to a version and checked with SHA-256; we never host or mirror model files.
- **Generic layers.** L1 is the bottom layer. Any content can go on any layer; higher layers cover lower ones; every layer plays sound. Layers have only hide, mute and lock. Everything else (normalization, effects, speed) is per clip.
- **Edit on proxies, export from originals.**
- **Ads follow `docs/product/monetization.md` exactly:** two interstitials per successful export (start, finish), never back to back (skip the finish ad if the start ad closed less than 30 s before), never while editing, never block or delay the export, skip when offline or not loaded within 2 s, consent first in the EEA and UK, no ads anywhere else, Pro users never request ads.
- **Clipra Pro** is the non-consumable product `clipra_pro`: acknowledge within 3 days, restore on every start, revoke on refund, cache the entitlement for offline use.
- **License policy:** the app is Apache-2.0. Only permissive dependencies (Apache-2.0, MIT, BSD, ISC, Zlib, OFL for fonts). No GPL or AGPL. Add every dependency to `docs/third-party.md`.

## Stack (see `docs/architecture.md`)

Android 10 and later (minSdk 29, targetSdk 36), app id `tech.idct.clipra`. Kotlin 2.x, Jetpack Compose, Hilt, Room, DataStore, WorkManager, Media3 1.11 (Transformer, CompositionPlayer, effects) around Clipra's own layer compositor in `core:engine` (decided by the engine spike, `docs/spikes/engine.md`), whisper.cpp via JNI, ONNX Runtime Mobile (DeepFilterNet 3), RNNoise, MediaPipe models on LiteRT (not MediaPipe Tasks, which sends usage data), Marian NMT with Mozilla's Firefox Translations models (not ML Kit, which sends usage data), Google Mobile Ads Next-Gen SDK, UMP SDK, Play Billing Library 9.x.

The decisions behind this stack are recorded in `docs/adr/`:
- [0001](docs/adr/0001-media-engine.md) media engine;
- [0002](docs/adr/0002-project-format.md) project format;
- [0003](docs/adr/0003-ai-runtimes.md) AI runtimes;
- [0004](docs/adr/0004-ads-and-billing.md) ads and billing;
- [0005](docs/adr/0005-min-sdk.md) Android 10+;
- [0006](docs/adr/0006-license-policy.md) license policy;
- [0007](docs/adr/0007-offline-translation.md) offline translation.

Follow them. A change to one of them comes with a new ADR that supersedes it. The spike reports are in `docs/spikes/`.

## Build

- Needs JDK 17 (`JAVA_HOME`) and the Android SDK with platform 37 (`ANDROID_HOME`).
- `./gradlew assembleDebug` builds the app. `./gradlew testDebugUnitTest :core:model:test` runs the unit tests and `./gradlew lintDebug` runs Android lint. With an emulator running, `./gradlew connectedDebugAndroidTest` runs the instrumented tests.
- `./gradlew spotlessCheck` checks the Kotlin style with ktlint; `./gradlew spotlessApply` fixes most findings.
- Screenshot tests (Roborazzi on Robolectric, `clipra.android.screenshots`) keep their reference images in `<module>/src/test/screenshots`. `./gradlew recordRoborazziDebug` records them after an intended UI change; CI compares with `-Proborazzi.test.verify=true`. Robolectric tests use `@Config(sdk = [34])`, since newer SDKs need a Java 21 test JVM.
- CI runs the style check, the debug build, the unit tests and lint on every pull request and on `main`, and attaches the debug APK to the run. A pull request is ready only when CI is green.
- Develop on the phone and tablet emulators; debug builds include x86_64 for them, release builds are arm64-v8a only. Speed is measured on real phones in a later phase: `./gradlew :benchmark:connectedBenchmarkAndroidTest` runs the startup and editor benchmarks on the app's `benchmark` build, and `docs/release/performance.md` covers the rest of the budgets and the baseline profile.
- New modules use the convention plugins in `build-logic/`. New libraries go into `gradle/libs.versions.toml` and `docs/third-party.md`.

## Working on an issue

1. Pick an issue from Project 6, respecting its "Depends on" list. Read its screenshots and the linked spec sections.
2. Branch from `main` as `<issue-number>-<short-name>`.
3. Implement in the module the architecture names. Keep feature modules independent of each other.
4. Add tests: unit tests for logic (commands, formulas, ad rules, entitlement), golden-image tests for GL effects, screenshot tests for screens against the design.
5. Open a pull request that says `Closes #<number>`, with before and after screenshots for UI work, and go through the review checklist of `.github/pull_request_template.md` (license policy first).

## Conventions

- Kotlin coding conventions, checked by ktlint (the IntelliJ IDEA style, as Android Studio formats) with the rules in `.editorconfig`. Lines stay within 100 characters. Kotlin files use LF line endings.
- Times on the timeline are microseconds (`Long`).
- User-visible strings in resources (English and Polish). Sentence case. Feature names as in `design/design-system.md` (Edit by text, Cutout, Auto captions, Clipra Pro, AI models).
- In code comments, docs, commit messages and UI copy, use a regular hyphen (-), never an em dash.
- Commit messages: imperative mood, reference the issue (`#12`).

## Updating the plan

The issues are the working copy. `scripts/plan/issues.json` and `docs/plan/ROADMAP.md` are the snapshot they were created from. `scripts/plan/publish.py` is safe to run again: it skips issues that already exist by title and only adds what is missing.
