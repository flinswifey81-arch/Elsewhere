# Memory V2 stabilization and continuity contract

Status: implementation plan; not a claim of completed functionality.
Base: work/memory-stage1-integration-2026-11-17

## Non-negotiable invariants
1. No mutation of authored Character, Persona, World or Lorebook canon by automatic extraction.
2. No cross-chat, cross-branch, cross-Wack, alternate-variant or unauthorized-character memory leakage.
3. Memory retrieval is local and incurs no model-provider request.
4. An inferred claim must retain provenance, confidence/review state, and any contradictory or superseding claims; never silently promote an inference to fact.
5. The player retains exclusive control of Persona thoughts, decisions, dialogue and actions.
6. Existing v15 backups must restore into v16+; migrations are additive and restore is rollback-safe.
7. Every generation mode (solo, scenario, group/co-op, send, retry, regenerate, continue) follows the same explicit prompt-budget and authorization contract; co-op remains on its current contract until compatible on both devices.

## Recovery gate
- Diff integration branch against main; reconcile main-only commits.
- Run Room 15->16 migration, backup round-trip, restore rollback, prompt privacy, recipient eligibility and generation regression tests.
- No merge to main before passing gates.

## Memory V2 model
- Canon: immutable authored identity and setting authority.
- Episodic events: temporal, provenance-backed occurrences and participants.
- Semantic facts: durable learned knowledge, with explicit scope and source.
- Relationship state: time-ordered changes, not a flat evergreen summary.
- Active scene: ephemeral current location, actors, goals and open threads.
- Knowledge graph: knows / believes / suspects / unknown, per character and time.
Reuse existing story-memory entities where possible; introduce new schema only with migration tests.

## Retrieval contract
1. Select only primary, current-branch source records after applying Wack/return rules.
2. Enforce audience/knowledge eligibility before scoring.
3. Rank by active-scene relevance, recency, pinned priority and unresolved threads; avoid duplicate claims.
4. Resolve latest state without erasing historical events. Explicit contradictions remain labeled, never silently collapsed.
5. Budget the final assembled request (including all system sections, transcript, director turns, images and reserved completion allowance). Protect authored core; drop optional context in deterministic priority order. If core alone cannot fit, fail before any provider request with actionable diagnostics.
6. Emit counts-only diagnostics; compare estimated prompt size to provider-reported tokens and cost. Do not log prompt contents.
7. Avoid full-chat archive scans per turn: use incremental provenance indexes or bounded lookups, preserving validation.

## Automatic extraction contract
- Extraction disabled by default until quality and privacy gates pass.
- Extract candidate events, changes, promises, discoveries and relationship updates, with exact source message and variant.
- Candidate is not authoritative until accepted by a defined policy or user review.
- Never infer private character knowledge merely because narrator text mentions it.
- Regeneration/edit/deletion invalidates or revisits affected candidates.

## Test matrix
- 500, 2,000 and 10,000-message synthetic chats: preparation latency, allocation, recall accuracy, cost estimate and provider usage.
- Secret known by A but not B; rumor versus fact; reconciliation after conflict; corrected identity; branch switch; variant regeneration; Wack return; missing source; deleted source; scenario narrator; co-op mismatch.
- Over-budget baseline, oversized lorebook, unknown model context, image input, reasoning-model output reserve, no paid API calls in tests.
- Backup/restore across versions and rollback on failure.

## Implementation order
A. Stabilize existing integration and tests.
B. Unified prompt budget + diagnostics and model-aware output reserve.
C. Indexed retrieval and temporal supersession/relationship state.
D. Candidate extraction and review UI.
E. Full benchmark suite, documentation and staged merge.
F. Separate PDF transcript export recovery/build, without changing memory semantics.
