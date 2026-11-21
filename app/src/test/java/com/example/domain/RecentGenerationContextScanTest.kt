package com.example.domain

import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.lore.LorebookActivationEngine
import com.example.domain.npc.NpcSceneCastResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Avoid walking 10k messages merely to scan the most recent 24 turns. */
class RecentGenerationContextScanTest {
    private fun message(index: Int, primary: Boolean = true) = MessageEntity(
        messageId = "turn-$index",
        chatId = "synthetic",
        speakerType = SpeakerType.PERSONA,
        speakerId = "persona",
        speakerDisplayNameSnapshot = "Mara",
        content = "Visit waypoint $index and remember the lantern.",
        orderIndex = index.toLong(),
        isPrimaryVariant = primary
    )

    @Test fun loreScanMatchesOldSelectionAcrossLongArchives() {
        val current = message(10_001)
        for (size in listOf(500, 2_000, 10_000)) {
            val history = (1..size).map { message(it, it % 13 != 0) }
            val legacyTail = history.filter { it.isPrimaryVariant }.takeLast(24)
            val expected = LorebookActivationEngine.conversationScanText(legacyTail, current)
            assertEquals("history size=$size",
                expected, LorebookActivationEngine.conversationScanText(history, current))
        }
    }

    @Test fun loreAndNpcSceneReadersInspectOnlyTheRecentWindow() {
        var reads = 0
        val history = object : AbstractList<MessageEntity>() {
            override val size: Int = 10_000
            override fun get(index: Int): MessageEntity {
                reads++
                return message(index + 1, primary = index < 9_998)
            }
        }
        val loreText = LorebookActivationEngine.conversationScanText(
            history, message(10_001), recentMessageLimit = 24
        )
        assertTrue(loreText.contains("waypoint 9998"))
        assertTrue(!loreText.contains("waypoint 9999"))
        assertTrue("Lore scan visited $reads turns", reads <= 26)
        reads = 0
        val result = NpcSceneCastResolver.resolve(emptyList(), history)
        assertTrue(result.isEmpty())
        assertTrue("NPC scan visited $reads turns", reads <= 26)
    }

    @Test fun loreScanWithZeroLimitStillIncludesCurrentTurn() {
        val history = (1..10).map(::message)
        val actual = LorebookActivationEngine.conversationScanText(
            history, message(11), recentMessageLimit = 0
        )
        assertTrue(actual.contains("waypoint 11"))
        assertTrue(!actual.contains("waypoint 10"))
    }
}
