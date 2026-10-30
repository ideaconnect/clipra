# 0002. Project format: versioned JSON documents, edits as commands

- Status: Accepted
- Date: 2026-10-28
- Issues: #10, #12

## Context
Projects must survive the app being killed, app updates and new features. Undo must be reliable across every kind of edit. User media never leaves the device, and its files can move or disappear.

## Decision
- **One JSON document per project:** `<files>/projects/<id>/project.json`, written with kotlinx.serialization by `ProjectJson` (`core:model`). Writes go to a temporary file first, and autosave runs after each committed edit. Room keeps only the index that Home lists.
- **Schema version.** Every document carries a `version`. New fields have defaults and unknown keys are ignored, so adding a field needs no migration. Renaming, moving or changing the meaning of a field adds a `Migration`, and `SCHEMA_VERSION` goes up by one. A document from a newer app is refused, not misread.
- **Times** on the timeline are microseconds (`Long`).
- **Media references.** Media is referenced by id and URI, never by an absolute path into the app's caches. Proxies, thumbnails and analyses are looked up by media id in `MediaCache`, and can be thrown away and rebuilt.
- **Edits are commands** (`EditCommand` in `core:model`). Each is applied to an immutable `Project`, and the session keeps the history for undo and redo. A command that can't apply throws `EditRejected` with a reason, and nothing changes.

## Consequences
- Every feature adds its settings as fields with defaults on the model, plus a command with unit tests.
- Clearing the cache never loses work; analyses (proxies, masks, camera motion, cleaned voices) are requested again when a project opens.
- A breaking change to the model needs a migration and a test that reads the older document.
