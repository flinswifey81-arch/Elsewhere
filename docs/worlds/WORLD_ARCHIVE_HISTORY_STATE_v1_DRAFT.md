# Elsewhere Worlds — History Snapshot & Reference Plan v1 (Draft)

**Status: contract and tests only. No archive export/import or restore UI is available.**  
Working branch: `feature/world-archive-history-closure-2026-11-05`, stacked on archive manifest PR #30 and Worlds foundation PR #15.

## Three distinct artifacts

1. **World definition** (`elsewhere_world_definition`): creator-approved setting, cast, accepted continuity and authored resources. It is not a transcript backup.
2. **World archive manifest** (`elsewhere_world_archive`): lists reserved definition/state files and binary asset entries with byte size, digest, ownership and safe path.
3. **World history state** (`elsewhere_world_state`): typed World-scoped records, with no Android-private asset file paths. All three must pass independent validation, and the encrypted container must pass authentication before importing.

## Selection boundary

A chat belongs in an archive only through an explicit World `CHAT` membership. The collector can select every attached chat or an explicitly chosen subset; selecting one World never sweeps up unrelated chats that share a Character or Persona. If World continuity cites a chat excluded from the selection, validation rejects the archive instead of dropping the citation. World memberships for independently linked Character, Persona, Scenario, Lorebook and NPC-bank records stay distinct from transitive chat dependencies.

Source is an already collected `BackupPayload` via a **pure selector**. That source type is reused as a read model, not as a mechanism to bypass whole-app backup rules. No database writes occur. The current selector is not yet connected to a Room export transaction.

## Preserved history

- Every selected chat, with type, timestamps, context/Wack reset boundary and **effective per-chat model/routing settings**. Chats that inherit global engine defaults are snapshotted into self-contained per-chat values, without changing the importing app's global defaults.
- All Persona/Character chat participants and full source cards.
- **All** messages, including nonprimary Regenerate variants, their shared `variantGroupId`, order and speaker snapshots. Generation metadata shares the source message's identity.
- Roleplay memories with category and source-message provenance; conversation summaries.
- Scenario templates **and each selected playthrough's immutable definition snapshot**.
- Attached Lorebooks, entries, Canon, Current, per-chat Current overrides, manual activation and attachment order.
- Chat NPC dossiers, enabled settings, append/revision history and exact source message/variant linkage; reusable NPC-library entries.
- Scoped and relevant reusable relationships, World cast and approved/draft/noncanon continuity events with source links.
- Portable attachment metadata, owner, archive-relative name, digest and sizes. Actual images and portraits remain a separate binary-staging obligation.

The snapshot must never serialize `MessageAttachmentEntity.relativePath`. Binary files require authenticated manifest entries and byte-for-byte digest/size validation before restore. No ZIP extraction or encryption is implemented in this PR.

## Privacy boundary

Future UI must obtain a distinct, explicit inclusion review for private Character/Persona notes, NPC secrets, private Lorebook notes, and unsent chat drafts. The selector rejects those fields by default, rather than quietly deleting them from the archive. All full-history archives must use user-controlled encryption.

No OpenRouter/API credentials, signing keys, global app preferences, active co-op room credentials, connected participants or network-control events enter this snapshot. A selected chat with co-op room state/provenance requires an explicit offline-conversion workflow; this draft refuses export instead of silently dropping authority information.

## Identity-remapping contract

`WorldArchiveIdPlanner` creates a *plan*, not an import:

- Caller provides one unique operation namespace (generated once and retained through a staged import). The same source + namespace yields the same destination IDs after an interrupted planning attempt.
- Separate ID spaces: World, World Cast, event, Character, Persona, Scenario, Scenario NPC, chat, message, **memory**, variant group, relationship, Lorebook, entry, local NPC and revisions, reusable NPC bank/entry, attachment.
- A `variantGroupId` equal to its originating message ID keeps that alias after remapping; other variant groups get their own ID. Generation metadata uses its message's remapped ID.
- Existing destination IDs and accidental collisions must be rejected before any mutation.
- **A matching display name does not mean matching identity.** No automatic merging of namesakes or Character/Persona role conversion.

Before a real restore, every reference must be rewritten **semantically**, including `WorldEntity.linksJson`, continuity-related cast IDs, Scenario template/session NPC IDs and source Character IDs, NPC Persona/Character knowledge maps, structured `StoryResponseCodec` speaker IDs inside messages, image asset owners and all typed table foreign keys. No unsafe global string replacement in RP prose.

## Required future release gates

1. Read a single consistent, World-scoped database snapshot; preview counts, optional private material and any unarchivable dependencies.
2. Freeze every required asset into a staged, content-addressed archive member; verify actual SHA-256, MIME and byte counts, file limits, archive paths and ownership.
3. Encrypt with a user passphrase and bind exact manifest + state bytes to the authenticated container; verify on decrypt.
4. On import, validate all three artifacts, type/schema/version, content references, exact filenames, and every expected/extra file; build a complete deterministic ID rewrite with collision preview.
5. **Transactional additive restore** with rollback for both Room data and assets, preserving unrelated production data. No automatic Test → production whole-app backup restore; selective World imports have their own separate, reviewed policy.
6. Automated round-trip and malicious/missing dependency tests, followed by actual Elsewhere Test export, uninstall/reinstall, restore, and cross-variant verification before using irreplaceable stories.

**Do not label this feature complete or place an Import World Archive button in the UI until all gates pass.**
