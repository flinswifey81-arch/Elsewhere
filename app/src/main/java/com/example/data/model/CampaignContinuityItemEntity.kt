package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Player-authored long-running Campaign continuity.
 *
 * ARC: ACTIVE or COMPLETE, with an editable summary.
 * THREAD/PROMISE/QUEST/DISCOVERY/RELATIONSHIP: OPEN or RESOLVED, with an
 * optional closing note. This is organization, not RPG gameplay.
 *
 * All records are private to ONE Campaign. Null-on-delete source references
 * preserve the authored record if a chapter or journal bookmark is removed.
 * Nothing here becomes World Canon or model context without explicit review.
 */
@Entity(
    tableName = "campaign_continuity_items",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["campaignId"], childColumns = ["campaignId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CampaignChapterEntity::class,
            parentColumns = ["chapterId"], childColumns = ["openedChapterId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = CampaignChapterEntity::class,
            parentColumns = ["chapterId"], childColumns = ["closedChapterId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = CampaignJournalEntryEntity::class,
            parentColumns = ["journalEntryId"], childColumns = ["sourceJournalEntryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("campaignId"),
        Index("openedChapterId"),
        Index("closedChapterId"),
        Index("sourceJournalEntryId")
    ]
)
data class CampaignContinuityItemEntity(
    @PrimaryKey val itemId: String = UUID.randomUUID().toString(),
    val campaignId: String,
    val itemType: String,
    val title: String,
    val details: String = "",
    val status: String = "OPEN",
    val resolution: String = "",
    val openedChapterId: String? = null,
    val closedChapterId: String? = null,
    val sourceJournalEntryId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
