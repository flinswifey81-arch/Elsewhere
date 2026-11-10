package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Stage 1 chat-local story memory. The old roleplay_memories and summaries
 * remain separate and unchanged; no implicit World/Campaign ownership.
 * Message provenance has NO cascading FK to messages: a deleted source must
 * make a memory ineligible, never silently erase its historical record.
 */
@Entity(
    tableName = "story_memories",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class,
        parentColumns = ["chatId"], childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("chatId"), Index("sourceMessageId")]
)
data class StoryMemoryEntity(
    @PrimaryKey val memoryId: String = UUID.randomUUID().toString(),
    val chatId: String,
    val ownerId: String,
    val scope: String = "CHAT",
    val branchId: String? = null,
    val content: String,
    val claim: String = "FACT",
    val visibility: String = "STORYTELLER_ONLY",
    val review: String = "APPROVED",
    val lifecycle: String = "ACTIVE",
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val originKind: String = "MANUAL",
    val sourceMessageId: String? = null,
    val sourceVariantGroupId: String? = null,
    val sourceOrderIndex: Long,
    val sourceSpeakerType: String? = null,
    val sourceSpeakerId: String? = null,
    val sourceCreatedAtEpochMillis: Long? = null,
    val sourceSha256: String? = null,
    val believedByKind: String? = null,
    val believedById: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
    val userEditedAt: Long? = null
)

/** Actor IDs are typed; merely mentioned actors do not get knowledge access. */
@Entity(
    tableName = "story_memory_actors",
    primaryKeys = ["memoryId", "actorKind", "actorId", "relation"],
    foreignKeys = [ForeignKey(
        entity = StoryMemoryEntity::class,
        parentColumns = ["memoryId"], childColumns = ["memoryId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class StoryMemoryActorEntity(
    val memoryId: String,
    val actorKind: String,
    val actorId: String,
    val relation: String // KNOWS or MENTIONED
)

/** User-initiated restoration applies only to this memory and exact Wack. */
@Entity(
    tableName = "story_memory_returns",
    primaryKeys = ["memoryId", "resetBoundaryOrderIndex"],
    foreignKeys = [ForeignKey(
        entity = StoryMemoryEntity::class,
        parentColumns = ["memoryId"], childColumns = ["memoryId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class StoryMemoryReturnEntity(
    val memoryId: String,
    val resetBoundaryOrderIndex: Long,
    val chatId: String,
    val branchId: String? = null,
    val approvedAtOrderIndex: Long,
    val initiatedByUser: Boolean
)
