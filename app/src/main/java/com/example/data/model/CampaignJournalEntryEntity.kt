package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Player-authored Campaign timeline. CHECKPOINT records link to the selected
 * message variant at save time; JOURNAL entries may have no source. The
 * original transcript is never duplicated into this table or model context.
 *
 * Deleting a chapter or source message preserves the Campaign's journal note,
 * but clears the live FK reference so the UI can flag orphaned provenance.
 * Deleting the Campaign itself intentionally removes its private journal.
 */
@Entity(
    tableName = "campaign_journal_entries",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["campaignId"],
            childColumns = ["campaignId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CampaignChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["sourceMessageId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("campaignId"),
        Index("chapterId"),
        Index("sourceMessageId")
    ]
)
data class CampaignJournalEntryEntity(
    @PrimaryKey val journalEntryId: String = UUID.randomUUID().toString(),
    val campaignId: String,
    val chapterId: String? = null,
    // Last-known provenance remains descriptive when chapter/message is deleted.
    val sourceChatId: String? = null,
    val sourceMessageId: String? = null,
    val sourceVariantGroupId: String? = null,
    val sourceOrderIndex: Long? = null,
    val sourceContentSha256: String? = null,
    val entryType: String = "JOURNAL",
    val title: String,
    val body: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
