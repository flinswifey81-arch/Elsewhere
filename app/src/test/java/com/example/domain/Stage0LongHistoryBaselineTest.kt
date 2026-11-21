package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.SpeakerType
import com.example.domain.memory.AutomaticMemoryManager
import com.example.domain.memory.ContextResetPolicy
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

/**
 * Stage 0 characterization tests: record the CURRENT implementation's behavior.
 * These tests do not claim semantic long-term recall works.
 * No provider requests, production data, or network access are used.
 */
@RunWith(RobolectricTestRunner::class)
class Stage0LongHistoryBaselineTest {
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

    private fun message(index: Int, content: String = "Ordinary fictional turn $index") =
        MessageEntity(
            messageId = "message-$index",
            chatId = "stage0-chat",
            speakerType = if (index % 2 == 1) SpeakerType.PERSONA else SpeakerType.CHARACTER,
            speakerId = if (index % 2 == 1) "persona-1" else "character-a",
            speakerDisplayNameSnapshot = if (index % 2 == 1) "Mara" else "Rowan",
            content = content,
            createdAt = index.toLong(),
            orderIndex = index.toLong()
        )

    @Test
    fun rollingSummaryRemainsBoundedAt5002000And10000Messages() = runBlocking {
        for (count in listOf(500, 2_000, 10_000)) {
            val history = (1..count).map(::message)
            val result = AutomaticMemoryManager(repository).updateAfterCompletedTurn(
                chatId = "stage0-chat",
                characterId = "character-a",
                personaMessage = history[count - 2], // final user turn preceding the last assistant reply
                allPrimaryMessages = history
            )
            val summary = requireNotNull(repository.getSummaryOnce("stage0-chat"))
            assertTrue("Summary missing at $count", result.rollingSummaryUpdated)
            assertTrue("Summary exceeded 12,000 chars at $count", summary.summary.length <= 12_000)
            assertEquals((count - 24).toLong(), summary.summarizedThroughOrderIndex)
            assertFalse("Current message leaked into summary at $count",
                summary.summary.contains("Ordinary fictional turn $count"))
        }
    }

    @Test
    fun olderEventDisappearsFromRollingSummaryAt2000Messages() = runBlocking {
        val history = (1..2_000).map { index ->
            if (index == 17) message(index, "The fictional silver compass is locked in the west vault.")
            else message(index)
        }
        AutomaticMemoryManager(repository).updateAfterCompletedTurn(
            chatId = "stage0-chat",
            characterId = "character-a",
            personaMessage = history[1_998],
            allPrimaryMessages = history
        )
        val summary = requireNotNull(repository.getSummaryOnce("stage0-chat"))
        // Characterize known limitation: tail-clipping is NOT durable episodic recall.
        assertFalse(summary.summary.contains("silver compass"))
    }

    @Test
    fun legacyDurableMemoryQueryDoesNotFilterPreWackSources() = runBlocking {
        val preBoundaryMemory = RoleplayMemoryEntity(
            chatId = "stage0-chat", characterId = "character-a",
            category = MemoryCategory.DETAIL,
            content = "The fictional brass key was hidden before Wack.",
            sourceMessageId = "message-17"
        )
        assertTrue(repository.insertIfNew(preBoundaryMemory))
        // Legacy repository queries have no Wack boundary argument.
        val fetched = repository.getMemoriesOnce("stage0-chat", "character-a")
        assertEquals(listOf(preBoundaryMemory.memoryId), fetched.map { it.memoryId })
    }

    @Test
    fun alternateVariantsAreNotBothEligibleForContext() {
        val granted = message(400, "Mara receives the brass key.")
            .copy(messageId = "key-granted", variantGroupId = "key-choice",
                isPrimaryVariant = false)
        val destroyed = message(400, "The brass key is destroyed.")
            .copy(messageId = "key-destroyed", variantGroupId = "key-choice",
                isPrimaryVariant = true)
        val selected = ContextResetPolicy.eligiblePrimaryMessages(
            listOf(granted, destroyed, message(401)), boundary = null
        )
        assertEquals(listOf("key-destroyed", "message-401"), selected.map { it.messageId })
        assertFalse(selected.any { it.content.contains("receives the brass key") })
    }

    @Test
    fun wackBoundaryExcludesOldTranscriptButPreservesPostBoundaryMessages() {
        val old = message(17, "Rowan promised to return the silver compass.")
        val new = message(6_001, "Mara enters the observatory.")
        val eligible = ContextResetPolicy.eligiblePrimaryMessages(
            listOf(old, new), boundary = 6_000L
        )
        assertEquals(listOf("message-6001"), eligible.map { it.messageId })
        assertTrue(eligible.all { it.orderIndex > 6_000L })
    }

    @Test
    fun explicitMemoryRetainsSourceMessageProvenance() = runBlocking {
        val source = message(17, "Remember that the silver compass belongs to Mara.")
        val result = AutomaticMemoryManager(repository).updateAfterCompletedTurn(
            "stage0-chat", "character-a", source, listOf(source)
        )
        val memory = repository.getMemoriesOnce("stage0-chat", "character-a").single()
        assertEquals(1, result.savedMemoryCount)
        assertEquals("message-17", memory.sourceMessageId)
        assertEquals("character-a", memory.characterId)
    }
}
