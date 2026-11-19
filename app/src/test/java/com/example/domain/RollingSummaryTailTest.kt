package com.example.domain

import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.memory.AutomaticMemoryManager
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.story.StoryResponseCodec
import org.junit.Assert.*
import org.junit.Test

/** The summary suffix must be byte-for-byte equivalent to the prior whole-archive build. */
class RollingSummaryTailTest {
    private fun message(i: Int, text: String) = MessageEntity(
        messageId = "synthetic-$i",
        chatId = "synthetic",
        speakerType = SpeakerType.PERSONA,
        speakerId = "mara",
        speakerDisplayNameSnapshot = if (i % 2 == 0) "Mara" else "Rowan",
        content = text,
        orderIndex = i.toLong()
    )

    private fun oldSummary(older: List<MessageEntity>): String =
        older.joinToString("\n") { m ->
            val compact = StoryResponseCodec.promptTranscript(
                NpcStructuredOutputParser.finalVisibleStoryText(m.content)
            ).replace(Regex("\\s+"), " ").trim().take(280)
            "${m.speakerDisplayNameSnapshot}: $compact"
        }.takeLast(12_000)

    @Test fun matchesOriginalFor5002000And10000Turns() {
        for (size in listOf(500, 2_000, 10_000)) {
            val history = (1..size).map { i ->
                val text = when (i % 7) {
                    0 -> "Quiet."
                    1 -> "별이 비추는 🌙 moonlit valley."
                    2 -> "A    longer\tstory\nbeat with irregular whitespace."
                    3 -> "I promise to return the compass. ".repeat(20)
                    4 -> "A scene unfolded in the harbor while the lights dimmed."
                    5 -> ""
                    else -> "A long fictional scene with many characters. ".repeat(18)
                }
                message(i, text)
            }
            assertEquals("Size=$size", oldSummary(history),
                AutomaticMemoryManager.rollingSummaryTail(history))
        }
    }

    @Test fun emptyAndSingleTurnMatchOriginal() {
        assertEquals("", AutomaticMemoryManager.rollingSummaryTail(emptyList()))
        val one = listOf(message(1, "Very short"))
        assertEquals(oldSummary(one), AutomaticMemoryManager.rollingSummaryTail(one))
    }

    @Test fun veryShortTurnsDoNotLoseTheNewlineAtTheTruncationBoundary() {
        val history = (1..7_000).map { message(it, if (it % 2 == 0) "" else "x") }
        assertEquals(oldSummary(history), AutomaticMemoryManager.rollingSummaryTail(history))
    }

    @Test fun longHistoryFormatsOnlyTheSuffixThatFitsInSummary() {
        var reads = 0
        val history = object : AbstractList<MessageEntity>() {
            override val size = 10_000
            override fun get(index: Int): MessageEntity {
                reads++
                return message(index + 1, "Fictional travel observations. ".repeat(24))
            }
        }
        val summary = AutomaticMemoryManager.rollingSummaryTail(history)
        assertEquals(12_000, summary.length)
        assertTrue("Scanned $reads historical turns for a 12k summary", reads <= 50)
        assertTrue(summary.contains("Fictional travel observations"))
    }
}
