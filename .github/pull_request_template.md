Closes #

## What changes

## Review checklist
- [ ] **License policy** ([ADR 0006](../docs/adr/0006-license-policy.md)). Each new dependency:
  - has a permissive license (Apache-2.0, MIT, BSD, ISC, Zlib; OFL for fonts), never GPL or AGPL;
  - is listed in `docs/third-party.md`;
  - is on the open-source licenses screen if it ships;
  - has its version in `gradle/libs.versions.toml`, at least two weeks old.
- [ ] **On-device only:** no user media, transcripts or edits leave the device. Network goes only to model downloads from official sources (pinned, SHA-256), ads for free users, and Play billing.
- [ ] **Ads** follow `docs/product/monetization.md`. No "10 PLN" placeholder: the price comes from Play Billing.
- [ ] **Design:** colours, type and sizes from the theme, never hard-coded. The screen matches its board, and any difference is noted below.
- [ ] **Strings:** in English and Polish resources, sentence case, no em dashes.
- [ ] **Tests:** unit tests for logic, golden images for GL effects, screenshot tests for screens (including the Polish one), recorded with `recordRoborazziDebug`.
- [ ] **CI is green:** style, debug build, unit tests, lint.
- [ ] **Decisions:** a choice that changes one in `docs/adr/` comes with a new or superseding ADR.

## Different from the design or the issue

## Screenshots
