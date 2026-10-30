# 0005. Android 10 and later

- Status: Accepted
- Date: 2026-10-28
- Issues: #3

## Context
A video editor depends on hardware encoders, MediaCodec behaving consistently, and phones fast enough for on-device AI. Older Android versions add device-specific codec bugs and slow phones that can't meet the performance budgets. Google Play also sets a minimum target SDK for updates.

## Decision
- minSdk 29 (Android 10).
- targetSdk 36, which Play requires since 31 August 2026.
- compileSdk 37.
- App id `tech.idct.clipra`.
- Release builds are arm64-v8a only; debug builds add x86_64 for the emulators. armeabi-v7a is added only if device data asks for it.

## Consequences
- Code can rely on Android 10 APIs: scoped storage with the photo picker, and the `MediaCodec` and `MediaMuxer` behaviour Media3 expects. Newer APIs need a version check (lint flags them, for example `InputStream.readNBytes` from API 33).
- Robolectric tests run with `@Config(sdk = [34])`, since newer SDKs need a Java 21 test JVM.
- The app id never changes once the app is on Google Play.
