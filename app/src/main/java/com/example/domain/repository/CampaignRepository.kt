package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.CampaignChapterEntity
import com.example.data.model.CampaignEntity
import com.example.data.model.CampaignJournalEntryEntity
import java.security.MessageDigest
import com.example.data.model.WorldMembershipEntity
import com.example.data.model.WorldResourceType
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

    // Journal and checkpoint records never enter a model prompt implicitly.
    fun observeJournal(campaignId: String) = dao.observeJournal(campaignId)
    suspend fun journal(campaignId: String) = dao.journal(campaignId)

    enum class CheckpointSourceStatus {
        CURRENT, BEFORE_WACK, OTHER_VARIANT, MESSAGE_EDITED,
        SOURCE_REMOVED, CHAPTER_REMOVED, START_OF_CHAPTER
    }

    /**
     * A checkpoint is a durable, non-destructive bookmark. It captures only
     * the current selected message's identity, variant and content digest,
     * not a duplicate transcript or a restorable overwrite of chat history.
     */
    suspend fun saveCheckpoint(
        campaignId: String, chapterId: String, title: String, notes: String = ""
    ): CampaignJournalEntryEntity = createJournalRecord(
        campaignId, chapterId, title, notes, "CHECKPOINT"
    )

    suspend fun addJournalNote(
        campaignId: String, title: String, body: String, chapterId: String? = null
    ): CampaignJournalEntryEntity = createJournalRecord(
        campaignId, chapterId, title, body, "JOURNAL"
    )

    private suspend fun createJournalRecord(
        campaignId: String, chapterId: String?, title: String, body: String, type: String
    ): CampaignJournalEntryEntity = coordinator.withMutation {
        database.withTransaction {
            require(dao.getCampaign(campaignId) != null) { "Campaign not found." }
            val chapter = chapterId?.let { requireNotNull(dao.getChapter(it)) { "Chapter not found." } }
            require(chapter == null || chapter.campaignId == campaignId) {
                "Cannot record an event against another Campaign's chapter."
            }
            require(type != "CHECKPOINT" || chapter != null) {
                "A checkpoint requires a selected chapter."
            }
            validateJournalText(title, body)
            val selected = if (type == "CHECKPOINT") {
                database.messageDao().getLatestPrimaryWindow(
                    requireNotNull(chapter).chatId, 1
                ).firstOrNull()
            } else null
            CampaignJournalEntryEntity(
                campaignId = campaignId,
                chapterId = chapterId,
                sourceChatId = if (type == "CHECKPOINT") chapter?.chatId else null,
                sourceMessageId = selected?.messageId,
                sourceVariantGroupId = selected?.variantGroupId,
                sourceOrderIndex = selected?.orderIndex,
                sourceContentSha256 = selected?.content?.let(::contentSha256),
                entryType = type,
                title = title.trim(),
                body = body.trim()
            ).also { dao.insertJournalEntry(it) }
        }
    }

    suspend fun updateJournalEntry(
        campaignId: String, entryId: String, title: String, body: String
    ) = coordinator.withMutation {
        database.withTransaction {
            val previous = requireNotNull(dao.getJournalEntry(entryId)) { "Journal entry not found." }
            require(previous.campaignId == campaignId) { "Entry belongs to another Campaign." }
            validateJournalText(title, body)
            require(dao.updateJournalEntry(previous.copy(
                title = title.trim(), body = body.trim(), updatedAt = System.currentTimeMillis()
            )) == 1)
        }
    }

    suspend fun deleteJournalEntry(campaignId: String, entryId: String) = coordinator.withMutation {
        database.withTransaction {
            require(dao.getJournalEntry(entryId)?.campaignId == campaignId) {
                "Entry belongs to another Campaign or no longer exists."
            }
            require(dao.deleteJournalEntry(entryId) == 1)
        }
    }

    /** Verify a saved checkpoint against the current chosen branch and Wack.
     * This is display-only. No historical message text is returned and no
     * other Campaign may query an entry without its matching Campaign ID.
     */
    suspend fun checkpointStatus(campaignId: String, entryId: String): CheckpointSourceStatus {
        val entry = requireNotNull(dao.getJournalEntry(entryId)) { "Entry not found." }
        require(entry.campaignId == campaignId) { "Entry belongs to another Campaign." }
        require(entry.entryType == "CHECKPOINT") { "Not a checkpoint." }
        if (entry.chapterId == null) return CheckpointSourceStatus.CHAPTER_REMOVED
        if (entry.sourceOrderIndex == null) return CheckpointSourceStatus.START_OF_CHAPTER
        val id = entry.sourceMessageId ?: return CheckpointSourceStatus.SOURCE_REMOVED
        val message = database.messageDao().getMessageById(id)
            ?: return CheckpointSourceStatus.SOURCE_REMOVED
        if (message.chatId != entry.sourceChatId ||
            message.variantGroupId != entry.sourceVariantGroupId ||
            message.orderIndex != entry.sourceOrderIndex) {
            return CheckpointSourceStatus.SOURCE_REMOVED
        }
        val resetIndex = database.chatDao().getChatById(message.chatId)?.contextResetOrderIndex
        if (resetIndex != null && message.orderIndex <= resetIndex) {
            return CheckpointSourceStatus.BEFORE_WACK
        }
        if (!message.isPrimaryVariant) return CheckpointSourceStatus.OTHER_VARIANT
        if (contentSha256(message.content) != entry.sourceContentSha256) {
            return CheckpointSourceStatus.MESSAGE_EDITED
        }
        return CheckpointSourceStatus.CURRENT
    }

    private fun validateJournalText(title: String, body: String) {
        require(title.trim().isNotBlank() && title.length <= 200) {
            "Journal entry title must contain 1–200 characters."
        }
        require(body.length <= 12_000) { "Journal entry exceeds 12,000 characters." }
    }

    private fun contentSha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    suspend fun createCampaign(worldId: String, title: String): CampaignEntity =
        coordinator.withMutation {
            database.withTransaction {
                require(database.worldDao().getWorld(worldId) != null) { "World not found." }
                val name = title.trim()
                require(name.isNotBlank() && name.length <= 200) {
                    "Campaign title must contain 1–200 characters."
                }
                CampaignEntity(worldId = worldId, title = name).also { dao.insertCampaign(it) }
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
                ).also { dao.insertChapter(it) }
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
