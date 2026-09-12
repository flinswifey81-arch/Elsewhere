package com.example.domain.repository

import com.example.data.ChatDao
import com.example.data.model.ChatEntity
import com.example.data.model.ChatParticipantEntity
import com.example.data.model.ChatPersonaParticipantEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ChatSummary
import com.example.data.model.ChatType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRepository(private val chatDao: ChatDao) {
    fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()
    
    fun getAllChatSummaries(): Flow<List<ChatSummary>> = chatDao.getAllChatSummaries()
    
    fun getRecentChatSummaries(limit: Int = 3): Flow<List<ChatSummary>> = chatDao.getRecentChatSummaries(limit)
    
    suspend fun getChatSummaryById(id: String): ChatSummary? = withContext(Dispatchers.IO) {
        chatDao.getChatSummaryById(id)
    }

    suspend fun createSoloChat(
        displayName: String,
        personaId: String,
        characterId: String
    ): String = withContext(Dispatchers.IO) {
        val chat = ChatEntity(displayName = displayName, chatType = ChatType.SOLO)
        chatDao.insertChat(chat)
        chatDao.insertChatPersonaParticipant(ChatPersonaParticipantEntity(chatId = chat.chatId, personaId = personaId))
        chatDao.insertChatParticipant(ChatParticipantEntity(chatId = chat.chatId, characterId = characterId))
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
    
    suspend fun updateChat(chat: ChatEntity) = withContext(Dispatchers.IO) {
        chatDao.updateChat(chat)
    }
}
