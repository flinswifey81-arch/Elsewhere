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
