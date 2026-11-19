package com.example.domain.repository

import com.example.data.MessageDao
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.compiler.HistoricalGenerationWindow
import kotlinx.coroutines.flow.Flow

class MessageRepository(
    private val messageDao: MessageDao,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {

    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForChat(chatId)
    }

    /**
     * UI display only. Returns complete variant groups; the extra group is
     * a has-earlier sentinel. Never use for export, synchronization or
     * long-term memory maintenance, which require archival queries.
     */
    fun observeRecentMessageGroups(
        chatId: String,
        visibleGroupLimit: Int
    ): Flow<List<MessageEntity>> {
        require(visibleGroupLimit in 1 until Int.MAX_VALUE) { "Visible group limit must be positive." }
        return messageDao.observeRecentMessageGroups(chatId, visibleGroupLimit + 1)
    }

    /** Scalar count only, never hydrates the archive in the ViewModel. */
    suspend fun countLogicalMessageGroups(chatId: String): Int =
        messageDao.countLogicalMessageGroups(chatId)

    suspend fun getMessagesForChatOnce(chatId: String): List<MessageEntity> =
        messageDao.getMessagesForChatOnce(chatId)

    /**
     * Returns at most [maxTurns] selected messages in chronological order.
     * This is a prompt-preparation window, not a replacement for the complete
     * history required by legacy automatic-memory summarization or export.
     */
    suspend fun getLatestPrimaryWindow(chatId: String, maxTurns: Int = 25): List<MessageEntity> {
        require(maxTurns in 1..100) { "Bounded history window must be within 1..100 turns." }
        return messageDao.getLatestPrimaryWindow(chatId, maxTurns).asReversed()
    }

    suspend fun getLatestPrimaryPersona(
        chatId: String,
        resetBoundary: Long?
    ): MessageEntity? = messageDao.getLatestPrimaryPersona(chatId, resetBoundary)

    suspend fun getSelectedGroupGenerationWindow(
        chatId: String,
        resetBoundary: Long?,
        maxTurns: Int = 25
    ): com.example.data.SelectedGroupGenerationWindow {
        require(maxTurns in 1..100) { "Bounded Group window must be within 1..100 turns." }
        return messageDao.getSelectedGroupGenerationWindow(chatId, resetBoundary, maxTurns)
    }

    /**
     * Selected primary messages newest first for rolling memory summaries.
     * A page contains at most 128 messages, even in 10k+ message chats.
     */
    suspend fun getSelectedHistoryReversePage(
        chatId: String,
        resetBoundary: Long?,
        offset: Int,
        pageSize: Int = 128
    ): List<MessageEntity> {
        require(offset >= 0) { "History offset cannot be negative." }
        require(pageSize in 1..256) { "History page size must be between 1 and 256." }
        return messageDao.getSelectedHistoryReversePage(chatId, resetBoundary, pageSize, offset)
    }

    /**
     * Oldest-first selected history pages for manual memory extraction.
     * Preserves the first source of a repeated fact, as before this change.
     */
    suspend fun getSelectedHistoryForwardPage(
        chatId: String,
        resetBoundary: Long?,
        offset: Int,
        pageSize: Int = 128
    ): List<MessageEntity> {
        require(offset >= 0) { "History offset cannot be negative." }
        require(pageSize in 1..256) { "History page size must be between 1 and 256." }
        return messageDao.getSelectedHistoryForwardPage(chatId, resetBoundary, pageSize, offset)
    }

    /**
     * When the UI only holds a paged history, an older Regenerate/Continue
     * action must still recover the right preceding Persona and 24 context
     * turns. The DAO reads the selected branch atomically as of the target,
     * rather than loading 10k messages just to regenerate an older bubble.
     */
    suspend fun selectHistoricalAction(
        chatId: String,
        targetMessageId: String,
        isGroup: Boolean,
        isContinuation: Boolean,
        resetBoundary: Long?
    ): HistoricalGenerationWindow.Selection? {
        val snapshot = messageDao.getHistoricalActionSnapshot(
            chatId, targetMessageId, resetBoundary
        ) ?: return null
        val target = snapshot.target

        fun withPersonaAnchor(
            recent: List<MessageEntity>,
            current: MessageEntity
        ): List<MessageEntity> {
            if (current.speakerType == SpeakerType.PERSONA ||
                recent.any { it.speakerType == SpeakerType.PERSONA }) return recent
            val persona = snapshot.lastPersonaBeforeTarget ?: return recent
            return listOf(persona) + recent
        }

        return if (isContinuation) {
            if (!target.isPrimaryVariant ||
                target.speakerType !in setOf(SpeakerType.CHARACTER, SpeakerType.NARRATOR)
            ) return null
            val recent = snapshot.beforeTargetNewestFirst.take(24).asReversed()
            HistoricalGenerationWindow.Selection(
                target, target, withPersonaAnchor(recent, target)
            )
        } else if (isGroup) {
            val current = snapshot.beforeTargetNewestFirst.firstOrNull() ?: return null
            val recent = snapshot.beforeTargetNewestFirst.drop(1).take(24).asReversed()
            HistoricalGenerationWindow.Selection(
                target, current, withPersonaAnchor(recent, current)
            )
        } else {
            val persona = snapshot.lastPersonaBeforeTarget ?: return null
            HistoricalGenerationWindow.Selection(
                target, persona, snapshot.beforePersonaNewestFirst.asReversed()
            )
        }
    }

    /**
     * Legacy-memory read gate: validate only saved memory source messages
     * and every alternate in their groups. Unattributed memories need no
     * transcript scan and remain subject to the existing Wack policy.
     */
    suspend fun getLegacyMemoryProvenance(
        chatId: String,
        memories: List<com.example.data.model.RoleplayMemoryEntity>
    ): List<MessageEntity> = MemoryProvenanceReader(messageDao).read(
        chatId,
        memories.mapNotNull { it.sourceMessageId }
    )

    suspend fun insertMessage(message: MessageEntity) {
        dataCoordinator.withMutation { messageDao.insertMessage(message) }
    }

    suspend fun updateMessage(message: MessageEntity) {
        dataCoordinator.withMutation { messageDao.updateMessage(message) }
    }

    suspend fun deleteMessage(message: MessageEntity) {
        dataCoordinator.withMutation { messageDao.deleteMessageAndRepair(message) }
    }

    suspend fun insertVariantAndSelect(message: MessageEntity) {
        dataCoordinator.withMutation { messageDao.insertVariantAndSelect(message) }
    }
    
    fun getVariants(variantGroupId: String): kotlinx.coroutines.flow.Flow<List<com.example.data.model.MessageEntity>> = messageDao.getVariants(variantGroupId)

    suspend fun getVariantsOnce(variantGroupId: String): List<MessageEntity> =
        messageDao.getVariantsOnce(variantGroupId)

    suspend fun setPrimaryVariant(variantGroupId: String, messageId: String) {
        dataCoordinator.withMutation {
            messageDao.clearPrimaryVariants(variantGroupId)
            messageDao.setPrimaryVariant(messageId)
        }
    }

    suspend fun insertGenerationMetadata(metadata: GenerationMetadataEntity) {
        dataCoordinator.withMutation { messageDao.insertGenerationMetadata(metadata) }
    }
    
    suspend fun getGenerationMetadata(messageId: String): GenerationMetadataEntity? {
        return messageDao.getGenerationMetadata(messageId)
    }
}
