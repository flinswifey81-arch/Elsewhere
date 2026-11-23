# Memory V2 — historical transcript search and indexed word lookup

Branch: draft PR #49. No release approval.

## Player-visible behavior

- Chat menu > Search chat history searches saved selected messages from the current chat, including older messages outside the visible scrollback.
- **Exact text** is the default and preserves the original case-insensitive literal substring search, including punctuation and partial words. It can scan the selected transcript.
- **Indexed words** uses an FTS4 unicode61 word/phrase index with a literal phrase post-filter. Search tokens are safely quoted so input never becomes FTS operators.
- Both modes return at most 30 newest matches. The live chat and selected-primary variant predicates apply on every indexed hit; discarded regenerations and other chats stay excluded.
- Pre-Wack messages remain visible *only to the player*. Searching them does not reintroduce memories, grant Character knowledge, or send historical messages to OpenRouter.

## Storage and safety

- An additive Room **v25 → v26** migration adds `messages_search_fts` and backfills previous transcripts without modifying original messages, branches, variants or Wack boundaries.
- Room-managed FTS synchronization follows insert, edit and deletion; selected-variant filtering is based on canonical message rows at query time.
- The indexed path improves whole-word lookup but is not equivalent to exact substring search. Both are retained intentionally.
- Current tests cover Room index use, historical backfill, edits/inserts/deletes, chat/variant isolation, exact-text preservation, operator safety and bounded results. Exact-head CI and emulator runs remain required evidence; their status must be checked on the latest commit, not inherited from an earlier green run.
- External-content FTS uses the SQLite message rowid, which is not declared as an immutable INTEGER PRIMARY KEY. Investigate VACUUM/rebuild stability before release.
- This is not automatic historical memory injection, not measured real-model continuity and not Stage 3 opt-in AI extraction. No paid provider calls were added.

## Release boundary

Keep PR #49 draft. No merge, production signing, or installation over the owner's app without explicit approval and complete migration/backup/device acceptance.
