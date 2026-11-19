package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.StoryMemoryActorEntity
import com.example.data.model.StoryMemoryKnowledgeChangeEntity
import com.example.data.model.StoryMemoryPerspectiveEntity
import com.example.data.model.StoryMemoryEntity
import com.example.data.model.StoryMemoryReturnEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryMemoryDao {
    @Query("SELECT * FROM story_memories WHERE chatId = :chatId ORDER BY createdAtEpochMillis, memoryId")
    fun observeForChat(chatId: String): Flow<List<StoryMemoryEntity>>

    @Query("SELECT * FROM story_memories WHERE chatId = :chatId ORDER BY createdAtEpochMillis, memoryId")
    suspend fun forChat(chatId: String): List<StoryMemoryEntity>

    @Query("SELECT MAX(sourceOrderIndex) FROM story_memories WHERE chatId = :chatId")
    suspend fun maxSourceOrderForChat(chatId: String): Long?

    @Query("SELECT * FROM story_memories WHERE memoryId = :memoryId LIMIT 1")
    suspend fun get(memoryId: String): StoryMemoryEntity?

    @Query("SELECT * FROM story_memories ORDER BY chatId, createdAtEpochMillis, memoryId")
    suspend fun getAll(): List<StoryMemoryEntity>

    @Query("SELECT * FROM story_memory_actors ORDER BY memoryId, actorKind, actorId, relation")
    suspend fun getAllActors(): List<StoryMemoryActorEntity>

    @Query("SELECT * FROM story_memory_actors WHERE memoryId = :memoryId")
    suspend fun actors(memoryId: String): List<StoryMemoryActorEntity>

    @Query("SELECT a.* FROM story_memory_actors a INNER JOIN story_memories m ON a.memoryId = m.memoryId WHERE m.chatId = :chatId")
    suspend fun actorsForChat(chatId: String): List<StoryMemoryActorEntity>

    @Query("SELECT COALESCE(MAX(revision), 0) FROM story_memory_perspectives WHERE memoryId = :memoryId AND characterId = :characterId")
    suspend fun latestPerspectiveRevision(memoryId: String, characterId: String): Long

    @Query("SELECT * FROM story_memory_perspectives WHERE memoryId = :memoryId AND characterId = :characterId ORDER BY revision")
    suspend fun perspectiveHistory(memoryId: String, characterId: String): List<StoryMemoryPerspectiveEntity>

    @Query("SELECT * FROM story_memory_perspectives p WHERE p.memoryId = :memoryId AND p.revision = (SELECT MAX(q.revision) FROM story_memory_perspectives q WHERE q.memoryId = p.memoryId AND q.characterId = p.characterId) ORDER BY p.characterId")
    suspend fun currentPerspectivesForMemory(memoryId: String): List<StoryMemoryPerspectiveEntity>

    @Query("SELECT p.* FROM story_memory_perspectives p INNER JOIN story_memories m ON p.memoryId = m.memoryId WHERE m.chatId = :chatId AND p.revision = (SELECT MAX(q.revision) FROM story_memory_perspectives q WHERE q.memoryId = p.memoryId AND q.characterId = p.characterId)")
    suspend fun currentPerspectivesForChat(chatId: String): List<StoryMemoryPerspectiveEntity>

    @Query("SELECT * FROM story_memory_perspectives ORDER BY memoryId, characterId, revision")
    suspend fun getAllPerspectives(): List<StoryMemoryPerspectiveEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPerspective(entry: StoryMemoryPerspectiveEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPerspectives(entries: List<StoryMemoryPerspectiveEntity>)

    @Query("DELETE FROM story_memory_actors WHERE memoryId = :memoryId AND relation = 'KNOWS'")
    suspend fun clearKnowledgeForMemory(memoryId: String)

    @Query("SELECT COALESCE(MAX(revision), 0) FROM story_memory_knowledge_changes WHERE memoryId = :memoryId")
    suspend fun latestKnowledgeRevision(memoryId: String): Long

    @Query("SELECT * FROM story_memory_knowledge_changes WHERE memoryId = :memoryId ORDER BY revision, characterId")
    suspend fun knowledgeChangesForMemory(memoryId: String): List<StoryMemoryKnowledgeChangeEntity>

    @Query("SELECT * FROM story_memory_knowledge_changes ORDER BY memoryId, revision, characterId")
    suspend fun getAllKnowledgeChanges(): List<StoryMemoryKnowledgeChangeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKnowledgeChanges(changes: List<StoryMemoryKnowledgeChangeEntity>)

    @Query("SELECT * FROM story_memory_returns ORDER BY memoryId, resetBoundaryOrderIndex")
    suspend fun getAllReturns(): List<StoryMemoryReturnEntity>

    @Query("SELECT * FROM story_memory_returns WHERE memoryId = :memoryId")
    suspend fun returns(memoryId: String): List<StoryMemoryReturnEntity>

    @Query("SELECT * FROM story_memory_returns WHERE chatId = :chatId")
    suspend fun returnsForChat(chatId: String): List<StoryMemoryReturnEntity>

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
