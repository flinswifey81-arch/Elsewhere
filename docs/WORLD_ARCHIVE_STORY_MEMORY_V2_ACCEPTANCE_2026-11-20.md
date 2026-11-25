# World Archive — Story Memory V2 preservation acceptance

Status: **Implemented in draft PR #49; requires current-head CI and end-to-end acceptance**. This is not a release approval, signed APK, permission to install, or claim that the complete Elsewhere roadmap is done.

## Previously confirmed defect

The encrypted World export copied legacy `roleplay_memories` and chat messages but omitted the five tables used by the new Story Memory V2 system. A World imported on another device therefore lost its newer memory continuity, character knowledge, and Wack history even when its transcript was intact. The **whole-app encrypted backup already preserved these tables**; this defect affected selected World transfer.

## Implemented data-preservation contract

A selective World archive now captures, validates, encrypts, remaps, and atomically restores the selected chat's:

- `story_memories`: authored/reviewable memories, prior relationship/promise links, source message/variant identity, approval, hidden/pinned and lifecycle state, source hash and player-authored outcome;
- `story_memory_actors`: named actor mentions, relationship/promise roles, and **explicit** current character KNOWS grants;
- `story_memory_returns`: the exact user-approved post-Wack returns and reset boundaries;
- `story_memory_knowledge_changes`: append-only character grant/revoke history;
- `story_memory_perspectives`: each character's revision history, stance, notes, and text fingerprint.

Only records belonging to **explicitly selected World chats** are allowed; nothing pulls in another World's memories by name. Cross-chat/missing-actor/grant and duplicate-record validation runs **before** Room changes. New identities are namespaced independently for chats, Characters, Personas, Story Memories, speakers, messages and variants. Deleted historical source-message references and missing predecessor links remain traceable but **do not become valid or reauthorized** just because a new World was imported. Valid structured-message source hashes are recalculated only if their **old** hash was still correct, so a stale memory cannot be silently revived by a JSON speaker-ID rewrite.

The Room restore adds the five record collections in one transaction with existing World messages/assets. A failure rolls back the entire new World. It does not modify existing chats, Worlds, actor grants, source messages, or global app settings.

The existing World archive state schema (`elsewhere_world_state`, schema 1) now has five **optional** fields with empty defaults, so encrypted archives made before this extension remain parseable. **No new migration of the production app database is required.** World export/import previews disclose the number of Story Memories; exporting any such record requires explicit sensitive-material consent and encryption.

## Acceptance tests added to existing CI

- `WorldArchiveMemoryV2TransferTest`: production-path **Room → reviewed encrypted World file → authenticated inspection → additive new-World Room import**; verify old data remains, all five tables survive, message/Character IDs and source hashes point to the imported records, temporal predecessor links stay intact, note/perspective revisions remain, and the default export denies unreviewed private memories.
- `WorldArchiveMemoryV2TransferTest`: prevent memories from unlinked chats being exported.
- `WorldArchiveHistoryTest`: malformed cross-World actor grants, wrong chat owners, invalid user-Wack consent, and perspective references fail closed; preserve deleted historical source provenance.
- `WorldArchiveHistoryTest`: structured-response speaker rewrites only refresh **valid** source hashes; stale original hashes remain stale. Preserve typed relationship and promise actor roles rather than silently omitting them.

The new integration test is required in `world-archive-contract.yml`, `worlds-unified-acceptance.yml`, and the **manual-only** production candidate builder. Those checks are not claimed to pass until verified against the exact current head.

## Remaining release scope

This fixes **chat-local Story Memory V2 transport**, not missing Campaigns, World-wide memory promotion, AI-assisted extraction, full World Definition JSON import, Living Cast conversion, image-inclusive PDF output or physical-device acceptance. Keep PR #49 **draft**, production signing off and the user's sole installed Elsewhere untouched until the full roadmap acceptance is complete.
