package com.example.domain.world

import com.example.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Validates a complete *selected World* data graph before any ZIP IO or Room mutation.
 * Missing records are errors, not invitations to silently drop a person's history.
 *
 * The companion WorldArchiveManifestCodec validates the ZIP manifest. This codec validates
 * the typed state and all available references, including non-primary variants and NPC
 * update provenance. It deliberately does NOT import data or rewrite embedded JSON IDs.
 */
object WorldArchiveStateCodec {
    const val FORMAT = "elsewhere_world_state"
    const val SCHEMA_VERSION = 1
    const val MAX_STATE_JSON_CHARS = 100_000_000
    private const val MAX_RECORDS_PER_COLLECTION = 500_000
    private val sha256Pattern = Regex("[a-f0-9]{64}")
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(WorldArchiveStateV1::class.java).failOnUnknown().indent("  ")
    private val worldLinksAdapter = moshi.adapter(WorldLinksV1::class.java)
    private val listAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )
    private val mapAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    fun parse(json: String): WorldArchiveStateV1 {
        require(json.length in 1..MAX_STATE_JSON_CHARS) { "World history snapshot is empty or oversized" }
        val state = try {
            adapter.fromJson(json)
        } catch (cause: Exception) {
            throw IllegalArgumentException("World history snapshot is malformed or has unknown fields", cause)
        } ?: throw IllegalArgumentException("World history snapshot is empty")
        validate(state)
        return state
    }

    fun encode(state: WorldArchiveStateV1): String {
        validate(state)
        val json = adapter.toJson(state)
        require(json.length <= MAX_STATE_JSON_CHARS) {
            "World history snapshot exceeds the supported size; split archives need a new version"
        }
        return json
    }

    private fun <T> ids(values: List<T>, label: String, key: (T) -> String): Set<String> {
        require(values.size <= MAX_RECORDS_PER_COLLECTION) { "Too many $label in World archive" }
        val selected = values.map(key)
        require(selected.all { it.isNotBlank() && it.length <= 200 }) { "$label contain invalid IDs" }
        require(selected.toSet().size == selected.size) { "Duplicate $label in World archive" }
        return selected.toSet()
    }

    private fun <T> requireScoped(
        values: List<T>, allowed: Set<String>, label: String, owner: (T) -> String
    ) {
        require(values.all { owner(it) in allowed }) { "$label references an unarchived chat or resource" }
    }

    private fun <T> requireUniqueComposite(values: List<T>, label: String, key: (T) -> String) {
        require(values.size <= MAX_RECORDS_PER_COLLECTION) { "Too many $label" }
        require(values.map(key).distinct().size == values.size) { "Duplicate $label" }
    }

    private fun jsonIds(json: String, label: String): List<String> = try {
        requireNotNull(listAdapter.fromJson(json)) { "Missing $label" }
    } catch (cause: Exception) {
        throw IllegalArgumentException("Invalid $label", cause)
    }

    private fun jsonMap(json: String, label: String): Map<String, String> = try {
        requireNotNull(mapAdapter.fromJson(json)) { "Missing $label" }
    } catch (cause: Exception) {
        throw IllegalArgumentException("Invalid $label", cause)
    }

    fun validate(state: WorldArchiveStateV1) {
        require(state.format == FORMAT && state.schemaVersion == SCHEMA_VERSION) {
            "Unsupported World history snapshot format or version"
        }
        require(state.worldId.isNotBlank() && state.world.worldId == state.worldId) {
            "World history snapshot and World identity disagree"
        }

        val cast = ids(state.cast, "World cast") { it.worldCharacterId }
        val events = ids(state.continuity, "World continuity events") { it.eventId }
        val characters = ids(state.characters, "Characters") { it.characterId }
        val personas = ids(state.personas, "Personas") { it.personaId }
        val scenarios = ids(state.scenarios, "Scenarios") { it.scenarioId }
        val chats = ids(state.chats, "Chats") { it.chatId }
        val messages = ids(state.messages, "Messages") { it.messageId }
        val relations = ids(state.relationships, "Relationships") { it.relationshipId }
        val books = ids(state.lorebooks, "Lorebooks") { it.lorebookId }
        val entries = ids(state.lorebookEntries, "Lorebook entries") { it.entryId }
        val npcs = ids(state.npcDossiers, "NPC dossiers") { it.npcId }
        val npcUpdates = ids(state.npcUpdates, "NPC updates") { it.updateId }
        val banks = ids(state.npcLibraryBanks, "NPC library banks") { it.bankId }
        val libraryNpcs = ids(state.npcLibraryEntries, "NPC library entries") { it.libraryNpcId }
        ids(state.messageAttachments, "Message attachments") { it.attachmentId }
        ids(state.roleplayMemories, "Roleplay memories") { it.memoryId }
        ids(state.generationMetadata, "Generation metadata") { it.messageId }
        ids(state.chatSettings, "Chat settings") { it.chatId }
        ids(state.conversationSummaries, "Conversation summaries") { it.chatId }
        ids(state.scenarioSessions, "Scenario sessions") { it.chatId }
        ids(state.npcBankSettings, "NPC bank settings") { it.chatId }

        require(state.cast.all { it.worldId == state.worldId } &&
            state.continuity.all { it.worldId == state.worldId }) {
            "A World cast member or continuity event belongs to another World"
        }

        val selectedMembers = state.memberships
        requireUniqueComposite(selectedMembers, "World memberships") { "${it.worldId}|\u0000|${it.resourceType}|\u0000|${it.resourceId}" }
        val membershipTargets = mapOf(
            WorldResourceType.CHAT.name to chats,
            WorldResourceType.CHARACTER.name to characters,
            WorldResourceType.PERSONA.name to personas,
            WorldResourceType.SCENARIO.name to scenarios,
            WorldResourceType.LOREBOOK.name to books,
            WorldResourceType.NPC_LIBRARY_BANK.name to banks
        )
        require(selectedMembers.all { member ->
            member.worldId == state.worldId &&
                member.resourceId in (membershipTargets[member.resourceType] ?: emptySet())
        }) { "World membership references an unsupported or missing resource" }

        // Only explicitly selected World chats belong in an archive. A Scenario membership
        // does not implicitly authorize exporting all other playthroughs of that template.
        val selectedChatIds = selectedMembers.filter { it.resourceType == WorldResourceType.CHAT.name }
            .map { it.resourceId }.toSet()
        require(chats == selectedChatIds) { "Some included chats are not explicitly selected for this World" }

        val links = try {
            requireNotNull(worldLinksAdapter.fromJson(state.world.linksJson))
        } catch (cause: Exception) {
            throw IllegalArgumentException("Invalid World links", cause)
        }
        require(links.relationships.all {
            it.fromWorldCharacterId in cast && it.toWorldCharacterId in cast
        }) { "A World relationship points outside the selected cast" }
        val scenarioNpcIds = mutableMapOf<String, Set<String>>()
        state.scenarios.forEach { scenario ->
            val definition = ScenarioCodec.decode(scenario.definitionJson)
            definition.validate()
            scenarioNpcIds[scenario.scenarioId] = definition.npcs.map { it.npcId }.toSet()
            require(definition.npcs.all {
                it.sourceCharacterId == null || it.sourceCharacterId in characters
            }) { "Scenario references a Character missing from the archive" }
        }
        links.castRoles.forEach { link ->
            require(link.worldCharacterId in cast) { "A World role refers to missing World cast" }
            require(when (link.roleType) {
                "CHARACTER" -> link.resourceId in characters
                "PERSONA" -> link.resourceId in personas
                "SCENARIO_NPC" -> link.resourceId in (scenarioNpcIds[link.scenarioId] ?: emptySet())
                "NPC_LIBRARY" -> link.resourceId in libraryNpcs
                else -> false
            }) { "World role points to an unarchived resource" }
        }
        require(jsonIds(state.world.defaultLorebookIdsJson, "World default Lorebooks").all { it in books }) {
            "World default Lorebook is not included"
        }
        state.continuity.forEach { event ->
            require(event.sourceChatId == null || event.sourceChatId in chats) {
                "World continuity cites a chat outside the archive"
            }
            require(jsonIds(event.relatedCastIdsJson, "Continuity cast").all { it in cast }) {
                "World continuity refers to an unarchived cast member"
            }
        }

        requireScoped(state.scenarioSessions, chats, "Scenario sessions") { it.chatId }
        state.scenarioSessions.forEach { session ->
            require(session.scenarioId in scenarios) { "Scenario session lost its template" }
            val snapshot = ScenarioCodec.decode(session.definitionJson)
            snapshot.validate()
            require(snapshot.npcs.all { it.sourceCharacterId == null || it.sourceCharacterId in characters }) {
                "Scenario playthrough snapshot references missing Character"
            }
        }
        requireScoped(state.chatParticipants, chats, "Character chat participants") { it.chatId }
        requireScoped(state.chatPersonaParticipants, chats, "Persona chat participants") { it.chatId }
        requireUniqueComposite(state.chatParticipants, "Character chat participants") { "${it.chatId}|${it.characterId}" }
        requireUniqueComposite(state.chatPersonaParticipants, "Persona chat participants") { "${it.chatId}|${it.personaId}" }
        require(state.chatParticipants.all { it.characterId in characters } &&
            state.chatPersonaParticipants.all { it.personaId in personas }) {
            "Chat participant is missing its Character or Persona"
        }
        requireScoped(state.chatSettings, chats, "Chat settings") { it.chatId }
        requireScoped(state.conversationSummaries, chats, "Conversation summaries") { it.chatId }
        require(state.chats.all { chat ->
            state.chatSettings.count { it.chatId == chat.chatId } == 1 &&
                (chat.chatType != ChatType.SCENARIO ||
                    state.scenarioSessions.count { it.chatId == chat.chatId } == 1)
        }) { "Selected chat is missing settings or its Scenario snapshot" }

        requireScoped(state.messages, chats, "Messages") { it.chatId }
        val messageById = state.messages.associateBy { it.messageId }
        require(state.messages.all { it.variantGroupId.isNotBlank() }) {
            "A message has no variant group identity"
        }
        val variantGroups = state.messages.groupBy { it.chatId to it.variantGroupId }
        require(variantGroups.values.all { variants -> variants.count { it.isPrimaryVariant } == 1 }) {
            "A message variant group has no unambiguous primary response"
        }
        require(state.messages.all { message ->
            message.responsibleParticipantId == null &&
                message.responsibleDeviceId == null &&
                message.coOpEventId == null
        }) { "Co-op provenance requires an explicit offline conversion before World export" }
        require(state.messages.all {
            when (it.speakerType) {
                SpeakerType.CHARACTER -> it.speakerId in characters
                SpeakerType.PERSONA -> it.speakerId in personas
                else -> true
            }
        }) { "A message speaker's Character or Persona is missing" }
        require(state.generationMetadata.all { it.messageId in messages }) {
            "Generation metadata has no matching archived message"
        }
        requireScoped(state.roleplayMemories, chats, "Roleplay memories") { it.chatId }
        require(state.roleplayMemories.all { memory ->
            memory.characterId in characters &&
                (memory.sourceMessageId == null ||
                    messageById[memory.sourceMessageId]?.chatId == memory.chatId)
        }) { "Memory references a missing or unrelated message or Character" }

        require(state.relationships.all { relation ->
            relation.sourceCharacterId in characters &&
                relation.targetCharacterId in characters &&
                (relation.scopeChatId == null || relation.scopeChatId in chats)
        }) { "Relationship refers to missing Characters or unrelated chats" }

        require(state.lorebookEntries.all { it.lorebookId in books }) {
            "A Lorebook entry lost its parent book"
        }
        requireScoped(state.chatLorebookAttachments, chats, "Chat Lorebook attachments") { it.chatId }
        requireUniqueComposite(state.chatLorebookAttachments, "Chat Lorebook attachments") {
            "${it.chatId}|${it.lorebookId}"
        }
        require(state.chatLorebookAttachments.all { it.lorebookId in books }) {
            "Chat Lorebook attachment points to an unarchived book"
        }
        requireScoped(state.chatLorebookOverlays, chats, "Lorebook overlays") { it.chatId }
        requireUniqueComposite(state.chatLorebookOverlays, "Lorebook overlays") {
            "${it.chatId}|${it.entryId}"
        }
        require(state.chatLorebookOverlays.all { it.entryId in entries }) {
            "Chat Lorebook Current overlay has no entry"
        }

        requireScoped(state.npcDossiers, chats, "NPC dossiers") { it.chatId }
        requireScoped(state.npcBankSettings, chats, "NPC bank settings") { it.chatId }
        requireScoped(state.npcUpdates, chats, "NPC updates") { it.chatId }
        require(state.npcDossiers.all { npc ->
            jsonMap(npc.personaRelationshipsJson, "NPC Persona relationships").keys.all { it in personas } &&
                jsonMap(npc.characterKnowledgeJson, "NPC Character knowledge").keys.all { it in characters }
        }) { "NPC knowledge refers to an unarchived Persona or Character" }
        require(state.npcUpdates.all { update ->
            update.npcId in npcs &&
                state.npcDossiers.first { it.npcId == update.npcId }.chatId == update.chatId &&
                messageById[update.sourceMessageId]?.let {
                    it.chatId == update.chatId && it.variantGroupId == update.variantGroupId
                } == true &&
                update.sourceRevision >= 0
        }) { "An NPC revision lost its source message, variant or dossier" }
        require(state.npcLibraryEntries.all { it.bankId in banks }) {
            "NPC library entry has no bank"
        }
        require(state.npcLibraryEntries.all { npc ->
            jsonMap(npc.personaRelationshipsJson, "Library NPC Persona relationships").keys.all { it in personas } &&
                jsonMap(npc.characterKnowledgeJson, "Library NPC Character knowledge").keys.all { it in characters }
        }) { "NPC library knowledge has missing linked People" }

        requireScoped(state.messageAttachments, chats, "Message attachments") { it.chatId }
        require(state.messageAttachments.all { attachment ->
            (attachment.messageId == null ||
                messageById[attachment.messageId]?.chatId == attachment.chatId) &&
                attachment.byteSize > 0 &&
                attachment.sha256.matches(sha256Pattern) &&
                attachment.assetPath == "assets/message-attachments/${attachment.attachmentId}"
        }) { "An attachment is missing its message or has unportable asset metadata" }

        // The entity ID collections above are kept deliberately separate: a message ID is
        // *also* the generation-metadata ID; they must be remapped as one identity.
        @Suppress("UNUSED_VARIABLE")
        val validatedCounts = events.size + relations.size + npcUpdates.size
    }

    /**
     * Validate archived asset metadata against the manifest. This does not verify ZIP bytes.
     * The future archive reader must hash/check every *actual* member before touching Room.
     */
    fun validateWithManifest(state: WorldArchiveStateV1, manifest: WorldArchiveManifestV1) {
        validate(state)
        WorldArchiveManifestCodec.validate(manifest)
        require(state.worldId == manifest.worldId) { "Archive manifest and history World IDs differ" }
        val attachments = state.messageAttachments.associateBy { it.assetPath }
        val assetEntries = manifest.entries.filter { it.kind == WorldArchiveEntryKind.MESSAGE_ATTACHMENT }
        require(assetEntries.map { it.path }.toSet() == attachments.keys) {
            "Archive manifest and message attachment payload do not match"
        }
        require(assetEntries.all { entry ->
            attachments[entry.path]?.let {
                it.attachmentId == entry.ownerId &&
                    it.sha256 == entry.sha256 && it.byteSize == entry.byteSize
            } == true
        }) { "Message attachment bytes or owners disagree with archive manifest" }
        val owners = mapOf(
            WorldArchiveEntryKind.CHARACTER_AVATAR to state.characters.map { it.characterId }.toSet(),
            WorldArchiveEntryKind.PERSONA_AVATAR to state.personas.map { it.personaId }.toSet(),
            WorldArchiveEntryKind.SCENARIO_AVATAR to state.scenarios.map { it.scenarioId }.toSet(),
            WorldArchiveEntryKind.NPC_PORTRAIT to state.npcDossiers.map { it.npcId }.toSet()
        )
        require(manifest.entries.all { entry ->
            owners[entry.kind]?.let { owned -> entry.ownerId in owned } ?: true
        }) { "World archive asset belongs to an excluded resource" }
    }
}
