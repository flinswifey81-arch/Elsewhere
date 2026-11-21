package com.example

import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.export.ChatPdfText
import com.example.domain.export.ChatPdfTurnRange
import com.example.domain.story.StoryResponse
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegment
import com.example.domain.story.StorySegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Non-network contract: readable output, player variants and range validation. */
@RunWith(RobolectricTestRunner::class)
class ChatPdfExportContractTest {
    private fun message(
        content: String,
        speaker: String = "The Wanderer",
        type: SpeakerType = SpeakerType.CHARACTER
    ): MessageEntity = MessageEntity(
        chatId = "long-story",
        speakerType = type,
        speakerId = "character",
        speakerDisplayNameSnapshot = speaker,
        content = content,
        orderIndex = 1L,
        isPrimaryVariant = true
    )

    @Test
    fun entireAndSelectedTurnRangesAreInclusive() {
        assertEquals(10, ChatPdfTurnRange(1, 10).count)
        assertEquals(1, ChatPdfTurnRange(47, 47).count)
        assertEquals(101, ChatPdfTurnRange(300, 400).count)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotExportZeroBasedTurn() {
        ChatPdfTurnRange(0, 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotExportInvertedRange() {
        ChatPdfTurnRange(11, 10)
    }

    @Test
    fun preservesSpeakerIdentityNewlinesAndUserText() {
        val msg = message("*I open the book.*\n\n\"Well, look at that.\"")
        assertEquals("The Wanderer", ChatPdfText.speaker(msg))
        assertEquals("*I open the book.*\n\n\"Well, look at that.\"",
            ChatPdfText.visibleContent(msg))
        val unnamed = message("Hello", speaker = "  ", type = SpeakerType.PERSONA)
        assertEquals("Persona", ChatPdfText.speaker(unnamed))
    }

    @Test
    fun structuredEnsembleSegmentsShowActualSpeakersWithoutInternalEnvelope() {
        val encoded = StoryResponseCodec.encode(
            StoryResponse(segments = listOf(
                StorySegment(StorySegmentType.NARRATION, "The curtain rises."),
                StorySegment(StorySegmentType.NPC, "It's time.", "npc-id", "Liora"),
                StorySegment(StorySegmentType.CHARACTER, "I'm ready.", "character", "Rowan")
            ))
        )
        val visible = ChatPdfText.visibleContent(message(encoded))
        assertTrue(visible.contains("The curtain rises."))
        assertTrue(visible.contains("Liora: It's time."))
        assertTrue(visible.contains("Rowan: I'm ready."))
        assertFalse(visible.contains("elsewhere-story-v1"))
        assertFalse(visible.contains("schemaVersion"))
    }
}
