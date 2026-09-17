package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_summaries")
data class ConversationSummaryEntity(
    @PrimaryKey val chatId: String,
    val summary: String,
    val summarizedThroughOrderIndex: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

