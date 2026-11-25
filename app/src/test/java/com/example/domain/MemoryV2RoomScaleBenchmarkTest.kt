package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.compiler.ContextBudgetProbe
import com.example.domain.compiler.ContextBudgetBucket
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.compiler.PromptOptionalContextChoices
import com.example.domain.compiler.PromptOptionalContextFitter
import com.example.domain.compiler.PromptHistoryTrimmer
import com.example.domain.memory.StoryCharacterPerspective
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

    private val rowanCharacter = CharacterEntity(
        characterId = "rowan", displayName = "Rowan", characterName = "Rowan",
        aliases = emptyList(), importSchemaVersion = 1,
        identityJson = "A stubborn traveling scholar.",
        appearanceJson = null, personalityJson = null, voiceJson = null,
        behaviorJson = null, backstoryJson = null, knowledgeJson = null,
        worldContextJson = null, writingRulesJson = null, examplesJson = null,
        authorNotesJson = null, extensionFieldsJson = null
    )

    private fun medianMs(times: List<Long>): Double =
        times.sorted()[times.size / 2] / 1_000_000.0

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

            // Final request budget (not just a standalone size estimate).
            // Deliberately crowd it with generated, lower-priority summary
            // and assert that reviewed source-linked events survive.
            val enormousSummary = "Generated journey recap. ".repeat(700)
            val budgetOptions = PromptOptionalContextChoices(
                includeLegacyMemories = false,
                includeStoryMemories = true,
                includeSummary = true
            )
            val finalFit = PromptOptionalContextFitter.fit(
                available = budgetOptions, modelContextWindowTokens = 8_192
            ) { choices ->
                compiler.compile(
                    scenario, persona, settings, history, current,
                    worldContext = "AUTHORED_WORLD_ROUTE_STAYS",
                    summary = if (choices.includeSummary) enormousSummary else null,
                    storyMemories = if (choices.includeStoryMemories) warm.selected else emptyList()
                )
            }
            assertTrue("Full prompt fits at $total", finalFit.decision.allowed)
            assertTrue("Generated summary gave way at $total", finalFit.summaryOmitted)
            assertFalse("Approved memory was dropped at $total", finalFit.storyMemoriesOmitted)
            assertTrue(finalFit.messages.first().content.contains("AUTHORED_WORLD_ROUTE_STAYS"))
            assertTrue(finalFit.messages.first().content.contains("Silver compass waypoint 17"))
            assertFalse(finalFit.messages.first().content.contains("Generated journey recap."))
            assertTrue(finalFit.messages.any { it.sourceMessageId == current.messageId })
            val finalUsage = ContextBudgetProbe.measure(finalFit.messages)
            assertTrue(
                "Approved Scenario Story Memory not attributed to MEMORY at $total",
                finalUsage.utf8BytesByBucket.getValue(ContextBudgetBucket.MEMORY) > 0
            )
            val hypotheticalTextUsd =
                requireNotNull(finalUsage.approximateTextInputUsd(2.5))
            assertTrue(hypotheticalTextUsd > 0)
            // This is a synthetic rate, NOT an OpenRouter model quote/bill.
            assertEquals(
                finalUsage.approximateInputTokens * 2.5 / 1_000_000.0,
                hypotheticalTextUsd, 0.00000001
            )

            // New character-level knowledge assertions reuse the SAME 10k
            // archive. A CHARACTER_ONLY secret must never bleed into the
            // narrator or another participant's prompt.
            database.chatDao().insertChatParticipant(
                ChatParticipantEntity(chatId = chatId, characterId = "rowan")
            )
            database.chatDao().insertChatParticipant(
                ChatParticipantEntity(chatId = chatId, characterId = "selene")
            )
            val secretMarker = "ROWAN_PRIVATE_COMPASS_$total"
            val privateMemory = repository.saveManual(
                chatId,
                "Silver compass waypoint: $secretMarker is Rowan's private theory."
            )
            repository.setCharacterKnowledge(chatId, privateMemory.memoryId, setOf("rowan"))
            repository.recordCharacterPerspective(
                chatId, privateMemory.memoryId, "rowan",
                StoryCharacterPerspective.BELIEVES,
                note = "PRIVATE_PLAYER_NOTE_NEVER_SEND"
            )
            val rowan = StoryActorRef(StoryActorKind.CHARACTER, "rowan")
            val selene = StoryActorRef(StoryActorKind.CHARACTER, "selene")
            val rowanRead = repository.retrieveForGeneration(
                chatId, rowan, "silver compass waypoint"
            )
            val seleneRead = repository.retrieveForGeneration(
                chatId, selene, "silver compass waypoint"
            )
            val narratorRead = repository.retrieveForGeneration(
                chatId, narrator, "silver compass waypoint"
            )
            assertEquals(1, rowanRead.selected.count { it.memoryId == privateMemory.memoryId })
            val rowanFact = rowanRead.selected.single { it.memoryId == privateMemory.memoryId }
            assertTrue(rowanFact.content.contains("Character belief"))
            assertTrue(rowanFact.content.contains(secretMarker))
            assertFalse(rowanFact.content.contains("PRIVATE_PLAYER_NOTE_NEVER_SEND"))
            assertFalse(seleneRead.selected.any { it.memoryId == privateMemory.memoryId })
            assertFalse(narratorRead.selected.any { it.memoryId == privateMemory.memoryId })
            assertTrue(
                (seleneRead.excludedCounts[StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT]
                    ?: 0) > 0
            )
            // Compile actual Solo and Group prompts from RECIPIENT-SCOPED
            // retrieval, not narrator memory. Group must never broadcast
            // Rowan's subjective belief to another Character.
            val characterCompiler = ContextCompilerV1()
            val soloPrompt = characterCompiler.compileSoloContext(
                character = rowanCharacter, persona = persona,
                chatHistory = history, currentMessage = current,
                responseProfile = ResponseLengthProfile.NORMAL,
                customMin = null, customTargetMax = null, customHardMax = null,
                storyMemories = rowanRead.selected,
                worldContext = "WORLD_RULES_ARE_PROTECTED"
            )
            val rowanGroupPrompt = characterCompiler.compileGroupCharacterContext(
                character = rowanCharacter, persona = persona,
                activeCharacterNames = listOf("Rowan", "Selene"),
                chatHistory = history, currentMessage = current,
                responseProfile = ResponseLengthProfile.NORMAL,
                customMin = null, customTargetMax = null, customHardMax = null,
                storyMemories = rowanRead.selected,
                worldContext = "WORLD_RULES_ARE_PROTECTED"
            )
            val seleneGroupPrompt = characterCompiler.compileGroupCharacterContext(
                character = rowanCharacter.copy(
                    characterId = "selene", displayName = "Selene", characterName = "Selene"
                ), persona = persona,
                activeCharacterNames = listOf("Rowan", "Selene"),
                chatHistory = history, currentMessage = current,
                responseProfile = ResponseLengthProfile.NORMAL,
                customMin = null, customTargetMax = null, customHardMax = null,
                storyMemories = seleneRead.selected,
                worldContext = "WORLD_RULES_ARE_PROTECTED"
            )
            for (allowed in listOf(soloPrompt, rowanGroupPrompt)) {
                assertTrue(
                    "Approved Character Story Memory not attributed to MEMORY at $total",
                    ContextBudgetProbe.measure(allowed)
                        .utf8BytesByBucket.getValue(ContextBudgetBucket.MEMORY) > 0
                )
                val text = allowed.first().content
                assertTrue("Private belief missing at $total", text.contains(secretMarker))
                assertTrue(text.contains("CHARACTER_BELIEF_UNCONFIRMED"))
                assertTrue(text.contains("WORLD_RULES_ARE_PROTECTED"))
                assertFalse(text.contains("PRIVATE_PLAYER_NOTE_NEVER_SEND"))
                assertFalse(text.contains("Silver compass waypoint 17"))
            }
            val seleneText = seleneGroupPrompt.joinToString("\n") { it.content }
            assertFalse("Secret reached another group recipient at $total",
                seleneText.contains(secretMarker))
            assertTrue(seleneText.contains("Respond only as Selene"))

            // Editing the underlying fact invalidates old in-story stance;
            // an old grant never authorizes a changed belief implicitly.
            repository.update(
                chatId, privateMemory.memoryId,
                text = "Silver compass waypoint: $secretMarker was disproven."
            )
            assertFalse(repository.retrieveForGeneration(
                chatId, rowan, "silver compass waypoint"
            ).selected.any { it.memoryId == privateMemory.memoryId })

            // A regeneration switch renders the originally selected source
            // ineligible without deleting historical memory rows.
            val primary = history[28]
            val alternate = primary.copy(
                messageId = "discarded-$total",
                content = "DISCARDED_VARIANT_SECRET",
                isPrimaryVariant = true
            )
            database.withTransaction {
                database.messageDao().updateMessage(primary.copy(isPrimaryVariant = false))
                database.messageDao().updateMessage(alternate)
            }
            val afterVariantSwitch = repository.retrieveForGeneration(
                chatId, narrator, "silver compass waypoint"
            )
            assertFalse(afterVariantSwitch.selected.any { it.memoryId == saved[1].memoryId })
            assertTrue(
                "Exact variant gate at $total",
                (afterVariantSwitch.excludedCounts[StoryMemoryExclusion.SOURCE_NOT_SELECTED] ?: 0) > 0
            )

            // A real continuity question need not repeat the exact words used
            // when an event was saved. Test ranking against newer distracting
            // facts at 500, 2k and 10k messages through production Room reads.
            val callback = repository.saveManual(chatId,
                "Nessa hid the letter behind the greenhouse mural.")
            val distractor = repository.saveManual(chatId,
                "Nessa chatted with the archivist at the evening market.")
            val forbidden = repository.saveManual(chatId,
                "Nessa stashed the missive beneath the secret vault.")
            repository.update(chatId, forbidden.memoryId, hidden = true)
            val paraphrase = repository.retrieveForGeneration(
                chatId, narrator, "Where did Nessa stash the missive?", maxMemories = 1
            )
            assertEquals("Wrong recalled event at $total",
                listOf(callback.memoryId), paraphrase.selected.map { it.memoryId })
            // Existing hidden Story Memory plus this concealed callback: both excluded.
            assertTrue((paraphrase.excludedCounts[StoryMemoryExclusion.HIDDEN] ?: 0) >= 2)
            val callbackPrompt = compiler.compile(
                scenario, persona, settings, history, current,
                storyMemories = paraphrase.selected
            ).joinToString("\\n") { it.content }
            assertTrue("Paraphrased callback omitted at $total",
                callbackPrompt.contains("greenhouse mural"))
            assertFalse(callbackPrompt.contains("evening market"))
            assertFalse(callbackPrompt.contains("secret vault"))
            assertNotEquals(callback.memoryId, distractor.memoryId)

            // The old archive remains stored, but the new Wack boundary is a
            // hard eligibility gate until one record is explicitly restored.
            val boundary = total.toLong() - 5L
            database.chatDao().setContextResetBoundary(chatId, boundary, boundary)
            val afterWack = repository.retrieveForGeneration(
                chatId, narrator, "silver compass waypoint"
            )
            assertFalse(afterWack.selected.any { it.memoryId == saved.first().memoryId })
            assertTrue(
                "Wack blocked pre-boundary records at $total",
                (afterWack.excludedCounts[StoryMemoryExclusion.WACK_BOUNDARY] ?: 0) >= 5
            )
            repository.restoreAcrossWack(chatId, saved.first().memoryId)
            val afterOneRestore = repository.retrieveForGeneration(
                chatId, narrator, "silver compass waypoint"
            )
            assertTrue(afterOneRestore.selected.any { it.memoryId == saved.first().memoryId })
            assertFalse(afterOneRestore.selected.any { it.memoryId == saved[2].memoryId })

            val usage = ContextBudgetProbe.measure(prompt)
            promptBytes += usage.inputUtf8Bytes
            println(
                "MEMORY_V2_ROOM_BENCHMARK messages=$total selectedMemories=8 " +
                    "sourceRows=${sourceRows.size} variantRows=${variantRows.size} providerCalls=0 " +
                    "retrievalMedianMs=${medianMs(readTimes)} " +
                    "prepareAndPreflightMedianMs=${medianMs(prepareTimes)} " +
                    "promptMessages=${prompt.size} promptBytes=${usage.inputUtf8Bytes} " +
                    "approxInputTokens=${usage.approximateInputTokens} " +
                    "safeFinalInputTokens=${finalFit.decision.estimatedInputTokens} " +
                    "safeInputCeiling=${finalFit.decision.inputCeilingTokens} " +
                    "finalPromptBytes=${finalUsage.inputUtf8Bytes} " +
                    "hypotheticalTextUsdAt2Point5PerMillion=${hypotheticalTextUsd} " +
                    "privateRecipients=1 variantInvalidation=true wackRestoreOne=true"
            )
        }
        assertTrue("Prompt scaled with the archive: $promptBytes",
            promptBytes.maxOrNull()!! - promptBytes.minOrNull()!! < 2_000L)
    }
}
