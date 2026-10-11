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
