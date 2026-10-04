package com.example.data

import androidx.room.*
import com.example.data.model.PersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun getAllPersonas(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE personaId = :id")
    suspend fun getPersonaById(id: String): PersonaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPersona(persona: PersonaEntity)

    @Update
    suspend fun updatePersona(persona: PersonaEntity)

    @Query("UPDATE personas SET isArchived = 1 WHERE personaId = :id")
    suspend fun archivePersona(id: String)

    @Query("SELECT DISTINCT chatId FROM chat_persona_participants WHERE personaId = :id")
    suspend fun getReferencedChatIds(id: String): List<String>

    @Query("DELETE FROM chat_persona_participants WHERE personaId = :id")
    suspend fun deleteChatParticipants(id: String)

    @Query("SELECT COUNT(*) FROM chat_persona_participants WHERE chatId = :chatId AND isActive = 1")
    suspend fun countActivePersonas(chatId: String): Int

    @Query("DELETE FROM personas WHERE personaId = :id")
    suspend fun deletePersonaById(id: String)

    @Transaction
    suspend fun deletePersonaPermanently(id: String): ProfileDeleteOutcome {
        if (getPersonaById(id) == null) return ProfileDeleteOutcome(existed = false)

        val referencedChatIds = getReferencedChatIds(id)
        deleteChatParticipants(id)
        deletePersonaById(id)
        val historyOnlyChatCount = referencedChatIds.count { countActivePersonas(it) == 0 }
        return ProfileDeleteOutcome(
            existed = true,
            affectedChatCount = referencedChatIds.size,
            historyOnlyChatCount = historyOnlyChatCount
        )
    }
}
