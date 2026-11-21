# Elsewhere Memory-First — Stage 1 integration and Stage 2 first-recall review

**Date:** 2026-11-17  
**Work:** draft PR #48, `work/memory-stage1-integration-2026-11-17`  
**Base:** draft Stage 2A `feature/stage2-local-retrieval-2026-11-10` (itself based on draft v16).  
**Production main:** 1.5.2 / Room v15, deliberately unchanged.  
**Status:** NOT RELEASED; do not merge, publish or install over the production app without release-gate review.

## Implemented in this review stack

1. **Legacy Wack and variants:** All Solo/Group/Scenario preparation and the Context Inspector apply `StoryMemoryEligibilityPolicy.evaluateLegacy` to existing character/narrator durable memories. Reject pre-Wack records, unselected/missing/edited sources, unknown-origin memories crossing Wack and wrong recipients. Preserve original records without destructive mutation.
2. **Manual character knowledge:** Story memory owners may use *Who knows this?* to explicitly grant memory to selected chat Character participants. Actor links are persisted in the v16 `story_memory_actors` table. Default remains storyteller-only; unselected actors never inherit secrets. Revocation preserves storyteller-level attribution of a mistaken belief, not the Character's access.
3. **Wack restoration:** Explicit per-record return grants respect the exact boundary; restoration of a Character-only memory never makes it Narrator-visible. The archive and its selected-variant references remain intact.
4. **Local retrieval:** `StoryMemoryRepository.retrieveForGeneration` reads records, selected source messages, source fingerprints, actors and Wack grants in one local Room transaction and passes them through the offline `StoryMemoryLocalRetriever`. Source/variant lookups are indexed once per read context to avoid repeated full-history scans. No embedding, network call, OpenRouter request or automatic World-canon promotion occurs.
5. **Prompt integration:** Solo, Group and Scenario now receive eligible, recipient-scoped local story memories via their existing generation prompts; group Character isolation and Persona agency instructions remain. Co-op deliberately stays on its prior fingerprint contract until both devices agree on shared memory authority.
6. **Additive context-cost guard:** `StoryMemoryPromptBudget` measures existing compiled prompts conservatively and only admits optional story memories fitting an estimated allowance, using cached OpenRouter model window sizes when already loaded. It does **not** trim the authored World, Persona, Character, Lorebooks, NPCs, recent transcript or output instructions and does not fetch new model metadata.
7. **NPC Wack:** Generation-side NPC dossier projections exclude pre-Wack model-generated changes while retaining manually authored NPC bases and preserving the historical NPC Bank. Offline tests cover post-Wack reappearance of newer changes.
8. **Context Inspector:** Its preview uses recipient-aware memory retrieval and the same *additive memory* allowance; it also includes the linked accepted World baseline. It is still not a byte-perfect replacement for every route of full generation.
9. **Regression coverage:** Imported Stage 0 characterization at 500, 2,000 and 10,000 messages, encrypted synthetic v15-backup compatibility fixtures, Room v16 storage/migration tests, Wack/source/variant/knowledge tests, budget tests and a full ViewModel→Room→prompt→fake-provider Wack/restoration test. Narrow and broad automated Android CI run on the stacked draft PR.

## Remaining technical limitations (do not conceal)

- **Stage 1 identity exit gate is NOT met.** World Cast, Character definitions, NPC Bank, and Scenario NPCs have not been unified under one shared stable identity/reference contract. The v16 memory records have typed actors and participant restrictions, but this does not complete NPC↔Character conversion.
- **Stage 2 unified context-budget exit gate is NOT met.** The new guard limits optional retrieved memories by conservative UTF-8 estimates and cached context windows. A pre-existing authored prompt can still exceed the model context ceiling. There is no actual tokenizer-aware, category-wide unified budget planner, enforced output reservation, FTS message indexing or benchmark proving model answers *use* remembered facts correctly. The 10k test measures retrieval/assembly, not live model answer quality.
- **No optional automatic extraction.** Stage 3 opt-in model selection, review queues, spend cap, deduplication and variant invalidation remain future work.
- **No Campaign history, cross-chapter scope or World-canon promotion.** Each new memory is chat-local. The World archive format does not yet include the new v16 memories.
- **No live-provider testing or on-device performance measurement.** CI tests use fake models; no subscription/API credits are consumed. Intermittent historical ChatDetail startup/dispatcher timeouts must be triaged separately, not silently waived.
- **Co-op is excluded** from the new memory-injection path until the shared context fingerprint includes equivalent memory ownership, source and budget checks on both devices.

## Required release block — user/device data

See `docs/STAGE1_V16_SAFE_UPGRADE_RELEASE_GATE.md` for the detailed checklist. All existing Android 1.5.2 data must survive a **real-format** v15 encrypted-backup inspection and restore, plus a detached, full-schema v15→v16 Room upgrade, rollback demonstration, signer/package checks and device installation checks. Synthetic unit fixtures are necessary but not sufficient. **Do not install a debug-signed or v16 APK on top of the user's only production app.** No file containing actual roleplays, passwords, or exported secrets should be committed to this public GitHub repository.

A draft PR and successful CI are not the user's approval to migrate installed data, ship a release or incur OpenRouter charges.

## Validation evidence

- Previously frozen v16 focused memory/migration/backup suite: PR #42, successful run 42804278879.
- Stage 2A deterministic local retrieval: PR #43, successful run 42913607258.
- Integrated review first passing run: `fa174784e9191e00939491e369c74168003af2c8`, run 44050763970.
- Newer commits including the indexed 10k retrieval test, additive budgets, NPC Wack filtering and full ViewModel recall tests require **fresh** passing CI evidence. Check PR #48's most recent workflow run before treating them as verified.

**Principle:** Store broadly; retrieve narrowly; send selectively.
