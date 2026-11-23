# Memory V2 — crowded-scene offline prompt acceptance (2026-11-23)

Status: committed test on draft PR #49; **not** production release or measured model-answer accuracy.

The new `MemoryV2CrowdedSceneAcceptanceTest` checks 2,000 synthetic Room messages, 144 unrelated memory distractors, selected and unselected regeneration variants, a hidden record, a pending proposal, a separate chat's private memory, changed relationships, an explicitly superseded promise, and a Character-only secret. The most recent 24 turns contain realistic 1,000+ character bodies. A Scenario prompt with an 8,192-token model window must preserve the relevant approved story memory and authored world rules after trimming older transcript and an oversized generated summary.

The test verifies what the production repository actually retrieves **and what the compiled, budget-fitted prompt contains**. It checks paraphrased letter location, completed promise, chronological argument/reconciliation, per-character knowledge, Wack and exact source-variant invalidation. No ModelProvider, network request or additional provider charge occurs. Diagnostic logs contain only counts and numeric safe budget estimates.

Prior `MemoryV2RoomScaleBenchmarkTest` separately exercises 500, 2,000 and 10,000 messages. Neither synthetic suite proves how an external model will respond in natural prose. An online quality evaluation requires the user's separate informed approval of model, scope and cost ceiling; do not silently perform paid tests or deploy to the user's device.

## First quality regression (caught by the new test)

The initial run reported an extra irrelevant active relationship alongside the correct fulfilled promise. The production local ranker granted every ACTIVE relationship a baseline bonus even with zero subject, word or actor overlap. This could waste a scarce prompt-memory slot and encourage an unrelated callback. The fix grants an active relationship's continuity bonus **only when the scene references its terms or active actors**; user-pinned memories and unresolved commitments retain their prior intentional priority. A focused unit regression asserts both exclusion for unrelated relationships and recall when the relationship is actually relevant. Exact-head CI must still pass before this is considered automated-verified.
