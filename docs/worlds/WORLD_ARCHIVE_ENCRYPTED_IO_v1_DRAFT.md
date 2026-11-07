# Elsewhere Worlds — Encrypted archive transport v1 (Draft)

**Status:** verified transport, snapshot-asset staging and read-only preview. **NOT a complete World backup/restore feature.**  
Stack: Worlds foundation PR #15 → manifest PR #30 → history/reference PR #31 → encrypted I/O PR #32.

## How an eventual export will work

1. Under a coordinated read snapshot, collect the selected World and its explicitly attached chats, settings, variant history, memory, NPC revisions, Lorebooks and scenario session definitions into a `WorldArchiveStateV1`.
2. Resolve *all* message attachment source rows and each included Character/Persona/Scenario's existing local portrait through the repositories. `WorldArchiveAssetCollector` stages verified copies in an app-private temporary directory, not a public media directory. Missing attachments or SHA-256 mismatches abort export.
3. `WorldArchiveDefinitionProjection` derives the World definition **from that same state snapshot**, including authored cast, accepted continuity, setting metadata and Scenario references. Draft continuity remains in the encrypted history state, not published in the accepted definition.
4. `WorldArchiveEncryptedIO.exportSelectedEncrypted` builds the strict manifest with owner IDs, sizes and SHA-256 hashes, writes a ZIP with the manifest first, reopens the resulting ZIP and verifies **every byte**, then encrypts it using the existing backup AES-256-GCM/passphrase primitive. The World schema stays distinct from whole-app backup, and the whole-app restore restrictions do not change.
5. The exporter copies the finished encrypted result to the requested destination only after internal validation. Its app-private temporary plaintext and encrypted staging files are deleted on normal completion or error.

**Current limitation:** The export pipeline is implemented as separate components, not yet hooked to a single consistent database transaction or Worlds-screen Export action. While the image collector copies repository files, the caller still must ensure an appropriate stable database snapshot boundary. A process killed mid-export may leave temporary app-private files that the future coordinator must clean on startup.

## How an eventual import will work

1. After passphrase unlock, `inspectEncrypted` decrypts to app-private scratch, checks the manifest-first ZIP contract, refuses unlisted/missing/duplicate/traversal entries, enforces uncompressed size limits, and verifies every file against its exact SHA-256 and byte length.
2. It parses and cross-checks the World definition and history graph. The returned `VerifiedWorldArchive` owns its temporary decrypted files and implements `Closeable`; it **must** be closed after preview or staging.
3. `WorldArchiveImportPreview` presents counts for chats, messages/variants, characters, personas, scenarios, NPC revisions, lore, attachments, optional portraits and flags for drafts/private notes/secrets. No plaintext RP excerpts need to appear in a general file list or notification.
4. `WorldArchiveIdPlanner` can propose an isolated deterministic set of new identifiers for the explicitly selected destination operation. It never guesses same-person identity from names; no database record is written by a plan.
5. The **unfinished** restorer must rewrite every typed reference plus embedded StoryResponse/Scenario/NPC JSON references by schema, stage assets to new safe device paths, validate a complete final transaction plan against the *current* database, then perform additive Room writes and file placement with rollback/recovery. It must prove that unrelated existing Worlds and chats remain intact.

## Safety / test requirements

Covered by offline tests:
- Correct encrypted history + attachment roundtrip.
- Wrong passphrase and modified ciphertext rejected.
- Missing, corrupt or ownership-mismatched image refused.
- Unexpected entries and deleted `world/state.json` rejected even in an otherwise valid encrypted container.
- Source image copies and private Android `relativePath` never placed into the portable state.
- Read-only preview, expected record counts, destination-independent ID plan.

Remaining release gates:
- Complete consistent Room snapshot export coordinator.
- Portable inclusion of every optional profile portrait and any future NPC portrait type.
- Full semantic ID rewrite; no global string replacement inside RP prose or silent default merging of namesakes.
- Atomic additive restore with database + asset rollback, file-space checks and crash recovery.
- Tests for truly enormous Worlds and OS process interruptions.
- Elsewhere Test device export → reinstall → import, then explicit cross-variant additive import validation, **before** accepting real irreplaceable histories.

Never turn a successful validation of an encrypted ZIP into an automatic 'Restore succeeded' result. A verified archive is a **candidate for review**, not an instruction to overwrite personal data.
