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
    fun persistedGeneratedAdapterEnvelopeRemainsReadableWithoutGeneratedAdapters() {
        val previouslySaved = "\u001eelsewhere-story-v1\n" +
            "{\"schemaVersion\":1,\"segments\":[" +
            "{\"type\":\"NARRATION\",\"content\":\"The old hall waits.\",\"speakerId\":null,\"speakerName\":null}," +
            "{\"type\":\"CHARACTER\",\"content\":\"\\\"Come in.\\\"\",\"speakerId\":\"character-1\",\"speakerName\":\"Zebulus\"}]}"

        val decoded = requireNotNull(StoryResponseCodec.decode(previouslySaved))

        assertEquals(listOf(StorySegmentType.NARRATION, StorySegmentType.CHARACTER), decoded.segments.map { it.type })
        assertEquals("Zebulus", decoded.segments.last().speakerName)
        assertEquals(decoded, StoryResponseCodec.decode(StoryResponseCodec.encode(decoded)))
    }

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

    @Test
    fun transcriptStyleFallbackRecoversNarrationCharacterAndNpcSegments() {
        val output = """
            Narrator: The lift doors close with a muted chime.

            Character (Zebulus): *He watches the floor numbers climb.* "You can still leave."

            NPC (Lady Ashford): "And miss this? Never."

            Narrator:
            Silence settles over the three of them.
        """.trimIndent()

        val parsed = parser.parse(output, primary, listOf(ashford))

        assertFalse(parsed.usedStructuredFormat)
        assertEquals(
            listOf(
                StorySegmentType.NARRATION,
                StorySegmentType.CHARACTER,
                StorySegmentType.NPC,
                StorySegmentType.NARRATION
            ),
            parsed.response.segments.map { it.type }
        )
        assertEquals("Lady Ashford", parsed.response.segments[2].speakerName)
        assertTrue(parsed.response.segments.none { it.content.contains("Character (Zebulus):") })
        assertTrue(parsed.response.segments.none { it.content.contains("Narrator:") })
    }

    @Test
    fun transcriptLabelsInsideOrdinaryProseOrQuotationsAreNotReinterpreted() {
        val output = """
            He sets down a book titled Character (Zebulus): A Study in Winter.
            "Narrator: You make everything sound ominous," she says.
            The phrase NPC (Lady Ashford): remains written in the margin.
        """.trimIndent()

        val parsed = parser.parse(output, primary, listOf(ashford))

        assertEquals(1, parsed.response.segments.size)
        assertEquals(StorySegmentType.CHARACTER, parsed.response.segments.single().type)
        assertEquals(output, parsed.response.segments.single().content)

        val quotedAtStart = "\"Narrator: That is not what I said,\" she replies."
        assertEquals(
            quotedAtStart,
            parser.parse(quotedAtStart, primary, listOf(ashford)).response.segments.single().content
        )
        val mismatchedCharacter = "Character (Lady Ashford): A Biography remains on the shelf."
        assertEquals(
            mismatchedCharacter,
            parser.parse(mismatchedCharacter, primary, listOf(ashford)).response.segments.single().content
        )
    }

    @Test
    fun loneBareSpeakerNameRemainsOrdinaryButMultipleKnownLabelsFormTranscript() {
        val ordinary = parser.parse("Zebulus: A Biography lies open on the desk.", primary, listOf(ashford))
        assertEquals("Zebulus: A Biography lies open on the desk.", ordinary.response.segments.single().content)

        val transcript = parser.parse(
            """
                Zebulus: "We should go."
                Lady Ashford: "At last, some sense."
            """.trimIndent(),
            primary,
            listOf(ashford)
        )
        assertEquals(
            listOf(StorySegmentType.CHARACTER, StorySegmentType.NPC),
            transcript.response.segments.map { it.type }
        )
    }

    @Test
    fun ensemblePromptHistoryUsesMarkersAndRepairsPreviouslyCollapsedTranscript() {
        val previouslyCollapsed = StoryResponseCodec.encode(
            com.example.domain.story.StoryResponse(
                segments = listOf(
                    com.example.domain.story.StorySegment(
                        type = StorySegmentType.CHARACTER,
                        content = """
                            Narrator: The rain starts again.
                            Character (Zebulus): "It always does."
                            NPC (Lady Ashford): "Only when you complain."
                        """.trimIndent(),
                        speakerId = primary.id,
                        speakerName = primary.name
                    )
                )
            )
        )

        val history = StoryResponseCodec.promptStructuredHistory(previouslyCollapsed, primary, listOf(ashford))
        val reparsed = parser.parse(history, primary, listOf(ashford))

        assertTrue(history.startsWith(StoryOutputParser.START_MARKER))
        assertTrue(history.endsWith(StoryOutputParser.END_MARKER))
        assertFalse(history.contains("Character (Zebulus):"))
        assertFalse(history.contains("NPC (Lady Ashford):"))
        assertEquals(
            listOf(StorySegmentType.NARRATION, StorySegmentType.CHARACTER, StorySegmentType.NPC),
            reparsed.response.segments.map { it.type }
        )
    }
}
