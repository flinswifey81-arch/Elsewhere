package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A player-confirmed pointer from ONE Campaign to ONE existing chat-local
 * Story Memory. The original memory/actors/knowledge and transcript are never
 * moved or rewritten. A content SHA-256 snapshot invalidates this approval
 * when the player later edits the source memory.
 *
 * Source chapter or memory deletion REVOKES the share, never erases the chat.
 * Only LATER chapters inside the same Campaign may read it, after independent
 * source-variant, source-Wack, destination-Wack and recipient-knowledge checks.
 */
@Entity(
    tableName = "campaign_memory_shares",
    primaryKeys = ["campaignId", "sourceMemoryId"],
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["campaignId"], childColumns = ["campaignId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = StoryMemoryEntity::class,
            parentColumns = ["memoryId"], childColumns = ["sourceMemoryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CampaignChapterEntity::class,
            parentColumns = ["chapterId"], childColumns = ["sourceChapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sourceMemoryId"), Index("sourceChapterId")]
)
data class CampaignMemoryShareEntity(
    val campaignId: String,
    val sourceMemoryId: String,
    val sourceChapterId: String,
    val sourceContentSha256: String,
    val approvedAt: Long = System.currentTimeMillis()
)
