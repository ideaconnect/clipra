# 0007. Offline translation: Marian with Mozilla's Firefox Translations models

- Status: Accepted
- Date: 2026-10-31
- Issues: #47
- Supersedes: the Translation row of [0003](0003-ai-runtimes.md). Amends [0006](0006-license-policy.md) for model data under MPL-2.0.

## Context
Captions can gain extra languages (#47). 0003 picked ML Kit's on-device translation, but its own rule says a runtime is added only after checking that it sends nothing over the network. ML Kit Translation fails that check. Google's terms and its Android data disclosure say it sends the language pair, per-installation identifiers, device and app information, and timings and error codes, through Firebase installations and remote config. There is no switch to turn this off.

The options were:
1. ML Kit, with its analytics declared.
2. Marian NMT with Mozilla's Firefox Translations models.
3. OPUS-MT, which has no mobile format, so we would have to convert and host the files ourselves.

The owner chose option 2 on 2026-10-31.

## Decision
- **Runtime:** Marian NMT, the browsermt fork (`browsermt/marian-dev`, MIT), in a new module `ml:translate` through JNI.
  - It is built from its official source at a pinned commit, with the submodules it needs: sentencepiece (Apache-2.0), ruy (Apache-2.0), simd_utils (BSD-2-Clause) and intgemm (MIT). Each archive is checked with SHA-256 at build time, as whisper.cpp is.
  - It runs on the CPU with int8 matrix products: ruy on arm64-v8a, intgemm on the x86_64 emulators.
  - The Bergamot translator (MPL-2.0) is not used. Captions translate one cue at a time, so its sentence splitting and batching aren't needed.
- **Models:** Mozilla's Firefox Translations models, release status, from Mozilla's model registry (`storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data`, listed in `db/models.json`).
  - Each language direction is three files: the int8 model, the SentencePiece vocabulary and the lexical shortlist.
  - Each file lives at the training run's own path, which never changes, so the run's path pins the version. Every file is checked with SHA-256 against `ModelCatalog`.
  - They are gzipped on the server and unpacked on the phone. They are downloaded like the other models (WorkManager, Wi-Fi only by default) and are never hosted or mirrored by us.
- **Language pairs:** Mozilla trains to and from English. English and Polish come first; a pair without English pivots through English (two models), as #47 planned.
- **Licenses:** the model files are MPL-2.0. 0006 is amended:
  - model data under MPL-2.0 may be used when it is downloaded at run time, used unmodified and not compiled into the app;
  - the licenses screen names it with its source;
  - code under MPL-2.0 still needs a review before it ships.

## Consequences
- Translation sends nothing. Captions, their text and the language pair stay on the phone.
- A direction is about 32 MB unpacked (the "base-memory" models), shown as a language pair on the AI models screen.
- The native library adds a few MB to the APK per ABI and must support 16 KB memory pages.
- Speed on phones is measured with the other budgets (#77).
- Mozilla's newer runs can replace a model by changing its path and checksum in `ModelCatalog`. A model is never replaced silently.
- 0003's Translation row now points here. 0006 lists the MPL-2.0 model data exception.
