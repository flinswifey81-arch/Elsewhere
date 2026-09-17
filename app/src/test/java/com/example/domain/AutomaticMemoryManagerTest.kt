package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.memory.AutomaticMemoryManager
import com.example.domain.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticMemoryManagerTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(database.memoryDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun explicitFactsPersistAndOlderMessagesRollIntoSummary() = runBlocking {
        val manager = AutomaticMemoryManager(repository, recentMessageLimit = 2)
        val messages = (1L..5L).map { index ->
            MessageEntity(
                messageId = "message-$index",
                chatId = "chat-1",
                speakerType = if (index % 2L == 1L) SpeakerType.PERSONA else SpeakerType.CHARACTER,
                speakerId = if (index % 2L == 1L) "persona-1" else "character-1",
                speakerDisplayNameSnapshot = if (index % 2L == 1L) "Mara" else "Rowan",
                content = if (index == 5L) "Remember that my favorite place is the old observatory." else "Turn $index",
                orderIndex = index
            )
        }

        manager.updateAfterCompletedTurn(
            chatId = "chat-1",
            characterId = "character-1",
            personaMessage = messages.last(),
            allPrimaryMessages = messages
        )

        val memories = repository.getMemoriesOnce("chat-1", "character-1")
        assertEquals(1, memories.size)
        assertEquals("my favorite place is the old observatory", memories.single().content)
        val summary = repository.getSummaryOnce("chat-1")
        assertTrue(summary!!.summary.contains("Turn 1"))
        assertTrue(summary.summary.contains("Turn 3"))
        assertFalse(summary.summary.contains("old observatory"))
    }

    @Test
    fun credentialLikeTextIsNeverStoredAsMemory() = runBlocking {
        val manager = AutomaticMemoryManager(repository)
        val message = MessageEntity(
            chatId = "chat-1",
            speakerType = SpeakerType.PERSONA,
            speakerId = "persona-1",
            speakerDisplayNameSnapshot = "Mara",
            content = "Remember that my API key is sk-not-a-real-secret",
            orderIndex = 1L
        )

        manager.updateAfterCompletedTurn("chat-1", "character-1", message, listOf(message))

        assertTrue(repository.getMemoriesOnce("chat-1", "character-1").isEmpty())
    }
}

