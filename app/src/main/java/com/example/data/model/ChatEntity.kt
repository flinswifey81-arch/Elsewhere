package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class ChatType { SOLO, GROUP }

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String = UUID.randomUUID().toString(),
    val displayName: String,
    val chatType: ChatType,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
