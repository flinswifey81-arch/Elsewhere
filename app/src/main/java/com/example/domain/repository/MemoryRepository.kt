package com.example.domain.repository

import com.example.data.MemoryDao
import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.RoleplayMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MemoryRepository(private val memoryDao: MemoryDao) {
    fun getMemories(chatId: String, characterId: String): Flow<List<RoleplayMemoryEntity>> =
        memoryDao.getMemories(chatId, characterId)

    fun getSummary(chatId: String): Flow<ConversationSummaryEntity?> = memoryDao.getSummary(chatId)

    suspend fun getMemoriesOnce(chatId: String, characterId: String): List<RoleplayMemoryEntity> =
        withContext(Dispatchers.IO) { memoryDao.getMemoriesOnce(chatId, characterId) }

    suspend fun getSummaryOnce(chatId: String): ConversationSummaryEntity? =
        withContext(Dispatchers.IO) { memoryDao.getSummaryOnce(chatId) }

    suspend fun insertIfNew(memory: RoleplayMemoryEntity) = withContext(Dispatchers.IO) {
        if (memoryDao.countMatchingMemory(memory.chatId, memory.characterId, memory.content) == 0) {
            memoryDao.insertMemory(memory)
        }
    }

    suspend fun deleteMemory(memory: RoleplayMemoryEntity) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemory(memory)
    }

    suspend fun upsertSummary(summary: ConversationSummaryEntity) = withContext(Dispatchers.IO) {
        memoryDao.upsertSummary(summary)
    }
}

