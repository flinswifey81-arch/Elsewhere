package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

class ScenarioRepository(
    private val database: AppDatabase,
    private val coordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    private val worldDefaultLoreAdapter = Moshi.Builder().build().adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    fun observeSession(chatId: String) = database.scenarioDao().observeSession(chatId)
    fun observeAll() = database.scenarioDao().observeAll()
    suspend fun get(id: String) = database.scenarioDao().get(id)
    suspend fun session(chatId: String) = database.scenarioDao().session(chatId)

    /**
     * Player-initiated revision of this ONE ongoing playthrough.
     * Does not rewrite its reusable template, prior transcript, World baseline
     * or any sibling sessions created from the same Scenario.
     * Generation rereads the current session definition for each new response.
     */
    suspend fun updateActiveSession(chatId: String, definition: ScenarioDefinition) =
        coordinator.withMutation {
            require(chatId.isNotBlank()) { "The active chat ID is missing." }
            definition.validate()
            database.withTransaction {
                validateLinkedCharacters(definition)
                val chat = requireNotNull(database.chatDao().getChatById(chatId)) {
                    "This chat no longer exists."
                }
                require(chat.chatType == ChatType.SCENARIO) {
                    "Only an ongoing Scenario playthrough can be edited here."
                }
                requireNotNull(database.scenarioDao().session(chatId)) {
                    "This Scenario playthrough no longer exists."
                }
                require(database.scenarioDao().updateSessionDefinition(
                    chatId, ScenarioCodec.encode(definition)
                ) == 1) {
                    "The Scenario playthrough changed before your edits were saved."
                }
            }
        }

    suspend fun save(id: String, definition: ScenarioDefinition) = coordinator.withMutation {
        require(id.isNotBlank()) { "Scenario identifier is required." }
        definition.validate()
        database.withTransaction {
            validateLinkedCharacters(definition)
            val old = get(id)
            database.scenarioDao().save(ScenarioEntity(
                id, ScenarioCodec.encode(definition),
                old?.createdAt ?: System.currentTimeMillis()
            ))
        }
    }
    suspend fun start(scenarioId: String, personaId: String, worldId: String? = null): String = coordinator.withMutation {
        database.withTransaction {
            val scenario = requireNotNull(get(scenarioId)) { "Scenario is unavailable." }
            val definition = ScenarioCodec.decode(scenario.definitionJson).also { it.validate() }
            validateLinkedCharacters(definition)
            requireNotNull(database.personaDao().getPersonaById(personaId)) { "Select an existing persona." }
            val world = worldId?.let { id ->
                requireNotNull(database.worldDao().getWorld(id)) { "Selected World is unavailable." }
            }
            val chat = ChatEntity(displayName = definition.displayTitle.ifBlank { definition.title }, chatType = ChatType.SCENARIO)
            database.chatDao().insertChat(chat)
            database.chatDao().insertChatPersonaParticipant(ChatPersonaParticipantEntity(chatId = chat.chatId, personaId = personaId))
            database.chatDao().insertChatSettings(ChatSettingsEntity(chatId = chat.chatId, useGlobalEngineDefaults = true))
            database.scenarioDao().insertSession(ScenarioSessionEntity(chat.chatId, scenarioId, scenario.definitionJson))
            if (world != null) {
                val worldDao = database.worldDao()
                worldDao.insertMembership(WorldMembershipEntity(worldId = world.worldId,
                    resourceType = WorldResourceType.CHAT.name, resourceId = chat.chatId))
                if (worldDao.getMembershipsForResource(WorldResourceType.SCENARIO.name, scenarioId)
                        .none { it.worldId == world.worldId }) {
                    worldDao.insertMembership(WorldMembershipEntity(worldId = world.worldId,
                        resourceType = WorldResourceType.SCENARIO.name, resourceId = scenarioId))
                }
                // Import remaps these IDs to this World's own reviewed copies.
                // Never attach an unrelated library book, even if an older
                // World record contains a stale or foreign default ID.
                val worldBooks = worldDao.getMemberships(world.worldId)
                    .filter { it.resourceType == WorldResourceType.LOREBOOK.name }
                    .map { it.resourceId }.toSet()
                val defaultBooks = requireNotNull(worldDefaultLoreAdapter.fromJson(world.defaultLorebookIdsJson)) {
                    "The World has an invalid default Lorebook list."
                }
                defaultBooks.distinct().filter { it in worldBooks }.forEachIndexed { index, lorebookId ->
                    if (worldDao.hasLorebook(lorebookId)) {
                        database.lorebookDao().upsertAttachment(
                            ChatLorebookAttachmentEntity(chat.chatId, lorebookId, sortPosition = index)
                        )
                    }
                }
            }
            if (definition.openingMessage.isNotBlank()) database.messageDao().insertMessage(MessageEntity(
                chatId = chat.chatId, speakerType = SpeakerType.NARRATOR, speakerId = "narrator:${chat.chatId}",
                speakerDisplayNameSnapshot = "Narrator", content = definition.openingMessage, orderIndex = chat.createdAt
            ))
            chat.chatId
        }
    }
    /**
     * A linked Character is a real stable ID, never a guessed name. Reject broken
     * references before saving or starting; nothing copies another chat's history.
     */
    private suspend fun validateLinkedCharacters(definition: ScenarioDefinition) {
        for (id in definition.npcs.mapNotNull { it.sourceCharacterId }.distinct()) {
            require(id.isNotBlank() && database.characterDao().getCharacterById(id) != null) {
                "A linked Scenario NPC Character no longer exists. Reassign or unlink it."
            }
        }
    }

    internal suspend fun approvedPlaythroughEntries(
        chatId: String, asOfEpochMillis: Long, historicalReplay: Boolean = false
    ): List<String> = CampaignRepository(database, coordinator)
        .narratorPlaythroughEntries(chatId, asOfEpochMillis, historicalReplay)

    /** Bounded shared World baseline for Scenario narration. */
    suspend fun worldContextForChat(chatId: String): String? {
        val memberships = database.worldDao().getMembershipsForResource(WorldResourceType.CHAT.name, chatId)
        val membership = memberships.singleOrNull() ?: return null
        val dao = database.worldDao()
        val world = dao.getWorld(membership.worldId) ?: return null
        val people = dao.getCast(world.worldId).take(24)
        val events = dao.getEvents(world.worldId).filter { it.status == "ACCEPTED" }.takeLast(12)
        return buildString {
            appendLine("Shared World: ${world.title}")
            if (world.settingSummary.isNotBlank()) appendLine("Setting: ${world.settingSummary}")
            if (world.description.isNotBlank()) appendLine("Premise: ${world.description}")
            if (world.worldRules.isNotBlank()) appendLine("Rules: ${world.worldRules}")
            if (people.isNotEmpty()) {
                appendLine("World Cast (stable identities; not everyone must appear in each scene):")
                people.forEach { person ->
                    appendLine("- ${person.name}: ${listOf(person.role, person.identity, person.personality, person.voice, person.knowledgeLimits).filter { it.isNotBlank() }.joinToString(" | ")}")
                }
            }
            if (events.isNotEmpty()) {
                appendLine("Accepted prior World continuity:")
                events.forEach { appendLine("- ${it.title}: ${it.facts}") }
            }
        }.take(16_000)
    }

}
