package com.example.domain

import com.example.data.model.ChatSettingsEntity
import com.example.data.model.CharacterEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.ScenarioDefinition
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextBudgetBucket
import com.example.domain.compiler.ContextBudgetProbe
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.compiler.ScenarioContextCompiler
import com.example.domain.memory.ContextResetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Stage 0 fixed synthetic long-story cases, NOT model-answer quality tests.
 * A passing assertion of "lost" is a reproducible baseline deficit, not success at recall.
 * No live provider traffic, API keys, or real user transcripts.
 */
class Stage0ContextCostAndContinuityBenchmarkTest {
    private val persona = PersonaEntity(
        personaId = "mara", displayName = "Mara", personaName = "Mara",
        aliases = emptyList(), importSchemaVersion = 1,
        identityJson = null, appearanceJson = null, personalityJson = null,
        roleplayProfileJson = null, backgroundJson = null,
        worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
    )
    private val scenario = ScenarioDefinition(title = "The Long Road", premise = "A continuous journey")
    private val settings = ChatSettingsEntity(chatId = "stage0-benchmark")
    private val character = CharacterEntity(
        characterId = "rowan", displayName = "Rowan", characterName = "Rowan",
        aliases = emptyList(), importSchemaVersion = 1,
        identityJson = null, appearanceJson = null, personalityJson = null,
        voiceJson = null, behaviorJson = null, backstoryJson = null,
        knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
        examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
    )

    private val earlyFacts = mapOf(
        17 to "EARLY_PROMISE: Mara swore to return Rowan's silver compass.",
        29 to "EARLY_SECRET: The caretaker concealed a letter behind the chapel bell.",
        41 to "EARLY_FALSE_BELIEF: Rowan thinks the bridge is intact, but it collapsed.",
        53 to "EARLY_RELATIONSHIP: Mara and Selene became trusted rivals.",
        67 to "EARLY_LOCATION: The party moved its home from the village to the harbor.",
        79 to "EARLY_RESOLVED_THREAD: The stolen treaty was returned and its quest ended."
    )

    private fun message(index: Int, content: String) = MessageEntity(
        messageId = "beat-$index",
        chatId = "stage0-benchmark",
        speakerType = SpeakerType.NARRATOR,
        speakerId = "narrator:stage0-benchmark",
        speakerDisplayNameSnapshot = "Narrator",
        content = content,
        orderIndex = index.toLong()
    )

    private fun history(total: Int): List<MessageEntity> = buildList {
        for (index in 1..total) {
            val body = earlyFacts[index]
                ?: if (index == total - 6) "RECENT_SCENE: Campfire after the winter crossing."
                else if (index == total - 5) "SELECTED_BRANCH: Opened the northern gate."
                else "Ordinary scene beat $index, nightfall and travel."
            val msg = message(index, body)
            add(if (index == total - 5) msg.copy(
                variantGroupId = "choice-at-$index", isPrimaryVariant = true
            ) else msg)
            if (index == total - 5) add(msg.copy(
                messageId = "discarded-choice-at-$index",
                content = "UNSELECTED_BRANCH: Secretly closed the northern gate.",
                variantGroupId = "choice-at-$index",
                isPrimaryVariant = false
            ))
        }
    }

    private fun next(total: Int) =
        message(total + 1, "What should happen as the party reaches the mountains?")

    private fun prompt(total: Int, summary: String? = null, memories: List<RoleplayMemoryEntity> = emptyList()) =
        ScenarioContextCompiler().compile(
            scenario = scenario, persona = persona, settings = settings,
            history = history(total), current = next(total),
            summary = summary, memories = memories
        )

    @Test
    fun realisticContinuityFactsDisappearWhilePromptFootprintStaysNearlyFlat() {
        val snapshots = listOf(500, 2_000, 10_000).map { count ->
            val began = System.nanoTime()
            // The real rolling summary is capped at 12,000 characters. Use the
            // same fixed-size synthetic recent-summary proxy at each history size.
            val compiled = prompt(count, summary = "Recent ordinary scene details. ".repeat(400).take(12_000))
            val footprint = ContextBudgetProbe.measure(compiled)
            val content = compiled.joinToString("\n") { it.content }
            earlyFacts.values.forEach { fact ->
                assertFalse("Unexpected whole-history retention at $count: $fact", content.contains(fact))
            }
            assertFalse(content.contains("UNSELECTED_BRANCH"))
            assertTrue(content.contains("SELECTED_BRANCH"))
            assertTrue(content.contains("RECENT_SCENE"))
            assertEquals(26, compiled.size)
            assertEquals(25, compiled.count { it.sourceMessageId != null })
            assertTrue(footprint.utf8BytesByBucket.getValue(ContextBudgetBucket.SUMMARY) >= 12_000L)
            val durationMs = (System.nanoTime() - began) / 1_000_000L
            // Only safe aggregate values are printed, never scene contents.
            println("STAGE0_CONTEXT_BENCHMARK messages=$count bytes=${footprint.inputUtf8Bytes} approxTokens=${footprint.approximateInputTokens} compileMs=$durationMs earlyFactsPresent=0 selectedVariantOnly=true")
            footprint
        }
        assertTrue(
            "Prompt growth unexpectedly followed total message count",
            abs(snapshots.last().inputUtf8Bytes - snapshots.first().inputUtf8Bytes) < 2_000L
        )
    }

    @Test
    fun savedRelevantMemoryCanReintroduceOneEarlyPromiseWithoutHistoryDump() {
        val without = prompt(10_000)
        val marker = "EARLY_PROMISE"
        assertFalse(without.any { it.content.contains(marker) })
        val sourceLinked = RoleplayMemoryEntity(
            chatId = "stage0-benchmark", characterId = "narrator:stage0-benchmark",
            category = MemoryCategory.PROMISE,
            content = earlyFacts.getValue(17),
            sourceMessageId = "beat-17"
        )
        val withMemory = prompt(10_000, memories = listOf(sourceLinked))
        assertTrue(withMemory.first().content.contains(marker))
        assertEquals(26, withMemory.size)
        val footprint = ContextBudgetProbe.measure(withMemory)
        assertTrue(footprint.utf8BytesByBucket.getValue(ContextBudgetBucket.MEMORY) > 0L)
        // This verifies compiler injection of a supplied record, not automatic
        // story extraction, semantic retrieval, or correct narrated answers.
    }

    @Test
    fun wackAndUnselectedVariantsStayExcludedFromCompiledLongStory() {
        val n = 10_000
        val boundary = 9_950L
        val eligible = ContextResetPolicy.eligiblePrimaryMessages(history(n), boundary)
        val oldSummary = ConversationSummaryEntity(
            chatId = "stage0-benchmark",
            summary = "OLD_WACK_SUMMARY_SECRET",
            summarizedThroughOrderIndex = boundary
        )
        assertNull(ContextResetPolicy.eligibleSummary(oldSummary, boundary))
        val output = ScenarioContextCompiler().compile(
            scenario = scenario, persona = persona, settings = settings,
            history = eligible, current = next(n),
            summary = ContextResetPolicy.eligibleSummary(oldSummary, boundary)?.summary
        )
        val flattened = output.joinToString("\n") { it.content }
        assertFalse(flattened.contains("OLD_WACK_SUMMARY_SECRET"))
        assertFalse(flattened.contains("UNSELECTED_BRANCH"))
        earlyFacts.values.forEach { assertFalse(flattened.contains(it)) }
        assertTrue(flattened.contains("SELECTED_BRANCH"))
        assertTrue(flattened.contains("RECENT_SCENE"))
        assertEquals(25, output.count { it.sourceMessageId != null })
    }

    @Test
    fun largeAuthoredScenarioAndWorldCanStillGrowWithoutAnEnforcedModelLimit() {
        val short = ScenarioContextCompiler().compile(
            scenario, persona, settings, emptyList(), next(10), worldContext = "A town."
        )
        val giant = ScenarioContextCompiler().compile(
            scenario.copy(instructions = "A".repeat(24_000)),
            persona, settings, emptyList(), next(10), worldContext = "W".repeat(18_000)
        )
        val smallCost = ContextBudgetProbe.measure(short)
        val bigCost = ContextBudgetProbe.measure(giant)
        assertTrue(bigCost.inputUtf8Bytes - smallCost.inputUtf8Bytes >= 40_000L)
        assertTrue(bigCost.approximateTokens(ContextBudgetBucket.WORLD) > 4_000L)
        assertTrue(bigCost.approximateTokens(ContextBudgetBucket.SCENARIO) > 5_000L)
        assertNull(bigCost.explicitOutputTokenCeiling)
        // Stage 2 must actively budget authored context; capping transcript
        // and rolling summary alone is not a provider-context-window limit.
    }

    @Test
    fun soloAndGroupPromptFootprintsStayBoundedButEarlyStoriesRemainUnavailable() {
        val soloSamples = mutableListOf<Long>()
        val groupSamples = mutableListOf<Long>()
        for (count in listOf(500, 2_000, 10_000)) {
            val sampleHistory = history(count)
            val current = next(count).copy(
                speakerType = SpeakerType.PERSONA, speakerId = persona.personaId,
                speakerDisplayNameSnapshot = persona.displayName
            )
            val syntheticSummary = "Most recent scene recap. ".repeat(500).take(12_000)
            val solo = ContextCompilerV1().compileSoloContext(
                character = character, persona = persona,
                chatHistory = sampleHistory, currentMessage = current,
                responseProfile = ResponseLengthProfile.NORMAL,
                customMin = null, customTargetMax = null, customHardMax = null,
                rollingSummary = syntheticSummary,
                worldContext = "Shared World: The Long Road."
            )
            val group = ContextCompilerV1().compileGroupCharacterContext(
                character = character, persona = persona,
                activeCharacterNames = listOf("Rowan", "Selene"),
                chatHistory = sampleHistory, currentMessage = current,
                responseProfile = ResponseLengthProfile.NORMAL,
                customMin = null, customTargetMax = null, customHardMax = null,
                rollingSummary = syntheticSummary,
                worldContext = "Shared World: The Long Road."
            )
            for ((mode, compiled) in listOf("solo" to solo, "group" to group)) {
                val flat = compiled.joinToString("\n") { it.content }
                earlyFacts.values.forEach { assertFalse(flat.contains(it)) }
                assertFalse(flat.contains("UNSELECTED_BRANCH"))
                assertTrue(flat.contains("SELECTED_BRANCH"))
                assertTrue(flat.contains("RECENT_SCENE"))
                val usage = ContextBudgetProbe.measure(compiled)
                for (bucket in listOf(
                    ContextBudgetBucket.CHARACTER, ContextBudgetBucket.PERSONA,
                    ContextBudgetBucket.WORLD, ContextBudgetBucket.SUMMARY,
                    ContextBudgetBucket.TRANSCRIPT
                )) assertTrue("$mode missing $bucket at $count", usage.utf8BytesByBucket.getValue(bucket) > 0)
                if (mode == "group") {
                    assertTrue(flat.contains("Respond only as Rowan"))
                    assertTrue(usage.utf8BytesByBucket.getValue(ContextBudgetBucket.DIRECTOR) > 0)
                    groupSamples.add(usage.inputUtf8Bytes)
                } else soloSamples.add(usage.inputUtf8Bytes)
                println("STAGE0_CONTEXT_BENCHMARK mode=$mode messages=$count bytes=${usage.inputUtf8Bytes} approxTokens=${usage.approximateInputTokens} earlyFactsPresent=0 selectedVariantOnly=true")
            }
        }
        assertTrue(abs(soloSamples.last() - soloSamples.first()) < 2_000L)
        assertTrue(abs(groupSamples.last() - groupSamples.first()) < 2_000L)
    }
}
