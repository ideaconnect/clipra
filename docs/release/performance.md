# Performance budgets on phones

How to check the budgets of `docs/architecture.md`, section 11, on real devices (#77). Emulators show that each measurement works, not how fast a phone is, so the results below count only from the devices in the matrix.

## 1. Devices

At least one low-end, two mid-range and one flagship phone, and one tablet. The budgets have to hold on the mid-range phones. Fill in a row per device before measuring:

| Tier | Device | SoC | RAM | Android | Results |
|---|---|---|---|---|---|
| Low-end | | | | | |
| Mid-range | | | | | |
| Mid-range | | | | | |
| Flagship | | | | | |
| Tablet | | | | | |

Before each run: charged above 50%, not charging, screen brightness at half, battery saver off, the phone at room temperature (wait 10 minutes after a previous run), no other apps open. Turn on USB debugging and check `adb devices`.

## 2. What measures what

| Budget | Target | Measured by | Reads |
|---|---|---|---|
| Cold start to Home | under 1.5 s | `StartupBenchmark` (`benchmark`) | `timeToInitialDisplayMs`, median, with the baseline profile |
| Timeline scroll and scrub | 60 fps with 6 layers on proxies | `EditorBenchmark` (`benchmark`) on the Budget project | `frameOverrunMs` P90 at or below 0, `frameDurationCpuMs` P90 under 16 ms |
| Preview playback | real time at 1080p, 3 video layers, 2 effects | `BudgetTest.previewsThreeLayersWithTwoEffectsInRealTime` (`core:engine`) | logcat tag `Budget`: at least 27 fps shown, at least 0.95x real time |
| Export 1080p30, 42 s, 3 layers | under 60 s | `BudgetTest.exportsThreeLayersIn60Seconds` (`core:engine`) | logcat tag `Budget` |
| Whisper base on 42 s of speech | under 20 s | `TranscriberTest` (`core:engine`; downloads the base model from its official source on the first run) | logcat tag `Transcriber` |
| DeepFilterNet 3 | faster than real time | `VoiceCleanupTest` (`core:engine`) | logcat tag `VoiceCleanup`: seconds for 10 s of sound |

## 3. Running them

**All at once.** With one phone connected and prepared, one command runs everything below and writes the report for #77 to `build/perf/<model>-<date>.md`: the device row, the commit, each budget with its measurement and whether it holds, the peak memory and the thermal status before and after.

```
python scripts/perf/budgets.py --clip clip.mp4
```

`--clip` is a minute of the phone's own 1080p30 video (optional), and `--serial` picks a phone when several are connected. Emulators need `--emulator`, and their numbers don't count. The sections below run the same measurements one by one.

**Startup and the editor.** The `benchmark` module measures the app's `benchmark` build: release code, shrunk and optimized by R8 but with readable names, signed with the debug key and showing Google's test ads, so it needs none of Clipra's keys.

```
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Results are in `benchmark/build/outputs/connected_android_test_additional_output/` (JSON and Perfetto traces) and in the console. One class at a time: add `-Pandroid.testInstrumentationRunnerArguments.class=tech.idct.clipra.benchmark.StartupBenchmark`. On an emulator, add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`; those numbers don't count.

**The Budget project.** `EditorBenchmark` measures the project named "Budget" when Home shows one, and a new text card project otherwise. Make it once per phone: record a minute of 1080p30 video with the phone's camera, put it on six layers of one project, L1 to L6, each over the whole minute, wait for the proxies, and rename the project to Budget (Home, the project's menu, Rename). Keep the welcome passed and the project on the phone between runs.

**Export and preview.** These are instrumented tests of `core:engine`. They loop the bundled 1080p test clips. For numbers that count, push a minute of the phone's own 1080p30 video first; the test uses it for all three layers:

```
adb push clip.mp4 /data/local/tmp/clipra-budget.mp4
./gradlew :core:engine:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=tech.idct.clipra.core.engine.budget.BudgetTest \
    -Pandroid.testInstrumentationRunnerArguments.budgets=check
adb logcat -d -s Budget
```

With `budgets=check` the test fails when a budget is missed; without it, it only logs.

**The AI models.** Run `TranscriberTest` and `VoiceCleanupTest` the same way (`class=tech.idct.clipra.core.engine.media.TranscriberTest`), on Wi-Fi the first time, and read their tags.

**Memory and heat.** During the export test, note the peak of `adb shell dumpsys meminfo tech.idct.clipra.core.engine.test` (TOTAL PSS) and the thermal status before and after three exports in a row (`adb shell dumpsys thermalservice`, "Thermal Status"). A status of 3 (severe) or more during export is a finding.

## 4. The baseline profile

`app/src/main/baseline-prof.txt` lists the code of starting, the projects, a new project in the editor, scrubbing and playing. ProfileInstaller compiles it when the app is installed, so the first starts are as fast as later ones. Generate it again when those screens change much, on a phone or an emulator with Android 13 or later:

```
./gradlew :benchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=tech.idct.clipra.benchmark.BaselineProfileGenerator
```

and copy the `BaselineProfileGenerator_generate-startup-prof.txt` it writes under `benchmark/build/outputs/connected_android_test_additional_output/` over `app/src/main/baseline-prof.txt`. The `benchmark` build keeps the class names (`app/benchmark-rules.pro`), so the profile matches the sources; R8 renames its entries in the release. `StartupBenchmark` measures start with and without it.

## 5. Results

Record each run in #77: the device row, the commit, and the numbers next to their budget. A missed budget gets its own issue with the trace or the log attached.
