# Images and NPC Bank

## Sending images

1. Open a Solo or local Group chat.
2. Tap the image button beside the message field.
3. Choose one image. Repeat to attach up to four images.
4. Review the thumbnails, remove any you do not want, add an optional caption, and send.

Elsewhere copies selected images into private app storage and normalizes them before saving. Normalization applies orientation, samples decoding to a bounded 4-megapixel working image, and re-encodes the pixels, removing EXIF fields such as location and camera metadata. Accepted inputs are JPEG, PNG, and WebP, up to 8 MB, 8192 px on either side, and 16 megapixels. A message's normalized images may total at most 12 MB. Normalized attachments are excluded from Android cloud backup and device transfer.

When a response is generated, the images on that Persona turn are transmitted to the selected model provider as multimodal input. This can increase provider token/image charges. Elsewhere sends images only after the loaded model catalog explicitly advertises image input; text-only or unresolved capabilities are blocked with a readable error. Retry and Regenerate reuse the same originating attachment without duplicating it, including each independently generated Group Character response. Continue does not resend the image. Attachments remain visible in local history.

Image attachment sync is intentionally unavailable in local co-op until a bounded authenticated binary-transfer protocol exists. Local co-op LAN transport is available only through the explicit Group-chat Host/Join flow; it never synchronizes images or NPC Bank records.

## NPC Bank

Each chat has a separate NPC Bank. Open it with the NPC Bank icon in the chat top bar.

Automatic discovery is off by default for every chat. Turn **Enabled** on only when you want the normal storyteller response to propose structured dossier changes. This does not make a second provider call: the metadata travels at the end of the ordinary response, is hidden from the displayed story, strictly validated, and applied only after the visible response succeeds.

The bank supports:

- manual add and edit;
- JSON file import and paste import;
- share-safe and full private JSON export;
- stable NPC IDs;
- established appearance and personality, with evolving role, goals, relationships, and narrative secrets;
- per-Persona relationship notes;
- per-Character knowledge notes for asymmetric group scenes;
- **Keep in scene**, which prioritizes that NPC during context selection;
- response-variant-aware updates, including Regenerate, variant switching, Continue, and reply deletion.

Automatic creation may establish an NPC's display name, aliases, appearance, and personality. After creation, those identity and core-profile fields are immutable to automatic model updates and can be changed only through an explicit manual edit. Automatic updates for an existing NPC are limited to role, goals, relationships, and narrative secrets. Regenerating an older reply compiles NPC context only from story state that existed before that target reply, so later developments are not leaked backward.

Private author notes are never included in provider context. Narrative secrets are stored separately from author notes and may guide storyteller continuity, but they are never automatically treated as Character knowledge or permission for a Character to react to them. To make a fact available to one AI Character, add it to that Character's **What [Character] knows** field. This prevents a Group character from receiving another participant's private/asymmetric knowledge.

Share-safe export omits narrative secrets, private author notes, and opaque extension fields. Full private export includes all three and should be saved only to a trusted location. Neither export includes API credentials.

The optional paid **Scan Story** action is not included in this version. NPC discovery occurs only through normal generated replies while the bank is enabled, or through manual/JSON entry. Elsewhere never starts a paid scan automatically.

## Narrator

Each chat has a separate **Add narrator guidance** toggle in Chat Settings. It is off by default. When enabled, saved instructions guide scene, world, atmosphere, consequences, and supporting-cast narration inside the same normal Character response. It does not create an independent AI, a separate speaker turn, or a second provider request.

Narrator guidance must not write dialogue, actions, thoughts, feelings, or decisions for the user's Persona. In Group chats it preserves the selected Character as the only AI Character speaker and does not authorize portraying the other AI Characters. It uses the same model request, so instruction labels are behavioral boundaries rather than hard information isolation.

Turning Narrator off restores ordinary response behavior without deleting its saved instructions, NPC dossiers, or NPC Bank settings.

## JSON format

The import schema is independent from the Room database version. Use `schema_version: 1`. Stable IDs should be UUIDs or another unique local identifier. On an ID collision, the current UI imports a new copy rather than silently overwriting an existing dossier.

See [`examples/npc_bank_example.json`](../examples/npc_bank_example.json).

`persona_relationships` and `character_knowledge` are maps keyed by stable Persona and Character IDs, not display names. `extension_fields` is reserved for explicit future additions. Unknown future NPC fields are preserved privately as extension fields; unknown root fields are safely ignored. Extension fields are never placed in provider context or share-safe exports. Malformed or oversized JSON fails without modifying existing dossiers.

## Recurring NPCs without name prompts (draft branch validation)

When the NPC Bank is enabled for a Solo chat, the prompt can now include a **bounded, non-secret potential cast** of up to four stored NPCs whose location/presence is not known, even if the player did not name them. A relevant role gets priority, followed by user-pinned recurring cast and recent updates. The narrator may choose an organic entrance when appropriate; this roster does not mean the NPC is currently present, does not allow them to speak before appearing, and does not force an encounter. Full dossiers remain exclusive to confirmed present NPCs. Explicit **Away**, Group and post-Wack unknown cast are excluded from these previews. The roster excludes private notes, secrets and per-Character knowledge. This is a model-available casting hint, not guaranteed participation in every reply. New-NPC creation and Persona-puppeteering reports are tracked separately in issues #51 and #50.

## Individual malformed NPC metadata (draft fix)

A valid terminal NPC update envelope may contain multiple independent dossiers. If one update violates per-entry semantic rules (such as attempting to change an existing NPC's identity), it is discarded with a count-only warning while other valid new NPCs remain eligible for atomic persistence alongside the story. Invalid schema, oversized envelopes, unknown fields (including private mutation attempts), non-terminal markers, or broken JSON still fail closed for the whole block. This does not attempt to invent NPCs from ambiguous prose or cause any extra provider call.
