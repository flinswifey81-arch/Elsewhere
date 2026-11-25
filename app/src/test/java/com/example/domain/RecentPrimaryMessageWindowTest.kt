package com.example.domain

import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.compiler.RecentPrimaryMessageWindow
import org.junit.Assert.*
import org.junit.Test

class RecentPrimaryMessageWindowTest {
    private fun message(i: Int, primary: Boolean = true) = MessageEntity(
        messageId = "turn-$i",
        chatId = "synthetic",
        speakerType = SpeakerType.PERSONA,
        speakerId = "mara",
        speakerDisplayNameSnapshot = "Mara",
        content = "Turn $i",
        orderIndex = i.toLong(),
        isPrimaryVariant = primary
    )

    @Test fun matchesExistingSemanticsAt5002000And10000Turns() {
        for (size in listOf(500, 2_000, 10_000)) {
            val history = (1..size).map { i ->
                message(i, primary = i % 11 != 0)
            }
            assertEquals(
                "size=$size",
                history.filter { it.isPrimaryVariant }.takeLast(24),
                RecentPrimaryMessageWindow.take(history, 24)
            )
        }
    }

    @Test fun largeArchiveOnlyVisitsRecentEntries() {
        var reads = 0
        val source = object : AbstractList<MessageEntity>() {
            override val size: Int = 10_000
            override fun get(index: Int): MessageEntity {
                reads++
                return message(index + 1, primary = index < 9_998)
            }
        }
        val result = RecentPrimaryMessageWindow.take(source, 24)
        assertEquals(24, result.size)
        assertEquals("turn-9975", result.first().messageId)
        assertEquals("turn-9998", result.last().messageId)
        assertTrue("Visited $reads items despite 24-turn window", reads <= 26)
    }

    @Test fun emptyAndShortHistoryAreStable() {
        assertTrue(RecentPrimaryMessageWindow.take(listOf(message(1)), 0).isEmpty())
        val history = listOf(message(1), message(2, false), message(3))
        assertEquals(listOf("turn-1", "turn-3"),
            RecentPrimaryMessageWindow.take(history, 24).map { it.messageId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeWindowRejected() {
        RecentPrimaryMessageWindow.take(emptyList(), -1)
    }
}
