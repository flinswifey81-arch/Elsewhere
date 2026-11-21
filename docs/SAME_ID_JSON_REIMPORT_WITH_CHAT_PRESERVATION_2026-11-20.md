# Elsewhere — confirmed same-ID JSON re-import without losing roleplays

Status: implemented in **draft PR #49**, subject to exact-head CI; **not released**.

## User-visible contract

When a JSON file or pasted JSON has the ID of an existing **Character** or **Persona**, Elsewhere does **not** silently overwrite it or reject it as the only option. The user is shown the existing ID and card name and must explicitly choose one of:

- **Replace Character/Persona JSON** — update this exact card *in place*; retain its stable ID, creation date, archival state, existing avatar and every linked RP chat, participant relation, message, Story Memory V2 record, legacy memory, World membership and history.
- **Keep existing** — skip this import, leave card and its RP untouched.
- **Import separate copy** — assign a new card ID, leaving the original bot and its chats attached to the original ID.

File batches stage and review **each duplicate ID individually** (including duplicates caused by earlier files in the same batch). Unique IDs still import normally. A user-skipped import is displayed as **Kept unchanged**, not as a validation failure. Malformed files cannot abort other valid imports. Pasted JSON follows the same confirmation flow.

The old card **definition text** is replaced by the new JSON, and the user is told to export a copy of the original card first if they might want to roll back its old wording. Existing RP messages and speaker name snapshots are intentionally **not** rewritten, and new model turns re-read the current Character/Persona card from Room.

## Critical implementation details

The old importer had the bool `replaceExisting` but wrote through `@Insert(onConflict = REPLACE)`. SQLite `INSERT OR REPLACE` deletes and recreates the row on a primary-key collision, which can cascade into chat participant and other FK-backed data loss. **Neither approved replacement path may use insert.**

Instead, both repositories use existing-row `@Update` under the app's shared backup/restore mutation lease. They preserve `createdAt` and `isArchived` from the original; `updatedAt` advances. New/unique IDs still take the ordinary insert path. Replacement refuses an absent original, missing Character ID, or conflicting replace + import-as-new flags.

Lorebook JSON also follows explicit same-ID confirmation. Its replacement no longer deletes/reinserts the Lorebook and all entries: matching entry IDs are updated in place, so attached chats and retained entry overlays remain intact. Entries **deliberately omitted** in the imported JSON and their corresponding overrides **are** deleted. The confirmation spells out that distinction. The multi-file Lorebook importer now stages duplicates for per-file preview, **Replace / New Copy / Keep Existing**, and reports reviewed outcomes. Non-Lorebook entry ID collisions fail closed or can use a new copy; they never steal another book's entry.

Authored **World Definition JSON** still imports as a separate new World, by design: replacing an entire active World graph would require a separate explicit migration contract. The encrypted complete World archive remains the mechanism for transporting chats and image files.

## Regression evidence required

- `SameIdJsonReplaceTest`: real Room database with linked live RP, historical messages, legacy and Story Memory V2 records, World resource association, device-local avatar. Confirm Character replacement twice keeps every link, while a Persona replacement retains its chat participant ID and portrait. Refuse missing/nonexistent/ambiguous overwrite; a separate copy gets a new ID.
- `ProfileJsonImportWorkflowTest`: Compose UI proves duplicate JSON is **not** overwritten until the user explicitly taps Replace; Keep Existing never calls the replace method.
- `BatchJsonImportTest`: mixed good, invalid, and duplicate files stage matching IDs; a single batch can contain multiple files with the same ID; skipped files are separate from failed files.
- `LorebookSameIdReplaceTest`: updates an existing Lorebook and retained entry without losing chat attachment/overlay, prunes only an entry intentionally omitted, refuses replacing nonexistent book.
- All tests are mandatory in `worlds-unified-acceptance.yml` and the manual-only signing workflow before any production candidate.

No physical device has been accessed; no real user chat has been deleted, edited, exported or installed over. Do **not** merge the draft PR or ship an APK solely because these code paths compile. 
