package com.example.data

import androidx.room.*
import com.example.data.model.CharacterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun getAllCharacters(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE characterId = :id")
    suspend fun getCharacterById(id: String): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacter(character: CharacterEntity)

    @Update
    suspend fun updateCharacter(character: CharacterEntity)

    @Query("UPDATE characters SET isArchived = 1 WHERE characterId = :id")
    suspend fun archiveCharacter(id: String)

    @Query("SELECT DISTINCT chatId FROM chat_participants WHERE characterId = :id")
    suspend fun getReferencedChatIds(id: String): List<String>

    @Query("DELETE FROM chat_participants WHERE characterId = :id")
    suspend fun deleteChatParticipants(id: String)

    @Query("SELECT COUNT(*) FROM chat_participants WHERE chatId = :chatId AND isActive = 1")
    suspend fun countActiveCharacters(chatId: String): Int

    @Query("DELETE FROM relationships WHERE sourceCharacterId = :id OR targetCharacterId = :id")
    suspend fun deleteRelationshipsForCharacter(id: String)

    @Query("DELETE FROM roleplay_memories WHERE characterId = :id")
    suspend fun deleteMemoriesForCharacter(id: String)

    @Query("DELETE FROM characters WHERE characterId = :id")
    suspend fun deleteCharacterById(id: String)

    @Transaction
    suspend fun deleteCharacterPermanently(id: String): ProfileDeleteOutcome {
        if (getCharacterById(id) == null) return ProfileDeleteOutcome(existed = false)

        val referencedChatIds = getReferencedChatIds(id)
        deleteChatParticipants(id)
        deleteRelationshipsForCharacter(id)
        deleteMemoriesForCharacter(id)
        deleteCharacterById(id)
        val historyOnlyChatCount = referencedChatIds.count { countActiveCharacters(it) == 0 }
        return ProfileDeleteOutcome(
            existed = true,
            affectedChatCount = referencedChatIds.size,
            historyOnlyChatCount = historyOnlyChatCount
        )
    }
}
