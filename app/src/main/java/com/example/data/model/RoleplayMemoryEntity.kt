package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class MemoryCategory {
    IDENTITY,
    RELATIONSHIP,
    PREFERENCE,
    EVENT,
    PROMISE,
    BOUNDARY,
    LOCATION,
    DETAIL
}

@Entity(
    tableName = "roleplay_memories",
    indices = [Index("chatId"), Index("characterId")]
)
data class RoleplayMemoryEntity(
    @PrimaryKey val memoryId: String = UUID.randomUUID().toString(),
    val chatId: String,
    val characterId: String,
    val category: MemoryCategory,
    val content: String,
    val sourceMessageId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

