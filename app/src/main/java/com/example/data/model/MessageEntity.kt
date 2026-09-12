package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class SpeakerType { PERSONA, CHARACTER, SYSTEM, DIRECTOR }

@Entity(tableName = "messages")
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
    val isPrimaryVariant: Boolean = true
)
