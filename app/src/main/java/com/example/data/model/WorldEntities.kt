package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** World is a setting/library identity, not a chat or a second Lorebook. */
@Entity(tableName = "worlds")
data class WorldEntity(
    @PrimaryKey val worldId: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val settingSummary: String = "",
    val worldRules: String = "",
    val genreTagsJson: String = "[]",
    /** Versioned World definition links; existing chats and source cards stay independent. */
    val linksJson: String = """{"cast_roles":[],"relationships":[]}""",
    val defaultLorebookIdsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A typed link to an existing resource. Deleting a World never deletes the linked resource. */
@Entity(
    tableName = "world_memberships",
    primaryKeys = ["worldId", "resourceType", "resourceId"],
    foreignKeys = [ForeignKey(
        entity = WorldEntity::class,
        parentColumns = ["worldId"],
        childColumns = ["worldId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("worldId")]
)
data class WorldMembershipEntity(
    val worldId: String,
    val resourceType: String,
    val resourceId: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class WorldResourceType {
    LOREBOOK, CHARACTER, PERSONA, SCENARIO, CHAT, NPC_LIBRARY_BANK
}

/** One in-world identity, potentially linked to multiple distinct acting roles later. */
@Entity(
    tableName = "world_cast",
    foreignKeys = [ForeignKey(
        entity = WorldEntity::class,
        parentColumns = ["worldId"],
        childColumns = ["worldId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("worldId")]
)
data class WorldCastEntity(
    @PrimaryKey val worldCharacterId: String = UUID.randomUUID().toString(),
    val worldId: String,
    val name: String,
    val aliasesJson: String = "[]",
    val identity: String = "",
    val appearance: String = "",
    val personality: String = "",
    val voice: String = "",
    val backstory: String = "",
    val motivations: String = "",
    val knowledgeLimits: String = "",
    val role: String = "",
    val provenanceKind: String = "manual",
    val provenanceId: String? = null,
    val lockedFieldsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** DRAFT events do not modify World Canon; promotion to ACCEPTED is explicit. */
@Entity(
    tableName = "world_continuity_events",
    foreignKeys = [ForeignKey(
        entity = WorldEntity::class,
        parentColumns = ["worldId"],
        childColumns = ["worldId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("worldId")]
)
data class WorldContinuityEventEntity(
    @PrimaryKey val eventId: String = UUID.randomUUID().toString(),
    val worldId: String,
    val title: String,
    val facts: String,
    val status: String = "DRAFT",
    val sourceChatId: String? = null,
    val relatedCastIdsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
