# Stage 1 — Story-memory identity and retrieval eligibility contract

**Status:** review-only foundation; **not integrated into generation**.  
**Source:** Elsewhere Memory-First Roadmap (2026-11-09), Stage 1 and locked decisions.  
**Baseline:** working v1.5.2 production, Room v15, existing `roleplay_memories` retained without changes.

## Why this must precede retrieval

Current `RoleplayMemoryEntity` stores `memoryId`, `chatId`, `characterId`,
`category`, `content`, optional `sourceMessageId`, timestamps. It has no stored
branch ID, source variant-group snapshot, per-speaker knowledge audience, review
status, accepted/resolved/superseded state, or explicit Wack restoration grant.

Current `ContextResetPolicy` excludes pre-Wack transcript and old rolling
summaries, but Solo/Group `ChatDetailViewModel.prepareGeneration` loads the
legacy durable memories without a Wack source filter. The current Scenario path
has a separate source-ID check against `history + current`. This contract does
**not** change that production behavior; integrating the gate is a later
behavioral change requiring tests, UX and backup compatibility.

## Fail-closed rules

1. **Ownership:** Stage 1 reads only records owned by this chat and sourced from
   the same chat. Future `CAMPAIGN`/`WORLD` are declared but ineligible until
   explicit campaign ownership and accepted-World promotion are implemented.
   Display names are never identity; Actor references use a typed stable ID.
2. **Branch:** Current stored `MessageEntity` has `variantGroupId` and
   `isPrimaryVariant`, but no complete branch ancestry. Message-sourced memory
   requires the source message to exist, be the unique primary for that variant
   group, and match the recorded speaker, group and order. A future explicit
   `branchId` must match exactly. This is a **local selected-variant guard**, not
   a claim that full branching timelines have already been implemented.
3. **Provenance:** New memories may capture a SHA-256 content digest so edits
   invalidate stale records until reconciling them. A missing source is never
   reconstructed by matching story text. A legacy source that was edited but
   lacks an original digest is ineligible pending review.
4. **Wack:** Pre-boundary memories do not enter context automatically. A
   `pinned` flag, later edit timestamp, or mere presence in a search result is
   insufficient. A return is permitted only for a **specific memory ID, chat,
   active Wack boundary and branch**, approved by the user after that boundary.
   An older grant does not carry across a later Wack. Reintroduction never
   bypasses ownership, selected-variant or knowledge rules.
5. **Manual origin:** A verified user-created memory records an independent
   order index so a genuinely new post-Wack entry can be eligible without a
   provider call or a source message. Old manually created records still need
   explicit reintroduction.
6. **Knowledge:** PUBLIC, STORYTELLER_ONLY, PERSONA_ONLY, CHARACTER_ONLY and
   NAMED_ACTORS are distinct. Mentioning a character in the story does **not**
   mean the character knows the fact. Secrets cannot be PUBLIC; a mistaken
   BELIEF must name its believer; an unresolved CONTRADICTION is currently
   storyteller-only. A narrator is not automatically a recipient for
   character-private knowledge. Structured output must eventually label
   claim type and speaker scope, not convert beliefs into objective facts.
7. **Review/lifecycle:** PENDING_REVIEW, REJECTED, HIDDEN and SUPERSEDED are
   ineligible. RESOLVED events remain eligible for relevant historical recall,
   but must retain their status when rendered; they cannot be presented as
   current open obligations. A pin increases future relevance **after**
   eligibility, never overrides a boundary.
8. **Legacy compatibility:** Without Wack and without a named branch, existing
   `roleplay_memories` can remain readable only to their original
   `characterId` (or matching Scenario narrator). With Wack, source-linked
   records require source order after the boundary unless explicitly restored;
   null-source legacy records require explicit restoration. Legacy records
   are not treated as global shared World facts.

## What this PR implements

`StoryMemoryEligibilityPolicy.kt` is a pure Kotlin in-memory decision function:
typed record/source/recipient/return contracts, explicit exclusion reasons,
source verification and a read-only legacy eligibility adapter.

`StoryMemoryEligibilityPolicyTest.kt` exercises safe and unsafe cases with
synthetic messages. Tests run in GitHub Actions without provider/network calls.

**Not implemented by this PR:** Room table, migration, public UI controls,
manual Save as Story Memory, branch graph, source-change invalidation jobs,
legacy memory backfill, local search, context assembler, token budgets or model
response quality tests. No existing memory row or transcript is updated.

## Proposed additive persistence phase (requires separate approval)

Build a **new** structured `story_memories` table, plus `story_memory_actors`,
`story_memory_knowledge` and `story_memory_explicit_returns` (or an equivalent
normalized design) under a reviewed v15→v16 Room migration. Do **not** overwrite
existing `roleplay_memories` or `conversation_summaries`. Retain source IDs,
variant-group identity, order, speaker identity, optional source fingerprint,
claim/epistemic state, review/lifecycle state and user-edit provenance.

Prefer indices on (chatId, branchId, status), sourceMessageId and scope. No
on-delete cascade should silently erase memory provenance: deletion or variant
changes should mark records ineligible/needs-review while retaining recoverable
history. Keep separate user grants for explicit restoration across Wack.

Backups must preserve these records and grants; prove migration and rollback
against production-format synthetic and test-copy data. World Definition JSON
must not become a vehicle for private Campaign memories. Existing approved
World continuity is a **separate** system, not an automatic destination for
episodic memory.

## Remaining decisions before live integration

- Define how to record exact branch ancestry and how it changes on regeneration
  (existing primary-variant flags do not fully encode downstream branches).
- Build the manual Save/Edit/Pin/Hide/Delete and user-initiated Restore UI.
- Decide how character-private and storyteller-only fragments are rendered in
  mixed Narrator + Character responses without speaker-knowledge leakage.
- Design safe legacy/null-source review and import/backfill without inventing
  provenance or losing existing manually saved facts.
- Test that the gate is applied to **Send, Retry, Regenerate, Continue and
  Scenario**, with selected variants, Wack and co-op authorization all agreeing.
- Add 500/2,000/10,000-message integration benchmarks for precision, false
  belief contamination and source invalidation, not just prompt presence.

**Release rule:** no merge of the future generation filter, new schema or
storage migration on the strength of these pure eligibility tests alone.
