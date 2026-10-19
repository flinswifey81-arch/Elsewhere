package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class SpeakerType { PERSONA, CHARACTER, SYSTEM, DIRECTOR, NARRATOR }

@Entity(
    tableName = "messages",
    indices = [Index(value = ["coOpEventId"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey val messageId: String = UUID.randomUUID().toString(),
    val chatId: String,
    val speakerType: SpeakerType,
    val speakerId: String,
    val speakerDisplayNameSnapshot: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val editedAt: Long? = null,
    val orderIndex: Long,
    val variantGroupId: String = messageId,
    val isPrimaryVariant: Boolean = true,
    val responsibleParticipantId: String? = null,
    val responsibleDeviceId: String? = null,
    val coOpEventId: String? = null
)
