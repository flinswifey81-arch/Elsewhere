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
    private val messageDao: MessageDao? = null,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
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
    ): String = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
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
    }

    suspend fun createGroupChat(
        displayName: String,
        personaIds: List<String>,
        characterIds: List<String>
    ): String = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
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
    }

    suspend fun getChatSettings(chatId: String): ChatSettingsEntity? = withContext(Dispatchers.IO) {
        chatDao.getChatSettings(chatId)
    }

    suspend fun insertChatSettings(settings: ChatSettingsEntity) =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) { chatDao.insertChatSettings(settings) }
        }

    fun getChatSettingsFlow(chatId: String): Flow<ChatSettingsEntity?> =
        chatDao.getChatSettingsFlow(chatId)

    fun getParticipantsForChat(chatId: String): Flow<List<ChatParticipantEntity>> =
        chatDao.getParticipantsForChat(chatId)

    suspend fun getParticipantsForChatOnce(chatId: String): List<ChatParticipantEntity> =
        withContext(Dispatchers.IO) { chatDao.getParticipantsForChatOnce(chatId) }

    fun getPersonaParticipantsForChat(chatId: String): Flow<List<ChatPersonaParticipantEntity>> =
        chatDao.getPersonaParticipantsForChat(chatId)

    suspend fun setGroupCharacterActive(chatId: String, characterId: String, isActive: Boolean) =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
                chatDao.setChatParticipantActive(chatId, characterId, isActive)
            }
        }

    suspend fun removeGroupCharacter(chatId: String, characterId: String) =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
                chatDao.deleteChatParticipant(chatId, characterId)
            }
        }

    suspend fun addGroupCharacter(chatId: String, characterId: String) =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
                chatDao.addChatParticipantAtEnd(chatId, characterId)
            }
        }

    suspend fun moveGroupCharacter(chatId: String, characterId: String, direction: Int) =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
                chatDao.moveChatParticipant(chatId, characterId, direction)
            }
        }
    
    suspend fun updateChat(chat: ChatEntity) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) { chatDao.updateChat(chat) }
    }

    suspend fun establishContextReset(chatId: String, requestedBoundary: Long = System.currentTimeMillis()): ChatEntity? =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
                chatDao.establishContextReset(chatId, requestedBoundary)
            }
        }

    suspend fun deleteChat(chatId: String) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) { chatDao.deleteChatAndOwnedData(chatId) }
    }
}
