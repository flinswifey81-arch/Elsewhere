package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Append-only log of the player's explicit character knowledge assignments.
 *
 * This is NOT an automatically inferred story event, and a revoked permission
 * is not proof that a fictional character forgot something. The canonical
 * current generation authorization remains story_memory_actors[KNOWS].
 * The log is chat-local through its memory FK, is never injected into prompts,
 * and is retained with the memory's backup.
 */
@Entity(
    tableName = "story_memory_knowledge_changes",
    primaryKeys = ["memoryId", "revision", "characterId"],
    foreignKeys = [ForeignKey(
        entity = StoryMemoryEntity::class,
        parentColumns = ["memoryId"],
        childColumns = ["memoryId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("characterId")]
)
data class StoryMemoryKnowledgeChangeEntity(
    val memoryId: String,
    val revision: Long,
    val characterId: String,
    val change: String, // GRANTED or REVOKED
    val latestMessageOrderIndex: Long? = null,
    val wackBoundaryOrderIndex: Long? = null,
    val recordedAtEpochMillis: Long = System.currentTimeMillis()
)
