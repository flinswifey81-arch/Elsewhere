package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val characterId: String = UUID.randomUUID().toString(),
    val displayName: String,
    val characterName: String,
    val aliases: List<String>,
    val importSchemaVersion: Int,
    val identityJson: String?,
    val appearanceJson: String?,
    val personalityJson: String?,
    val voiceJson: String?,
    val behaviorJson: String?,
    val backstoryJson: String?,
    val knowledgeJson: String?,
    val worldContextJson: String?,
    val writingRulesJson: String?,
    val examplesJson: String?,
    val authorNotesJson: String?,
    val extensionFieldsJson: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false
)
