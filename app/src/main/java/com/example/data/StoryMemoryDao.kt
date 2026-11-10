package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.StoryMemoryActorEntity
import com.example.data.model.StoryMemoryEntity
import com.example.data.model.StoryMemoryReturnEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryMemoryDao {
    @Query("SELECT * FROM story_memories WHERE chatId = :chatId ORDER BY createdAtEpochMillis, memoryId")
    fun observeForChat(chatId: String): Flow<List<StoryMemoryEntity>>

    @Query("SELECT * FROM story_memories WHERE chatId = :chatId ORDER BY createdAtEpochMillis, memoryId")
    suspend fun forChat(chatId: String): List<StoryMemoryEntity>

    @Query("SELECT * FROM story_memories WHERE memoryId = :memoryId LIMIT 1")
    suspend fun get(memoryId: String): StoryMemoryEntity?

    @Query("SELECT * FROM story_memories ORDER BY chatId, createdAtEpochMillis, memoryId")
    suspend fun getAll(): List<StoryMemoryEntity>

    @Query("SELECT * FROM story_memory_actors ORDER BY memoryId, actorKind, actorId, relation")
    suspend fun getAllActors(): List<StoryMemoryActorEntity>

    @Query("SELECT * FROM story_memory_actors WHERE memoryId = :memoryId")
    suspend fun actors(memoryId: String): List<StoryMemoryActorEntity>

    @Query("SELECT * FROM story_memory_returns ORDER BY memoryId, resetBoundaryOrderIndex")
    suspend fun getAllReturns(): List<StoryMemoryReturnEntity>

    @Query("SELECT * FROM story_memory_returns WHERE memoryId = :memoryId")
    suspend fun returns(memoryId: String): List<StoryMemoryReturnEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(memory: StoryMemoryEntity)

    @Update
    suspend fun update(memory: StoryMemoryEntity)

    @Delete
    suspend fun delete(memory: StoryMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActor(actor: StoryMemoryActorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReturn(grant: StoryMemoryReturnEntity)

    @Query("DELETE FROM story_memory_returns WHERE memoryId = :memoryId AND resetBoundaryOrderIndex = :boundary")
    suspend fun revokeReturn(memoryId: String, boundary: Long)

    @Query("DELETE FROM story_memories")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(memories: List<StoryMemoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActors(actors: List<StoryMemoryActorEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReturns(returns: List<StoryMemoryReturnEntity>)
}
