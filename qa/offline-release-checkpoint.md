# Offline release rehearsal

Checkpoint: 2026-11-24 — first Memory V2 and PDF release rehearsal.

Update this checkpoint on the Elsewhere development draft only when the
current candidate is ready for the two longer offline checks:

- Generate a fresh synthetic database and encrypted backup with the pinned
  historical Room v15 build. Verify native migration, rollback, and restore
  against the exact candidate commit.
- Rehearse the upgrade on a fresh GitHub-hosted disposable Android emulator,
  check that the original synthetic records and file survive, and exercise
  the production PDF exporter with Android's native renderer.

These runs use disposable test builds and synthetic fixtures only. They do not
use OpenRouter, install anything on an owner's device, use a production key,
publish artifacts, merge the draft, or authorize release. The signed candidate
workflow remains separate and manual.
