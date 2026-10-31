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
- **Production**, as a staged rollout: 10%, then 50%, then 100%. Between steps, watch Android vitals for at least a day: crash-free users at or above 99.5% (the v1 goal), and ANRs below Play's bad-behaviour threshold. Halt the rollout if either slips.

## 6. Store listing

The texts live in `app/src/main/play/listings/<language>/`: `title.txt` (at most 30 characters), `short-description.txt` (80) and `full-description.txt` (4000), in English (`en-US`, the default) and Polish (`pl-PL`). Copy them into Play Console (Grow > Store presence > Main store listing, and Translations for Polish). The listing names only features that are in the app; add new ones as they ship.

The graphics are in `app/src/main/play/listings/en-US/graphics/` and are made by `python scripts/store/graphics.py`:

- the 512 × 512 icon, drawn from the launcher icon (still the placeholder until the design has an app icon);
- the 1024 × 500 feature graphic;
- seven phone screenshots (1080 × 1920) and three 10-inch tablet screenshots (2560 × 1440).

The screenshots show the design's boards with a caption, leaving out boards with prototype placeholders and features still to come. The Polish listing uses the same graphics. Replace them with captures of the release on a phone when the store look is settled.

Also in the listing:

- **Category:** Video Players & Editors.
- **Contact email:** the support address shown on the listing.
- **Website:** idct.tech.
- **Privacy policy:** https://idct.tech/clipra/privacy (Polish: https://idct.tech/clipra/prywatnosc), published from `privacy-policy.md` and `privacy-policy.pl.md`. Settings > Privacy policy links to it in the app.

## 7. Before the first production release

- Play Console setup done (`play-console.md`, #66): Clipra Pro, the ads declaration, data safety, content rating, target audience.
- The privacy policy is live on idct.tech (done 31 October 2026); `app-ads.txt` is live too, with the AdMob publisher id.
- The budgets are met on the mid-range phones (`performance.md`, #77).
- The closed test is complete, if the account needs one.
