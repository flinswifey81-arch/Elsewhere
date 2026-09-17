package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.RoleplayMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM roleplay_memories WHERE chatId = :chatId AND characterId = :characterId ORDER BY updatedAt ASC")
    fun getMemories(chatId: String, characterId: String): Flow<List<RoleplayMemoryEntity>>

    @Query("SELECT * FROM roleplay_memories WHERE chatId = :chatId AND characterId = :characterId ORDER BY updatedAt ASC")
    suspend fun getMemoriesOnce(chatId: String, characterId: String): List<RoleplayMemoryEntity>

    @Query("SELECT COUNT(*) FROM roleplay_memories WHERE chatId = :chatId AND characterId = :characterId AND lower(content) = lower(:content)")
    suspend fun countMatchingMemory(chatId: String, characterId: String, content: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: RoleplayMemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: RoleplayMemoryEntity)

    @Query("SELECT * FROM conversation_summaries WHERE chatId = :chatId")
    fun getSummary(chatId: String): Flow<ConversationSummaryEntity?>

    @Query("SELECT * FROM conversation_summaries WHERE chatId = :chatId")
    suspend fun getSummaryOnce(chatId: String): ConversationSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: ConversationSummaryEntity)
}

