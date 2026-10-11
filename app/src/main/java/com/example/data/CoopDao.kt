package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.CoopEventEntity
import com.example.data.model.CoopParticipantEntity
import com.example.data.model.CoopPersonaOwnershipEntity
import com.example.data.model.CoopRoomEntity
import com.example.data.model.CoopMessageTombstoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CoopDao {
    @Query("SELECT * FROM coop_rooms WHERE chatId = :chatId LIMIT 1")
    fun observeRoomForChat(chatId: String): Flow<CoopRoomEntity?>

    @Query("SELECT * FROM coop_rooms WHERE chatId = :chatId LIMIT 1")
    suspend fun getRoomForChat(chatId: String): CoopRoomEntity?

    @Query("SELECT * FROM coop_rooms WHERE roomId = :roomId LIMIT 1")
    suspend fun getRoom(roomId: String): CoopRoomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoom(room: CoopRoomEntity)

    @Update
    suspend fun updateRoom(room: CoopRoomEntity)

    @Query("SELECT * FROM coop_participants WHERE roomId = :roomId ORDER BY sortPosition, joinedAt, participantId")
    fun observeParticipants(roomId: String): Flow<List<CoopParticipantEntity>>

    @Query("SELECT * FROM coop_participants WHERE roomId = :roomId ORDER BY sortPosition, joinedAt, participantId")
    suspend fun getParticipants(roomId: String): List<CoopParticipantEntity>

    @Query("SELECT * FROM coop_participants WHERE participantId = :participantId LIMIT 1")
    suspend fun getParticipant(participantId: String): CoopParticipantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipant(participant: CoopParticipantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipants(participants: List<CoopParticipantEntity>)

    @Query("UPDATE coop_participants SET isConnected = :connected, lastSeenAt = :seenAt WHERE participantId = :participantId")
    suspend fun setParticipantConnected(participantId: String, connected: Boolean, seenAt: Long)

    @Query("UPDATE coop_participants SET isConnected = 0, lastSeenAt = :seenAt WHERE roomId = :roomId")
    suspend fun markRoomParticipantsDisconnected(roomId: String, seenAt: Long)

    @Query("SELECT * FROM coop_persona_ownership WHERE roomId = :roomId ORDER BY sortPosition, personaId")
    fun observePersonaOwnerships(roomId: String): Flow<List<CoopPersonaOwnershipEntity>>

    @Query("SELECT * FROM coop_persona_ownership WHERE roomId = :roomId ORDER BY sortPosition, personaId")
    suspend fun getPersonaOwnerships(roomId: String): List<CoopPersonaOwnershipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersonaOwnership(ownership: CoopPersonaOwnershipEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersonaOwnerships(ownerships: List<CoopPersonaOwnershipEntity>)

    @Query("SELECT * FROM coop_events WHERE roomId = :roomId AND clientEventId = :clientEventId LIMIT 1")
    suspend fun getEventByClientId(roomId: String, clientEventId: String): CoopEventEntity?

    @Query("SELECT * FROM coop_events WHERE eventId = :eventId LIMIT 1")
    suspend fun getEventById(eventId: String): CoopEventEntity?

    @Query("SELECT * FROM coop_events WHERE roomId = :roomId AND sequence > :after ORDER BY sequence LIMIT :limit")
    suspend fun getEventsAfter(roomId: String, after: Long, limit: Int): List<CoopEventEntity>

    @Query("SELECT * FROM coop_events WHERE roomId = :roomId AND eventType = :eventType ORDER BY sequence DESC LIMIT 1")
    suspend fun getLatestEventOfType(roomId: String, eventType: String): CoopEventEntity?

    @Query("SELECT * FROM coop_events WHERE roomId = :roomId AND eventType = :eventType ORDER BY sequence")
    suspend fun getEventsOfType(roomId: String, eventType: String): List<CoopEventEntity>

    @Query("SELECT * FROM coop_events WHERE roomId = :roomId AND eventType IN (:eventTypes) ORDER BY sequence DESC LIMIT 1")
    suspend fun getLatestEventOfTypes(roomId: String, eventTypes: List<String>): CoopEventEntity?

    @Query("SELECT COUNT(*) FROM coop_events WHERE roomId = :roomId")
    suspend fun countEvents(roomId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: CoopEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageTombstone(tombstone: CoopMessageTombstoneEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM coop_message_tombstones WHERE roomId = :roomId AND messageId = :messageId)")
    suspend fun isMessageTombstoned(roomId: String, messageId: String): Boolean

    @Query("DELETE FROM coop_events WHERE roomId = :roomId AND sequence < :beforeSequence")
    suspend fun trimEventsBefore(roomId: String, beforeSequence: Long)

    @Query("DELETE FROM coop_persona_ownership WHERE roomId = :roomId")
    suspend fun deleteOwnerships(roomId: String)

    @Query("DELETE FROM coop_participants WHERE roomId = :roomId")
    suspend fun deleteParticipants(roomId: String)

    @Query("DELETE FROM coop_events WHERE roomId = :roomId")
    suspend fun deleteEvents(roomId: String)

    @Query("DELETE FROM coop_message_tombstones WHERE roomId = :roomId")
    suspend fun deleteMessageTombstones(roomId: String)

    @Query("DELETE FROM coop_rooms WHERE roomId = :roomId")
    suspend fun deleteRoomRecord(roomId: String)

    @Transaction
    suspend fun deleteRoom(roomId: String) {
        deleteEvents(roomId)
        deleteMessageTombstones(roomId)
        deleteOwnerships(roomId)
        deleteParticipants(roomId)
        deleteRoomRecord(roomId)
    }
}
