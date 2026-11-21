package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Campaigns are PRIVATE playthroughs within a reusable World.
 * A World is shared setting canon; sibling Campaigns never inherit one another's events.
 * RESTRICT stops World deletion from silently erasing its Campaign organization.
 */
@Entity(
    tableName = "campaigns",
    foreignKeys = [ForeignKey(
        entity = WorldEntity::class,
        parentColumns = ["worldId"],
        childColumns = ["worldId"],
        onDelete = ForeignKey.RESTRICT
    )],
    indices = [Index("worldId")]
)
data class CampaignEntity(
    @PrimaryKey val campaignId: String = UUID.randomUUID().toString(),
    val worldId: String,
    val title: String,
    val summary: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * An episode is a LINK to an existing chat, not a copy of its messages or variants.
 * Every chat may belong to at most ONE Campaign. Deleting Campaign organization
 * removes these links without deleting the conversation.
 */
@Entity(
    tableName = "campaign_chapters",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["campaignId"],
            childColumns = ["campaignId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["chatId"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("campaignId"),
        Index(value = ["chatId"], unique = true),
        Index(value = ["campaignId", "ordinal"], unique = true)
    ]
)
data class CampaignChapterEntity(
    @PrimaryKey val chapterId: String = UUID.randomUUID().toString(),
    val campaignId: String,
    val chatId: String,
    val ordinal: Long,
    val title: String,
    val recap: String = "",
    val state: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
