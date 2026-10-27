# Ensemble narration in Character chats

When Narrator is enabled for a local Solo Character chat, one provider response may contain an ordered mix of narration, the primary Character, and supporting NPCs. Group chats retain their per-Character generation and speaker-isolation flow. Scenario Mode retains its dedicated narrator compiler and runtime. An active local co-op room also keeps the established unstructured Character response path.

## Provider and parsing contract

`StoryOutputParser` defines a line-oriented response contract with a version marker and a small JSON header for each segment. Segment prose stays outside JSON so it can stream incrementally. The parser resolves the primary Character from the chat rather than trusting model-supplied identity, and resolves NPC IDs and names against the NPC Bank when it is enabled.

The parser removes complete, malformed, and truncated control syntax from displayed text. If a model ignores or damages the contract, all recoverable prose is saved as an ordinary primary Character segment. Formatting failure never triggers another provider request.

NPC Bank update metadata remains a separate terminal control block. It is parsed before story segmentation, and its prompt requires that metadata to follow the story end marker.

## Persistence

One generated exchange is stored as one existing Character `MessageEntity`. Its `content` contains a versioned JSON envelope prefixed by an internal record marker. This keeps segment order and identity inside the existing message, variant, regeneration, continuation, edit/delete, co-op, and backup lifecycle. It requires no database migration.

The envelope codec uses Moshi's platform JSON values rather than generated adapters, and initializes only when an encoded story is actually read or written. The line-oriented model parser reads segment headers directly with `JsonReader` and is initialized only for generation. Opening a chat therefore does not initialize the structured-output parser, while envelopes saved by the original version remain compatible.

Continue appends parsed segments to the existing logical response. Regenerate creates another complete message variant. Copy and context compilation use readable transcripts; lore activation, memory summaries, and NPC relevance never scan the internal JSON envelope.

Editing an ensemble response presents a readable transcript. Saving that edit intentionally converts it to a normal Character response because a plain text editor cannot preserve speaker boundaries reliably.

## Presentation

Narration is italic standalone prose without an avatar or bubble. Primary Character segments retain the Character name and artwork. NPC segments show their resolved name and a generated fallback avatar because NPC Bank dossiers do not currently store profile artwork. Character and NPC segment bodies continue to use the existing Markdown renderer, including italic actions.

## Optional co-lead focus (Solo only)

Character-chat **Chat Settings → Narrator → Co-Leads (Solo Ensemble)** is off by default. With Narrator enabled and the NPC Bank enabled, marking an NPC **Keep in scene** designates that NPC as a co-lead when this setting is on. The compiler gives those pinned NPCs and the selected Character comparable emotional and narrative weight without imposing equal word counts or requiring each participant to speak every turn. Unpinned NPCs remain supporting cast.

This is a single normal provider response with the existing segment presentation, not independent model calls. For longer exchanges with two or more active co-leads, consider the Long response-length profile (which applies to the complete exchange).

With no pinned NPCs (or when the bank is disabled), ordinary primary-Character focus remains in force. Turning Narrator off retains the co-lead preference but does not activate co-leads. Group speaker isolation and Scenario Mode are unchanged. Segmentation and actual character participation remain model-dependent.

The per-chat co-lead switch is persisted with chat settings. Database version 11 uses `MIGRATION_10_11` to add it as disabled for existing chats.

## Limits

- Segment quality still depends on model instruction-following. The fallback protects the text and interface when formatting is malformed, but it cannot infer missing speaker boundaries reliably.
- Dynamically introduced NPCs receive a stable generated identity derived from their supplied name. They are not automatically added to the NPC Bank unless the existing NPC update contract also supplies a valid update.
- Previously saved ensemble responses keep their segmented presentation if Narrator is later disabled; disabling Narrator changes future generations.
