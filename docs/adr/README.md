# Architecture decisions

Each record says what was decided, why, and what follows from it, so every contributor (and Claude) works to the same rules. A record is accepted once it is merged. To change a decision, add a new record that supersedes the old one, and mark the old one superseded; don't rewrite history.

| # | Decision | Status |
|---|---|---|
| [0001](0001-media-engine.md) | Media engine: Clipra's layer compositor inside Media3 | Accepted |
| [0002](0002-project-format.md) | Project format: versioned JSON documents, edits as commands | Accepted |
| [0003](0003-ai-runtimes.md) | AI runtimes: everything on the device, official models only | Accepted |
| [0004](0004-ads-and-billing.md) | Ads and billing: two export ads, a one-time Clipra Pro | Accepted |
| [0005](0005-min-sdk.md) | Android 10 and later | Accepted |
| [0006](0006-license-policy.md) | License policy: Apache-2.0 app, permissive dependencies only | Accepted |

## Template

```markdown
# NNNN. Title

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD
- Issues: #N

## Context
What forces the decision: the product rule, the constraint, what was measured.

## Decision
What we do, in a few sentences.

## Consequences
What follows, good and bad, and what to watch.
```
