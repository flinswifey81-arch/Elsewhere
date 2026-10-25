package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.*

class ScenarioRepository(
    private val database: AppDatabase,
    private val coordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    fun observeAll() = database.scenarioDao().observeAll()
    suspend fun get(id: String) = database.scenarioDao().get(id)
    suspend fun session(chatId: String) = database.scenarioDao().session(chatId)
    suspend fun save(id: String, definition: ScenarioDefinition) = coordinator.withMutation {
        require(id.isNotBlank()) { "Scenario identifier is required." }
        definition.validate()
        val old = get(id)
        database.scenarioDao().save(ScenarioEntity(id, ScenarioCodec.encode(definition), old?.createdAt ?: System.currentTimeMillis()))
    }
    suspend fun start(scenarioId: String, personaId: String): String = coordinator.withMutation {
        database.withTransaction {
            val scenario = requireNotNull(get(scenarioId)) { "Scenario is unavailable." }
            val definition = ScenarioCodec.decode(scenario.definitionJson).also { it.validate() }
            requireNotNull(database.personaDao().getPersonaById(personaId)) { "Select an existing persona." }
            val chat = ChatEntity(displayName = definition.displayTitle.ifBlank { definition.title }, chatType = ChatType.SCENARIO)
            database.chatDao().insertChat(chat)
            database.chatDao().insertChatPersonaParticipant(ChatPersonaParticipantEntity(chatId = chat.chatId, personaId = personaId))
            database.chatDao().insertChatSettings(ChatSettingsEntity(chatId = chat.chatId, useGlobalEngineDefaults = true))
            database.scenarioDao().insertSession(ScenarioSessionEntity(chat.chatId, scenarioId, scenario.definitionJson))
            if (definition.openingMessage.isNotBlank()) database.messageDao().insertMessage(MessageEntity(
                chatId = chat.chatId, speakerType = SpeakerType.NARRATOR, speakerId = "narrator:${chat.chatId}",
                speakerDisplayNameSnapshot = "Narrator", content = definition.openingMessage, orderIndex = chat.createdAt
            ))
            chat.chatId
        }
    }
}
