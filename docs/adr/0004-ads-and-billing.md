# 0004. Ads and billing: two export ads, a one-time Clipra Pro

- Status: Accepted
- Date: 2026-10-28
- Issues: #61, #62, #63, #64, #65

## Context
Clipra is free, with no watermark and nothing locked. It needs income that doesn't get in the way of editing and doesn't break the on-device promise. `docs/product/monetization.md` is the product rule.

## Decision
- **Ads:** the Google Mobile Ads Next-Gen SDK, interstitials only, two per successful export.
  - Ad 1 shows once the render has started; Ad 2 shows when the export finishes.
  - `AdGate` (`monetization`) holds the rules:
    - never back to back (Ad 2 is skipped if Ad 1 closed less than 30 s before);
    - never while editing;
    - never blocking or delaying the export;
    - skipped when offline or not loaded within 2 s;
    - no ads anywhere else.
  - The SDK starts only when a free user with consent opens Export. Debug builds use Google's test units.
- **Consent:** the UMP SDK, asked after onboarding where the law requires it (EEA, UK), and from "Ad privacy choices". Every ad request checks it first.
- **Billing:** Play Billing Library 9.x, the non-consumable product `clipra_pro`.
  - Purchases are verified against the Play public key, granted at once and acknowledged within 3 days.
  - Pro is restored on every start, revoked on refund, and cached in DataStore for offline use.
  - Pro users never request ads.
  - The price always comes from Play Billing; "10 PLN" in the design is a placeholder.

## Consequences
- These are closed Google SDKs under Google's terms (0006). Their network use is ads and billing only, and the privacy policy names them.
- Release builds need `clipraPlayLicenseKey`, `clipraAdMobAppId` and `clipraAdUnitInterstitial`, and fail without them.
- Changes to ad behaviour start with `docs/product/monetization.md` and `AdGate`'s unit tests.
