# Memory V2 — long-story synthetic acceptance and release evidence

Status: **draft validation record**, not a shipping approval or live model-quality claim.  
Work: draft PR #49, `work/memory-v2-stabilization-2026-11-18`.  
Fixture: `app/src/test/java/com/example/domain/MemoryV2RoomScaleBenchmarkTest.kt`.

## What this benchmark actually exercises

The test uses **real in-memory Room tables** and the production `StoryMemoryRepository`, local provenance reader, recipient eligibility filter, local ranker, Solo/Group/Scenario context compilers, conservative request preflight, and the optional-context budget fitter.

For each of **500, 2,000, and 10,000 synthetic selected story messages**, it creates source-linked events at turns 17, 29, 41, 53, 67, 79, and two near the end, plus a discarded alternate variant. It checks these independently:

| Area | Required invariant |
|---|---|
| Long-history recall | All 8 eligible source-linked events are found locally; early events survive the 24-turn recent transcript boundary |
| Recipient privacy | Rowan's explicit character-only memory/belief is not retrieved by Selene or the narrator |
| Subjectivity | Rowan receives a **belief, not fact**, label; private player perspective notes never reach a prompt |
| Solo/Group compilation | Rowan's private belief appears only in Rowan-scoped Solo and Group prompts, not Selene's Group prompt |
| Rejected material | Pending/hidden records and discarded alternate text cannot enter the narrator's prompt |
| Variant provenance | Switching selected message variant invalidates the original source-linked record |
| Edited in-story perspective | Player-editing a memory invalidates a previously recorded subjective stance until explicitly reaffirmed |
| Wack isolation | Pre-Wack memories remain in the archive but are not retrieved; restoring one exact record does not restore other records |
| Budgeting | Oversized generated summary is omitted before approved Story Memory; authored World baseline and player turn remain |
| Scale and cost | Prompt byte footprint remains bounded across all three history lengths; logs show estimated input tokens, final safe ceiling, and **hypothetical text-only** pricing |
| Privacy of evidence | Console output is counts, elapsed times, and numeric estimates—not synthetic transcript text, private memory values, model credentials, or user data |
| Provider calls | Repository, prompt compilation and preflight run locally; this fixture constructs **no ModelProvider** |

**Two token estimates have different purposes.** `ContextBudgetProbe` reports a rough UTF-8 ÷ 4 text-only budget for diagnostics; `PromptPreflight` uses a more conservative input estimate, image allowances, and a reserved completion allowance to gate submission. Their numbers must not be presented as interchangeable, tokenizer-exact, or billed costs.

The diagnostic price uses an arbitrary fixture rate of **$2.50 per million text input tokens**, purely to validate arithmetic; it is **not** a model quote, a provider bill, or a prediction of the user's OpenRouter balance. It excludes images, output/reasoning tokens, caching effects, and router/provider adjustments.

## Evidence and limitations

CI workflow `Memory V2 Verification` executes the Room scale benchmark; the broad regression workflows must also pass on the exact candidate commit. `MEMORY_V2_ROOM_BENCHMARK` lines print numeric measurements, not artificial latency pass/fail thresholds; host and runner variance means CI median timings are diagnostic only.

These are **deterministic input/retrieval assertions**, not proof that Sonnet or another LLM will correctly remember the evidence in its generated prose. Actual model-answer quality, the semantic precision/recall of candidate extraction, production Android memory pressure, exact tokenizer ceilings, online charged request-vs-response cost comparison, co-op ownership parity and historical backup upgrades still need dedicated evidence.

### No-release / data safety gate

Do not treat green CI as permission to merge or install over the user's sole production app. Keep PR #49 draft, check exact package ID and signing certificate identity, retain protected copies of the current encrypted production backup, test restore and schema migration on a disposable QA build/device, demonstrate rollback/recovery, and require the user's explicit approval before any production installation or paid provider test.

See `docs/STAGE1_V16_SAFE_UPGRADE_RELEASE_GATE.md` and `docs/MEMORY_V2_STABILIZATION_CONTRACT.md`. Those older v16 release-gate documents remain **gates**, not proof that the now-v21 development database has been verified on a device.
