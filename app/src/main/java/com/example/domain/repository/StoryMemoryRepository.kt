package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.StoryMemoryActorEntity
import com.example.data.model.StoryMemoryEntity
import com.example.data.model.StoryMemoryReturnEntity
import com.example.domain.memory.*
import com.example.domain.story.StoryResponseCodec
import kotlinx.coroutines.flow.Flow

/**
 * Local user-controlled story memory. Kept out of generation until a separate
 * retrieval/assembler gate is reviewed. All edits take the shared maintenance
 * lock used by backup/restore; Room changes are atomic.
 */
class StoryMemoryRepository(
    private val database: AppDatabase,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    private val dao get() = database.storyMemoryDao()

    fun observe(chatId: String): Flow<List<StoryMemoryEntity>> = dao.observeForChat(chatId)

    suspend fun get(chatId: String, memoryId: String): StoryMemoryEntity? =
        dao.get(memoryId)?.takeIf { it.chatId == chatId }

    suspend fun saveManual(
        chatId: String,
        text: String,
        claim: StoryMemoryClaim = StoryMemoryClaim.FACT
    ): StoryMemoryEntity = dataCoordinator.withMutation {
        require(text.trim().isNotEmpty()) { "Write something to remember." }
        require(text.length <= 12_000) { "A story memory must fit within 12,000 characters." }
        database.withTransaction {
            val chat = requireNotNull(database.chatDao().getChatById(chatId)) { "Chat no longer exists." }
            val latest = database.chatDao().getLatestMessageOrderIndex(chatId) ?: -1L
            val ordinal = maxOf(latest, chat.contextResetOrderIndex ?: -1L) + 1L
            // STORYTELLER_ONLY never grants a Character omniscient knowledge.
            StoryMemoryEntity(
                chatId = chatId, ownerId = chatId,
                content = text.trim(), claim = claim.name,
                visibility = StoryMemoryVisibility.STORYTELLER_ONLY.name,
                review = StoryMemoryReview.APPROVED.name,
                originKind = "MANUAL", sourceOrderIndex = ordinal
            ).also { dao.insert(it) }
        }
    }

    /** A saved message keeps exact variant/speaker fingerprint and order. */
    suspend fun saveFromMessage(chatId: String, messageId: String): StoryMemoryEntity =
        dataCoordinator.withMutation {
            database.withTransaction {
                requireNotNull(database.chatDao().getChatById(chatId)) { "Chat no longer exists." }
                val message = requireNotNull(database.messageDao().getMessageById(messageId))
                    { "Source message was deleted." }
                require(message.chatId == chatId) { "Message belongs to another story." }
                val variants = database.messageDao().getVariantsOnce(message.variantGroupId)
                require(variants.count { it.isPrimaryVariant } == 1 && message.isPrimaryVariant) {
                    "Only the selected message variant may be saved."
                }
                // Ensemble messages are stored as a structured envelope; save a
                // readable story excerpt while fingerprinting the actual source
                // bytes so future edits/branch changes remain detectable.
                val content = (StoryResponseCodec.decode(message.content)
                    ?.let(StoryResponseCodec::readableTranscript)
                    ?: message.content).trim()
                require(content.isNotBlank() && content.length <= 12_000) {
                    "Message is empty or too long to save as one memory."
                }
                StoryMemoryEntity(
                    chatId = chatId, ownerId = chatId,
                    content = content, claim = StoryMemoryClaim.EVENT.name,
                    visibility = StoryMemoryVisibility.STORYTELLER_ONLY.name,
                    review = StoryMemoryReview.APPROVED.name,
                    originKind = "MESSAGE", sourceMessageId = message.messageId,
                    sourceVariantGroupId = message.variantGroupId,
                    sourceOrderIndex = message.orderIndex,
                    sourceSpeakerType = message.speakerType.name,
                    sourceSpeakerId = message.speakerId,
                    sourceCreatedAtEpochMillis = message.createdAt,
                    sourceSha256 = StoryMemoryEligibilityPolicy.sha256(message.content)
                ).also { dao.insert(it) }
            }
        }

    /** Update only user-managed fields. Provenance stays immutable. */
    suspend fun update(
        chatId: String, memoryId: String,
        text: String? = null, pinned: Boolean? = null, hidden: Boolean? = null,
        lifecycle: StoryMemoryLifecycle? = null
    ): StoryMemoryEntity = dataCoordinator.withMutation {
        database.withTransaction {
            val old = requireNotNull(get(chatId, memoryId)) { "Memory no longer exists in this chat." }
            val cleaned = text?.trim()
            if (cleaned != null) require(cleaned.isNotBlank() && cleaned.length <= 12_000) {
                "A memory must contain 1–12,000 characters."
            }
            val now = System.currentTimeMillis()
            old.copy(
                content = cleaned ?: old.content,
                pinned = pinned ?: old.pinned,
                hidden = hidden ?: old.hidden,
                lifecycle = lifecycle?.name ?: old.lifecycle,
                userEditedAt = if (cleaned != null) now else old.userEditedAt,
                updatedAtEpochMillis = now
            ).also { dao.update(it) }
        }
    }

    suspend fun delete(chatId: String, memoryId: String) = dataCoordinator.withMutation {
        database.withTransaction {
            get(chatId, memoryId)?.let { dao.delete(it) }
        }
    }

    suspend fun getReturns(chatId: String, memoryId: String): List<StoryMemoryReturnEntity> {
        requireNotNull(get(chatId, memoryId)) { "Memory does not belong to this chat." }
        return dao.returns(memoryId)
    }

    /**
     * The user explicitly restores this exact record for the current Wack.
     * No source-variant/identity/secret-policy bypass. The grant is stored
     * atomically and is invalid after a new Wack boundary.
     */
    suspend fun restoreAcrossWack(chatId: String, memoryId: String): StoryMemoryReturnEntity =
        dataCoordinator.withMutation {
            database.withTransaction {
                val chat = requireNotNull(database.chatDao().getChatById(chatId)) {
                    "Chat no longer exists."
                }
                val boundary = requireNotNull(chat.contextResetOrderIndex) {
                    "There is no active Wack boundary."
                }
                val record = requireNotNull(get(chatId, memoryId)) {
                    "Memory does not belong to this chat."
                }
                require(record.sourceOrderIndex <= boundary) {
                    "This memory was created after the current Wack."
                }
                val withoutWack = eligibility(
                    record, chatId, boundary = null,
                    recipient = StoryActorRef(StoryActorKind.NARRATOR, "narrator:$chatId")
                )
                require(withoutWack.eligible) {
                    "This memory is not currently restorable: ${withoutWack.reason}"
                }
                StoryMemoryReturnEntity(
                    memoryId = record.memoryId, resetBoundaryOrderIndex = boundary,
                    chatId = chatId, branchId = null,
                    approvedAtOrderIndex = maxOf(
                        (database.chatDao().getLatestMessageOrderIndex(chatId) ?: boundary) + 1L,
                        boundary + 1L
                    ),
                    initiatedByUser = true
                ).also { dao.insertReturn(it) }
            }
        }

    suspend fun eligibility(
        memory: StoryMemoryEntity,
        chatId: String,
        boundary: Long?,
        recipient: StoryActorRef
    ): StoryMemoryDecision {
        if (memory.chatId != chatId) return StoryMemoryDecision(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE)
        val actors = dao.actors(memory.memoryId)
        val returns = dao.returns(memory.memoryId)
        fun actor(kind: String, id: String): StoryActorRef? = runCatching {
            StoryActorRef(StoryActorKind.valueOf(kind), id)
        }.getOrNull()
        val knownBy = actors.filter { it.relation == "KNOWS" }
            .mapNotNull { actor(it.actorKind, it.actorId) }.toSet()
        val mentioned = actors.filter { it.relation == "MENTIONED" }
            .mapNotNull { actor(it.actorKind, it.actorId) }.toSet()
        val converted = StoryMemoryRecord(
            memoryId = memory.memoryId,
            content = memory.content, createdAtEpochMillis = memory.createdAtEpochMillis,
            updatedAtEpochMillis = memory.updatedAtEpochMillis,
            ownerId = memory.ownerId, sourceChatId = memory.chatId,
            scope = StoryMemoryOwnerScope.valueOf(memory.scope), branchId = memory.branchId,
            origin = if (memory.originKind == "MESSAGE") {
                StoryMemoryOrigin.Message(
                    messageId = requireNotNull(memory.sourceMessageId),
                    variantGroupId = requireNotNull(memory.sourceVariantGroupId),
                    orderIndex = memory.sourceOrderIndex,
                    speakerType = com.example.data.model.SpeakerType.valueOf(requireNotNull(memory.sourceSpeakerType)),
                    speakerId = requireNotNull(memory.sourceSpeakerId),
                    sourceCreatedAtEpochMillis = requireNotNull(memory.sourceCreatedAtEpochMillis),
                    sourceSha256 = memory.sourceSha256
                )
            } else StoryMemoryOrigin.Manual(memory.sourceOrderIndex),
            claim = StoryMemoryClaim.valueOf(memory.claim),
            visibility = StoryMemoryVisibility.valueOf(memory.visibility),
            knownBy = knownBy,
            believedBy = if (memory.believedByKind != null && memory.believedById != null)
                actor(memory.believedByKind, memory.believedById) else null,
            actors = mentioned,
            review = StoryMemoryReview.valueOf(memory.review),
            lifecycle = StoryMemoryLifecycle.valueOf(memory.lifecycle),
            hidden = memory.hidden, pinned = memory.pinned,
            userEditedAt = memory.userEditedAt
        )
        val localReturns = returns.map {
            ExplicitMemoryReturn(it.memoryId, it.chatId, it.resetBoundaryOrderIndex,
                it.approvedAtOrderIndex, it.branchId, it.initiatedByUser)
        }.toSet()
        return StoryMemoryEligibilityPolicy.evaluate(converted, StoryMemoryReadContext(
            chatId = chatId, branchId = null, wackBoundaryOrderIndex = boundary,
            recipient = recipient,
            archivedMessages = database.messageDao().getMessagesForChatOnce(chatId),
            explicitReturns = localReturns
        ))
    }
}
