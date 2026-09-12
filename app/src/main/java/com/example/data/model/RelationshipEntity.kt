package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "relationships")
data class RelationshipEntity(
    @PrimaryKey val relationshipId: String = UUID.randomUUID().toString(),
    val sourceCharacterId: String,
    val targetCharacterId: String,
    val targetNameSnapshot: String,
    val relationshipType: String,
    val dynamic: String,
    val status: String,
    val scopeChatId: String? = null // null if reusable
)
