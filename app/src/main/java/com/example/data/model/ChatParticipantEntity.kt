package com.example.data.model

import androidx.room.Entity

@Entity(
    tableName = "chat_participants",
    primaryKeys = ["chatId", "characterId"]
)
data class ChatParticipantEntity(
    val chatId: String,
    val characterId: String,
    val joinedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val sortPosition: Int = 0
)

@Entity(
    tableName = "chat_persona_participants",
    primaryKeys = ["chatId", "personaId"]
)
data class ChatPersonaParticipantEntity(
    val chatId: String,
    val personaId: String,
    val joinedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val sortPosition: Int = 0
)
