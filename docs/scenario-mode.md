# Scenario Mode

Scenario Mode is additive to Solo and Group Character chats. Open **More → Scenarios** on compact screens (or **Scenarios** in the wide navigation bar). Create a Quick Scene or Open World / Sandbox, save it, then choose **New playthrough** and an existing Persona. Continue saved stories from **Chats**. Model, provider routing, sampling, and response length remain per-chat settings.

Only title and premise are required. Opening text is optional. Quick Scene hides optional worldbuilding; Sandbox initially expands it. Switching the editor experience preserves all fields and never changes the runtime or imposes an ending. Recurring NPCs are an expandable, dynamically sized list, supporting four or more. Artwork uses the existing local image picker, normalization and storage pipeline. No image generation is added.

## Architecture and data

- `data/model/ScenarioEntity.kt`: typed `ScenarioDefinition` and `ScenarioNpc`, Moshi serialization, validation, library and session entities. Display title, public description, categories, tags and artwork are presentation data, excluded from model instructions.
- `data/ScenarioDao.kt`, `domain/repository/ScenarioRepository.kt`: Room persistence and transactional playthrough creation using the existing Persona participant and message tables. No fake Character records are created.
- `AppDatabase.MIGRATION_8_9`: adds `scenarios` and `scenario_sessions`. Existing tables and records remain intact. Session rows cascade only when their owning chat is deleted. Templates do not own or delete playthrough histories.
- Each session stores a definition snapshot. Editing a template affects new playthroughs only; saved sessions keep their starting world and NPC definitions. Events, generated NPCs, relationships and consequences stay in that session's messages and existing local memory/summary infrastructure.
- `domain/compiler/ScenarioContextCompiler.kt`: separate narrator instructions, worldbuilding, named NPC definitions, Persona sections, session memory/lore, length settings and recent messages. Public metadata and Persona private notes are excluded. NPC instructions cannot redefine the player. Character prompts are unchanged.
- `ChatDetailViewModel`: branches only request preparation for Scenario chats. A lightweight response-speaker value allows the existing streaming, retry, partial save, variants, continuation and metadata pipeline to persist `NARRATOR` messages. One normal Send performs one provider request. Multiple NPCs can speak within that narrator message; individual NPC requests are not scheduled.
- Existing memory storage uses its legacy `characterId` scope column with a session-specific `narrator:<chatId>` key. No shared world memory is introduced. The existing local summary is lossy (bounded excerpts), and the compiler keeps 24 recent prior messages plus the current message. This does not promise perfect long-term continuity or new model-specific token budgeting.
- Context Inspector previews the scenario compiler; character narrator-guidance controls and the character NPC Bank are hidden in Scenario chats to avoid conflicting systems.
- Backup payloads include definitions, session snapshots, narrator-scoped memories and scenario artwork. Missing scenario arrays default to empty for older backups. The normal backup database-version guard prevents older app versions from accepting newer data they cannot understand.

## Deliberately deferred

Existing Character selection as a scenario NPC is not exposed yet. `ScenarioNpc.sourceCharacterId` reserves stable linkage. A future resolver should map approved Character fields (including manual personality/background/voice), exclude author notes and unknown extensions, and define snapshot-versus-live-update behavior. It must not mutate or duplicate the original Character record. The current narrator uses the NPC fields stored in the scenario snapshot.

No new co-op scenario protocol, cloud discovery, public publishing, separate desktop application, AI background calls or image generation is introduced. Wide-window coverage applies to the native Android Compose UI.

## Verification

Final command: `bash gradlew :app:testDebugUnitTest :app:assembleDebug --offline --no-daemon --max-workers=4` (using `/workspace/toolchains/elsewhere-env.sh`).

- IMPLEMENTED: Scenario editors/library, one-request runtime, isolated sessions, migration, artwork and backups.
- AUTOMATED TESTED: 261 tests across 43 suites; 0 failures, 0 errors, 0 skipped. Debug APK built successfully. The focused set passed all 27 tests (8 scenario runtime/compiler/repository, 2 phone/wide editor, 5 migration, 12 backup tests).
- MANUALLY VERIFIED: No physical-device or emulator interaction was performed.
- UNVERIFIED: Live OpenRouter storytelling quality, physical-device appearance and keyboard behavior. Wide-window tests cover Android Compose, not a separate desktop app.

Tests use local mocked model responses and do not spend OpenRouter credits. An initial baseline run had one Group UI initialization timeout; the final full suite, including all Group tests, passed without changing Group behavior. The scenario retry regression exposed a fast-error/UI-emission race, now covered and fixed by reading the saved player turn from Room on retry.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Work branch: `feature/scenario-mode`, based on the repository's existing backup/NPC implementation at `af4b8e0`.

Paused earlier multi-call narrator work was preserved outside this feature in `/workspace/paused-narrator-work/changes.patch` and `new-files.tar` before restoring the original Character implementation. Nothing was committed or pushed.
