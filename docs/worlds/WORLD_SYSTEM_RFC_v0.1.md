# Elsewhere Worlds — Foundation RFC v0.1

Status: DESIGN DRAFT, NOT IMPLEMENTED. Owner: Elsewhere. Working branch: feature/worlds-foundation-2026-11-01.
Baseline: the current Android app; production 1.5 remains untouched. This document describes proposed features, not currently supported JSON import formats.

## Goal

A World is a reusable, portable setting that organizes its lore, cast, Character and Persona roles, episode templates, ongoing chats, and deliberately shared continuity. Manual editing and validated JSON input are equal first-class creation routes. Worlds are general-purpose: no police-procedural, television-episode, romance, or Omegaverse behavior is hardcoded.

Primary acceptance story: Law & Disorder: Mondstadt has a durable home chat, standalone crime/comedy/romance episodes, a stable World Cast, and portable history. The story must survive uninstall/reinstall of Elsewhere Test even when APK signing keys differ.

## Existing systems to reuse (verified in repository)

- Character and Persona records have separate IDs and separate import/authoring semantics.
- Lorebooks have durable Canon, reusable Current, chat-scoped Current overrides, activation modes, and chat attachments. ScenarioContextCompiler already accepts active lore via the Scenario chat preparation path.
- ScenarioDefinition and ScenarioSessionEntity support editor-created templates, an initial snapshot per playthrough, one narrator generation per ordinary Send, and independent scenario history.
- Character-chat NPC Bank supports opt-in, validated structured NPC creation and change tracking by response variant; its current dossiers are chat-local. A reusable NPC Library with banks and entries also exists.
- Scenario NPCs are stored within the Scenario definition/snapshot; Scenario chats do not use the Character-chat NPC Bank. ScenarioNpc.sourceCharacterId is a reserved, not yet resolved link.
- Existing encrypted whole-app backup includes Scenarios, sessions, lore, memories, chats, messages, avatars and attachments, and forbids Test backup restore into production. This is a deliberate safety boundary, not a portable selective World import.
- Current app database version at the time of this RFC is 12; new tables require explicit migration tests.

No new provider service, background daemon, public hosting, network sharing, or moderation system is required.

## Principles and invariants

1. **World identity is not chat identity.** A World has a stable worldId. A chat or Scenario can belong to one World while the same reusable Character or Persona can be used in several Worlds through explicit membership/role records. An existing non-World chat remains valid.
2. **Canonical cast identity is separate from acting roles.** One World person has a worldCharacterId; optional links to independent Character, Persona, NPC-library, and Scenario-role records. An NPC can become an AI Character or a player Persona without replacing the original. Cross-world variants can be independent identities.
3. **Link, don't duplicate by default.** Existing Character, Persona, Lorebook and Scenario records remain the source records. World tables hold membership, labels and World-specific portrayal/relationship overlays. Exports carry self-contained copies with remappable IDs.
4. **Shared baseline vs playthrough events.** World baseline/continuity is explicit. Scenario sessions still snapshot their opening definition. Session discoveries, generated NPCs and relationships do not automatically rewrite World Canon.
5. **No invisible retcons.** Changes to World lore/cast do not silently rewrite saved transcripts or existing session snapshots. Future launches can use updated World data; live session refresh must be explicit and explain consequences.
6. **Protect Persona agency and knowledge boundaries.** NPC and World prompts must not speak for the player, make all secrets publicly known, or turn storyteller-only context into Character knowledge.
7. **Portable before irreplaceable play.** A successful, tested World definition round trip and complete World archive round trip must exist before relying on a disposable Test install for persistent fiction.
8. **Control model cost.** Existing single-response NPC metadata remains the default; no additional LLM scan/summary call happens silently.
9. **Private by default.** No external hosting, automatic publicity, API credentials, signing keys, or real account secrets in World packages.

## Proposed storage model

These names are conceptual until a reviewed Room migration is implemented.

- WorldEntity: worldId, title, description, setting summary, optional rules/style, timestamps, archive status. Does not replace Lorebook.
- WorldMembershipEntity: worldId, resourceType [LOREBOOK, CHARACTER, PERSONA, SCENARIO, CHAT, NPC_LIBRARY_BANK], resourceId, membership settings, optional role. A resource may belong to more than one World with deliberate conflict handling.
- WorldCastEntity: worldCharacterId, worldId, name, aliases, stable identity/appearance/personality/voice/history/knowledge limits, relationships, provenance, locks, optional linked Character and Persona IDs. Stable fields require explicit edits; avoid storing a Character card as opaque magic in the World.
- WorldNpcLinkEntity: worldId, worldCharacterId, originating chatId/scenarioId/npcId as appropriate, and promotion state. Preserve original chat-local dossier/variant history.
- WorldContinuityEventEntity: eventId, worldId, title, concise facts, story-time/order if known, sourceChatId/sourceMessageIds if any, status [DRAFT, ACCEPTED, NONCANON], scope, optional relevant cast IDs, timestamps. Acceptance into shared continuity always requires a user action.
- WorldChatState: the World attached to a chat, selected World continuity snapshot/version, World lore attachments, and optional session-scoped NPC state. Keep live chat operations working for chats with no World.
- Suggested WorldImportRecord / WorldExportJob state: temporary validated import preview and transactional commit plan; no plaintext archive data persisted unnecessarily.

Use distinct stable IDs with explicit foreign keys and uniqueness constraints. Prefer additive migration and mapping tables to adding many nullable worldId columns to every existing table. When deletion is requested, review detach vs delete; deleting a World must not silently delete independently stored Characters or chats.

## World definition JSON (draft contract)

Separate format from existing Elsewhere Lorebook, NPC Bank, Character and Persona JSON imports.

Proposed top-level markers:
- format = elsewhere_world_definition
- schema_version = 1
- world = stable identity, description, general setting, optional genre labels/defaults
- resources = embedded self-contained approved Lorebooks, World Cast, optional Character and Persona definitions, Scenario templates
- links = ID-based World membership and cast-to-role mappings
- continuity = accepted baseline World events and a creator-authored current state only (no entire chat transcripts)

No external file path references or remote content; embedded records are validated using the actual schemas for their record type. Scenario definitions must be validated against the existing ScenarioDefinition model, not mistakenly treated as a currently shipping standalone Scenario JSON importer. Manual editor saves to the same model as JSON import. The import preview lists exact resources, collisions, replacements and omissions. All strings/arrays are bounded; any future schema changes are versioned. Private notes, deep secrets and licensed derivative-world content require an explicit inclusion/review decision.

## Complete World archive (draft contract)

An independent, **selective** archive format, distinct from the existing full-app encrypted backup. Proposed suffix: .elsewhereworld (provisional). The archive includes a validated World definition and a dependency-closed, World-scoped data snapshot:

- Scenario templates and session snapshots
- associated selected chats, participants/Persona selection, messages and all variants, continuity reset boundaries, settings/drafts, metadata and chat-local summaries/memories
- selected World-related Lorebook entries, attachments, per-chat Current overlays/manual activation
- chat NPC dossiers, update/revision/variant history, linked NPC-library records
- assets such as Character/Persona/Scenario images and message attachments; any references use archive-internal paths
- accepted World continuity plus optional DRAFT entries only when intentionally included

No credentials, signing material, device identity, active co-op session credentials or provider secrets. Do not silently import active network-room state. Default archive is encrypted with an explicit user passphrase using audited existing backup crypto primitives where feasible; archive manifest uses independent format/version, sizes, hashes and integrity checks. The cross-variant importer must validate the archive without relying on app package ID, while retaining the existing whole-app Test→Production restriction unchanged.

Import preview and plan before write: new World copy / merge into explicitly selected World / cancel. ID remapping is total and deterministic *within the operation*: world, cast, source NPC, character, persona, scenario, chat, session, message, variantGroup, memory/sourceMessage, lorebook/entry, image and attachment identifiers, and all dependent relationship/knowledge maps. Never silently merge two people because names match. On collision, create independent copies by default, with explicit resolution for intentional links. Reject dangling references, unsupported newer archive versions, corrupt/checksum-failed files, oversized entries, zip-slip paths, and partial bundles. Stage files, validate, and commit transactionally with rollback/recovery. Import never erases unrelated production data. Preview must report exact counts, missing dependencies and privacy-sensitive inclusions.

A World definition JSON and a complete World archive solve DIFFERENT needs. Never describe the definition JSON as protecting playthrough history. Existing whole-app backup remains the independent disaster-recovery option.

## LLM-assisted NPC generation: from scene character to durable cast

Reference behavior: Megumin Suite's automated dossiers, significance gating, evolving goals/relationships and duplicate-person resolution. Adapt patterns to Elsewhere's existing provider and app model, rather than copying its proprietary code or importing its preset protocol.

**Scope:** Character chat or Scenario session, with separate per-chat enablement; optional World association. No automatic addition to shared World Cast.

**Proposed user modes:** OFF; SUGGEST (recommended default when enabled); AUTO_LOCAL (validated discoveries stored only in current chat). Existing NPC discovery switches remain supported for older Character chats. World promotion is always explicit, including in AUTO_LOCAL.

**Significance gate:** Prefer named + meaningfully voiced + narratively staked participants, or explicit user-requested recurring characters. Do not make dossiers for every passerby, generic clerk, unnamed crowd member, or the Persona. Avoid a fixed number of NPCs per World; context selection has bounded relevance budgets, not arbitrary cast limits.

**Normal successful reply pipeline:**
1. World/Scenario/Character context supplies relevant existing roster names, IDs and aliases so the model can reuse a known person.
2. The same storytelling response may contain hidden, structured NPC create/update proposals. Visible narrative renders independently, with a parser fallback for malformed metadata.
3. Validate IDs, names, types, schema, size, exact chat/world scope, prohibited Persona impersonation, and duplicate-name ambiguity. Never trust raw model instructions as app commands.
4. On successful visible generation only, record local NPC creation/proposals atomically with its message and variant identity. Failed/aborted generation cannot mutate continuity. Regeneration/variant selection/removal must reverse or replay only the correct local changes.
5. A candidate drawer shows: new NPC / possible match to existing World or local NPC / ignore. User can approve, edit, merge by verified ID, or reject.
6. Approved local NPCs can be promoted to a World Character. This freezes a reviewed canonical profile and preserves a provenance link, but does not transplant chat-specific temporary beliefs or secrets as universal truth.

**Stable and evolving fields:** Stable identity/name/aliases/appearance/voice/background/creator-locked canon cannot be rewritten automatically once accepted. Goals, agenda, job status, scene location, relationship state, beliefs and narrative secrets may receive specific diffs with source/variant attribution and undo. Prevent stale episode state from overwriting another episode's current progress. Treat knowledge asymmetrically. A world-level timeline update is explicit and reviewed.

**Existing-person matching:** Stable IDs first; then within-World names/aliases, conversation context and roles. Ambiguous collision is a UI question with Same person / New person / Not now, not an LLM guess. Never merge namesakes across unrelated Worlds. Same-person selection records identity links and supports undo.

**Cost:** The default route adds no provider calls beyond the ordinary response and uses bounded hidden metadata. Optional on-demand "Scan this episode for NPCs" and "Improve dossier with AI" are explicitly user-triggered, display provider/cost warning, and can be canceled. No unsolicited background spend.

**Reusable conversion:** World Cast actions Create Character Bot / Create Persona / Add as Scenario NPC. Each produces a correctly typed record with a new stable ID and provenance link, preserving source NPC; missing fields can be edited manually. Optional AI-assisted completion is a conscious, potentially paid action. Creating a Persona never authorizes the model to control that Persona; creating a Character does not grant omniscience. Refresh/replace is opt-in per role so running chat snapshots stay stable.

## Editor UX and routing

Worlds screen: New World (manual) / Import World JSON / Import World Archive. World page: Overview, Lore, Cast, Episodes, Chats, Continuity, Import/Export. Each section offers both manual edit and appropriate validated JSON input; JSON input cannot bypass rules or mutate another resource on name collision. "Start episode" selects existing World then Persona, snapshots episode definition, associates session, attaches selected World lore, and includes a relevant, bounded cast. "Record development" is review-before-accept, not automatic whole-story canonization.

NPC candidate review should be unobtrusive: no mandatory extra form before every message, no interruption of streaming, and no World-cast modification without an explicit approval.

## Release gating and test matrix

**Milestone 0:** Verify current encrypted Test backup export, inspect, uninstall/reinstall, restore on device with disposable Scenario/session data. Save backup outside app storage; never assume Test backup can restore to production.

**Milestone 1:** Add World Room schema + migrations, repository and manual World editor. Existing chats/scenarios and lore continue unchanged; no fork of the Lorebook compiler. Tests for memberships, deletion/detach, link precedence and no-World regression.

**Milestone 2:** World definition JSON parser, strict validation, export/import preview and complete round-trip including cross-world collision cases. Keep current individual JSON import flows intact.

**Milestone 3 (required BEFORE irreplaceable story):** Selective, encrypted World archive export/import: full history and images, inter-ID remapping, corruption/version/size limits, atomic rollback, Test→Test reinstall and Test→production additive import. Confirm no unrelated production records are overwritten.

**Milestone 4:** NPC generation from Character and Scenario messages, candidate review, duplicate matching, local variant-safe updates, World promotion; existing NPC bank stays operational. Test multiple namesakes, partial output, regeneration, deletion, and a scene with many disposable strangers.

**Milestone 5:** NPC→Character and NPC→Persona conversions, World-sourced Scenario roles, reusable scene cast. Test source snapshot/refresh behavior and Persona agency.

**Milestone 6:** Episode continuity and optional directed summaries after explicit user action; ensure past sessions do not see future canon without a deliberate refresh.

Each milestone needs focused unit and integration tests and a physical Test-app smoke test. Production 1.5 remains frozen until all required gates pass and the user elects to ship. Code review must explicitly check schema migration, startup costs, model token growth, and data loss paths.

## Exclusions from first release

- No full game-state simulation, background autonomous people/agents, per-NPC provider calls, forced multi-turn writer orchestration, public character marketplace or hosting, cloud sync, invented universal World memory, model-generated images, automatic publication, or lorebook auto-retcon.
- No assumption that HereHaven or Megumin Suite code is licensed for reuse. Implement our own compatible workflows and terminology.
- No requirement that every World be episodic or use the same character/writing style.

## Open implementation decisions (not blockers for drafting)

- Whether WorldCast fields should reside in a new typed JSON column or normalized subrecords; document migration and field versioning before coding.
- Best canonical event ordering for branching episode timelines; use a neutral event+scope model initially, not a forced single global timeline.
- Whether "attach World to existing chat" should apply lore immediately or prompt to snapshot the attachment; require a preview.
- Key management policy for encrypted World archives, including recovery from forgotten passphrase. No silently stored passphrases.

## First integration fixture

World: **Law & Disorder: Mondstadt**. 2026 Earth-adjacent, nonmagical Teyvat with Omegaverse biology. Mondstadt's Knights function as a police department; Varka is Police Commissioner, Jean Chief, Flins District Attorney, Venti Mayor. Piper Grace is a new Omega in town; Flins and Varka are an established mated Alpha pair, and romantic comedy runs alongside procedural episodes. Sucrose works in the morgue, Barbara is an EMT, and Lohen is a brilliant eccentric detective. This fixture establishes only creator-approved facts and remains separate from the generic app data model.
