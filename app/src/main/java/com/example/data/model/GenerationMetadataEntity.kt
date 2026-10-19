package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "generation_metadata")
data class GenerationMetadataEntity(
    @PrimaryKey val messageId: String,
    val requestedModelId: String? = null,
    val resolvedModelId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val reportedCost: Double? = null,
    val finishReason: String? = null,
    val provider: String? = null,
    val generationTimeMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val isPartial: Boolean = false,
    @ColumnInfo(defaultValue = "0") val wasUserStopped: Boolean = false
)
