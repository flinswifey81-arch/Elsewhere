package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.compiler.ContextBudgetProbe
import com.example.domain.compiler.PromptHistoryTrimmer
import com.example.domain.compiler.ScenarioContextCompiler
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryActorRef
import com.example.domain.memory.StoryMemoryExclusion
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.StoryMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real Room-backed 500/2k/10k synthetic transcript benchmark.
 * All reads go through production retrieval; no provider or network is used.
 * Timings are diagnostics, NOT absolute CI performance thresholds.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryV2RoomScaleBenchmarkTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: StoryMemoryRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = StoryMemoryRepository(database, AppDataMaintenanceCoordinator())
    }

    @After fun tearDown() = database.close()

    private val persona = PersonaEntity(
        personaId = "mara", displayName = "Mara", personaName = "Mara",
        aliases = emptyList(), importSchemaVersion = 1,
        identityJson = null, appearanceJson = null, personalityJson = null,
        roleplayProfileJson = null, backgroundJson = null,
        worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
    )

    private fun medianMs(times: List<Long>): Double = times.sorted()[times.size / 2] / 1_000_000.0

    @Test
    fun roomRetrievalAndPreparationAt5002000And10000Messages() = runBlocking {
        val promptBytes = mutableListOf<Long>()
        for (total in listOf(500, 2_000, 10_000)) {
            val chatId = "bench-$total"
            database.chatDao().insertChat(
                ChatEntity(chatId = chatId, displayName = "Synthetic long chat", chatType = ChatType.SCENARIO)
            )
            val important = setOf(17, 29, 41, 53, 67, 79, total - 2, total - 1)
            val history = (1..total).map { order ->
                MessageEntity(
                    messageId = "m-$total-$order", chatId = chatId,
                    speakerType = SpeakerType.NARRATOR, speakerId = "narrator:$chatId",
                    speakerDisplayNameSnapshot = "Narrator",
                    content = if (order in important)
                        "Silver compass waypoint $order was visited and documented."
                    else "Ordinary fictional travel beat $order.",
                    createdAt = 1_700_000_000_000L + order,
                    orderIndex = order.toLong()
                )
            }
            // Fixture population is excluded from measured read latency.
            database.withTransaction {
                history.forEach { database.messageDao().insertMessage(it) }
                database.messageDao().insertMessage(
                    history[28].copy(
                        messageId = "discarded-$total", content = "DISCARDED_VARIANT_SECRET",
                        isPrimaryVariant = false
                    )
                )
            }
            val saved = history.filter { it.orderIndex.toInt() in important }.map {
                repository.saveFromMessage(chatId, it.messageId)
            }
            val hidden = repository.saveManual(chatId, "Silver compass hidden private memo")
            repository.update(chatId, hidden.memoryId, hidden = true)
            val pending = repository.saveManual(chatId, "Silver compass pending review memo")
            database.storyMemoryDao().update(pending.copy(review = "PENDING_REVIEW"))
            val narrator = StoryActorRef(StoryActorKind.NARRATOR, "narrator:$chatId")
            val query = "silver compass waypoint"

            // Warm-up before recording median of three independently checked reads.
            val warm = repository.retrieveForGeneration(chatId, narrator, query)
            assertEquals("Recall at $total", 8, warm.selected.size)
            assertEquals(8, warm.eligibleCount)
            assertEquals(1, warm.excludedCounts[StoryMemoryExclusion.HIDDEN])
            assertEquals(1, warm.excludedCounts[StoryMemoryExclusion.NOT_APPROVED])
            val readTimes = (1..3).map {
                val begin = System.nanoTime()
                val result = repository.retrieveForGeneration(chatId, narrator, query)
                val elapsed = System.nanoTime() - begin
                assertEquals(warm.selected.map { it.memoryId }, result.selected.map { it.memoryId })
                elapsed
            }
            val sourceRows = database.messageDao().getMemorySourceMessages(
                chatId, saved.mapNotNull { it.sourceMessageId }
            )
            val variantRows = database.messageDao().getMemorySourceVariants(
                chatId, saved.mapNotNull { it.sourceVariantGroupId }
            )
            assertEquals("Bounded source read at $total", 8, sourceRows.size)
            assertEquals("Discarded variant checked at $total", 9, variantRows.size)

            val current = MessageEntity(
                messageId = "current-$total", chatId = chatId,
                speakerType = SpeakerType.PERSONA, speakerId = "mara",
                speakerDisplayNameSnapshot = "Mara",
                content = "Where is our silver compass?",
                orderIndex = total.toLong() + 1
            )
            val scenario = ScenarioDefinition(title = "The Long Road", premise = "A continuous journey")
            val settings = ChatSettingsEntity(chatId = chatId)
            val compiler = ScenarioContextCompiler()
            val prepareTimes = mutableListOf<Long>()
            var prompt = compiler.compile(scenario, persona, settings, history, current, storyMemories = warm.selected)
            repeat(3) {
                val begin = System.nanoTime()
                prompt = compiler.compile(scenario, persona, settings, history, current, storyMemories = warm.selected)
                val budget = PromptHistoryTrimmer.fit(prompt, 8_192)
                assertTrue("Prompt within budget at $total", budget.decision.allowed)
                assertEquals(0, budget.removedHistoricalTurns)
                prepareTimes += System.nanoTime() - begin
            }
            val flattened = prompt.joinToString("\n") { it.content }
            assertEquals(26, prompt.size)
            assertEquals(25, prompt.count { it.sourceMessageId != null })
            assertTrue(flattened.contains("Silver compass waypoint 17"))
            assertTrue(flattened.contains("Where is our silver compass?"))
            assertFalse(flattened.contains("DISCARDED_VARIANT_SECRET"))
            assertFalse(flattened.contains("Silver compass hidden private memo"))
            assertFalse(flattened.contains("Silver compass pending review memo"))
            val usage = ContextBudgetProbe.measure(prompt)
            promptBytes += usage.inputUtf8Bytes
            println(
                "MEMORY_V2_ROOM_BENCHMARK messages=$total selectedMemories=8 " +
                    "sourceRows=${sourceRows.size} variantRows=${variantRows.size} providerCalls=0 " +
                    "retrievalMedianMs=${medianMs(readTimes)} " +
                    "prepareAndPreflightMedianMs=${medianMs(prepareTimes)} " +
                    "promptMessages=${prompt.size} promptBytes=${usage.inputUtf8Bytes} " +
                    "approxInputTokens=${usage.approximateInputTokens}"
            )
        }
        assertTrue("Prompt scaled with the archive: $promptBytes",
            promptBytes.maxOrNull()!! - promptBytes.minOrNull()!! < 2_000L)
    }
}
