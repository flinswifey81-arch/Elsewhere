package com.example

import com.example.domain.story.StoryOutputParser
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegmentType
import com.example.domain.story.StorySpeakerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryResponseTest {
    private val parser = StoryOutputParser()
    private val primary = StorySpeakerIdentity("character-1", "Zebulus")
    private val ashford = StorySpeakerIdentity("npc-ashford", "Lady Ashford")

    @Test
    fun alternatingNarrationCharacterAndNpcSegmentsPreserveOrderAndIdentity() {
        val output = """
            <|elsewhere_story_v1|>
            <|segment|>{"type":"narration"}
            The fire crackles. Neither person speaks.
            <|segment|>{"type":"character"}
            *He looks away.* "I've never been most people."
            <|segment|>{"type":"narration"}
            The admission hangs between them.
            <|segment|>{"type":"npc","speaker_id":"npc-ashford","speaker_name":"Wrong model name"}
            "Perhaps that's exactly the problem."
            <|segment|>{"type":"character"}
            *He gives her a sharp look.* "Nobody asked you."
            <|end_story|>
        """.trimIndent()

        val parsed = parser.parse(output, primary, listOf(ashford))

        assertEquals(
            listOf(
                StorySegmentType.NARRATION,
                StorySegmentType.CHARACTER,
                StorySegmentType.NARRATION,
                StorySegmentType.NPC,
                StorySegmentType.CHARACTER
            ),
            parsed.response.segments.map { it.type }
        )
        assertEquals("Zebulus", parsed.response.segments[1].speakerName)
        assertEquals("npc-ashford", parsed.response.segments[3].speakerId)
        assertEquals("Lady Ashford", parsed.response.segments[3].speakerName)
        assertEquals(null, parsed.warning)
    }

    @Test
    fun malformedHeadersAndIncompleteOutputHideMarkersAndPreserveProse() {
        val malformed = """
            <|elsewhere_story_v1|>
            <|segment|>{not-json}
            The rain still reaches the windows.
            <|segment|>{"type":"character"}
            "Then we wait."
        """.trimIndent()

        val parsed = parser.parse(malformed, primary, final = true)
        val encoded = StoryResponseCodec.encode(parsed.response)
        val visible = StoryResponseCodec.visibleText(encoded)

        assertNotNull(parsed.warning)
        assertTrue(visible.contains("The rain still reaches the windows."))
        assertTrue(visible.contains("Then we wait."))
        assertFalse(visible.contains("<|"))
        assertFalse(visible.contains("not-json"))
    }

    @Test
    fun inlineOrTruncatedControlSyntaxIsNeverExposedAndSurroundingProseSurvives() {
        val malformed = """
            Before the pause. <|segment|>{"type":"narration"} After the pause.
            The door moves. <|end_story
        """.trimIndent()

        val visible = StoryResponseCodec.visibleText(
            StoryResponseCodec.encode(parser.parse(malformed, primary).response)
        )

        assertTrue(visible.contains("Before the pause."))
        assertTrue(visible.contains("After the pause."))
        assertTrue(visible.contains("The door moves."))
        assertFalse(visible.contains("<|"))
        assertFalse(visible.contains("type"))
    }

    @Test
    fun streamingParserShowsOpenSegmentBeforeClosingMarkerArrives() {
        val partial = """
            <|elsewhere_story_v1|>
            <|segment|>{"type":"narration"}
            A long silence settles over the room.
            <|segment|>{"type":"character"}
            *He folds his arms.* "I
        """.trimIndent()

        val parsed = parser.parse(partial, primary, final = false)

        assertEquals(2, parsed.response.segments.size)
        assertEquals(StorySegmentType.NARRATION, parsed.response.segments[0].type)
        assertEquals(StorySegmentType.CHARACTER, parsed.response.segments[1].type)
        assertTrue(parsed.response.segments[1].content.endsWith("\"I"))
        assertEquals(null, parsed.warning)
    }

    @Test
    fun ordinaryOutputFallsBackToCharacterAndContinuationAppendsLogicalSegments() {
        val first = parser.parse("A quiet ordinary reply.", primary)
        assertFalse(first.usedStructuredFormat)
        assertEquals(StorySegmentType.CHARACTER, first.response.segments.single().type)

        val encodedFirst = StoryResponseCodec.encode(first.response)
        val second = parser.parse(
            """
                <|elsewhere_story_v1|>
                <|segment|>{"type":"narration"}
                The clock strikes midnight.
                <|end_story|>
            """.trimIndent(),
            primary
        )
        val appended = StoryResponseCodec.append(
            encodedFirst,
            StoryResponseCodec.encode(second.response),
            primary
        )
        val decoded = requireNotNull(StoryResponseCodec.decode(appended))

        assertEquals(listOf(StorySegmentType.CHARACTER, StorySegmentType.NARRATION), decoded.segments.map { it.type })
        assertTrue(StoryResponseCodec.promptTranscript(appended).contains("Narrator: The clock strikes midnight."))
    }
}
