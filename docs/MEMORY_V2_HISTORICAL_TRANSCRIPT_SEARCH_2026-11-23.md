# Memory V2 — reader-only historical transcript search checkpoint

Branch: draft PR #49, not production.
Status: IMPLEMENTED on the development branch; automated and device verification remain separate gates.

## Behavior
- Chat menu > Search chat history searches **persisted selected-primary messages in this chat**, even outside the current 100-group UI window.
- Search is local, case-insensitive for SQLite's built-in lower() behavior, uses literal phrase/substring matching (no wildcard expansion), and returns up to the 30 newest matches. The repository enforces 2–120 search characters and 1–50 returned rows.
- The reader shows speaker labels and an excerpt; tapping a match shows its saved full text. This is an inspection tool, not an instruction to the model.
- Past Wack transcript remains readable, as already required for normal history/PDF; **no** search result is used by generation, reintroduced as Story Memory, or granted to a Character. A manual Save as Memory action still requires the separate explicit review/eligibility path.
- No network or paid calls, no schema migration, no bulk hydration of 10,000 messages into the UI.

## Quality evidence and limitations
- `ChatHistorySearchTest` exercises real in-memory Room queries for earlier-than-window matches, chat isolation, excluded regenerations, variant switching, literal percent/underscore search, newest-first ordering and bounded results.
- This is a first reader-facing feature, **not** Stage 2's automatic historical-message context retrieval, semantic matching, or an FTS index. SQLite can scan the selected transcript for the substring; query-plan/performance tests for very long message bodies remain required before claiming indexed scale.
- SQLite's default `lower()` is not full Unicode case folding; non-ASCII case variations may be missed.
- Tests and the CI selector have been added, but cannot be called passing until the exact-head workflow completes. Android UI interaction and actual model-response continuity remain unverified.
- PR #49 remains draft; do not merge, sign a production release, install over the owner's app, or treat this as release approval.
