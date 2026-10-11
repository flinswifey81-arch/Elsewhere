package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

object CoopRole {
    const val HOST = "HOST"
    const val CLIENT = "CLIENT"
}

object CoopConnectionState {
    const val DISCONNECTED = "DISCONNECTED"
    const val CONNECTING = "CONNECTING"
    const val CONNECTED = "CONNECTED"
    const val SUSPENDED_HOST_OFFLINE = "SUSPENDED_HOST_OFFLINE"
}

object CoopPayerMode {
    const val EACH_PARTICIPANT = "EACH_PARTICIPANT"
    const val HOST = "HOST"
    const val SELECTED_PARTICIPANT = "SELECTED_PARTICIPANT"

    val all = setOf(EACH_PARTICIPANT, HOST, SELECTED_PARTICIPANT)
}

object CoopEventType {
    const val MESSAGE_UPSERT = "MESSAGE_UPSERT"
    const val MESSAGE_DELETE = "MESSAGE_DELETE"
    const val PARTICIPANT_UPSERT = "PARTICIPANT_UPSERT"
    const val PARTICIPANT_LEFT = "PARTICIPANT_LEFT"
    const val PERSONA_OWNERSHIP_SET = "PERSONA_OWNERSHIP_SET"
    const val CHARACTER_ROSTER_SET = "CHARACTER_ROSTER_SET"
    const val PAYER_POLICY_SET = "PAYER_POLICY_SET"
    const val GENERATION_REQUESTED = "GENERATION_REQUESTED"
    const val GENERATION_APPROVED = "GENERATION_APPROVED"
    const val GENERATION_EXECUTING = "GENERATION_EXECUTING"
    const val GENERATION_FINISHED = "GENERATION_FINISHED"
    const val GENERATION_FAILED = "GENERATION_FAILED"

    val all = setOf(
        MESSAGE_UPSERT,
        MESSAGE_DELETE,
        PARTICIPANT_UPSERT,
        PARTICIPANT_LEFT,
        PERSONA_OWNERSHIP_SET,
        CHARACTER_ROSTER_SET,
        PAYER_POLICY_SET,
        GENERATION_REQUESTED,
        GENERATION_APPROVED,
        GENERATION_EXECUTING,
        GENERATION_FINISHED,
        GENERATION_FAILED
    )
}

@Entity(
    tableName = "coop_rooms",
    indices = [Index(value = ["chatId"], unique = true)]
)
data class CoopRoomEntity(
    @PrimaryKey val roomId: String = UUID.randomUUID().toString(),
    val chatId: String,
    val localRole: String,
    val hostParticipantId: String,
    val localParticipantId: String,
    val payerMode: String = CoopPayerMode.EACH_PARTICIPANT,
    val selectedPayerParticipantId: String? = null,
    val hostAddress: String? = null,
    val hostPort: Int = 42424,
    val hostPublicKey: String = "",
    val hostKeyFingerprint: String = "",
    val pairingSalt: String = "",
    val pairingCodeHash: String = "",
    val connectionState: String = CoopConnectionState.DISCONNECTED,
    val orderBase: Long = System.currentTimeMillis(),
    val nextHostSequence: Long = 1L,
    val lastAppliedSequence: Long = 0L,
    val activeGenerationRequestId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "coop_participants",
    indices = [Index("roomId"), Index("deviceId")]
)
data class CoopParticipantEntity(
    @PrimaryKey val participantId: String = UUID.randomUUID().toString(),
    val roomId: String,
    val deviceId: String,
    @ColumnInfo(defaultValue = "''") val identityPublicKey: String = "",
    val displayName: String,
    val isHost: Boolean = false,
    val isConnected: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val sortPosition: Int = 0
)

@Entity(
    tableName = "coop_persona_ownership",
    primaryKeys = ["roomId", "personaId"],
    indices = [Index("participantId")]
)
data class CoopPersonaOwnershipEntity(
    val roomId: String,
    val personaId: String,
    val participantId: String,
    val personaDisplayNameSnapshot: String,
    val isActive: Boolean = true,
    val sortPosition: Int = 0
)

@Entity(
    tableName = "coop_events",
    indices = [
        Index(value = ["roomId", "sequence"], unique = true),
        Index(value = ["roomId", "clientEventId"], unique = true)
    ]
)
data class CoopEventEntity(
    @PrimaryKey val eventId: String = UUID.randomUUID().toString(),
    val roomId: String,
    val sequence: Long,
    val clientEventId: String,
    val originParticipantId: String,
    val eventType: String,
    val payloadJson: String,
    @ColumnInfo(defaultValue = "''") val originPayloadJson: String = "",
    @ColumnInfo(defaultValue = "''") val originSignature: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "coop_message_tombstones",
    primaryKeys = ["roomId", "messageId"],
    indices = [Index("messageId")]
)
data class CoopMessageTombstoneEntity(
    val roomId: String,
    val messageId: String,
    val deletedSequence: Long,
    val deletedAt: Long
)

data class CoopRoomSnapshot(
    val room: CoopRoomEntity,
    val participants: List<CoopParticipantEntity>,
    val personaOwnerships: List<CoopPersonaOwnershipEntity>
)
