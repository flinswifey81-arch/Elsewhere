package com.example.data

import androidx.room.*
import com.example.data.model.ChatEntity
import com.example.data.model.ChatParticipantEntity
import com.example.data.model.ChatPersonaParticipantEntity
import com.example.data.model.ChatSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Transaction
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun getAllChatSummaries(): Flow<List<ChatSummary>>

    @Transaction
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentChatSummaries(limit: Int): Flow<List<ChatSummary>>

    @Transaction
    @Query("SELECT * FROM chats WHERE chatId = :id")
    suspend fun getChatSummaryById(id: String): ChatSummary?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE chatId = :id")
    suspend fun getChatById(id: String): ChatEntity?

    @Query("SELECT MAX(orderIndex) FROM messages WHERE chatId = :chatId")
    suspend fun getLatestMessageOrderIndex(chatId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Delete
    suspend fun deleteChat(chat: ChatEntity)

    @Query("DELETE FROM generation_metadata WHERE messageId IN (SELECT messageId FROM messages WHERE chatId = :chatId)")
    suspend fun deleteGenerationMetadataForChat(chatId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String)

    @Query("DELETE FROM message_attachments WHERE chatId = :chatId")
    suspend fun deleteMessageAttachmentsForChat(chatId: String)

    @Query("DELETE FROM npc_dossier_updates WHERE chatId = :chatId")
    suspend fun deleteNpcUpdatesForChat(chatId: String)

    @Query("DELETE FROM npc_dossiers WHERE chatId = :chatId")
    suspend fun deleteNpcDossiersForChat(chatId: String)

    @Query("DELETE FROM npc_bank_settings WHERE chatId = :chatId")
    suspend fun deleteNpcSettingsForChat(chatId: String)

    @Query("DELETE FROM conversation_summaries WHERE chatId = :chatId")
    suspend fun deleteSummaryForChat(chatId: String)

    @Query("UPDATE chats SET contextResetOrderIndex = :boundary, updatedAt = :updatedAt WHERE chatId = :chatId")
    suspend fun setContextResetBoundary(chatId: String, boundary: Long, updatedAt: Long)

    @Transaction
    suspend fun establishContextReset(chatId: String, requestedBoundary: Long): ChatEntity? {
        val latestOrderIndex = getLatestMessageOrderIndex(chatId)
        val boundary = maxOf(requestedBoundary, latestOrderIndex ?: requestedBoundary)
        setContextResetBoundary(chatId, boundary, requestedBoundary)
        deleteSummaryForChat(chatId)
        return getChatById(chatId)
    }

    @Query("DELETE FROM roleplay_memories WHERE chatId = :chatId")
    suspend fun deleteMemoriesForChat(chatId: String)

    @Query("DELETE FROM relationships WHERE scopeChatId = :chatId")
    suspend fun deleteRelationshipsForChat(chatId: String)

    @Query("DELETE FROM chat_settings WHERE chatId = :chatId")
    suspend fun deleteSettingsForChat(chatId: String)

    @Query("DELETE FROM chat_participants WHERE chatId = :chatId")
    suspend fun deleteCharacterParticipantsForChat(chatId: String)

    @Query("DELETE FROM chat_persona_participants WHERE chatId = :chatId")
    suspend fun deletePersonaParticipantsForChat(chatId: String)

    @Query("DELETE FROM chat_lorebook_entry_overlays WHERE chatId = :chatId")
    suspend fun deleteLorebookOverlaysForChat(chatId: String)

    @Query("DELETE FROM chat_lorebook_attachments WHERE chatId = :chatId")
    suspend fun deleteLorebookAttachmentsForChat(chatId: String)

    @Query("DELETE FROM coop_events WHERE roomId IN (SELECT roomId FROM coop_rooms WHERE chatId = :chatId)")
    suspend fun deleteCoopEventsForChat(chatId: String)

    @Query("DELETE FROM coop_message_tombstones WHERE roomId IN (SELECT roomId FROM coop_rooms WHERE chatId = :chatId)")
    suspend fun deleteCoopMessageTombstonesForChat(chatId: String)

    @Query("DELETE FROM coop_persona_ownership WHERE roomId IN (SELECT roomId FROM coop_rooms WHERE chatId = :chatId)")
    suspend fun deleteCoopOwnershipsForChat(chatId: String)

    @Query("DELETE FROM coop_participants WHERE roomId IN (SELECT roomId FROM coop_rooms WHERE chatId = :chatId)")
    suspend fun deleteCoopParticipantsForChat(chatId: String)

    @Query("DELETE FROM coop_rooms WHERE chatId = :chatId")
    suspend fun deleteCoopRoomsForChat(chatId: String)

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun deleteChatById(chatId: String)

    @Transaction
    suspend fun deleteChatAndOwnedData(chatId: String) {
        deleteGenerationMetadataForChat(chatId)
        deleteMessageAttachmentsForChat(chatId)
        deleteNpcUpdatesForChat(chatId)
        deleteNpcDossiersForChat(chatId)
        deleteNpcSettingsForChat(chatId)
        deleteMessagesForChat(chatId)
        deleteSummaryForChat(chatId)
        deleteMemoriesForChat(chatId)
        deleteRelationshipsForChat(chatId)
        deleteSettingsForChat(chatId)
        deleteLorebookOverlaysForChat(chatId)
        deleteLorebookAttachmentsForChat(chatId)
        deleteCoopEventsForChat(chatId)
        deleteCoopMessageTombstonesForChat(chatId)
        deleteCoopOwnershipsForChat(chatId)
        deleteCoopParticipantsForChat(chatId)
        deleteCoopRoomsForChat(chatId)
        deleteCharacterParticipantsForChat(chatId)
        deletePersonaParticipantsForChat(chatId)
        deleteChatById(chatId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatParticipant(participant: ChatParticipantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatPersonaParticipant(participant: ChatPersonaParticipantEntity)

    @Query("SELECT * FROM chat_participants WHERE chatId = :chatId ORDER BY sortPosition ASC")
    fun getParticipantsForChat(chatId: String): Flow<List<ChatParticipantEntity>>

    @Query("SELECT * FROM chat_participants WHERE chatId = :chatId ORDER BY sortPosition ASC")
    suspend fun getParticipantsForChatOnce(chatId: String): List<ChatParticipantEntity>

    @Query("SELECT * FROM chat_participants WHERE chatId = :chatId AND characterId = :characterId")
    suspend fun getChatParticipant(chatId: String, characterId: String): ChatParticipantEntity?

    @Update
    suspend fun updateChatParticipant(participant: ChatParticipantEntity)

    @Query("DELETE FROM chat_participants WHERE chatId = :chatId AND characterId = :characterId")
    suspend fun deleteChatParticipant(chatId: String, characterId: String)

    @Transaction
    suspend fun setChatParticipantActive(chatId: String, characterId: String, isActive: Boolean) {
        getChatParticipant(chatId, characterId)?.let { participant ->
            updateChatParticipant(participant.copy(isActive = isActive))
        }
    }

    @Transaction
    suspend fun addChatParticipantAtEnd(chatId: String, characterId: String) {
        val nextPosition = (getParticipantsForChatOnce(chatId).maxOfOrNull { it.sortPosition } ?: -1) + 1
        insertChatParticipant(
            ChatParticipantEntity(
                chatId = chatId,
                characterId = characterId,
                isActive = true,
                sortPosition = nextPosition
            )
        )
    }

    @Transaction
    suspend fun moveChatParticipant(chatId: String, characterId: String, direction: Int) {
        val ordered = getParticipantsForChatOnce(chatId).sortedBy { it.sortPosition }
        val currentIndex = ordered.indexOfFirst { it.characterId == characterId }
        if (currentIndex < 0) return
        val targetIndex = (currentIndex + direction).coerceIn(0, ordered.lastIndex)
        if (targetIndex == currentIndex) return

        val current = ordered[currentIndex]
        val target = ordered[targetIndex]
        updateChatParticipant(current.copy(sortPosition = target.sortPosition))
        updateChatParticipant(target.copy(sortPosition = current.sortPosition))
    }

    @Query("SELECT * FROM chat_persona_participants WHERE chatId = :chatId ORDER BY sortPosition ASC")
    fun getPersonaParticipantsForChat(chatId: String): Flow<List<ChatPersonaParticipantEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatSettings(settings: com.example.data.model.ChatSettingsEntity)

    @Query("SELECT * FROM chat_settings WHERE chatId = :chatId")
    suspend fun getChatSettings(chatId: String): com.example.data.model.ChatSettingsEntity?

    @Query("SELECT * FROM chat_settings WHERE chatId = :chatId")
    fun getChatSettingsFlow(chatId: String): Flow<com.example.data.model.ChatSettingsEntity?>

    @Query("UPDATE chat_settings SET coLeadRebalanceTurnsRemaining = coLeadRebalanceTurnsRemaining - 1 WHERE chatId = :chatId AND coLeadRebalanceTurnsRemaining > 0 AND coLeadEnsembleEnabled = 1 AND narratorEnabled = 1")
    suspend fun consumeCoLeadRebalanceTurn(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEngineDefaults(defaults: com.example.data.model.EngineDefaultsEntity)

    @Query("SELECT * FROM engine_defaults WHERE defaultsId = 'global' LIMIT 1")
    suspend fun getEngineDefaults(): com.example.data.model.EngineDefaultsEntity?

    @Query("SELECT * FROM engine_defaults WHERE defaultsId = 'global' LIMIT 1")
    fun observeEngineDefaults(): Flow<com.example.data.model.EngineDefaultsEntity?>
}
