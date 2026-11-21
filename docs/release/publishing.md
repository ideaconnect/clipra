# Publishing to Google Play

How a Clipra release gets from a tag to the Play Store (#78). The Play Console setup (product, AdMob, data safety, content rating) is in `play-console.md`. The performance checks before a release are in `performance.md`.

## 1. The upload key

Play App Signing holds the key that signs the app on users' phones. Clipra signs each upload with its own upload key, which Play checks. A lost upload key can be replaced through Play Console support; the app signing key never leaves Google.

Create the upload key once, on a trusted machine:

```
keytool -genkeypair -keystore clipra-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and its passwords in a password manager and in one offline backup. Never commit them. In Play Console, choose Play App Signing with the key Google generates when the app is created (Setup > App signing).

## 2. Secrets

The release workflow (`.github/workflows/release.yml`) reads these secrets of the GitHub environment "release" (Settings > Environments). Add the owner as a required reviewer there, so no tag builds a release unseen.

| Secret | Value |
|---|---|
| `CLIPRA_UPLOAD_KEYSTORE` | The keystore, base64-encoded: `base64 -w0 clipra-upload.jks` |
| `CLIPRA_UPLOAD_KEYSTORE_PASSWORD` | The keystore password |
| `CLIPRA_UPLOAD_KEY_ALIAS` | `upload` |
| `CLIPRA_UPLOAD_KEY_PASSWORD` | The key password |
| `CLIPRA_PLAY_LICENSE_KEY` | Play Console > Monetize > Monetization setup > Licensing |
| `CLIPRA_ADMOB_APP_ID` | AdMob > Apps > App settings |
| `CLIPRA_AD_UNIT_INTERSTITIAL` | AdMob > Ad units, the export interstitial |

For a release built on your own machine, set the same values as Gradle properties in `~/.gradle/gradle.properties`: `clipraUploadKeystore` (the keystore's path), `clipraUploadKeystorePassword`, `clipraUploadKeyAlias`, `clipraUploadKeyPassword`, `clipraPlayLicenseKey`, `clipraAdMobAppId` and `clipraAdUnitInterstitial`. A release build stops with the name of whatever is missing (`checkReleaseSigning`, `checkReleaseKeys`).

## 3. Versions

A tag `v1.2.3` on `main` gives versionName 1.2.3 and versionCode 10203 (major × 10000 + minor × 100 + patch; minor and patch below 100). Play takes each version code once, so a bundle built again for any reason goes out as the next patch.

## 4. Making a release

1. Update the release notes in `app/src/main/play/release-notes/<language>/default.txt`, in English and Polish, at most 500 characters each. `StoreListingTest` checks the limits.
2. Merge to `main` and wait for CI to pass.
3. Tag and push: `git tag v1.2.3 && git push origin v1.2.3`.
4. Approve the "release" environment in the workflow run. When it finishes, download the `clipra-1.2.3` artifact. It holds `app-release.aab` and `mapping.txt`. The bundle already carries the R8 mapping and the native debug symbols, so Play shows readable crashes without extra uploads.
5. Upload the bundle in Play Console (Test and release > the track > Create new release) and paste the release notes. The first bundle of the app always goes through Play Console by hand.

## 5. Tracks

- **Internal testing**, for every release first. Up to 100 testers, available within minutes. Check on a phone installed from Play:
  - the first run;
  - an export with both ads;
  - buying Clipra Pro as a license tester (Setup > License testing), then refunding it to see Pro revoked;
  - restoring Pro after reinstalling;
  - an AI model download.
- **Closed testing**. A personal developer account created after 13 November 2023 must run a closed test with at least 12 testers, opted in for 14 days in a row, before it can apply for production. Check the current rule in Play Console (Dashboard). Organization accounts can skip this.
- **Production**, as a staged rollout: 10%, then 50%, then 100%. Between steps, watch Android vitals for at least a day: the user-perceived crash rate and ANR rate stay below Play's bad-behaviour thresholds. Halt the rollout if either slips.

## 6. Store listing

The texts live in `app/src/main/play/listings/<language>/`: `title.txt` (at most 30 characters), `short-description.txt` (80) and `full-description.txt` (4000), in English (`en-US`, the default) and Polish (`pl-PL`). Copy them into Play Console (Grow > Store presence > Main store listing, and Translations for Polish). The listing names only features that are in the app; add new ones as they ship.

The graphics are in `app/src/main/play/listings/<language>/graphics/`:

- the 512 × 512 icon (`en-US` only; Polish shows it too): the Clipra mark (`design/logo/clipra-mark.svg`) on the accent colour, full bleed, since Play rounds the corners. Made by `python scripts/store/graphics.py`;
- the 1024 × 500 feature graphic, one per language: "Privacy first", the three-line promise (no account, no cloud, no watermark) and three app screens captured on a phone;
- per language, eight screenshots for each device type (#282), in the language of the listing: phone (`phone-screenshots`, 1080 × 1920), 7-inch tablet (`tablet-screenshots`, 1440 × 2560) and 10-inch tablet (`large-tablet-screenshots`, 2560 × 1440). Each is a capture of the app under a headline and, apart from the export screen whose headline says it, a "No watermark" badge; together they show the timeline with layers, a clip over a clip, the layers sheet, a person cut out over another clip, text, auto captions, edit by text, effects, audio and the export. `StoreGraphicsTest` checks the count, the sizes and Play's limits.

How the screenshots are made:

1. Build `./gradlew :app:assembleBenchmark` (release code, debug-signed, with x86_64) and install it on emulators: `Pixel_8_API_37` (phone), a Nexus 7 (2013) AVD on API 37 (7-inch, portrait, the phone layout) and `Pixel_Tablet_API_37` (10-inch, landscape, the tablet layout).
2. Copy the stock media into the app's files and add the demo projects ("Weekend in the Tatras", "Tatras vlog", "Over the mountains"; Polish: "Weekend w Tatrach", "Vlog z Tatr", "Nad górami") with their captions, made by a real Auto captions run. Switch the system language per set and use SystemUI demo mode (09:30, full battery and signal, no notifications).
3. Capture each screen with `adb exec-out screencap -p` as `<lang>-<device>-<name>.png` (device `p`, `s` or `t`; the names are in `scripts/store/screenshots.py`).
4. `python scripts/store/screenshots.py CAPTURES` cuts the system bars, frames the screen and writes all six sets (`en-p pl-t ...` limits it to some). The copy is in the script; colours and fonts come from the design tokens and `core/fonts`.

The footage is free stock media, no personal footage: Mixkit clips 42323 (the vlogger), 2213 (waterfall), 51689 (mountain peak), 51502 (rocky coast), 34388 (skatepark) and 34487 (city walk), and the Mixkit track "Just Keep Walking", all under the Mixkit License. The vlogger's English and Polish voiceover is generated (ElevenLabs text to speech), not a recording of a person.

Also in the listing:

- **Category:** Video Players & Editors.
- **Contact email:** the support address shown on the listing.
- **Website:** idct.tech.
- **Privacy policy:** https://idct.tech/clipra/privacy (Polish: https://idct.tech/clipra/prywatnosc), published from `privacy-policy.md` and `privacy-policy.pl.md`. Settings > Privacy policy links to it in the app.

## 7. Before the first production release

- Play Console setup done (`play-console.md`, #66): Clipra Pro, the ads declaration, data safety, content rating, target audience.
- The privacy policy is live on idct.tech (done 31 October 2026); `app-ads.txt` is live too, with the AdMob publisher id.
- The budgets are met on the low-end test phone, the Galaxy S9 (`performance.md`, #77).
- The closed test is complete, if the account needs one.
