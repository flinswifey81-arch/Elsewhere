# Elsewhere in-chat memory tools

The chat screen's top-right **In-chat tools** overflow menu contains two local tools:

- **Update Memory Now**
- **Wack**

Both tools are disabled while chat history is still loading, a generation is running, or a stopped partial reply still needs **Keep Partial** or **Discard Partial**. They also reject concurrent message saves and repeated taps. Neither tool starts a model-provider request or spends provider credits.

## Update Memory Now

**Update Memory Now** runs Elsewhere's existing deterministic `AutomaticMemoryManager` immediately. It recognizes the same explicit Persona facts, preferences, boundaries, promises, relationships, locations, and `remember ...` statements used by automatic memory. It is not an AI scene-summary feature and does not perform general semantic extraction.

- In a Solo chat, it updates the chat's sole Character.
- In a Group chat, it updates only the currently selected active Character. It never falls back to a different Character.
- After Wack, it scans only eligible primary Persona messages written after the reset boundary.
- Existing content-based deduplication and source-message provenance are preserved.
- The result reports how many new durable memories were saved and separately says whether the rolling summary was refreshed. A successful run can honestly report zero new durable memories.

## Wack

**Wack** requires an explicit confirmation. The confirmation recommends running **Update Memory Now** first if the pre-reset conversation contains facts that should become durable memory.

After confirmation, Wack atomically records a persistent chat-wide ordering boundary and deletes that chat's rolling conversation summary. It does not delete or hide the transcript.

The following stay saved:

- visible messages, edits, variants, and generation metadata;
- message images and attachments;
- durable roleplay memories;
- Character and Persona records;
- Lorebooks, attachments, canon/current content, and manual/always-active entries;
- NPC Bank records, relationships, and projected NPC state;
- model, response-length, Narrator, draft, and other chat settings.

Future Solo and Group model context uses only messages after the boundary. The same post-boundary window is used by Lorebook keyword activation, NPC relevance scans, automatic rolling-summary updates, manual memory updates, and Context Inspector. Keyword Lorebook entries can activate again from new text; always-active and manually activated entries remain available. Durable memory, saved NPC state, and Lorebook state remain available because Wack is a short-term conversation-context reset, not a world-data reset.

Retry, Regenerate, and Continue are blocked for pre-reset messages with an explanation. Reading, copying, editing, deleting, and switching visible historical variants remain ordinary local transcript operations; none of those actions moves the stored boundary or makes old content eligible again. New messages receive ordering values after the boundary, including when older messages were deleted.

Wack cannot retract information from model-provider requests that were already sent before the reset. It affects only future locally compiled requests. These tools do not enable or configure co-op networking.

## Signed-phone update checklist

After installing a user-signed update over an existing version without clearing app data:

1. Open an existing Solo chat and confirm its transcript, variants, images, durable memories, Lorebooks, NPC Bank, Narrator settings, and model settings are still present.
2. Run **Update Memory Now** and verify the working indicator changes to a success or explicit no-new-memory result without network activity.
3. In a Group chat, select Character B, run **Update Memory Now**, and confirm Character A's durable memory did not receive the new fact.
4. Start a generation and confirm both tools are unavailable; stop after partial text and confirm they remain unavailable until **Keep Partial** or **Discard Partial** is chosen.
5. In a chat with an old keyword-only Lorebook trigger, run **Wack**, confirm the warning text, and verify the transcript remains visible.
6. Open Context Inspector and verify it shows an active Wack boundary and the number of pre-reset messages excluded.
7. Generate from a new post-reset Persona message and verify old transcript text and old keyword-only Lorebook entries are absent, while durable memory, always/manual Lorebook entries, saved NPC state, and current settings remain available.
8. Confirm Retry, Regenerate, and Continue show a clear block for pre-reset messages, then verify the same actions work normally on post-reset messages.
9. Force-stop and reopen Elsewhere, then repeat Context Inspector and one post-reset generation to confirm the boundary persisted.

Do not use a real paid provider call until the compiled Context Inspector output has been reviewed and the user intentionally chooses to generate.
