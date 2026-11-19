package com.example.domain

import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.compiler.HistoricalGenerationWindow
import org.junit.Assert.*
import org.junit.Test

/**
 * Regenerate/Continue history remains equivalent to the old full-filter
 * implementation, without materializing a second 10k-turn transcript.
 */
class HistoricalGenerationWindowTest {
    private fun msg(
        id: String,
        order: Long,
        role: SpeakerType = SpeakerType.CHARACTER,
        primary: Boolean = true
    ) = MessageEntity(
        messageId = id,
        chatId = "synthetic",
        speakerType = role,
        speakerId = if (role == SpeakerType.PERSONA) "mara" else "rowan",
        speakerDisplayNameSnapshot = if (role == SpeakerType.PERSONA) "Mara" else "Rowan",
        content = "Story event $id",
        orderIndex = order,
        isPrimaryVariant = primary
    )

    @Test fun groupRegenerateAndContinueMatchOldTimelineAtThreeScales() {
        for (count in listOf(500, 2_000, 10_000)) {
            val messages = (1..count).map { order ->
                msg(
                    "m-$order", order.toLong(),
                    if (order == 7 || order == count - 6) SpeakerType.PERSONA else SpeakerType.CHARACTER,
                    primary = order % 19 != 0
                )
            }.toMutableList()
            // Regenerate the selected last Character after an alternate branch.
            messages.add(msg("rejected-last", count.toLong(), primary = false))
            for (target in listOf("m-${count - 3}", "rejected-last")) {
                val selected = messages.filter { it.isPrimaryVariant || it.messageId == target }
                val beforeTarget = selected.takeWhile { it.messageId != target }
                val expectedCurrent = beforeTarget.last()
                val expectedHistory = beforeTarget.dropLast(1)
                val actual = requireNotNull(
                    HistoricalGenerationWindow.regenerate(messages, target, isGroup = true, resetBoundary = null)
                )
                assertEquals(target, actual.target.messageId)
                assertEquals(expectedCurrent, actual.current)
                assertEquals(expectedHistory.takeLast(24), actual.history.takeLast(24))
            }

            val target = "m-${count - 1}"
            val before = messages.filter { it.isPrimaryVariant }.takeWhile { it.messageId != target }
            val continuation = requireNotNull(
                HistoricalGenerationWindow.continueFrom(messages, target, resetBoundary = null)
            )
            assertEquals(target, continuation.current.messageId)
            assertEquals(before.takeLast(24), continuation.history.takeLast(24))
        }
    }

    @Test fun soloRegenerateUsesLastPersonaBeforeTargetNotLastCharacter() {
        val messages = listOf(
            msg("persona-a", 1, SpeakerType.PERSONA),
            msg("character-1", 2),
            msg("persona-b", 3, SpeakerType.PERSONA),
            msg("character-2", 4),
            msg("alternate-target", 4, primary = false),
            msg("character-3", 5)
        )
        val result = requireNotNull(
            HistoricalGenerationWindow.regenerate(
                messages, "alternate-target", isGroup = false, resetBoundary = null
            )
        )
        assertEquals("persona-b", result.current.messageId)
        assertEquals(listOf("persona-a", "character-1"),
            result.history.map { it.messageId })
        assertEquals("alternate-target", result.target.messageId)
    }

    @Test fun distantPersonaIdentityAnchorDoesNotExpandPromptHistory() {
        val messages = (1..10_000).map { index ->
            msg(
                "m-$index", index.toLong(),
                if (index == 15 || index == 95) SpeakerType.PERSONA else SpeakerType.CHARACTER
            )
        }
        val continuing = requireNotNull(
            HistoricalGenerationWindow.continueFrom(messages, "m-10000", resetBoundary = null)
        )
        assertEquals("m-95", continuing.history.first().messageId)
        assertEquals("m-9976", continuing.history[1].messageId)
        assertEquals(25, continuing.history.size)
        assertEquals(messages.dropLast(1).takeLast(24), continuing.history.takeLast(24))

        // After Wack the same older Persona is no longer eligible. No prompt
        // may receive it through the anchor or history window.
        val afterWack = requireNotNull(
            HistoricalGenerationWindow.continueFrom(messages, "m-10000", resetBoundary = 1_000L)
        )
        assertEquals(24, afterWack.history.size)
        assertFalse(afterWack.history.any { it.speakerType == SpeakerType.PERSONA })
    }

    @Test fun recentRegenerateAndContinueDoNotAllocateOrScanFullArchive() {
        var reads = 0
        val source = object : AbstractList<MessageEntity>() {
            override val size = 10_000
            override fun get(index: Int): MessageEntity {
                reads++
                return msg(
                    "m-${index + 1}", index.toLong(),
                    if (index % 9 == 0) SpeakerType.PERSONA else SpeakerType.CHARACTER
                )
            }
        }
        val continueWindow = requireNotNull(
            HistoricalGenerationWindow.continueFrom(source, "m-10000", null)
        )
        assertEquals(24, continueWindow.history.size)
        assertTrue("Continue inspected $reads rows", reads <= 55)
        reads = 0
        val regenerated = requireNotNull(
            HistoricalGenerationWindow.regenerate(source, "m-10000", true, null)
        )
        assertEquals("m-9999", regenerated.current.messageId)
        assertTrue("Regenerate inspected $reads rows", reads <= 56)
    }

    @Test fun unselectedContinueAndMissingTargetsFailClosed() {
        val messages = listOf(
            msg("persona", 1, SpeakerType.PERSONA),
            msg("selected", 2),
            msg("discarded", 2, primary = false)
        )
        assertNull(HistoricalGenerationWindow.continueFrom(messages, "discarded", null))
        assertNull(HistoricalGenerationWindow.continueFrom(messages, "missing", null))
        assertNull(HistoricalGenerationWindow.regenerate(messages, "missing", true, null))
        assertNull(HistoricalGenerationWindow.regenerate(messages, "persona", true, null))
    }
}
