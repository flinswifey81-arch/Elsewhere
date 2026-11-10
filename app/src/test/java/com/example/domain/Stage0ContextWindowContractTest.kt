package com.example.domain

import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ScenarioDefinition
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ScenarioContextCompiler
import com.example.domain.memory.ContextResetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 0: characterization tests of the CURRENT context contract, without API calls.
 * This is not a test of Stage 2 retrieval or final prompt token budgets.
 */
class Stage0ContextWindowContractTest {
    private val persona = PersonaEntity(
        personaId = "stage0-persona", displayName = "Mara", personaName = "Mara",
        aliases = emptyList(), importSchemaVersion = 1,
        identityJson = null, appearanceJson = null, personalityJson = null,
        roleplayProfileJson = null, backgroundJson = null,
        worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
    )
    private val scenario = ScenarioDefinition(title = "Stage 0 Story", premise = "A long journey.")
    private val settings = ChatSettingsEntity(chatId = "stage0-chat")

    private fun message(
        order: Int,
        text: String = "TURN_" + order + "_SENTINEL",
        primary: Boolean = true
    ) = MessageEntity(
        messageId = "turn-$order", chatId = "stage0-chat",
        speakerType = SpeakerType.NARRATOR, speakerId = "narrator:stage0-chat",
        speakerDisplayNameSnapshot = "Narrator", content = text,
        orderIndex = order.toLong(), isPrimaryVariant = primary
    )

    @Test
    fun scenarioHistoryWindowStaysAt24Across5002000And10000Messages() {
        val compiler = ScenarioContextCompiler()
        listOf(500, 2_000, 10_000).forEach { historySize ->
            val history = (1..historySize).map { message(it) }
            val current = message(historySize + 1, "CURRENT_PLAYER_SCENE")
            val output = compiler.compile(
                scenario = scenario, persona = persona, settings = settings,
                history = history, current = current
            )
            assertEquals("historySize=$historySize", 26, output.size)
            assertEquals(
                "historySize=$historySize",
                (historySize - 23..historySize).map { "turn-$it" },
                output.drop(1).dropLast(1).map { it.sourceMessageId }
            )
            assertEquals("turn-" + (historySize + 1), output.last().sourceMessageId)
            assertFalse("historySize=$historySize", output.any { it.content.contains("TURN_1_SENTINEL") })
        }
    }

    @Test
    fun scenarioCompilerIgnoresNonPrimaryHistoryVariants() {
        val history = (1..30).map { order ->
            if (order == 27) message(order, "UNSELECTED_BRANCH_SECRET", primary = false)
            else message(order)
        }
        val output = ScenarioContextCompiler().compile(
            scenario, persona, settings, history, message(31, "CURRENT_SCENE")
        )
        assertEquals(26, output.size)
        assertFalse(output.any { it.content.contains("UNSELECTED_BRANCH_SECRET") })
        assertFalse(output.any { it.sourceMessageId == "turn-27" })
        assertTrue(output.any { it.sourceMessageId == "turn-30" })
    }

    @Test
    fun contextResetExcludesEarlierHistoryAndUnselectedPostResetVariants() {
        val transcript = (1..10_000).map { order ->
            message(order, primary = order % 10 != 0)
        }
        val selected = ContextResetPolicy.eligiblePrimaryMessages(transcript, boundary = 6_000L)
        assertEquals(3_600, selected.size)
        assertTrue(selected.all { it.orderIndex > 6_000L && it.isPrimaryVariant })
        assertEquals("turn-6001", selected.first().messageId)
        assertEquals("turn-9999", selected.last().messageId)
        assertFalse(selected.any { it.messageId == "turn-10" || it.messageId == "turn-10000" })
    }

    @Test
    fun preResetSummaryIsIneligibleAndPostResetSummaryIsEligible() {
        val old = ConversationSummaryEntity("stage0-chat", "OLD_SECRET", 6_000L)
        val fresh = ConversationSummaryEntity("stage0-chat", "CURRENT_SCENE", 6_100L)
        assertNull(ContextResetPolicy.eligibleSummary(old, 6_000L))
        assertEquals(fresh, ContextResetPolicy.eligibleSummary(fresh, 6_000L))
    }
}
