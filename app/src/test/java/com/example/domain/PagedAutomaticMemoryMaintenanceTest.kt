package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.memory.AutomaticMemoryManager
import com.example.domain.memory.ContextResetPolicy
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Paged Room maintenance must reproduce whole-archive results exactly. */
@RunWith(RobolectricTestRunner::class)
class PagedAutomaticMemoryMaintenanceTest {
    private lateinit var db: AppDatabase
    private lateinit var messages: MessageRepository
    private lateinit var memory: MemoryRepository
    private lateinit var manager: AutomaticMemoryManager

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val coordinator = AppDataMaintenanceCoordinator()
        messages = MessageRepository(db.messageDao(), coordinator)
        memory = MemoryRepository(db.memoryDao(), coordinator)
        manager = AutomaticMemoryManager(memory)
    }

    @After fun tearDown() = db.close()

    private fun turn(id: Int, chat: String, content: String, persona: Boolean = false) =
        MessageEntity(
            messageId = "turn-$chat-$id",
            chatId = chat,
            speakerType = if (persona) SpeakerType.PERSONA else SpeakerType.CHARACTER,
            speakerId = if (persona) "persona" else "character",
            speakerDisplayNameSnapshot = if (persona) "Mara" else "Rowan",
            content = content,
            orderIndex = id.toLong()
        )

    private suspend fun insertChat(chatId: String, total: Int, long: Boolean): List<MessageEntity> {
        db.chatDao().insertChat(ChatEntity(
            chatId = chatId, displayName = "Synthetic", chatType = ChatType.SOLO
        ))
        val history = (1..total).map { index ->
            val text = if (long) {
                "A remembered scene $index where the travelers discussed their plans. ".repeat(4)
            } else if (index % 7 == 0) "" else "Short scene $index"
            turn(index, chatId, text, persona = index % 5 == 0)
        }
        db.withTransaction {
            history.forEach { db.messageDao().insertMessage(it) }
        }
        return history
    }

    @Test fun suffixMatchesWholeArchiveAt5002000And10000Messages() = runBlocking {
        for (total in listOf(500, 2_000, 10_000)) {
            val chatId = "large-$total"
            val full = insertChat(chatId, total, long = true)
            val selected = manager.loadSummarySource(chatId, null, messages)
            assertTrue("Paged source at $total fetched ${selected.size} rows",
                selected.size < total / 2)
            val legacyOlder = full.dropLast(24)
            val pagedOlder = selected.dropLast(24)
            assertEquals("Summary text changed at $total",
                AutomaticMemoryManager.rollingSummaryTail(legacyOlder),
                AutomaticMemoryManager.rollingSummaryTail(pagedOlder))
            assertEquals(
                legacyOlder.maxOf { it.orderIndex },
                pagedOlder.maxOf { it.orderIndex }
            )
            assertEquals(full.last().messageId, selected.last().messageId)
            println("PAGED_AUTOMATIC_MEMORY messages=$total sourceRows=${selected.size} providerCalls=0")
        }
    }

    @Test fun shortAndEmptyTurnsStillMatchWithoutTruncatingEarly() = runBlocking {
        val full = insertChat("short", 500, long = false)
        val paged = manager.loadSummarySource("short", null, messages, pageSize = 31)
        assertEquals(full, paged)
        assertEquals(
            AutomaticMemoryManager.rollingSummaryTail(full.dropLast(24)),
            AutomaticMemoryManager.rollingSummaryTail(paged.dropLast(24))
        )
    }

    @Test fun afterWackNeverIncludesPreResetRowsAndMatchesLegacySummary() = runBlocking {
        val full = insertChat("wack", 500, long = true)
        val boundary = 275L
        val expected = ContextResetPolicy.eligiblePrimaryMessages(full, boundary)
        val paged = manager.loadSummarySource("wack", boundary, messages)
        assertTrue(paged.isNotEmpty())
        assertTrue(paged.all { it.orderIndex > boundary })
        assertEquals(
            AutomaticMemoryManager.rollingSummaryTail(expected.dropLast(24)),
            AutomaticMemoryManager.rollingSummaryTail(paged.dropLast(24))
        )
        assertEquals(expected.dropLast(24).maxOf { it.orderIndex },
            paged.dropLast(24).maxOf { it.orderIndex })
    }

    @Test fun completedTurnPersistsSameSummaryAndPersonaMemoryAsLegacy() = runBlocking {
        val full = insertChat("completion", 150, long = true).toMutableList()
        val source = turn(151, "completion",
            "I promise to return the compass.", persona = true)
        val reply = turn(152, "completion", "I will hold you to that promise.")
        messages.insertMessage(source)
        messages.insertMessage(reply)
        full.add(source)
        full.add(reply)

        val newResult = manager.updateAfterCompletedTurnFromStoredHistory(
            "completion", "character", source, messages, resetBoundary = null
        )
        val pagedSummary = requireNotNull(memory.getSummaryOnce("completion"))
        assertEquals(1, newResult.savedMemoryCount)
        assertTrue(newResult.rollingSummaryUpdated)

        val legacyResult = manager.updateAfterCompletedTurn(
            "completion", "character", source, full
        )
        val legacySummary = requireNotNull(memory.getSummaryOnce("completion"))
        assertEquals(0, legacyResult.savedMemoryCount)
        assertEquals(pagedSummary.summary, legacySummary.summary)
        assertEquals(pagedSummary.summarizedThroughOrderIndex,
            legacySummary.summarizedThroughOrderIndex)
        assertEquals("to return the compass",
            memory.getMemoriesOnce("completion", "character").single().content)
    }

    @Test fun manualPagedMemoryMatchesLegacyAndNeverExtractsPreWackFacts() = runBlocking {
        val chatId = "manual-memory"
        val original = insertChat(chatId, 300, long = true)
        val edits = mapOf(
            40 to "I prefer forbidden mint tea.",
            150 to "I prefer jasmine tea.",
            200 to "Remember that the silver compass rests beneath the bridge.",
            250 to "I prefer jasmine tea." // Duplicate must keep source turn 150.
        )
        val expected = original.map { message ->
            val updatedText = edits[message.orderIndex.toInt()]
            if (updatedText == null) message else message.copy(content = updatedText).also {
                db.messageDao().updateMessage(it)
            }
        }
        val eligible = ContextResetPolicy.eligiblePrimaryMessages(expected, 100L)
        val legacy = manager.updateFromConversation(
            chatId, "legacy-character", eligible
        )
        val legacySummary = requireNotNull(memory.getSummaryOnce(chatId))
        val legacyFacts = memory.getMemoriesOnce(chatId, "legacy-character")
            .map { Triple(it.category, it.content, it.sourceMessageId) }

        val paged = manager.updateFromStoredConversation(
            chatId, "paged-character", messages, resetBoundary = 100L
        )
        val pagedSummary = requireNotNull(memory.getSummaryOnce(chatId))
        val pagedFacts = memory.getMemoriesOnce(chatId, "paged-character")
            .map { Triple(it.category, it.content, it.sourceMessageId) }.toSet()

        assertEquals(2, legacy.savedMemoryCount)
        assertEquals(legacy.savedMemoryCount, paged.savedMemoryCount)
        assertEquals(legacyFacts, pagedFacts)
        assertEquals(legacySummary.summary, pagedSummary.summary)
        assertEquals(legacySummary.summarizedThroughOrderIndex,
            pagedSummary.summarizedThroughOrderIndex)
        assertFalse(pagedFacts.any { it.second.contains("forbidden mint tea") })
        assertTrue(pagedFacts.any { it.second == "jasmine tea" })
        assertEquals(
            "turn-manual-memory-150",
            pagedFacts.single { it.second == "jasmine tea" }.third
        )
    }

    @Test fun fewerThanRecentLimitMessagesProduceNoSummary() = runBlocking {
        val history = insertChat("tiny", 12, long = true)
        val selected = manager.loadSummarySource("tiny", null, messages)
        assertEquals(history, selected)
        val result = manager.updateAfterCompletedTurnFromStoredHistory(
            "tiny", "character", history.last().copy(
                speakerType = SpeakerType.PERSONA,
                speakerId = "persona"
            ), messages, resetBoundary = null
        )
        assertFalse(result.rollingSummaryUpdated)
        assertNull(memory.getSummaryOnce("tiny"))
    }
}
