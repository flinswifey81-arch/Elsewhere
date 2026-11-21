package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.MemoryCategory
import com.example.data.model.RoleplayMemoryEntity
import com.example.domain.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Current-storage regression: chat, character, provenance, and duplicate protection. */
@RunWith(RobolectricTestRunner::class)
class Stage0MemoryIsolationTest {
    @Test
    fun memoriesStayScopedByChatAndCharacterAndKeepTheirSourceMessage() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = MemoryRepository(database.memoryDao())
            fun memory(chat: String, character: String, source: String) = RoleplayMemoryEntity(
                chatId = chat, characterId = character,
                category = MemoryCategory.PROMISE,
                content = "Return the silver compass",
                sourceMessageId = source
            )
            assertTrue(repository.insertIfNew(memory("chat-a", "character-a", "msg-17")))
            assertFalse(repository.insertIfNew(memory("chat-a", "character-a", "msg-99")))
            assertTrue(repository.insertIfNew(memory("chat-a", "character-b", "msg-17")))
            assertTrue(repository.insertIfNew(memory("chat-b", "character-a", "msg-17")))

            val stored = repository.getMemoriesOnce("chat-a", "character-a")
            assertEquals(1, stored.size)
            assertEquals("msg-17", stored.single().sourceMessageId)
            assertEquals(1, repository.getMemoriesOnce("chat-a", "character-b").size)
            assertEquals(1, repository.getMemoriesOnce("chat-b", "character-a").size)
            assertTrue(repository.getMemoriesOnce("chat-b", "character-b").isEmpty())
        } finally {
            database.close()
        }
    }
}
