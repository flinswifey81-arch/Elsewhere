package com.example.domain.repository

import com.example.data.MessageDao
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val messageDao: MessageDao) {

    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForChat(chatId)
    }

    suspend fun insertMessage(message: MessageEntity) {
        messageDao.insertMessage(message)
    }

    suspend fun updateMessage(message: MessageEntity) {
        messageDao.updateMessage(message)
    }

    suspend fun deleteMessage(message: MessageEntity) {
        messageDao.deleteMessage(message)
    }
    
    fun getVariants(variantGroupId: String): kotlinx.coroutines.flow.Flow<List<com.example.data.model.MessageEntity>> = messageDao.getVariants(variantGroupId)

    suspend fun setPrimaryVariant(variantGroupId: String, messageId: String) {
        messageDao.clearPrimaryVariants(variantGroupId)
        messageDao.setPrimaryVariant(messageId)
    }

    suspend fun insertGenerationMetadata(metadata: GenerationMetadataEntity) {
        messageDao.insertGenerationMetadata(metadata)
    }
    
    suspend fun getGenerationMetadata(messageId: String): GenerationMetadataEntity? {
        return messageDao.getGenerationMetadata(messageId)
    }
}
