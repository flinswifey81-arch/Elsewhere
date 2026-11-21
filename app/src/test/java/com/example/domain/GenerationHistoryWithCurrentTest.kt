package com.example.domain

import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.compiler.GenerationHistoryWithCurrent
import org.junit.Assert.*
import org.junit.Test

class GenerationHistoryWithCurrentTest {
    private fun message(index: Int) = MessageEntity(
        messageId = "turn-$index", chatId = "synthetic",
        speakerType = SpeakerType.PERSONA, speakerId = "persona",
        speakerDisplayNameSnapshot = "Mara",
        content = "Scene $index", orderIndex = index.toLong()
    )

    @Test fun behavesExactlyLikeConcatenatedList() {
        for (size in listOf(0, 1, 24, 500, 2_000, 10_000)) {
            val history = (1..size).map(::message)
            val current = message(size + 1)
            val view = GenerationHistoryWithCurrent(history, current)
            assertEquals(history + current, view)
            assertEquals(size + 1, view.size)
            assertEquals(current, view.last())
        }
    }

    @Test fun creationAndTailAccessDoNotCopyTenThousandEntries() {
        var sourceReads = 0
        val source = object : AbstractList<MessageEntity>() {
            override val size: Int = 10_000
            override fun get(index: Int): MessageEntity {
                sourceReads++
                return message(index + 1)
            }
        }
        val current = message(10_001)
        val view = GenerationHistoryWithCurrent(source, current)
        assertEquals(0, sourceReads)
        assertEquals(current, view.last())
        assertEquals(0, sourceReads)
        assertEquals(message(10_000), view[9_999])
        assertEquals(1, sourceReads)
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun rejectsIndexAfterCurrent() {
        GenerationHistoryWithCurrent(emptyList(), message(1))[1]
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun rejectsNegativeIndex() {
        GenerationHistoryWithCurrent(emptyList(), message(1))[-1]
    }
}
