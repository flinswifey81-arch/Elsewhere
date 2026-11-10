# Stage 1 v15 → v16 release gate — verify before touching a user's installed app

**Status:** Release BLOCKED. This document is a manual QA procedure, **not** evidence that an installed production 1.5.2 database has been upgraded safely. PR #42 adds new local story-memory tables; PR #45 adds a simulated v15 encrypted-archive restore test.

## Non-negotiable boundaries

- Do not merge an unfinished stage, publish a v16 APK, uninstall an existing Test/Production app, swap signing keys, or silently migrate a phone.
- Do not test a v16→v15 *in-place* downgrade. A v16 backup may not be restorable by v15.
- Do not make paid OpenRouter calls as part of backup or migration validation. Keep test content synthetic unless the owner knowingly supplies a private, sanitized test copy.
- Do not move messages between variants, erase Wack markers, or promote private Campaign events into World canon during any recovery.
- Real data is not to be committed to GitHub, logged in Actions, attached to public PRs, or placed in an unencrypted debug artifact.

## Existing automated evidence

1. Stage 1 migration test creates a *minimal hand-built* v15 SQLite schema and verifies that new v16 tables are created without deleting legacy chat, summary or roleplay-memory rows. It is not a full production v15 database.
2. The long historical migration-chain test starts with synthetic v3 schema and uses Room to migrate/validate the resulting v16 database.
3. v16 encrypted full-backup round-trip covers `story_memories`, `story_memory_actors`, and `story_memory_returns`; old payload JSON defaults those collections to empty.
4. The separate PR #45 test builds a *simulated* v15 manifest/data archive (without the three new collections), encrypts it, inspects and restores it through v16, and checks variants, old memories, summaries, and Wack boundaries.
5. None of these tests alone satisfies a **real production-shaped v15 database upgrade** or **real 1.5.2 encrypted backup restore**.

## Required evidence before release approval

1. **Keep the current production installation intact.** Use the built-in v15 encrypted export on the known-good 1.5.2 install, save it outside the app, and protect the passphrase. Do not uninstall, clear app data or overwrite the only copy. Retain a separate copy of that exported file.
2. **Verify the archive in isolation.** Compute and compare a local SHA-256 digest before and after copying. Confirm that an isolated v16 QA build can *inspect* the archive and report the expected v15 version, number of chats, messages, memories and attachments. Never print archive content or passphrases in CI.
3. **Restore on a disposable QA environment, not production.** Use a correctly signed, separate Test build / emulator with a permitted backup application-ID migration source and an empty disposable database. Confirm full encrypted restore, binary images, existing Character/Persona records, selected message variants, summary/legacy memory, attached Lorebooks, NPC state, Scenarios, linked Worlds and accepted continuity. Do not uninstall a differently signed existing Test app to accomplish this.
4. **Exercise an actual v15→v16 Room upgrade on a copied v15 database.** Use a separately provisioned QA harness with the *complete production-shaped v15 schema and reference content*, verify Room schema validation, and compare detailed record counts/hashes before and after. No schema version spoofing or hand-created minimal tables as a substitute.
5. **Check post-restore behavior offline.** Wack boundaries remain effective; unselected/regenerated variants remain unselected; secrets do not become general Character knowledge; all v16 memory tables are initially empty on a v15 input. No World/cross-branch canon promotion or additional provider requests.
6. **Demonstrate recovery.** Induce a failed restore on a disposable database and verify the safety backup restores the pre-restore data. Confirm the original encrypted v15 export survives unchanged.
7. **Record the evidence.** Store exact v15 source build, v16 candidate SHA, package ID, signing certificate identity, checksum comparisons, pass/fail counts and safe test artifacts; redact real content and secrets. Attach results to the release review, not private records.

## Sign-off conditions

- CI for scoped memory/storage/backup/UI and the broad RP suite is green on the integrated release-candidate SHA, with repeated runs sufficient to triage known intermittent startup/image-capability timeouts.
- Real-format archive inspection and restore, full-schema migration, rollback/recovery and device-specific checks are independently demonstrated.
- Owner approves the proposed release, signer, data handling and installation path. Approval must not be inferred from a green draft PR.

**Until then: v16 work stays in review branches; the installed v15 app stays untouched.**
