# Spike: whisper.cpp on Android with word timestamps (#7)

**Question.** How fast and how accurate are on-device captions with whisper.cpp? Which model should be the default, and how should long clips be chunked?

**Answer.**
- **Speed.** whisper.cpp runs well within real time on the emulator:
  - English, 42 s: tiny 1.7 s, base 3.7 s, small 11.0 s.
  - Polish, 42 s: base 4.9 s, small 17.9 s.

  These are emulator numbers on a desktop CPU; phones still have to be measured.
- **Accuracy.** English is accurate with every model (4 to 7% WER). Polish needs small (26%), as base gets 32% and tiny 51%.
- **Word timestamps.** whisper's own token timestamps place words that start after a pause within about 0.1 s.
- **Recommendation.** Keep **Base as the default** (the design's choice), and suggest **Small for Polish** and other languages Whisper knows less well. Transcribe a clip in one `whisper_full` call, splitting only very long media at silences.

## Setup

- **Build.** whisper.cpp v1.9.5 (MIT, ggml-org/whisper.cpp), CPU backend only.
  - Built from the official release tarball, which Gradle downloads and checks against its SHA-256 (`ml/whisper/build.gradle.kts`). The JNI bridge is in `ml/whisper/src/main/cpp`, the Kotlin API in `Whisper.kt`.
  - About 3 MB of native code per ABI.
  - **x86_64** (emulators only) with AVX2, FMA and F16C. ggml turns these off when cross-compiling; without them everything was 2.5 to 4 times slower.
  - **arm64-v8a** at the baseline (NEON, no fp16 or dot product); see the recommendations.
- **Decoding.** Greedy sampling, 4 threads, `no_context`, token timestamps, the default temperature fallback, and flash attention (the default) except where DTW is measured.
- **Models.** The official ggml files from `ModelCatalog` (Hugging Face `ggerganov/whisper.cpp` at `5359861`), SHA-256 checked: tiny (78 MB), base (148 MB), small (488 MB).
- **Clips.** Each is 42 s, 16 kHz mono (`ml/whisper/src/androidTest/assets`):
  - English: the LibriVox reading of Svend Fleuron's *Grim: The Story of a Pike*, chapter 1, from 0:40 (CC0).
  - Polish: the LibriVox reading of Bolesław Prus's *Faraon*, volume 2, chapter 1, from 0:40 (public domain).
  - Reference texts: Project Gutenberg #40921 and Wolne Lektury (`faraon-tom-drugi`).
- **Device.** Pixel 10 Pro emulator (API 36.1, x86_64, 4 cores) on the development PC. Emulator times are not phone times.
- **How to run.** `WhisperSpikeTest` skips unless the models are copied into the test app (see its KDoc). It writes the transcripts and every word's times to `files/whisper-spike.md`.

## Results

| Model | Language | Time for 42 s | Real-time factor | WER | Mean token p | whisper memory | Peak process RSS |
|---|---|---|---|---|---|---|---|
| tiny | English | 1.7 s | 0.04 | 6.2% | 0.89 | 222 MB | 404 MB |
| base | English | 3.7 s | 0.09 | 7.3% | 0.93 | 315 MB | 476 MB |
| small | English | 11.0 s | 0.26 | 4.2% | 0.95 | 727 MB | 874 MB |
| base + DTW | English | 4.4 s | 0.10 | 7.3% | 0.93 | 406 MB | 576 MB |
| tiny | Polish | 2.8 s | 0.07 | 51.0% | 0.68 | 222 MB | 433 MB |
| base | Polish | 4.9 s | 0.12 | 32.3% | 0.79 | 315 MB | 503 MB |
| small | Polish | 17.9 s | 0.43 | 26.0% | 0.90 | 727 MB | 995 MB |
| base + DTW | Polish | 5.8 s | 0.14 | 30.2% | 0.80 | 406 MB | 615 MB |

How to read the table:
- **WER** is against the books' text, lowercased, with punctuation and hyphens ignored. The clips start and end mid-sentence, which costs a word or two at each end.
- **whisper memory** is the model plus its key/value caches and compute buffers, as whisper.cpp reports them.
- **Peak process RSS** includes the test process (about 180 MB).
- **Loading** takes 0.1 to 0.3 s, so a model can be loaded per transcription and freed afterwards.

### Word error impressions

- **English.** All three models are usable.
  - Small is nearly verbatim. Its four errors are "cross-dike" for "cross-dyke", "water plants" split differently, and "dull" (missing in every model; the reader may have skipped it).
  - Base misheard the clipped first word ("capoutery" for "powdery") and "sun-worn".
  - Tiny wrote "sun-wormed".
- **Polish.**
  - **Tiny** is not usable: half the words are wrong ("Pami ma", "waskaweni oczyna z trzechu").
  - **Base** gets the sentences right but merges and misspells words ("srebrzyste w odynilu", "ptadztwo").
  - **Small** reads well, except for rare words and names ("nomesu habu od Pamoru" for "nomesu Habu od pomoru"), a few joins ("dwuolbrzymich", "ponadłany") and the cut-off last words. Its single temperature fallback, on the cut-off end, added about 15% to its time.
  - A 19th-century novel full of Egyptian names is harder than everyday speech, so real Polish vlogs should do better. Still, Polish needs small.
- **Confidence.** Mean token probability tracks the errors: the wrong words score low ("capoutery" 0.25, "Pamięmo" 0.38). Underlining words below about 0.5 as low-confidence (the CaptionsEdit design) would catch most errors without marking correct words.

### Word timestamps

At speech onsets after a pause of 150 ms or more (11 in the English clip, 14 in the Polish one), each word start was compared with the onset:

| | Token timestamps (t0) | DTW (base, flash attention off) |
|---|---|---|
| English, base | mean 0.09 s, worst 0.28 s | mean 0.21 s, worst 0.48 s |
| Polish, base | mean 0.17 s, worst 0.50 s | mean 0.11 s, worst 0.19 s |
| English, small / Polish, small | 0.12 s / 0.05 s | not measured |

DTW is not clearly better: better for Polish, worse for English. It also costs 20 to 40% more time and about 90 MB of memory, because it needs flash attention off.

## Recommendations

1. **Default model: Base.** It is fast (0.09 to 0.12 real-time on the emulator), accurate in English, and needs about 315 MB while it runs. That matches the design's Default badge and the onboarding's "good on most phones".
   - When the caption language is Polish, or not English, and Small is downloaded, suggest it. CaptionsSetup should explain that Small is more accurate and slower.
   - Large v3 Turbo (q5_0, 574 MB) should be measured on phones for Polish before it is recommended for any language.
2. **Chunking.**
   - Pass a clip's whole sound to one `whisper_full` call. whisper.cpp slides its own 30 s windows, and `no_context` keeps a bad window from spilling into the next.
   - Report progress with `progress_callback`, and cancel with `abort_callback`.
   - Media longer than about 10 minutes (38 MB of float samples) should be split near the 10-minute marks, at the quietest 0.5 s within ±15 s, so no word is cut. The chunk's start is added to its timestamps.
   - Keep the temperature fallback: it costs time only where decoding fails, and that is where accuracy needs it.
3. **Word times.**
   - Use token timestamps; leave DTW off until a time-aligned test set shows it helps.
   - Edit by text (#48) should not cut exactly at a word's timestamp. It should snap each cut to the quietest point within about ±0.3 s of the boundary, since starts can be up to 0.5 s off.
4. **Phone builds (before #44 ships).**
   - Add an `armv8.2-a+fp16+dotprod` variant of the arm64 library and load it when the CPU has those features, as whisper.cpp's own Android example does. Keep the baseline library for Cortex-A53-only phones.
   - Then measure time, memory and battery on two phones (one mid-range, one older) and confirm the default.
5. **Threads.** Use 4 threads, the big cores on most phones, until the phone measurements say otherwise.

## Still to do

- Measure on real phones: time, memory, battery, and whether Base is still the right default on an older phone.
- Build the arm64 fp16 and dot product variant.
- Build a time-aligned test set (our own recordings) to settle token timestamps against DTW.
