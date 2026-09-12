package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val personaId: String = UUID.randomUUID().toString(),
    val displayName: String,
    val personaName: String,
    val aliases: List<String>,
    val importSchemaVersion: Int,
    val identityJson: String?,
    val appearanceJson: String?,
    val personalityJson: String?,
    val roleplayProfileJson: String?,
    val backgroundJson: String?,
    val worldContextJson: String?,
    val privateNotesJson: String?,
    val extensionFieldsJson: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false
)
