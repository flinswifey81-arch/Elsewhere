package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.CampaignChapterEntity
import com.example.data.model.CampaignEntity
import com.example.data.model.WorldMembershipEntity
import com.example.data.model.WorldResourceType
import java.util.UUID

/**
 * Local Campaign organization only. No paid provider calls, automatic chapter-memory
 * sharing, World-canon promotion, or changes to saved messages / variants.
 */
class CampaignRepository(
    private val database: AppDatabase,
    private val coordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    private val dao get() = database.campaignDao()

    fun observeForWorld(worldId: String) = dao.observeForWorld(worldId)
    fun observeChapters(campaignId: String) = dao.observeChapters(campaignId)
    suspend fun forWorld(worldId: String) = dao.forWorld(worldId)
    suspend fun chapters(campaignId: String) = dao.chapters(campaignId)
    suspend fun getCampaign(campaignId: String) = dao.getCampaign(campaignId)

    suspend fun createCampaign(worldId: String, title: String): CampaignEntity =
        coordinator.withMutation {
            database.withTransaction {
                require(database.worldDao().getWorld(worldId) != null) { "World not found." }
                val name = title.trim()
                require(name.isNotBlank() && name.length <= 200) {
                    "Campaign title must contain 1–200 characters."
                }
                CampaignEntity(worldId = worldId, title = name).also(dao::insertCampaign)
            }
        }

    suspend fun renameCampaign(campaignId: String, title: String) = coordinator.withMutation {
        database.withTransaction {
            val existing = requireNotNull(dao.getCampaign(campaignId)) { "Campaign not found." }
            val name = title.trim()
            require(name.isNotBlank() && name.length <= 200) { "Campaign title must contain 1–200 characters." }
            require(dao.updateCampaign(existing.copy(title = name, updatedAt = System.currentTimeMillis())) == 1)
        }
    }

    /**
     * A chat can be attached to exactly one Campaign; duplicate roleplay contexts
     * in sibling Campaigns would otherwise create ambiguous private history.
     * Explicit Campaign membership also attaches an unlinked chat to its World,
     * but never steals a chat already linked to a different World.
     */
    suspend fun addChapter(campaignId: String, chatId: String, title: String): CampaignChapterEntity =
        coordinator.withMutation {
            database.withTransaction {
                val campaign = requireNotNull(dao.getCampaign(campaignId)) { "Campaign not found." }
                require(database.chatDao().getChatById(chatId) != null) { "Chat not found." }
                require(dao.chapterForChat(chatId) == null) {
                    "This chat already belongs to a Campaign. Keep independent stories separate."
                }
                val memberships = database.worldDao()
                    .getMembershipsForResource(WorldResourceType.CHAT.name, chatId)
                require(memberships.all { it.worldId == campaign.worldId }) {
                    "This chat belongs to a different World."
                }
                val chapterTitle = title.trim()
                require(chapterTitle.isNotBlank() && chapterTitle.length <= 200) {
                    "Chapter title must contain 1–200 characters."
                }
                if (memberships.isEmpty()) {
                    database.worldDao().insertMembership(WorldMembershipEntity(
                        campaign.worldId, WorldResourceType.CHAT.name, chatId
                    ))
                }
                val ordinal = dao.lastOrdinal(campaignId)
                require(ordinal < Long.MAX_VALUE) { "Campaign chapter numbering is exhausted." }
                CampaignChapterEntity(
                    campaignId = campaignId,
                    chatId = chatId,
                    ordinal = ordinal + 1,
                    title = chapterTitle
                ).also(dao::insertChapter)
            }
        }

    /** Recaps are editable local notes, not automatically approved cross-chapter memory. */
    suspend fun updateChapter(
        campaignId: String, chapterId: String, title: String,
        recap: String, complete: Boolean
    ) = coordinator.withMutation {
        database.withTransaction {
            val previous = requireNotNull(dao.getChapter(chapterId)) { "Chapter not found." }
            require(previous.campaignId == campaignId) { "Chapter belongs to another Campaign." }
            val name = title.trim()
            require(name.isNotBlank() && name.length <= 200) { "Chapter title must contain 1–200 characters." }
            require(recap.length <= 12_000) { "Chapter recap exceeds 12,000 characters." }
            require(dao.updateChapter(previous.copy(
                title = name, recap = recap.trim(),
                state = if (complete) "COMPLETE" else "ACTIVE",
                updatedAt = System.currentTimeMillis()
            )) == 1)
        }
    }

    /** Removing a chapter link never removes its chat, messages, variants or memories. */
    suspend fun removeChapter(campaignId: String, chapterId: String) = coordinator.withMutation {
        database.withTransaction {
            require(dao.getChapter(chapterId)?.campaignId == campaignId) {
                "Chapter belongs to another Campaign or no longer exists."
            }
            require(dao.deleteChapter(chapterId) == 1)
        }
    }

    /** Explicit deletion removes only Campaign/chapter organization; all chats survive. */
    suspend fun deleteCampaign(campaignId: String) = coordinator.withMutation {
        database.withTransaction {
            require(dao.deleteCampaign(campaignId) == 1) { "Campaign not found." }
        }
    }
}
