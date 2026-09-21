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

    @Query("SELECT * FROM chat_persona_participants WHERE chatId = :chatId ORDER BY sortPosition ASC")
    fun getPersonaParticipantsForChat(chatId: String): Flow<List<ChatPersonaParticipantEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatSettings(settings: com.example.data.model.ChatSettingsEntity)

    @Query("SELECT * FROM chat_settings WHERE chatId = :chatId")
    suspend fun getChatSettings(chatId: String): com.example.data.model.ChatSettingsEntity?

    @Query("SELECT * FROM chat_settings WHERE chatId = :chatId")
    fun getChatSettingsFlow(chatId: String): Flow<com.example.data.model.ChatSettingsEntity?>
}
