# Monetization: export ads and Clipra Pro

Clipra is free. Free users see **two fullscreen interstitial ads per export**. **Clipra Pro** is a single 10 PLN purchase that removes them for good.

Design: `AdStart`, `AdFinish`, `ExportProgress--finished`, `ExportDone`, `Pro`, `Pro--owned`, `TabletAd`, `TabletPro`, flows `screens/flows/export-with-ads.png` and `screens/flows/clipra-pro.png`.

## 1. Ad slots

| Slot | When it shows | Closes into |
|---|---|---|
| Ad 1 (start) | Right after the user taps **Export to Gallery** and the render has started | Export progress |
| Ad 2 (finish) | When the export finishes **and** the user is still on the export progress screen | Saved to Gallery |

### Rules

1. **Never block the export.** The render starts before Ad 1 is shown and keeps running behind it. If an ad is not loaded within 2 seconds, or the device is offline, skip that slot.
2. **Never back to back.** AdMob says not to show an interstitial immediately after another one was shown and closed. If Ad 1 closed less than 30 seconds before the export finishes (short exports), skip Ad 2.
3. **Never interrupt editing.** If the user left the progress screen (Keep editing, Home, background), skip Ad 2. The finish notification opens the Saved screen without an ad.
4. **Only on success.** No Ad 2 if the export failed or was cancelled.
5. **At most two per export**, and no ads anywhere else in the app (not on launch, not on exit, not between editor screens).
6. **Closable within 15 seconds.** Use standard interstitial units; do not use formats that cannot be closed after 15 seconds (Google Play policy).
7. **Clear ownership.** The app is in the foreground when an ad shows; ads never show from the background service.
8. **Pro users never request ads.** Do not even initialise the ads SDK for Pro users on cold start once the entitlement is cached.
9. **Consent first.** In the EEA and UK, ask for consent with a Google-certified consent platform before the first ad request; without consent, request limited or non-personalised ads only, as the consent result allows.
10. **Preload.** Load Ad 1 when the Export screen opens and Ad 2 when the export starts, so neither appears late.

### Copy around ads

- Under the Export button (free users only): "Two short ads play while it exports. **Remove for 10 PLN**" (links to Pro).
- Under "See your video" when the export finishes: "A short ad plays first. Clipra Pro removes it."
- Saved screen card: "Export without ads next time · Clipra Pro · 10 PLN, one payment".
- The ad itself is drawn by the ad SDK. Clipra cannot draw over it.

## 2. Clipra Pro

| Item | Value |
|---|---|
| Product type | Google Play one-time product, non-consumable |
| Product ID | `clipra_pro` |
| Price | 10.00 PLN in Poland. Other countries: set in Play Console (price template or automatic conversion) |
| What it unlocks | No ads. Nothing else is gated |
| Scope | The Google account; works on every Android device signed in to it |
| Shown price | Always the formatted price returned by Play Billing for the user (for example "10,00 zł"), never a hardcoded string |

### Purchase flow

1. Pro screen shows benefits, price, **Buy Pro**, **Restore purchase**, the payment note and **Ad privacy choices**.
2. Buy opens the Google Play purchase sheet.
3. On `PURCHASED`: verify the purchase signature, grant Pro, **acknowledge within 3 days** (unacknowledged purchases are refunded automatically by Google Play), show the owned state.
4. On `PENDING` (for example cash payment): show "Payment pending" and grant only when it becomes `PURCHASED`.
5. Restore: `queryPurchasesAsync` for one-time products; grant if `clipra_pro` is owned. Also run this on every app start.
6. Refund or revoke: if a later query no longer returns the purchase, remove Pro.
7. Cache the entitlement locally (DataStore) so Pro users see no ads offline.

### Verification

There is no backend. Verify the purchase signature on the device against the app's Play public key and keep the key obfuscated. For a 10 PLN product this is an accepted trade-off; revisit server-side verification if a backend appears.

## 3. SDKs and versions (checked October 2026)

| Need | Choice | Notes |
|---|---|---|
| Interstitial ads | Google Mobile Ads **Next-Gen SDK** (`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk`) | Google's preferred Android ads SDK since mid-2026. Requires minSdk 24. Supports ad preloading. The legacy `play-services-ads` line is supported only until mid-2028 |
| Consent | Google **User Messaging Platform** (UMP) SDK | Google-certified CMP. Provide a privacy options entry ("Ad privacy choices") so users can change consent |
| Billing | **Play Billing Library 9.x** (9.1.0, June 2026) | Version 8 or later is required for new apps and updates from 31 August 2026 (extensions until 1 November 2026). Use `enableAutoServiceReconnection()` |

## 4. Play Console and policy checklist

The step-by-step setup, with what to enter in each form, is in `docs/release/play-console.md`.

- Ads declaration: "Contains ads" = yes.
- Data safety: declare what the ads SDK collects (device or advertising identifiers, diagnostics). Clipra itself collects no user content.
- `com.google.android.gms.permission.AD_ID` permission (added by the ads SDK) is expected for ads.
- Target audience: not children. Apps for children must follow the Families policy and certified ad SDK rules.
- Content rating questionnaire mentions ads.
- In-app product `clipra_pro` created, priced at 10.00 PLN, activated.
- Test with license testers and AdMob test ad units in debug builds; never click real ads during testing.

## 5. Sources

- AdMob, Disallowed interstitial implementations: https://support.google.com/admob/answer/6201362
- Google Play, Ads policy (unexpected interstitials, 15-second close rule): https://support.google.com/googleplay/android-developer/answer/9857753
- Play Billing, One-time products: https://developer.android.com/google/play/billing/one-time-products
- Play Billing Library release notes: https://developer.android.com/google/play/billing/release-notes
- AdMob, GDPR and the UMP SDK: https://developers.google.com/admob/android/privacy/gdpr
- GMA Next-Gen SDK: https://developers.google.com/admob/android/next-gen
