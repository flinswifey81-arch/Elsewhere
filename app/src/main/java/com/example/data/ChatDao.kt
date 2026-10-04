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

    @Query("DELETE FROM conversation_summaries WHERE chatId = :chatId")
    suspend fun deleteSummaryForChat(chatId: String)

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

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun deleteChatById(chatId: String)

    @Transaction
    suspend fun deleteChatAndOwnedData(chatId: String) {
        deleteGenerationMetadataForChat(chatId)
        deleteMessagesForChat(chatId)
        deleteSummaryForChat(chatId)
        deleteMemoriesForChat(chatId)
        deleteRelationshipsForChat(chatId)
        deleteSettingsForChat(chatId)
        deleteLorebookOverlaysForChat(chatId)
        deleteLorebookAttachmentsForChat(chatId)
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
}
