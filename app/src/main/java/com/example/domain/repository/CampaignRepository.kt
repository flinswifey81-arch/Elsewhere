package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.CampaignChapterEntity
import com.example.data.model.CampaignEntity
import com.example.data.model.CampaignJournalEntryEntity
import com.example.data.model.CampaignContinuityItemEntity
import com.example.data.model.CampaignMemoryShareEntity
import com.example.data.model.CampaignPlaythroughField
import com.example.data.model.campaignPlaythroughStateAt
import com.example.data.model.playthroughSubject
import com.example.data.model.PLAYTHROUGH_NARRATOR_APPROVED
import com.example.data.model.ChatType
import com.example.domain.memory.StoryMemoryEligibilityPolicy
import com.example.data.model.WorldContinuityEventEntity
import java.security.MessageDigest
import com.example.data.model.WorldMembershipEntity
import com.example.data.model.WorldResourceType
/**
 * Local Campaign organization only. No paid provider calls, automatic chapter-memory
 * sharing, automatic World-canon promotion, or changes to saved messages / variants.
 */
class CampaignRepository(
    private val database: AppDatabase,
    private val coordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global,
    private val coverImages: ProfileImageRepository? = null
) {
    private val dao get() = database.campaignDao()

    fun observeForWorld(worldId: String) = dao.observeForWorld(worldId)
    fun observeChapters(campaignId: String) = dao.observeChapters(campaignId)
    suspend fun forWorld(worldId: String) = dao.forWorld(worldId)
    suspend fun chapters(campaignId: String) = dao.chapters(campaignId)
    suspend fun getCampaign(campaignId: String) = dao.getCampaign(campaignId)

    /** A chat with no Campaign remains a completely independent story. */
    suspend fun campaignForChat(chatId: String): CampaignEntity? {
        val chapter = dao.chapterForChat(chatId) ?: return null
        return dao.getCampaign(chapter.campaignId)
    }

    suspend fun sharedMemoryIdsForChat(chatId: String): Set<String> {
        val campaign = campaignForChat(chatId) ?: return emptySet()
        val memories = database.storyMemoryDao().forChat(chatId).map { it.memoryId }.toSet()
        return dao.memorySharesForCampaign(campaign.campaignId)
            .mapNotNull { it.sourceMemoryId.takeIf(memories::contains) }.toSet()
    }

    /**
     * One explicit user confirmation; keep the source chat-local Story Memory
     * unchanged. A digest invalidates this approval on subsequent text edits.
     * Knowledge grants and selected variants remain owned by the source chat.
     */
    suspend fun shareStoryMemoryWithCampaign(
        campaignId: String, chatId: String, memoryId: String
    ): CampaignMemoryShareEntity = coordinator.withMutation {
        database.withTransaction {
            val chapter = requireNotNull(dao.chapterForChat(chatId)) {
                "Only chats linked as Campaign chapters can share memory."
            }
            require(chapter.campaignId == campaignId) {
                "This chapter belongs to another Campaign."
            }
            requireNotNull(dao.getCampaign(campaignId)) { "Campaign not found." }
            val source = requireNotNull(database.storyMemoryDao().get(memoryId)) {
                "Story Memory not found."
            }
            require(source.chatId == chatId && source.ownerId == chatId &&
                source.scope == "CHAT" && source.branchId == null) {
                "Only this chapter's chat-local Story Memories can be shared."
            }
            require(source.review == "APPROVED" && !source.hidden &&
                source.lifecycle != "SUPERSEDED" && source.content.isNotBlank()) {
                "Approve and show this Story Memory before sharing it."
            }
            val wack = database.chatDao().getChatById(chatId)?.contextResetOrderIndex
            if (wack != null && source.sourceOrderIndex <= wack) {
                require(database.storyMemoryDao().returns(memoryId).any {
                    it.chatId == chatId &&
                        it.resetBoundaryOrderIndex == wack &&
                        it.approvedAtOrderIndex > wack &&
                        it.initiatedByUser && it.branchId == null
                }) {
                    "Restore this specific Story Memory after Wack before sharing it."
                }
            }
            if (source.originKind == "MESSAGE") {
                val message = requireNotNull(
                    source.sourceMessageId?.let { database.messageDao().getMessageById(it) }
                ) { "The original message no longer exists." }
                require(message.chatId == chatId && message.isPrimaryVariant &&
                    message.variantGroupId == source.sourceVariantGroupId &&
                    message.orderIndex == source.sourceOrderIndex &&
                    message.speakerType.name == source.sourceSpeakerType &&
                    message.speakerId == source.sourceSpeakerId &&
                    message.createdAt == source.sourceCreatedAtEpochMillis &&
                    (if (source.sourceSha256 != null)
                        StoryMemoryEligibilityPolicy.sha256(message.content) == source.sourceSha256
                    else message.editedAt == null)
                ) { "The source message changed or its variant is no longer selected." }
            } else {
                require(source.originKind == "MANUAL") {
                    "Cannot share a Story Memory with unknown provenance."
                }
            }
            require(dao.getMemoryShare(campaignId, memoryId) == null) {
                "This memory has already been shared with this Campaign."
            }
            CampaignMemoryShareEntity(
                campaignId = campaignId, sourceMemoryId = memoryId,
                sourceChapterId = chapter.chapterId,
                sourceContentSha256 = StoryMemoryEligibilityPolicy.sha256(source.content)
            ).also { dao.insertMemoryShare(it) }
        }
    }

    suspend fun revokeCampaignMemoryShare(
        campaignId: String, chatId: String, memoryId: String
    ) = coordinator.withMutation {
        database.withTransaction {
            val chapter = requireNotNull(dao.chapterForChat(chatId)) {
                "This chat is not a Campaign chapter."
            }
            require(chapter.campaignId == campaignId) { "Wrong Campaign." }
            val source = requireNotNull(database.storyMemoryDao().get(memoryId)) {
                "Source memory no longer exists."
            }
            require(source.chatId == chatId) { "Cannot revoke another chat's share." }
            require(dao.getMemoryShare(campaignId, memoryId)?.sourceChapterId ==
                chapter.chapterId) { "Campaign memory share no longer exists." }
            require(dao.deleteMemoryShare(campaignId, memoryId) == 1)
        }
    }

    // Each Campaign's arcs/threads are local notes, never implicit prompt input.
    fun observeContinuityItems(campaignId: String) = dao.observeContinuityItems(campaignId)
    suspend fun continuityItems(campaignId: String) = dao.continuityItems(campaignId)

    /** A player-set value for this episode; blank explicitly clears a prior value. */
    suspend fun setPlaythroughState(
        campaignId: String,
        chapterId: String,
        field: CampaignPlaythroughField,
        subject: String,
        value: String,
        shareWithNarrator: Boolean = false
    ): CampaignContinuityItemEntity = coordinator.withMutation {
        database.withTransaction {
            require(dao.getChapter(chapterId)?.campaignId == campaignId) {
                "Select an episode in this Campaign."
            }
            validateContinuityText(subject, value)
            val previous = dao.continuityItems(campaignId).singleOrNull {
                it.openedChapterId == chapterId && it.itemType == field.itemType &&
                    playthroughSubject(it.title) == playthroughSubject(subject)
            }
            val row = (previous ?: CampaignContinuityItemEntity(
                campaignId = campaignId,
                itemType = field.itemType,
                title = subject.trim(),
                openedChapterId = chapterId
            )).copy(
                title = subject.trim(),
                details = value.trim(),
                status = if (value.isBlank()) "RESOLVED" else "OPEN",
                resolution = if (shareWithNarrator && value.isNotBlank()) PLAYTHROUGH_NARRATOR_APPROVED else "",
                updatedAt = System.currentTimeMillis()
            )
            if (previous == null) dao.insertContinuityItem(row)
            else require(dao.updateContinuityItem(row) == 1)
            row
        }
    }

    suspend fun playthroughStateForChapter(
        campaignId: String,
        chapterId: String
    ): List<CampaignContinuityItemEntity> = database.withTransaction {
        val chapter = requireNotNull(dao.getChapter(chapterId)?.takeIf { it.campaignId == campaignId }) {
            "Select an episode in this Campaign."
        }
        campaignPlaythroughStateAt(chapter, dao.chapters(campaignId), dao.continuityItems(campaignId))
    }

    /**
     * Only explicitly shared facts for this Scenario's narrator. Fail closed
     * across Wack, unlinked episodes and revisions newer than the player turn.
     * For historical actions, still-shared facts and episode links must strictly
     * predate that turn. Equal timestamps cannot prove ordering. A changed or
     * revoked value is omitted, not reconstructed from a superseded version.
     * No Character/NPC knowledge grants are created and nothing is sent here.
     */
    internal suspend fun narratorPlaythroughEntries(
        chatId: String, asOfEpochMillis: Long, historicalReplay: Boolean = false
    ): List<String> =
        database.withTransaction {
            val chat = database.chatDao().getChatById(chatId) ?: return@withTransaction emptyList()
            if (chat.chatType != ChatType.SCENARIO || chat.contextResetOrderIndex != null) {
                return@withTransaction emptyList()
            }
            val chapter = dao.chapterForChat(chatId) ?: return@withTransaction emptyList()
            if (historicalReplay && chapter.createdAt >= asOfEpochMillis) return@withTransaction emptyList()
            val chapters = dao.chapters(chapter.campaignId)
            val chapterById = chapters.associateBy { it.chapterId }
            val selected = campaignPlaythroughStateAt(chapter, chapters, dao.continuityItems(chapter.campaignId))
                .sortedWith(compareByDescending<CampaignContinuityItemEntity> {
                    it.openedChapterId?.let(chapterById::get)?.ordinal
                }.thenByDescending { it.updatedAt })
            val eligibleSourceChats = mutableMapOf<String, Boolean>()
            buildList {
                for (item in selected) {
                    if (item.resolution != PLAYTHROUGH_NARRATOR_APPROVED || item.updatedAt > asOfEpochMillis) continue
                    if (historicalReplay && item.updatedAt >= asOfEpochMillis) continue
                    val sourceChapter = item.openedChapterId?.let(chapterById::get) ?: continue
                    if (historicalReplay && sourceChapter.createdAt >= asOfEpochMillis) continue
                    val sourceIsEligible = eligibleSourceChats.getOrPut(sourceChapter.chatId) {
                        val sourceChat = database.chatDao().getChatById(sourceChapter.chatId)
                        sourceChat != null && sourceChat.contextResetOrderIndex == null
                    }
                    if (!sourceIsEligible) continue
                    val field = CampaignPlaythroughField.fromItemType(item.itemType) ?: continue
                    val title = item.title.lineSequence().joinToString(" ") { it.trim() }
                    val details = item.details.lineSequence().joinToString("\n  ")
                    add("- ${field.label}, $title: $details")
                }
            }
        }

    private val continuityTypes = setOf(
        "ARC", "THREAD", "PROMISE", "QUEST", "DISCOVERY", "RELATIONSHIP"
    )

    /**
     * A Campaign arc summary or ongoing thread, always authored explicitly.
     * Optional original chapter or journal provenance must belong to the SAME
     * Campaign. The record neither duplicates messages nor updates World Canon.
     */
    suspend fun addContinuityItem(
        campaignId: String, type: String, title: String, details: String,
        openedChapterId: String? = null, sourceJournalEntryId: String? = null
    ): CampaignContinuityItemEntity = coordinator.withMutation {
        database.withTransaction {
            requireNotNull(dao.getCampaign(campaignId)) { "Campaign not found." }
            require(type in continuityTypes) { "Unsupported Campaign continuity type." }
            validateContinuityText(title, details)
            if (type == "ARC") {
                require(details.trim().isNotBlank()) {
                    "An arc summary must describe what happened."
                }
            }
            requireContinuitySources(campaignId, openedChapterId, sourceJournalEntryId)
            CampaignContinuityItemEntity(
                campaignId = campaignId, itemType = type,
                title = title.trim(), details = details.trim(),
                status = if (type == "ARC") "ACTIVE" else "OPEN",
                openedChapterId = openedChapterId,
                sourceJournalEntryId = sourceJournalEntryId
            ).also { dao.insertContinuityItem(it) }
        }
    }

    /** Closing an arc or thread updates only the specific Campaign's record.
     * A user can reopen it later; its previous details and source survive.
     */
    suspend fun updateContinuityItem(
        campaignId: String, itemId: String, title: String, details: String,
        status: String, resolution: String = "", closedChapterId: String? = null
    ): CampaignContinuityItemEntity = coordinator.withMutation {
        database.withTransaction {
            val existing = requireNotNull(dao.getContinuityItem(itemId)) {
                "Campaign continuity item not found."
            }
            require(existing.campaignId == campaignId) {
                "Cannot edit another Campaign's continuity."
            }
            validateContinuityText(title, details)
            require(resolution.length <= 12_000) {
                "Resolution exceeds 12,000 characters."
            }
            require(existing.itemType != "ARC" || details.trim().isNotBlank()) {
                "An arc summary cannot be empty."
            }
            val active = if (existing.itemType == "ARC") "ACTIVE" else "OPEN"
            val complete = if (existing.itemType == "ARC") "COMPLETE" else "RESOLVED"
            require(status == active || status == complete) {
                "Invalid state for this Campaign continuity type."
            }
            val endChapter = if (status == complete) closedChapterId else null
            if (endChapter != null) {
                val end = requireNotNull(dao.getChapter(endChapter)) {
                    "Closing chapter not found."
                }
                require(end.campaignId == campaignId) {
                    "A Campaign cannot close its arc or thread in another story."
                }
                val startOrdinal = existing.openedChapterId
                    ?.let { dao.getChapter(it)?.ordinal }
                require(startOrdinal == null || end.ordinal >= startOrdinal) {
                    "Closing chapter cannot precede the opening chapter."
                }
            }
            existing.copy(
                title = title.trim(), details = details.trim(),
                status = status,
                resolution = if (status == complete) resolution.trim() else "",
                closedChapterId = endChapter,
                updatedAt = System.currentTimeMillis()
            ).also { updated ->
                require(dao.updateContinuityItem(updated) == 1)
            }
        }
    }

    suspend fun deleteContinuityItem(campaignId: String, itemId: String) =
        coordinator.withMutation {
            database.withTransaction {
                require(dao.getContinuityItem(itemId)?.campaignId == campaignId) {
                    "Cannot delete another Campaign's continuity."
                }
                require(dao.deleteContinuityItem(itemId) == 1)
            }
        }

    private suspend fun requireContinuitySources(
        campaignId: String, chapterId: String?, journalEntryId: String?
    ) {
        if (chapterId != null) {
            require(dao.getChapter(chapterId)?.campaignId == campaignId) {
                "Opening chapter belongs to another Campaign."
            }
        }
        if (journalEntryId != null) {
            val journal = requireNotNull(dao.getJournalEntry(journalEntryId)) {
                "Source Journal entry not found."
            }
            require(journal.campaignId == campaignId) {
                "Source Journal entry belongs to another Campaign."
            }
            if (chapterId != null && journal.chapterId != null) {
                require(journal.chapterId == chapterId) {
                    "Source Journal and opening chapter do not match."
                }
            }
        }
    }

    private fun validateContinuityText(title: String, details: String) {
        require(title.trim().isNotBlank() && title.length <= 200) {
            "Continuity item title must contain 1–200 characters."
        }
        require(details.length <= 12_000) {
            "Continuity item details exceed 12,000 characters."
        }
    }

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
                val chatId = requireNotNull(chapter).chatId
                val resetBoundary = database.chatDao()
                    .getChatById(chatId)?.contextResetOrderIndex
                // A checkpoint created AFTER Wack may never anchor to an older
                // pre-Wack turn, even when it is the last visible message.
                database.messageDao().getSelectedHistoryReversePage(
                    chatId, resetBoundary, 1, 0
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

    /**
     * Copies ONLY player-reviewed, player-edited journal text to a World DRAFT.
     * This is the first half of deliberate promotion: existing World review must
     * separately accept the draft before it can affect another Campaign.
     * A private checkpoint cannot be promoted directly; its source may be
     * invalidated by Wack or a regenerated variant. A player can author a
     * new Journal note describing the version they intend to share.
     */
    suspend fun proposeJournalEntryForWorldReview(
        campaignId: String, journalEntryId: String,
        reviewedTitle: String, reviewedFacts: String
    ): WorldContinuityEventEntity = coordinator.withMutation {
        database.withTransaction {
            val campaign = requireNotNull(dao.getCampaign(campaignId)) {
                "Campaign not found."
            }
            val entry = requireNotNull(dao.getJournalEntry(journalEntryId)) {
                "Campaign Journal entry not found."
            }
            require(entry.campaignId == campaignId) {
                "Cannot promote another Campaign's private notes."
            }
            require(entry.entryType == "JOURNAL") {
                "Only reviewed Journal entries can become World drafts; checkpoints remain private."
            }
            require(reviewedTitle.trim().isNotEmpty() && reviewedTitle.length <= 200) {
                "Give the World draft a title of 1–200 characters."
            }
            require(reviewedFacts.trim().isNotEmpty() && reviewedFacts.length <= 12_000) {
                "Give the World draft reviewed details (up to 12,000 characters)."
            }
            require(database.worldDao().getWorld(campaign.worldId) != null) {
                "The Campaign's World is unavailable."
            }
            // Provenance is informational; only the reviewed title and facts
            // are copied. Source message, other journal entries, secrets,
            // memories and alternate branches are never copied implicitly.
            val sourceChatId = entry.chapterId?.let { chapterId ->
                dao.getChapter(chapterId)?.takeIf { it.campaignId == campaignId }?.chatId
            }
            WorldContinuityEventEntity(
                worldId = campaign.worldId,
                title = reviewedTitle.trim(),
                facts = reviewedFacts.trim(),
                status = "DRAFT",
                sourceChatId = sourceChatId
            ).also { database.worldDao().insertEvent(it) }
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
        runCatching { coverImages?.removeImage(ProfileImageKind.CAMPAIGN_COVER,campaignId) }
        Unit
    }
}
