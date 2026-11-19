package com.example.domain.repository

import com.example.data.MessageDao
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

class MessageRepository(
    private val messageDao: MessageDao,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {

    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForChat(chatId)
    }

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
