# Memory V2 — reproducible long-chat scale measurements (2026-11-19)

Status: **AUTOMATED TESTED** for the focused CI benchmark on commit `e67583f`. Not device-verified or a production-latency guarantee.

## Scope and evidence

Test: `app/src/test/java/com/example/domain/MemoryV2RoomScaleBenchmarkTest.kt`.
CI workflow: `.github/workflows/memory-v2-verification.yml`.

The test builds synthetic Room v16 histories at 500, 2,000, and 10,000 messages. In each history it saves eight message-linked, approved storyteller memories; inserts one unselected alternate variant, one hidden manual memory, and one pending-review memory; and invokes production `StoryMemoryRepository.retrieveForGeneration`. It checks exact source/variant results, recipient eligibility, selected recall, and privacy exclusions. The actual `ScenarioContextCompiler` then builds a prompt, and `PromptHistoryTrimmer` verifies the full request fits the synthetic 8,192-token window.

Each stage has one warm-up plus three timed samples. Values below are medians in milliseconds, excluding fixture insertion. Runs execute without any `ModelProvider` instance, API key, paid model request, or user conversation data.

### Before bounded recent-history selection

CI: `82388fb` — Memory V2 Verification succeeded.

| Archive messages | Retrieval median (ms) | Prepare + preflight median (ms) | Prompt bytes | Rough input tokens |
|---:|---:|---:|---:|---:|
| 500 | 3.322 | 0.160 | 3,040 | 760 |
| 2,000 | 2.976 | 0.267 | 3,066 | 767 |
| 10,000 | 4.003 | 1.216 | 3,067 | 767 |

### After bounded recent-history selection and generation lifecycle fix

CI: `e67583f` — Memory V2 Verification succeeded.

| Archive messages | Retrieval median (ms) | Prepare + preflight median (ms) | Prompt bytes | Rough input tokens |
|---:|---:|---:|---:|---:|
| 500 | 1.800 | 0.075 | 3,040 | 760 |
| 2,000 | 2.728 | 0.043 | 3,066 | 767 |
| 10,000 | 2.811 | 0.032 | 3,067 | 767 |

At every scale: eight approved memories selected, eight exact source rows plus nine source-variant rows returned, 26 prompt messages, and zero provider calls. The 10k-turn prompt stays about the same size as the 500-turn prompt. The bounded selector no longer allocates and filters a full transcript-sized list inside either compiler. A separate regression asserts that only 26 list items need inspection when selecting 24 turns after two unselected recent variants.

## Interpretation and limitations

- Small timing differences between CI runs are noisy. These measurements demonstrate a strongly improved algorithmic bound in the compiler, not a universal end-user speedup or an apples-to-apples hardware benchmark.
- Source/variant **rows returned** are bounded. SQLite may still scan the messages table internally because `variantGroupId` does not yet have its own index. Benchmark the query plan and add a composite index only through an explicitly reviewed additive Room migration with backup/restore tests.
- The current test exercises Room retrieval + scenario compilation + prompt preflight in isolation. It does **not** include every ViewModel step, remaining legacy-memory full-archive reads, user-interface rendering, provider latency, model-answer correctness, or cost charged by OpenRouter.
- Synthetic messages are short and there are eight saved memories. Additional tests should cover realistic 1k–3k-character turns, 100+ approved and excluded candidate memories, long summaries, images, and low-context-window trimming.
- The rough token estimate comes from `ContextBudgetProbe` (UTF-8 bytes / 4); it is not the conservative preflight estimator, a tokenizer, or a statement of provider billing. No real USD savings have been measured.
- No heap-allocation profile or Android-device CPU measurement has been collected. Do not infer memory/energy savings from the wall-clock numbers.

## Next engineering gate

1. Verify current focused and wider CI gates, then obtain repeat/stress results rather than relying on one run.
2. Inspect SQLite `EXPLAIN QUERY PLAN` for the variant-group lookup. If an index is added, use an additive schema migration with Room migration and backup/restore regression coverage.
3. Benchmark 100+ candidate memories and long turn bodies and profile the remaining full-history reads in the actual ViewModel generation path.
4. Retain character-knowledge/Wack/variant privacy tests while adding temporal supersession; do not replace correctness with performance shortcuts.
