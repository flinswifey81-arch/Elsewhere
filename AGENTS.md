# AGENTS.md

# Elsewhere

Elsewhere is a private, local-first native Android roleplay application for text-based AI roleplay.

It is a completely separate project from Riven's Penthouse. Do not reuse Riven-specific architecture, assets, branding, or assumptions.

The project began in Google AI Studio and is now intended to be maintained in Android Studio with Codex assistance.

The primary product goal is simple:

> Make high-quality solo and group roleplay easy to run without depending on third-party RP platforms, while keeping characters, personas, chats, settings, and history under the user's control.

## 1. Non-negotiable development rules

Always follow these rules unless the user explicitly changes them.

- Native Android only.
- Kotlin only.
- Jetpack Compose only.
- `minSdk 33`.
- Local-first architecture.
- Do not convert the app to React, Flutter, a web app, or a cross-platform framework.
- Do not add Firebase, cloud accounts, authentication, analytics, ads, subscriptions, or social features unless explicitly requested.
- Do not add image generation, voice generation, or unrelated AI features unless explicitly requested.
- Do not hardcode API keys, credentials, tokens, or secrets.
- Never put API keys in source control, exported JSON, Room entities, logs, backups, crash messages, or debug output.
- Preserve working functionality when adding new features.
- Prefer incremental changes over broad rewrites.
- Keep the project buildable after each substantial task.
- Do not use destructive Room migrations as a shortcut.
- Do not silently delete, rename, merge, reinterpret, or overwrite user data.
- Do not commit unless explicitly asked.
- Do not push unless explicitly asked.
- Explain material architectural changes.
- If a requested change would require a schema migration, say so before implementing it.
- If a visual requirement needs an external asset, say so clearly rather than pretending code-only styling fully achieved it.
- If runtime behavior was not actually exercised, do not claim it was verified.

### Verification language

Every completion report should distinguish among:

- **IMPLEMENTED**
- **AUTOMATED TESTED**
- **MANUALLY VERIFIED**
- **UNVERIFIED**

Never say "fully functional", "stable", "perfect", or "manually verified" unless that claim is supported by actual execution.

Compilation is not runtime verification.

## 2. Product identity

App name:

**Elsewhere**

Core feeling:

- private story archive
- literary doorway into other worlds
- intimate
- slightly dreamy
- warm enough for long reading sessions
- generalized across fandoms and original characters
- elegant without being ornate

Current visual direction:

### Light theme
Archive / parchment.

- warm parchment or ivory background
- pale stone or soft lavender-tinted surfaces
- deep plum / charcoal ink text
- muted violet accent
- restrained warm gold secondary accent
- avoid clinical white

### Dark theme
Midnight archive.

- deep indigo / midnight plum background
- charcoal-plum surfaces
- warm ivory text
- muted lavender-gray secondary text
- dusty lavender accent
- restrained warm gold secondary accent

Important:

Minimal must not become sterile.
Do not make the UI look like a default Material demo.

The user is willing to create or provide custom visual assets when needed. If a paper texture, decorative header, or other visual cannot be achieved well in Compose/vector resources alone, request an asset and specify:

- file type
- size or aspect ratio
- transparency requirements
- intended placement

Do not bluff visual fidelity.

The current launcher icon is not considered final artwork.

## 3. Current technical stack

The existing project was built as a native Android app using:

- Kotlin
- Jetpack Compose
- Material 3
- Room
- Moshi
- OkHttp
- OkHttp SSE
- Kotlin coroutines
- StateFlow / ViewModel patterns
- Jetpack DataStore for appearance preferences
- AndroidKeyStore for OpenRouter API-key encryption
- AES/GCM/NoPadding for local API-key encryption

Confirm the actual dependency versions in Gradle before making dependency assumptions.

## 4. Architecture boundaries

Keep the following concerns separated:

1. Compose UI
2. Domain/data models
3. Room persistence
4. Repositories
5. JSON import/export
6. Secure settings
7. Model-provider networking
8. Prompt/context compilation

Do not put OpenRouter calls directly in Compose screens.

Do not build prompt strings directly inside UI code.

Do not collapse characters, personas, chats, messages, worlds, relationships, and model settings into one blob.

## 5. Stable identity rules

All persistent entities use stable internal IDs.

Display labels are not identity.

Renaming a character, persona, chat, or group must not break relationships or history.

UUID-style IDs are preferred where the current code already uses them.

Multiple character records may share the same in-world name.

Example:

- display name: `Sylus - Husband Edition`
- character name: `Sylus Qin`

Another record may also use:

- display name: `Sylus - Canon`
- character name: `Sylus Qin`

This is intentional.

## 6. Character model

Character creation is primarily JSON-based.

Do not replace this with a large manual form.

Required identity fields:

- `schema_version`
- `character_id`
- `display_name`
- `character_name`
- `aliases`

Meaning:

### display_name
Private organizational label shown in the app.

Examples:

- `Sylus - Husband Edition`
- `Sylus - Canon`
- `Sylus - College AU`

### character_name
Actual in-world name.

Example:

- `Sylus Qin`

### aliases
Alternate names, nicknames, titles, surnames, or other names.

Optional structured sections currently intended to exist:

### identity
Possible fields:

- age
- pronouns
- gender
- species
- occupation
- role
- origin
- current_location
- affiliations

### appearance
Flexible, not over-normalized.

Possible fields:

- summary
- height
- build
- hair
- eyes
- distinguishing_features
- clothing_style

### personality

- summary
- core_traits
- values
- motivations
- fears
- flaws
- contradictions
- habits
- emotional_patterns

### voice

- speech_style
- cadence
- vocabulary
- accent_notes
- humor
- verbal_habits
- pet_names
- example_phrases

Voice means how the character talks. Do not merge it into personality.

### behavior

Possible fields:

- general
- under_stress
- angry
- affectionate
- jealous
- protective
- vulnerable
- in_conflict
- in_danger
- with_strangers
- with_trusted_people

Additional future behavior keys should remain possible.

### relationships

Reusable relationship templates may include:

- target_id
- target_name
- relationship_type
- status
- history
- dynamic
- character_feelings
- character_beliefs
- boundaries
- behavioral_notes

Imported relationship templates are not the same thing as runtime Room relationship state.

### backstory

- summary
- history
- important_events
- family
- formative_experiences

### knowledge

Structured knowledge may cover:

- canon facts
- AU facts
- organizations
- family
- locations
- powers
- terminology
- history
- secrets

Do not permanently flatten all knowledge into one prompt blob.

### world_context

- world_id
- setting_name
- canon_source
- timeline
- universe_notes

Original characters must work without fandom metadata.

### writing_rules

- pov
- tense
- response_style
- dialogue_balance
- action_style
- pacing
- formatting_preferences
- prohibited_behaviors
- required_behaviors

These are model-portrayal rules, not personality fields.

### examples

- dialogue_examples
- behavior_examples
- scene_examples

### author_notes

Local private notes.

Do not automatically send `author_notes` to model context.

## 7. Persona model

Personas are reusable and independent from characters.

Required fields:

- `schema_version`
- `persona_id`
- `display_name`
- `persona_name`
- `aliases`

Optional sections:

### identity

- age
- pronouns
- gender
- species
- occupation
- role
- affiliations

### appearance

Use the same flexible philosophy as Character appearance.

### personality

Optional structured persona information.

### roleplay_profile

- self_description
- behavioral_notes
- communication_style
- preferences
- boundaries

### background

- summary
- history
- relationships
- relevant_facts

### world_context

Optional setting/world origin.

### private_notes

Local private notes.

Do not automatically send `private_notes` to model context.

A Persona must remain reusable across many Characters and Chats.

## 8. JSON import/export philosophy

JSON is the primary bot/persona creation workflow.

The app should support:

- Import JSON file
- Paste JSON
- Validate
- Preview
- Confirm
- Persist
- Export
- Duplicate

Do not force the user to fill dozens of fields manually.

JSON rules:

- known fields should remain typed
- arrays remain arrays
- nested structures remain structured
- unknown future root fields should not crash import
- preserve unknown extension fields across import/export where the current implementation supports it
- do not silently discard unknown data
- malformed JSON must fail safely
- missing required fields must produce readable errors
- imported/exported JSON schema version is independent from Room database version

Duplicate internal IDs must never silently overwrite existing records.

Conceptual choices:

- Cancel
- Import as New Copy
- Replace Existing

`Import as New Copy` generates a fresh stable internal ID.

## 9. Chat model

Two chat types exist:

- `SOLO`
- `GROUP`

A Chat must not structurally assume only one human Persona.

Do not put a single `personaId` directly on Chat as the only persona relationship.

Use join/participant structures.

### Character participation

A character participant record should conceptually support:

- chatId
- characterId
- joinedAt
- isActive
- sort/order

### Persona participation

A persona participant record should conceptually support:

- chatId
- personaId
- joinedAt
- isActive
- sort/order

This is intentional future-proofing for cooperative RP.

A normal Solo chat:

- one active Persona
- one active Character

A future cooperative room may contain:

- multiple Personas
- multiple Characters

Do not implement co-op networking until explicitly requested.

## 10. Message model

Every message permanently preserves speaker identity.

Do not infer speaker from alternating order.

Conceptual fields include:

- messageId
- chatId
- speakerType
- speakerId
- speakerDisplayNameSnapshot
- content
- createdAt
- editedAt
- order/sort information

Speaker types should support at least:

- `PERSONA`
- `CHARACTER`
- `SYSTEM`
- `DIRECTOR`

Do not use a globally singular `USER` assumption.

Historical speaker names should remain understandable even if a character/persona is later renamed.

## 11. Group chat design requirements

Group chat is a major product requirement, but AI group generation is not considered complete yet.

The design goal is specifically to avoid "group chat soup."

Each character remains an independent character.

Never ask one model call to portray all group participants simultaneously as a default group-chat strategy.

When Character A responds:

- load Character A's own card/context
- provide shared scene/history
- provide what A knows about other participants
- instruct the model to respond only as A

When Character B responds:

- compile B independently
- provide the same shared scene/history
- provide B-specific knowledge/relationships
- instruct the model to respond only as B

Messages must remain separately attributed.

Desired future controls:

- manually choose next Character
- Auto mode later
- Everyone / sequence mode later
- temporarily mute a Character
- temporarily remove/re-add a Character
- regenerate one Character's response only
- delete one response without deleting the whole turn
- manually insert/repair a message
- "Respond to..." a specific earlier message

Crossovers matter.

Character knowledge must be asymmetric where appropriate.

Character A knowing a fact does not mean Character B automatically knows it.

## 12. Relationship state

Runtime relationships may be asymmetric.

Future structure should allow:

- what A knows about B
- what B knows about A
- A's beliefs about B
- B's beliefs about A
- dynamic
- relationship status
- reusable or chat-specific scope

Do not flatten relationship knowledge into one omniscient group blob.

## 13. OpenRouter

OpenRouter is the current model backend.

The app should remain provider-abstracted so other providers can be added later.

Expected OpenRouter behavior currently implemented or intended:

- real model catalog
- model selection per chat
- provider routing controls
- streaming responses
- usage/cost metadata
- cancellation
- safe errors
- local-first chat storage

API key rules:

- stored only locally
- encrypted using AndroidKeyStore-backed key material
- AES/GCM/NoPadding
- never stored plaintext
- never exported
- never logged
- never put in Room
- encrypted payload excluded from Auto Backup/device transfer

Do not use deprecated `EncryptedSharedPreferences` or deprecated `MasterKey` APIs.

## 14. Per-chat model settings

Model configuration belongs to the Chat, not permanently to the Character.

The same Character must work with different models in different chats.

Per-chat settings should include:

- selected model ID
- provider routing mode
- selected provider endpoint if applicable
- temperature where supported
- top_p where supported
- response length profile

Provider routing modes:

### AUTO
Normal OpenRouter routing.

### PREFER
Prefer a selected provider/endpoint but allow fallback.

### LOCK
Restrict generation to selected provider/endpoint and disable fallback.

If a locked endpoint becomes unavailable, do not silently switch providers.

## 15. Response length controls

Elsewhere should control response length more reliably than vague prompt wording alone.

Profiles:

### SHORT
- target: 400 to 800 characters
- hard max: 1000 characters

### NORMAL
- target: 900 to 1600 characters
- hard max: 1900 characters

### LONG
- target: 1600 to 2600 characters
- hard max: 3000 characters

### CUSTOM

User configures:

- target minimum
- target maximum
- hard maximum

Validation:

`minimum <= target maximum <= hard maximum`

Do not automatically regenerate or spend more money because a response is shorter than target.

A short response can be artistically correct.

## 16. ContextCompiler rules

The ContextCompiler is a core part of the product.

Static Character/Persona profile data is instructional context.

Do not represent static profile data as fake conversation history.

Correct role mapping:

- static Character profile/context: system/instruction context
- static Persona profile/context: system/instruction context
- historical Persona message: user
- historical Character message: assistant

Do not pretend a Persona said their biography aloud.

Do not pretend a Character biography is a prior assistant message.

Context should be assembled fresh per request from structured source data.

Possible Character ordering:

1. Core roleplay instruction
2. Character identity
3. Character appearance
4. Character personality
5. Character voice
6. Character behavior
7. Character backstory
8. Character knowledge
9. world_context
10. writing_rules
11. examples
12. relationship context relevant to active Persona
13. active Persona identity
14. Persona appearance if relevant
15. Persona personality/roleplay profile
16. relevant Persona background/world context
17. response-length instruction
18. recent conversation history
19. current Persona message

Skip empty sections.

Do not send:

- Character `author_notes`
- Persona `private_notes`
- unknown extension fields unless explicitly mapped later

## 17. Default roleplay behavior contract

For a normal Solo generation, the app-level contract should generally enforce:

- portray the selected Character only
- treat the active Persona as the user's character
- do not write dialogue for the Persona
- do not dictate the Persona's actions
- do not dictate the Persona's thoughts
- do not dictate the Persona's feelings
- do not decide the Persona's choices
- react to the Persona's spoken dialogue and visible actions
- maintain Character identity and voice
- respect established relationship state
- respect established conversation events
- do not mention prompts, JSON, context compilation, or app mechanics in-character

Character-specific writing rules may refine this behavior.

Do not globally sanitize conflict, flaws, tension, or difficult characterization into automatic emotional resolution.

## 18. Solo chat generation behavior

Desired lifecycle:

1. Persist Persona message locally.
2. Compile context.
3. Begin OpenRouter streaming request.
4. Stream Character response into UI.
5. On success, persist Character response.
6. Persist generation metadata.
7. Update chat timestamp.
8. Preserve user draft correctly.

On generation failure:

- keep the Persona message
- do not create fake Character output
- show recoverable error
- Retry must not duplicate the Persona turn

## 19. Streaming and stop behavior

Streaming should:

- assemble chunks exactly once
- avoid duplicated content
- handle `[DONE]`
- stay responsive
- support cancellation

Stop Generation should:

- cancel the underlying network request
- retain received partial text temporarily
- offer Keep Partial / Discard Partial

If kept:

- save partial Character message
- mark metadata appropriately

If discarded:

- do not persist Character response

Do not delete the Persona turn.

## 20. Regenerate, variants, Continue, edit, delete

### Regenerate

Must:

- use context from before target Character response
- exclude target old response
- not duplicate Persona message
- preserve old response as alternate variant
- create new variant
- allow switching variants
- use currently selected primary variant in future context

### Continue

Must:

- continue the same Character response
- not create a fake Persona turn
- instruct the model not to repeat existing text
- preserve separate generation metadata for continuation

### Edit

Must:

- preserve speaker identity
- set `editedAt`
- use edited content in future context
- not silently regenerate later messages

### Delete

Must:

- remove only the selected item/variant
- not rewrite neighboring turns
- preserve remaining stable IDs
- promote another variant if the deleted message was primary and alternatives remain

## 21. Generation metadata

Generated Character responses should be able to retain:

- requested model
- resolved model
- provider/endpoint if actually known
- prompt tokens
- completion tokens
- total tokens
- reasoning tokens if returned
- cached tokens if returned
- reported cost
- character count
- finish reason
- response-length profile
- duration
- generation timestamp
- partial/user-stopped status if applicable

Do not invent provider information that was not returned.

A user should eventually be able to answer:

"Why did that generation cost so much?"

without guessing.

## 22. Context Inspector

Context Inspector is an important debugging feature.

It should show:

- Character sections included
- Persona sections included
- relationship context included
- response-length instruction
- historical messages included
- historical messages omitted due to limits
- model
- provider routing
- approximate or actual token information
- final request message roles

Optional raw request view may exist, but:

- redact API key
- do not display Authorization header
- omit sensitive headers

## 23. Drafts

Unsent drafts should persist per chat.

Leaving and returning to a chat should not erase the user's draft.

Sending should clear the draft only after the Persona message is successfully persisted.

Network failure must not erase the draft or duplicate the Persona turn.

## 24. Appearance settings

Current appearance preferences use DataStore.

Desired existing controls:

- System / Light / Dark
- font size
- accent preset

Current accent presets were intended to include:

- Dusty Lavender
- Muted Rose
- Sage
- Soft Blue
- Warm Gold
- Plum

Future theme hooks should support:

- app background
- surface/card color
- Persona bubble color
- Character bubble color
- primary text
- secondary text
- chat font
- chat font size
- corner style

Do not hardcode theme values independently in every screen.

Use centralized theme state.

## 25. Known history and current caution areas

Google AI Studio produced the first implementation, but its completion summaries were not always reliable.

Do not assume a feature works because comments, reports, or placeholder code say it does.

Inspect actual implementation.

Known history:

### Fixed or substantially addressed

- project originally lived in a non-persistent AI Studio path and was lost
- project was rebuilt under `/app/applet`
- current source was backed up to ZIP and GitHub
- Character and Persona import were originally placeholder buttons
- Android Storage Access Framework integration was later added
- Paste JSON was added
- first-run empty-state dead ends were improved
- global Settings previously crashed because DataStore preference keys encountered incompatible legacy primitive types
- regression tests were added around global Settings/navigation

### Current or recently observed problem

**The gear icon in a Solo Chat top bar crashes when opening per-chat Chat Settings / Model Settings.**

Treat this as a high-priority first investigation unless already fixed in the repository version Codex receives.

Do not assume Stage 3.1 Chat Settings UI is complete merely because the data layer exists.

Inspect:

- ChatDetailScreen gear click handler
- NavHost route
- ChatSettingsScreen
- ChatSettingsViewModel
- ChatSettingsEntity initialization
- missing model/default state
- route argument parsing
- nullable state
- provider metadata state
- offline state

The Chat Settings screen should open safely even if:

- no API key exists
- no model is selected
- model catalog has never loaded
- provider mode is AUTO
- no endpoint is selected
- no ChatSettingsEntity exists yet
- device is offline

### Current visual state

The app is cleaner than the original prototype but still visually plain.

A future archive/parchment polish pass is desired.

Do not claim the parchment aesthetic is complete if it is only beige Material surfaces.

## 26. Launcher icon

The current launcher icon is not accepted as final art.

The intended concept is:

- clear open doorway
- door visibly ajar
- warm light pouring through
- dark surrounding field
- minimal
- elegant
- readable at small size
- no circular emblem
- no abstract D/keyhole/music-note shape
- no text

If good launcher art cannot be created reliably from vector geometry alone, request an external asset.

Do not keep iterating abstract geometric icons and claiming they match the concept.

## 27. Future cooperative RP

Co-op is a planned future feature.

The user and Bella want to participate in the same RP room.

Future shared rooms should support:

- multiple human Personas
- multiple AI Characters
- explicit speaker identity
- synchronized chat history
- independent Character relationships with each human Persona
- optional private/asymmetric knowledge later

Do not implement co-op networking until explicitly requested.

### Co-op generation cost modes

Future shared rooms should support an explicit cost/billing mode:

- Each participant pays for their own generations
- Host covers all generations
- Selected participant covers all generations

Important security rule:

The paying participant's API key must never be shared with other participants.

If one participant covers the room, model requests should be executed through that participant's device/account or another design that preserves secret isolation.

The room should make sponsorship explicit, for example:

`AI costs: Covered by Bella`

Future usage tracking may show room-specific generation costs without exposing account-wide OpenRouter information.

## 28. Backup and export expectations

The app is local-first.

Future user-data backup should eventually support characters, personas, chats, and settings safely.

Do not include API keys in backups.

Do not leave fake active-looking "Export ZIP" controls if backup is not implemented.

If a control is not implemented:

- remove it
- or clearly disable it so it cannot be mistaken for working functionality

## 29. Testing expectations

Before claiming completion:

- run the relevant unit tests
- run Robolectric/Compose tests where applicable
- run the Gradle build
- report exact test counts
- do not say a runtime feature is manually verified unless it actually was

When modifying a bug-prone UI flow, add regression coverage where practical.

Important areas for automated coverage:

- JSON import and malformed input
- extension-field preservation
- duplicate IDs
- ContentResolver import path
- ContextCompiler role mapping
- private note exclusions
- history trimming
- response length validation
- SSE chunk assembly
- usage metadata parsing
- Retry without duplicate Persona turn
- Regenerate without duplicate Persona turn
- variants
- Continue
- provider AUTO/PREFER/LOCK request construction
- draft persistence
- generation metadata persistence
- edit/delete
- Settings rendering
- Chat Settings navigation/rendering

Tests must not spend real OpenRouter credits.

Use mocked HTTP responses.

## 30. First Codex tasks

When Codex first receives this repository, do this in order:

### A. Inspect, do not immediately rewrite

1. Read Gradle files and dependency versions.
2. Inspect package structure.
3. Inspect Room entities, DAOs, database version, and migrations.
4. Inspect repositories.
5. Inspect JSON models/import/export.
6. Inspect OpenRouter provider implementation.
7. Inspect ContextCompiler.
8. Inspect navigation graph.
9. Inspect Solo chat screen.
10. Inspect Chat Settings gear route and crash-prone path.
11. Run the existing test suite.
12. Run the build.

Report actual state before broad refactoring.

### B. Fix current runtime blocker

Investigate the Solo Chat gear crash first if it still exists.

Do not guess.
Use stack trace / Logcat if available.

Implement the smallest correct fix.

Add regression coverage.

### C. Verify first live Solo RP path

Once Chat Settings opens:

1. Import a Character.
2. Import a Persona.
3. Create Solo Chat.
4. Configure model.
5. Configure response length.
6. Inspect context.
7. User manually chooses when to perform a paid OpenRouter generation.

Do not trigger paid OpenRouter requests automatically.

### D. Only then continue visual polish

After core navigation and Solo RP are stable, proceed with archive/parchment styling.

## 31. Style of collaboration

The user prefers explicit, concrete instructions and wants to understand major changes.

Do not bury important decisions.

When you change something, summarize:

- what changed
- why
- files touched
- migrations, if any
- tests run
- what is verified vs unverified

Do not overwhelm the user with low-value implementation narration.

The user is intentionally building Elsewhere incrementally.

Protect the working base.

## 32. Guiding principle

Elsewhere should make creating and using roleplay characters feel easy.

The desired creation loop is:

**Idea → JSON → Import → RP**

Not:

**Idea → twenty-seven manual fields → configuration maze → abandoned bot**

Every feature should be judged against that goal.
