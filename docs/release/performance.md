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
| Timeline scroll and scrub | 60 fps with 6 layers on proxies | `EditorBenchmark` (`benchmark`) on the Budget project | `frameOverrunMs` P90 at or below 0, `frameDurationCpuMs` P90 under 16 ms; on Android 10 and 11 the CPU P90 alone (below) |
| Preview playback | real time at 1080p, 3 video layers, 2 effects | `BudgetTest.previewsThreeLayersWithTwoEffectsInRealTime` (`core:engine`) | logcat tag `Budget`: the lowest of three runs at least 27 fps shown, at least 0.95x real time |
| Export 1080p30, 42 s, 3 layers | under 120 s | `BudgetTest.exportsThreeLayersIn120Seconds` (`core:engine`) | logcat tag `Budget` |
| A reversed layer (#229) | real time, under 120 s | `BudgetTest.previewsAReversedPictureInPictureInRealTime` (two layers, the picture in picture reversed), `BudgetTest.exportsThreeLayersWithAReversedOneIn120Seconds` (the three layers, the middle one reversed) (`core:engine`) | logcat tag `Budget`, the lines starting `reversed`: the preview from its copies (the budget), and beside it the same layers forwards, read back to front before the copies are made, and how long they took; the export |
| Person cutout (#49) | real time at 1080p, with the layer below and with the background blurred | `BudgetTest.previewsACutOutClipInRealTime` (`core:engine`; a 1080p clip over a photo, in 16:9) | logcat tag `Budget`, the lines starting `cutout`: with the layer below and with the background blurred, the lowest of three runs at least 27 fps, at least 0.95x real time. Beside them, not judged: the same clip uncut (`cutout off`), measured first, and how long the masks took to make against the clip's length (the clip plays uncut until they are made) |
| Whisper base on 42 s of speech | under 20 s | `TranscriberTest` (`core:engine`; downloads the base model from its official source on the first run) | logcat tag `Transcriber` |
| DeepFilterNet 3 | faster than real time | `VoiceCleanupTest` (`core:engine`) | logcat tag `VoiceCleanup`: seconds for 10 s of sound |

**Android 10 and 11.** Macrobenchmark's `FrameTimingMetric` reports `frameOverrunMs` only from Android 12 (API 31), since it reads each frame's deadline from SurfaceFlinger's frame timeline, which older versions don't have. On Android 10 and 11 the benchmark JSON has only `frameDurationCpuMs` and `frameCount`, so there the timeline budget is judged by `frameDurationCpuMs` P90 under 16 ms alone, and the report says "no overrun metric before Android 12" next to the number. That pass is weaker than one with both numbers: it shows that the app's own work on the UI thread and RenderThread fits in a frame, while a frame can still be late because of the GPU or the compositor. On every version the report also gives the share of frames that took 16 ms of CPU or more, counted from the per-frame times in the JSON (`frameDurationCpuMs`, `runs`). It doesn't change the verdict, but it shows how many frames were slow, which the P90 alone doesn't.

## 3. Running them

**All at once.** With one phone connected and prepared, one command runs everything below and writes the report for #77 to `build/perf/<model>-<date>.md`: the device row, the commit, each budget with its measurement and whether it holds, the peak memory and the thermal status before and after.

```
python scripts/perf/budgets.py --clip clip.mp4
```

`--clip` is a minute of the phone's own 1080p30 video (optional), and `--serial` picks a phone when several are connected. Emulators need `--emulator`, and their numbers don't count. `--skip-startup` runs only the editor of the two benchmarks, which keeps the Budget project on Android 13 and older (see below). The phone can be set to any language: the engine tests log their numbers with a decimal point, and the script also reads the decimal comma (`67,2 s`) that builds before #198 logged in Polish and other decimal-comma locales. The sections below run the same measurements one by one. The script's own tests run without a phone: `python -m pytest scripts/perf` (with pytest installed).

**Startup and the editor.** The `benchmark` module measures the app's `benchmark` build: release code, shrunk and optimized by R8 but with readable names, signed with the debug key and showing Google's test ads, so it needs none of Clipra's keys. It is profileable (`app/src/benchmark/AndroidManifest.xml`), which Macrobenchmark requires on Android 10 and 11.

```
./gradlew :benchmark:connectedBenchmarkAndroidTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Without `leaveApksInstalledAfterRun`, the task uninstalls the app at the end, and the welcome and the Budget project go with it. Results are in `benchmark/build/outputs/connected_android_test_additional_output/` (JSON and Perfetto traces) and in the console. One class at a time: add `-Pandroid.testInstrumentationRunnerArguments.class=tech.idct.clipra.benchmark.StartupBenchmark`. The journeys find the app's buttons by their English or Polish labels, so the phone can be set to either language. On an emulator, add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`; those numbers don't count.

**The Budget project.** `EditorBenchmark` measures the project named "Budget" when Home shows one. Make it once per phone: record a minute of 1080p30 video with the phone's camera, put it on six layers of one project, L1 to L6, each over the whole minute, wait for the proxies, and rename the project to Budget (Home, the project's menu, Rename). On phones without the system photo picker, such as those with Android 10, Add media opens the system file picker, whose links last after Clipra closes (#226). Builds before that fix lost the clips' originals once the app closed (the clips showed the missing badge), so make the project with a build that has it.

The project stays on the phone between runs as long as nothing deletes the app's data:

- `EditorBenchmark` leaves the compilation state as the install made it (`CompilationMode.Ignore()`). Resetting it, as the other modes do, uninstalls and installs the app again on Android 13 and older unless the phone is rooted (#241).
- `budgets.py` passes `leaveApksInstalledAfterRun=true` to the benchmark task. Pass it when running the task by hand too.
- `StartupBenchmark` does reset the compilation state, since it measures start with and without the baseline profile. On Android 13 and older (not rooted) that reinstalls the app and deletes the project, so `budgets.py` measures the editor first and says so in the report. Runs with `--skip-startup` keep the project; after a full run on those phones, make it again before the editor is measured next. On Android 14 and later the reset keeps the app's data.

Without a Budget project, `EditorBenchmark` measures a new text card project, so the journey works on any device, but that is not what the budget asks for. With `-Pandroid.testInstrumentationRunnerArguments.budgetProject=required` it fails instead; `budgets.py` passes it on phones, not on emulators. The logcat tag `BudgetProject` says which project was measured, and the report names it.

**Export and preview.** These are instrumented tests of `core:engine`. They loop the bundled 1080p test clips. For numbers that count, push a minute of the phone's own 1080p30 video first; the test uses it for all three layers (and for the cut-out clip):

```
adb push clip.mp4 /data/local/tmp/clipra-budget.mp4
./gradlew :core:engine:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=tech.idct.clipra.core.engine.budget.BudgetTest \
    -Pandroid.testInstrumentationRunnerArguments.budgets=check
adb logcat -d -s Budget
```

With `budgets=check` the test fails when a budget is missed; without it, it only logs.

The preview test plays the project three times, each with a new player, logs a line per run and judges the lowest (#243): on a Galaxy S9 one run could show 30 fps and the next 8, so a single run is luck. Each run's line also says how many canvases the compositor drew a second and how long a draw took (mean and P90). Fewer canvases than the frame rate means frames were dropped before the draw, or the draw is too slow; more canvases than pictures shown means pictures were drawn and then dropped as late.

Below Android 12 the preview test first plays the same three runs with the compressed sound and logs them as "three layers with the compressed sound (before #268)", which `budgets.py` doesn't read; then it decodes the media's sound to PCM as the editor does in the background (#268), logs how long that took and how much it takes, and measures the budget's runs from the decodes. The other preview tests play from the decodes as well.

**The AI models.** Run `TranscriberTest` and `VoiceCleanupTest` the same way (`class=tech.idct.clipra.core.engine.media.TranscriberTest`), on Wi-Fi the first time, and read their tags.

**Memory and heat.** During the export test, note the peak of `adb shell dumpsys meminfo tech.idct.clipra.core.engine.test` (TOTAL PSS) and the thermal status before and after three exports in a row (`adb shell dumpsys thermalservice`, "Thermal Status"). A status of 3 (severe) or more during export is a finding.

**When the timeline budget is missed.** `EditorPartsBenchmark` measures the two parts of the editor journey apart, on the same project: `scrub` (the timeline scrubbed back and forth) and `play` (the project played for a moment, twice). Run it as the editor benchmark, with `-Pandroid.testInstrumentationRunnerArguments.class=tech.idct.clipra.benchmark.EditorPartsBenchmark`. Runs of the same build can differ by several milliseconds at P90, so compare a few runs, not one.

On Android 10 and 11, which report no `frameOverrunMs`, the CPU time overstates what the display misses: it adds up the UI thread's and the RenderThread's time, which work on successive frames side by side, and it includes the RenderThread's wait for a free buffer while the app is a frame ahead of the display. `scripts/perf/display-frames.sql` reads a trace of an iteration for what a 60 Hz display got: the vsyncs between the app's first and last frame, SurfaceFlinger's compositions and the vsyncs that showed an old picture again. It also gives each CPU's mean clock: on some phones the frames' CPU time follows the clock, which drops when the app has less to do. On a Galaxy S9 (#244) the scrub of the Budget project read 20 to 26 ms at P90 in runs of the same build, while the display got a new picture on 94 to 97% of the vsyncs.

## 4. The baseline profile

`app/src/main/baseline-prof.txt` lists the code of starting, the projects, a new project in the editor, scrubbing and playing. ProfileInstaller compiles it when the app is installed, so the first starts are as fast as later ones. Generate it again when those screens change much, on a phone or an emulator with Android 13 or later:

```
./gradlew :benchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=tech.idct.clipra.benchmark.BaselineProfileGenerator
```

and copy the `BaselineProfileGenerator_generate-startup-prof.txt` it writes under `benchmark/build/outputs/connected_android_test_additional_output/` over `app/src/main/baseline-prof.txt`. The `benchmark` build keeps the class names (`app/benchmark-rules.pro`), so the profile matches the sources; R8 renames its entries in the release. `StartupBenchmark` measures start with and without it.

## 5. Results

Record each run in #77: the device row, the commit, and the numbers next to their budget. A missed budget gets its own issue with the trace or the log attached.
