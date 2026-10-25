package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "npc_dossiers",
    indices = [Index(value = ["chatId"])]
)
data class NpcDossierEntity(
    @PrimaryKey val npcId: String = UUID.randomUUID().toString(),
    val chatId: String,
    val displayName: String,
    val aliasesJson: String = "[]",
    val role: String = "",
    val appearance: String = "",
    val personality: String = "",
    /** Legacy/general storyteller summary; scoped Persona relationships live in personaRelationshipsJson. */
    val relationshipToPersona: String = "",
    val personaRelationshipsJson: String = "{}",
    /** Character-id keyed knowledge. Narrative secrets are not copied here automatically. */
    val characterKnowledgeJson: String = "{}",
    val goals: String = "",
    /** May enter provider context only as storyteller continuity; never as speaker knowledge or in share-safe export. */
    val secrets: String = "",
    /** Local-only private notes; never sent to a model or placed in exports by default. */
    val authorNotes: String = "",
    val keepInScene: Boolean = false,
    val isManualBase: Boolean = true,
    val extensionFieldsJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "npc_bank_settings")
data class NpcBankSettingsEntity(
    @PrimaryKey val chatId: String,
    val isEnabled: Boolean = false
)

@Entity(
    tableName = "npc_dossier_updates",
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["npcId"]),
        Index(value = ["sourceMessageId"]),
        Index(value = ["variantGroupId"]),
        Index(value = ["sourceMessageId", "sourceRevision", "npcId", "fieldName"], unique = true)
    ]
)
data class NpcDossierUpdateEntity(
    @PrimaryKey val updateId: String = UUID.randomUUID().toString(),
    val chatId: String,
    val npcId: String,
    val sourceMessageId: String,
    val variantGroupId: String,
    /** Stable story position of the source response; regeneration must not move it later. */
    val sourceOrderIndex: Long,
    /** Continue appends another independently reversible update batch to the same message. */
    val sourceRevision: Int,
    val fieldName: String,
    val oldValue: String?,
    val newValue: String,
    val isApplied: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

data class NpcDossier(
    val npcId: String,
    val chatId: String,
    val displayName: String,
    val aliases: List<String>,
    val role: String,
    val appearance: String,
    val personality: String,
    val relationshipToPersona: String,
    val personaRelationships: Map<String, String>,
    val characterKnowledge: Map<String, String>,
    val goals: String,
    val secrets: String,
    val authorNotes: String,
    val keepInScene: Boolean,
    val isManualBase: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "npc_library_banks")
data class NpcLibraryBankEntity(
    @PrimaryKey val bankId: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "npc_library_entries",
    indices = [Index(value = ["bankId"])]
)
data class NpcLibraryEntryEntity(
    @PrimaryKey val libraryNpcId: String = UUID.randomUUID().toString(),
    val bankId: String,
    val displayName: String,
    val aliasesJson: String = "[]",
    val role: String = "",
    val appearance: String = "",
    val personality: String = "",
    val relationshipToPersona: String = "",
    val personaRelationshipsJson: String = "{}",
    val characterKnowledgeJson: String = "{}",
    val goals: String = "",
    val secrets: String = "",
    val authorNotes: String = "",
    val keepInScene: Boolean = false,
    val extensionFieldsJson: String? = null,
    val sortPosition: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
