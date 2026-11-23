# Memory V2 — reader-only historical transcript search checkpoint

Branch: draft PR #49, not production.
Status: IMPLEMENTED on the development branch; automated and device verification remain separate gates.

## Behavior
- Chat menu > Search chat history searches **persisted selected-primary messages in this chat**, even outside the current 100-group UI window.
- Search is local, uses a Room-managed FTS4 Unicode text index for whole-word and contiguous-phrase candidates, then checks the literal phrase. Punctuation-only searches use a bounded-result SQLite fallback. Returns up to the 30 newest matches; the repository enforces 2–120 search characters and 1–50 returned rows.
- The reader shows speaker labels and an excerpt; tapping a match shows its saved full text. This is an inspection tool, not an instruction to the model.
- Past Wack transcript remains readable, as already required for normal history/PDF; **no** search result is used by generation, reintroduced as Story Memory, or granted to a Character. A manual Save as Memory action still requires the separate explicit review/eligibility path.
- No network or paid calls, no schema migration, no bulk hydration of 10,000 messages into the UI.

## Quality evidence and limitations
- `ChatHistorySearchTest` exercises real in-memory Room queries for earlier-than-window matches, chat isolation, excluded regenerations, variant switching, literal percent/underscore search, newest-first ordering, edit/insert/delete index sync, SQL plan selection, bounded results and safely quoted operators. `AppDatabaseMigrationTest` now checks v25→v26 transcript preservation and initial FTS backfill.
- Database v26 introduces an external-content FTS4 index on messages, with a one-time migration rebuild. The original messages, branches, Wack boundaries and selected variants remain unchanged. Room's generated triggers keep the index synchronized as content is edited, inserted, or deleted. The search query filters chat ID and primary selection when reading results. Whole-word / contiguous-phrase matching does not guarantee arbitrary mid-word substring matches. Punctuation-only fallback can still scan chat text.
- SQLite's default `lower()` is not full Unicode case folding; non-ASCII case variations may be missed.
- FTS migration and live index correctness require exact-head CI verification; a standalone SQLite smoke check of the external-content index/query-plan has run. Android UI interaction and actual model-response continuity remain unverified.
- PR #49 remains draft; do not merge, sign a production release, install over the owner's app, or treat this as release approval.
