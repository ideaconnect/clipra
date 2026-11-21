# Play Console and AdMob setup

What to enter in Google Play Console and AdMob before monetized builds can ship (#66). The rules behind it are in `docs/product/monetization.md`. Each step names where the value comes from in the code, so the console and the app agree.

## 1. App

- **Package:** `tech.idct.clipra`. It can't be changed after the first upload.
- **App name:** Clipra. **Default language:** English (United States), with Polish added in the store listing (#78).
- **App or game:** App. **Free or paid:** Free (Clipra Pro is an in-app product).
- **Category:** Video Players & Editors.

## 2. In-app product

- **Product id:** `clipra_pro`. The app asks for exactly this id (`monetization`, `ProStore`).
- **Type:** one-time product (non-consumable). The app acknowledges each purchase within three days and restores it on every start.
- **Name:** Clipra Pro. **Description:** "No ads when you export. Everything else in Clipra is free already."
- **Price:** 10.00 PLN in Poland. Other countries are still to be decided (`docs/plan`, open decision on Pro prices). Use automatic conversion until then. The app always shows the price Play returns, never a fixed one.
- **Status:** Active.
- **Licensing key:** Monetize > Monetization setup > Licensing. Set it as `clipraPlayLicenseKey` in `~/.gradle/gradle.properties` or as the `ORG_GRADLE_PROJECT_clipraPlayLicenseKey` variable. Release builds fail without it (`checkReleaseKeys`).

## 3. AdMob

- **App:** Android, `tech.idct.clipra`, linked to the Play listing once it exists. Set its app id as `clipraAdMobAppId`.
- **Ad unit:** one interstitial ("Export"), used for both export ads (start and finish). Set its id as `clipraAdUnitInterstitial`.
- **app-ads.txt** on https://idct.tech/app-ads.txt, and the developer website in the Play listing must be https://idct.tech:

  ```
  google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0
  ```

  Replace `pub-XXXXXXXXXXXXXXXX` with the AdMob publisher id (AdMob > Settings > Account information).
- **Privacy and messaging:** create the GDPR message (EEA and UK) for the app. The app shows it through UMP before any ad request.
- **Blocking controls:** none required. Never click real ads while testing; debug builds use Google's test unit.

## 4. App content (Policy > App content)

- **Privacy policy:** https://idct.tech/clipra/privacy, published 31 October 2026 from `docs/release/privacy-policy.md` (Polish: https://idct.tech/clipra/prywatnosc). The app links it under Settings > Privacy policy.
- **Ads:** "Yes, my app contains ads".
- **App access:** "All functionality is available without special access". There are no accounts or logins.
- **Target audience:** 13 and over (13 to 15, 16 to 17, 18 and over), and 14 and over in Brazil, set on 21 November 2026. Not designed for children under 13: Clipra isn't in the Families program, and its ads SDK isn't Families-certified.
- **Content rating** (IARC questionnaire):
  - Category: All other app types.
  - Violence, sexuality, language, controlled substances, gambling: none.
  - User-generated content shared with other users: no. Videos stay on the device; sharing goes through the system share sheet.
  - Online content (content that isn't in the downloaded app, such as films, songs, articles or AI-generated content): no. Model files are parts of features, not content to watch or read, and the AI features only work on the user's own media, on the phone. Ads have their own question.
  - Digital purchases: yes (Clipra Pro).
  - Ads: yes.
  - Result, submitted on 21 November 2026: PEGI 3, ESRB Everyone, USK 0, IARC 3+ and ClassInd 14 (Brazil). The privacy policy's Children section explains that the ratings describe the app's content and that Clipra is for people aged 13 and over. Submit a new questionnaire when a change affects these answers.
- **Data safety:** see section 5.
- **Advertising ID:** "Yes, my app uses advertising ID", for advertising or marketing (the ads SDK adds `AD_ID`).
- **Government apps, financial features, health:** no.
- **Foreground services** (asked when a release is reviewed). Media processing exists from Android 15, so on Android 10 to 14 export and transcription run as data sync instead (`ExportService`, `TranscriptionWorker`); model downloads are data sync on every version (`ModelDownloadWorker`).
  - `FOREGROUND_SERVICE_DATA_SYNC`: Network processing > Other (AI model downloads); Local data processing > Media transcoding (export on Android 10 to 14) and Other (caption transcription on Android 10 to 14).
  - `FOREGROUND_SERVICE_MEDIA_PROCESSING`: Media transcoding (export) and Other (caption transcription).
  - Each "Other" needs a description. Model downloads: "Downloads the on-device AI models the user chooses in Settings > AI models (Whisper speech recognition, DeepFilterNet voice cleanup, offline translation models) from their official sources. The user starts the download; its progress shows in a notification, and it continues if the user leaves the app." Transcription: "Transcribes the speech in the user's video on the device with Whisper, for Auto captions and Edit by text. The user starts it, progress shows in a notification, and the audio never leaves the device." For data sync, start it with "On Android 14 and earlier, which have no media processing type:".
  - Play may ask for a video of each use: an export, a model download and a transcription, each going on with its notification after the user leaves the app.

## 5. Data safety answers

Clipra itself collects nothing. Media, transcripts, captions and edits stay on the device, there is no account, and there is no analytics or crash reporting of our own. Model downloads fetch files and send nothing about the user. What is declared comes from Google's SDKs, and only for free users (Pro users never load the ads SDK).

**Does your app collect or share any of the required user data types?** Yes, through the ads SDK.

| Data type | Collected | Shared | Optional | Purpose | From |
|---|---|---|---|---|---|
| Device or other IDs (advertising id, app set id) | Yes | Yes | No (users can reset or delete the ad ID, but the SDK always sends the app set ID) | Advertising or marketing, Analytics, Fraud prevention | Google Mobile Ads SDK |
| Approximate location (from the IP address) | Yes | Yes | No | Advertising or marketing, Analytics, Fraud prevention | Google Mobile Ads SDK |
| App interactions (ad views and taps) | Yes | Yes | No | Advertising or marketing, Analytics, Fraud prevention | Google Mobile Ads SDK |
| Diagnostics (app launch time, hang rate, energy use; not crash logs) | Yes | Yes | No | Advertising or marketing, Analytics, Fraud prevention | Google Mobile Ads SDK; Play Billing Library (its own diagnostics, for every user) |
| Purchase history | Yes | No | No | App functionality (restoring Clipra Pro) | Google Play Billing |

- **Ephemeral processing:** no, for every row.
- **Is all user data encrypted in transit?** Yes (HTTPS for model downloads, ads and billing).
- **Account creation:** "My app does not allow users to create an account".
- **Can users request that data be deleted?** No. The app keeps no data off the device, so there is nothing for us to delete; Google handles the data its ads collect, and the privacy policy links https://policies.google.com/technologies/partner-sites.
- **Additional badges** (independent security review, UPI): none.
- **Not declared, because none of it leaves the device:** photos and videos, audio, files and docs, messages, contacts, location (precise), personal info.

The ads rows follow Google's [Google Mobile Ads SDK data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure), checked on 21 November 2026: IP address, user product interactions, diagnostic information and device identifiers, each collected and shared for advertising, analytics and fraud prevention. Only the ad ID is optional, so the device IDs row, which also holds the app set ID, is required. That page covers the legacy SDK (25.5.0); the Next-Gen SDK that Clipra uses has no page of its own, so the same answers apply. The Play Billing Library has no data safety page either (checked the same day). The billing library (9.1) logs its diagnostics to Google through Google's data transport (`com.google.android.datatransport`, the Clearcut backend), for every user, since Clipra connects to billing on each start to restore Clipra Pro; declare what that guidance lists for it.

What the SDKs add to the release manifest (audited 2026-10-31, `processBenchmarkManifest`):

- **Google Mobile Ads SDK:** `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID`, `READ_BASIC_PHONE_STATE` (a normal permission, no prompt), its ad activity and Play's `hsdp` shim. It starts only when a free user with consent opens Export (`NextGenAdsGateway`); the consent form is never shown to Pro users (`AdConsent`).
- **Play Billing:** `BILLING`, its proxy activities, and the data transport services above.
- **LiteRT:** Play's asset delivery (`ai-delivery`, `asset-delivery`), unused by Clipra; its extraction services are disabled and nothing starts it.
- **ONNX Runtime:** its 1DS telemetry provider is removed (`ml:voice`), see `docs/third-party.md`.
- No other provider starts at launch besides AndroidX Startup (emoji, lifecycle, profile installer). Offline translation (#47) runs Marian on the phone and sends nothing (ADR 0007); its model downloads come from Mozilla's storage like the other model downloads.

## 6. Testing (acceptance of #66)

1. Add license testers: Settings > License testing, with the testers' Google accounts.
2. Upload a signed release build to the internal test track (#78, `publishing.md`). The first upload is by hand in the console.
3. On a tester's phone, install from the internal track. Buy Clipra Pro: the test card is offered, no money moves, and the export ads stop. Uninstall, reinstall and open: Pro is restored. Refund in the console: Pro is revoked on the next start.
4. Free tester: the release build with the real ad unit shows Google's test ads only if the tester's device is added as a test device in AdMob. Never click real ads.
