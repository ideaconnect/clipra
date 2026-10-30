# 0006. License policy: Apache-2.0 app, permissive dependencies only

- Status: Accepted
- Date: 2026-10-28
- Issues: #8

## Context
Clipra is Apache-2.0 and ships on Google Play. Copyleft code in the APK would bind the whole app to its terms. Models and fonts carry licenses too.

## Decision
- **What may ship:**
  - code under Apache-2.0, MIT, BSD, ISC, Zlib or a similar permissive license;
  - fonts under the SIL Open Font License;
  - models under permissive licenses (Whisper MIT, DeepFilterNet MIT or Apache-2.0, MediaPipe Apache-2.0).
- **Google's closed SDKs** (Mobile Ads, UMP, Play Billing, ML Kit) are used under their own terms.
- **Never** GPL or AGPL code. LGPL only as a separately loaded library, and only after review.
- **Tools that don't ship** (build and test tools, FFmpeg on a developer's machine for test clips) are listed separately and may have other licenses.
- **Every dependency** is listed in `docs/third-party.md` with its use and license, in the same pull request that adds it. Its version goes in `gradle/libs.versions.toml`, at least two weeks old.
- **The open-source licenses screen** (Settings > Open-source licenses) lists what the app ships and shows each license text from `feature/settings/src/main/assets/licenses`.
  - The list (`OpenSourceComponents`) is kept by hand from `docs/third-party.md`, and `LicensesTest` checks that every text it names exists.
  - A Gradle-generated list (such as the AboutLibraries plugin) would be the next step if the dependency list grows past what is easy to keep by hand. It would need the same review of each license.

## Consequences
- The pull request template asks, for every new dependency: its license, its row in `docs/third-party.md`, its entry on the licenses screen, and no network use beyond the allowed hosts.
- A dependency that pulls in a copyleft transitive dependency is rejected, or the transitive dependency is excluded.
- The licenses screen and `docs/third-party.md` change together.
