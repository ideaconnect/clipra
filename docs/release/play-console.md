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

- **Privacy policy:** the URL of the policy published on idct.tech (#78; draft in `docs/release/privacy-policy.md`).
- **Ads:** "Yes, my app contains ads".
- **App access:** "All functionality is available without special access". There are no accounts or logins.
- **Target audience:** 18 and over (or 16 and over). Not designed for children: Clipra isn't in the Families program, and its ads SDK isn't Families-certified.
- **Content rating** (IARC questionnaire):
  - Category: All other app types.
  - Violence, sexuality, language, controlled substances, gambling: none.
  - User-generated content shared with other users: no. Videos stay on the device; sharing goes through the system share sheet.
  - Digital purchases: yes (Clipra Pro).
  - Ads: yes.
- **Data safety:** see section 5.
- **Advertising ID:** "Yes, my app uses advertising ID", for advertising or marketing (the ads SDK adds `AD_ID`).
- **Government apps, financial features, health:** no.

## 5. Data safety answers

Clipra itself collects nothing. Media, transcripts, captions and edits stay on the device, there is no account, and there is no analytics or crash reporting of our own. Model downloads fetch files and send nothing about the user. What is declared comes from Google's SDKs, and only for free users (Pro users never load the ads SDK).

**Does your app collect or share any of the required user data types?** Yes, through the ads SDK.

| Data type | Collected | Shared | Optional | Purpose | From |
|---|---|---|---|---|---|
| Device or other IDs (advertising id, app set id) | Yes | Yes | Yes (ad personalization follows the UMP consent; Pro users have no ads) | Advertising or marketing, Analytics, Fraud prevention | Google Mobile Ads SDK |
| Approximate location (from the IP address) | Yes | Yes | No | Advertising or marketing, Fraud prevention | Google Mobile Ads SDK |
| App interactions (ad views and taps) | Yes | Yes | No | Advertising or marketing, Analytics | Google Mobile Ads SDK |
| Diagnostics (crash logs and performance of the ads SDK) | Yes | Yes | No | Analytics | Google Mobile Ads SDK |
| Purchase history | Yes | No | No | App functionality (restoring Clipra Pro) | Google Play Billing |

- **Is all user data encrypted in transit?** Yes (HTTPS for model downloads, ads and billing).
- **Can users request that data be deleted?** The app keeps no data off the device. Ad data follows Google's policies; link to https://policies.google.com/technologies/partner-sites in the policy.
- **Not declared, because none of it leaves the device:** photos and videos, audio, files and docs, messages, contacts, location (precise), personal info.

Check these answers against Google's current SDK guidance when filling in the form: [Google Mobile Ads SDK data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure) and the Play Billing Library's own data safety guidance. The billing library (9.1) logs its diagnostics to Google through Google's data transport (`com.google.android.datatransport`, the Clearcut backend), for every user, since Clipra connects to billing on each start to restore Clipra Pro; declare what that guidance lists for it.

What the SDKs add to the release manifest (audited 2026-10-31, `processBenchmarkManifest`):

- **Google Mobile Ads SDK:** `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID`, `READ_BASIC_PHONE_STATE` (a normal permission, no prompt), its ad activity and Play's `hsdp` shim. It starts only when a free user with consent opens Export (`NextGenAdsGateway`); the consent form is never shown to Pro users (`AdConsent`).
- **Play Billing:** `BILLING`, its proxy activities, and the data transport services above.
- **LiteRT:** Play's asset delivery (`ai-delivery`, `asset-delivery`), unused by Clipra; its extraction services are disabled and nothing starts it.
- **ONNX Runtime:** its 1DS telemetry provider is removed (`ml:voice`), see `docs/third-party.md`.
- No other provider starts at launch besides AndroidX Startup (emoji, lifecycle, profile installer). If offline translation ships with ML Kit (#47, undecided), add its analytics too: the language pair, an installation id, device info and timings, for Analytics.

## 6. Testing (acceptance of #66)

1. Add license testers: Settings > License testing, with the testers' Google accounts.
2. Upload a signed release build to the internal test track (#78, `publishing.md`). The first upload is by hand in the console.
3. On a tester's phone, install from the internal track. Buy Clipra Pro: the test card is offered, no money moves, and the export ads stop. Uninstall, reinstall and open: Pro is restored. Refund in the console: Pro is revoked on the next start.
4. Free tester: the release build with the real ad unit shows Google's test ads only if the tester's device is added as a test device in AdMob. Never click real ads.
