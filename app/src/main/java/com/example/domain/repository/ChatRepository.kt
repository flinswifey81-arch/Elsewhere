package com.example.domain.repository

import com.example.data.ChatDao
import com.example.data.MessageDao
import com.example.data.model.ChatEntity
import com.example.data.model.ChatParticipantEntity
import com.example.data.model.ChatPersonaParticipantEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ChatSummary
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRepository(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao? = null
) {
    fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()
    
    fun getAllChatSummaries(): Flow<List<ChatSummary>> = chatDao.getAllChatSummaries()
    
    fun getRecentChatSummaries(limit: Int = 3): Flow<List<ChatSummary>> = chatDao.getRecentChatSummaries(limit)
    
    suspend fun getChatSummaryById(id: String): ChatSummary? = withContext(Dispatchers.IO) {
        chatDao.getChatSummaryById(id)
    }

    suspend fun createSoloChat(
        displayName: String,
        personaId: String,
        characterId: String,
        characterDisplayName: String? = null,
        initialCharacterMessage: String? = null
    ): String = withContext(Dispatchers.IO) {
        val chat = ChatEntity(displayName = displayName, chatType = ChatType.SOLO)
        chatDao.insertChat(chat)
        chatDao.insertChatPersonaParticipant(ChatPersonaParticipantEntity(chatId = chat.chatId, personaId = personaId))
        chatDao.insertChatParticipant(ChatParticipantEntity(chatId = chat.chatId, characterId = characterId))
        if (!initialCharacterMessage.isNullOrEmpty() && characterDisplayName != null) {
            requireNotNull(messageDao) { "MessageDao is required to persist an initial Character message" }
                .insertMessage(
                MessageEntity(
                    chatId = chat.chatId,
                    speakerType = SpeakerType.CHARACTER,
                    speakerId = characterId,
                    speakerDisplayNameSnapshot = characterDisplayName,
                    content = initialCharacterMessage,
                    orderIndex = chat.createdAt
                )
            )
        }
        chat.chatId
    }

    suspend fun createGroupChat(
        displayName: String,
        personaIds: List<String>,
        characterIds: List<String>
    ): String = withContext(Dispatchers.IO) {
        val chat = ChatEntity(displayName = displayName, chatType = ChatType.GROUP)
        chatDao.insertChat(chat)
        personaIds.forEachIndexed { index, pId ->
            chatDao.insertChatPersonaParticipant(ChatPersonaParticipantEntity(chatId = chat.chatId, personaId = pId, sortPosition = index))
        }
        characterIds.forEachIndexed { index, cId ->
            chatDao.insertChatParticipant(ChatParticipantEntity(chatId = chat.chatId, characterId = cId, sortPosition = index))
        }
        chat.chatId
    }

    suspend fun getChatSettings(chatId: String): ChatSettingsEntity? = withContext(Dispatchers.IO) {
        chatDao.getChatSettings(chatId)
    }

    suspend fun insertChatSettings(settings: ChatSettingsEntity) = withContext(Dispatchers.IO) {
        chatDao.insertChatSettings(settings)
    }

    fun getChatSettingsFlow(chatId: String): Flow<ChatSettingsEntity?> =
        chatDao.getChatSettingsFlow(chatId)
    
    suspend fun updateChat(chat: ChatEntity) = withContext(Dispatchers.IO) {
        chatDao.updateChat(chat)
    }
}
