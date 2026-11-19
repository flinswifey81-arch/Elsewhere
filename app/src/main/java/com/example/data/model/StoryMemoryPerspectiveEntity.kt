package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Player-authored character perspective about one story memory.
 *
 * A revision is append-only; the most recent revision for a character is its
 * current perspective. No AI interpretation or automatic canon promotion.
 * This does not grant KNOWS access to an unauthorized character.
 */
@Entity(
    tableName = "story_memory_perspectives",
    primaryKeys = ["memoryId", "characterId", "revision"],
    foreignKeys = [
        ForeignKey(
            entity = StoryMemoryEntity::class,
            parentColumns = ["memoryId"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("characterId")]
)
data class StoryMemoryPerspectiveEntity(
    val memoryId: String,
    val characterId: String,
    val revision: Long,
    val stance: String, // KNOWS, BELIEVES, SUSPECTS, UNAWARE
    /** SHA-256 of the exact player-approved memory text when this state was set. */
    val contentSha256: String,
    val note: String? = null,
    val latestMessageOrderIndex: Long? = null,
    val wackBoundaryOrderIndex: Long? = null,
    val recordedAtEpochMillis: Long = System.currentTimeMillis()
)
