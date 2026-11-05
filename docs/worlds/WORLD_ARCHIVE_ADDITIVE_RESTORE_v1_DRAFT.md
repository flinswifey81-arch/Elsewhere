# Elsewhere Worlds — Additive Restore v1 (Draft)

**Status: implementation and Robolectric tests on a draft feature branch; NOT a released user feature.**  
Stack: Worlds foundation #15 → manifest #30 → history #31 → encrypted transport #32 → semantic ID rewrite #33 → additive restore #34.

## What this restore does

`WorldArchiveAdditiveRestorer.importAsNewWorld` accepts an already unlocked, authenticated and validated `VerifiedWorldArchive`, plus a unique durable import-operation namespace. It does **not** import into an existing World or replace a database. It only creates a second World with newly namespaced identities.

Under the same application-wide mutation gate used by backup/restore, the importer:

1. Recovers any interrupted World imports.
2. Revalidates the manifest and history graph; creates a deterministic new-World ID plan and applies the schema-aware transformation to memories, all message variants, StoryResponse speaker IDs, Scenario/NPC references, Lorebook links, etc.
3. Stages exact decrypted archive image bytes in app-private storage; checks **each staged file** against its declared SHA-256 and byte length again.
4. Validates that every destination path is an app-owned attachment or profile-image path that **does not already exist**. Profile pictures use `ProfileImageRepository.storageKey` with the *new* Character/Persona/Scenario ID. Never merge by display name.
5. Writes an import-owner journal to `filesDir/world_import_recovery`, flushed and atomically renamed **before** the first final file is promoted.
6. Moves staged files into new private paths and inserts **all** rows inside one Room transaction. Normal backup `INSERT ABORT` methods are reused, and `ScenarioDao.insertForWorldImport` is explicitly ABORT rather than REPLACE. No delete or table clear.
7. If the transaction throws, Room reverts every row, and the importer removes the newly promoted files. If the process dies mid-transaction, the Room transaction rolls back and the journal remains for recovery.
8. If Room commits, the imported World row acts as the commit marker. Journal cleanup or app-restart recovery keeps the committed image files; an uncommitted World triggers orphan-file deletion.

`ElsewhereApplication` probes only for leftover import journals or staging directories. If present, it schedules recovery under the mutation lock on the IO dispatcher. Normal launches avoid the recovery query. A future Import screen must still await successful recovery before allowing another import.

## Tested with private, fabricated data

- Existing World/chat/dialogue remains unchanged after adding a second World with a chat and image.
- Failures injected immediately after image promotion and after the entire record insertion batch restore the prior database and remove uncommitted image files.
- A second import with the same namespace cannot overwrite or duplicate the first.
- Collision with an existing Scenario aborts without replacing the Scenario's definition.
- Namesake Character profiles remain separate after remapping and importing a second portrait.
- Variant Regenerate branches and source-message/variant-linked NPC revisions retain their original logical relationships.
- Simulated leftover journal removes orphaned images, but leaves committed import files intact.

## Explicit limits before release

- The Import/Restore UI is **not wired**; no archive is imported through normal gameplay. User review and consent, private-note/draft inclusion warnings, file-picker, export snapshot consistency and passphrase handling still need end-to-end wiring.
- No merge-into-existing-World capability or automatic namesake identity reconciliation. Unknown/opaque authoring structures fail closed.
- Unsupported NPC portrait files fail rather than silently lose content; the NPC portrait storage adapter is a separate implementation task.
- Room transactions and asset promotion are coordinated, but filesystem and SQLite are not one true atomic storage engine. Journal cleanup is designed for process death, and must be tested under actual power interruption, low disk space, and forced termination before trusting irreplaceable histories.
- Safeguards must be tested on a separate Elsewhere Test installation with export → uninstall/reinstall → restore, plus additive import into an installation already containing unrelated World histories. No automatic production migration.
- Huge Worlds, asset/storage quota limits, encryption export destinations and foreground execution restrictions need practical device verification.

**Never claim this is fully crash-proof or user-ready based on passing local/CI tests alone.**
