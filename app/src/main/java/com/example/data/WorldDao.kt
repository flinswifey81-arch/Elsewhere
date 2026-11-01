package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldDao {
    @Query("SELECT * FROM worlds ORDER BY updatedAt DESC, worldId")
    fun observeWorlds(): Flow<List<WorldEntity>>

    @Query("SELECT * FROM worlds ORDER BY worldId")
    suspend fun getWorlds(): List<WorldEntity>

    @Query("SELECT * FROM worlds WHERE worldId = :worldId")
    suspend fun getWorld(worldId: String): WorldEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorld(world: WorldEntity)

    @Update
    suspend fun updateWorld(world: WorldEntity): Int

    @Query("DELETE FROM worlds WHERE worldId = :worldId")
    suspend fun deleteWorld(worldId: String): Int

    @Query("SELECT * FROM world_memberships WHERE worldId = :worldId ORDER BY resourceType, resourceId")
    suspend fun getMemberships(worldId: String): List<WorldMembershipEntity>

    @Query("SELECT * FROM world_memberships ORDER BY worldId, resourceType, resourceId")
    suspend fun getAllMemberships(): List<WorldMembershipEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembership(member: WorldMembershipEntity)

    @Query("DELETE FROM world_memberships WHERE worldId = :worldId AND resourceType = :type AND resourceId = :resourceId")
    suspend fun deleteMembership(worldId: String, type: String, resourceId: String)

    @Query("SELECT * FROM world_cast WHERE worldId = :worldId ORDER BY name COLLATE NOCASE, worldCharacterId")
    suspend fun getCast(worldId: String): List<WorldCastEntity>

    @Query("SELECT * FROM world_cast ORDER BY worldId, worldCharacterId")
    suspend fun getAllCast(): List<WorldCastEntity>

    @Query("SELECT * FROM world_cast WHERE worldCharacterId = :id")
    suspend fun getCastMember(id: String): WorldCastEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCastMember(member: WorldCastEntity)

    @Update
    suspend fun updateCastMember(member: WorldCastEntity): Int

    @Query("SELECT * FROM world_continuity_events WHERE worldId = :worldId ORDER BY createdAt, eventId")
    suspend fun getEvents(worldId: String): List<WorldContinuityEventEntity>

    @Query("SELECT * FROM world_continuity_events ORDER BY worldId, eventId")
    suspend fun getAllEvents(): List<WorldContinuityEventEntity>

    @Query("SELECT * FROM world_continuity_events WHERE eventId = :id")
    suspend fun getEvent(id: String): WorldContinuityEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: WorldContinuityEventEntity)

    @Update
    suspend fun updateEvent(event: WorldContinuityEventEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorlds(worlds: List<WorldEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemberships(members: List<WorldMembershipEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCast(members: List<WorldCastEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvents(events: List<WorldContinuityEventEntity>)

    @Query("DELETE FROM worlds")
    suspend fun clearWorlds()

    @Query("SELECT EXISTS(SELECT 1 FROM lorebooks WHERE lorebookId = :id)")
    suspend fun hasLorebook(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM characters WHERE characterId = :id)")
    suspend fun hasCharacter(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM personas WHERE personaId = :id)")
    suspend fun hasPersona(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM scenarios WHERE scenarioId = :id)")
    suspend fun hasScenario(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chats WHERE chatId = :id)")
    suspend fun hasChat(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM npc_library_banks WHERE bankId = :id)")
    suspend fun hasNpcLibraryBank(id: String): Boolean
}
