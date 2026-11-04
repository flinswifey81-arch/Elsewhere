package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.story.StoryResponse
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegment
import com.example.domain.story.StorySegmentType
import com.example.ui.screens.MessageBubble
import com.example.ui.screens.MessageGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Offline Compose regression coverage: no chat model, API key, or network request. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h800dp")
class ChatMessageReadingUiTest {
    @get:Rule val compose = createComposeRule()

    private fun message(content: String) = MessageEntity(
        messageId = "message-1",
        chatId = "chat",
        speakerType = SpeakerType.CHARACTER,
        speakerId = "rowan",
        speakerDisplayNameSnapshot = "Rowan",
        content = content,
        orderIndex = 2L
    )

    @Test
    fun longCharacterReplyUsesAvailableWidthAndRetainsWholeMessageCopy() {
        val prose = ("A lengthy answer with several clauses and enough words to wrap naturally. ").repeat(14)
        val entry = message(prose)
        var copied: String? = null
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(420.dp)) {
                    MessageBubble(
                        group = MessageGroup(entry, listOf(entry)),
                        onSwitchVariant = {}, onRegenerate = {}, onContinue = {},
                        onCopy = { copied = it }, onEdit = { _, _ -> },
                        onDelete = {}, onShowMetadata = {}
                    )
                }
            }
        }
        val bubble = compose.onNodeWithTag("chat_message_bubble").getUnclippedBoundsInRoot()
        val avatar = compose.onNodeWithTag("message_avatar_CHARACTER_rowan").getUnclippedBoundsInRoot()
        assertTrue("Long prose should use most of the 420dp reading width", bubble.right - bubble.left > 330.dp)
        assertTrue("Avatar must align with the top of the bubble", abs((bubble.top - avatar.top).value) < 2f)
        compose.onNodeWithContentDescription("Message menu").performClick()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(prose, copied)
    }

    @Test
    fun ensembleReplyHasWideSelectableSpeakerBubblesAndWholeReplyCopy() {
        val story = StoryResponse(segments = listOf(
            StorySegment(StorySegmentType.CHARACTER,
                ("The captain considers the situation carefully and replies in a measured voice. ").repeat(10),
                "rowan", "Rowan"),
            StorySegment(StorySegmentType.NPC,
                ("I have brought the maps and the latest reports for you to inspect. ").repeat(10),
                "npc-1", "Liora")
        ))
        val entry = message(StoryResponseCodec.encode(story))
        var copied: String? = null
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(420.dp)) {
                    MessageBubble(
                        group = MessageGroup(entry, listOf(entry)),
                        onSwitchVariant = {}, onRegenerate = {}, onContinue = {},
                        onCopy = { copied = it }, onEdit = { _, _ -> },
                        onDelete = {}, onShowMetadata = {}
                    )
                }
            }
        }
        val first = compose.onNodeWithTag("story_message_bubble_0").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("story_message_bubble_1").getUnclippedBoundsInRoot()
        assertTrue(first.right - first.left > 330.dp)
        assertTrue(second.right - second.left > 330.dp)
        compose.onNodeWithContentDescription("Message menu").performClick()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(StoryResponseCodec.readableTranscript(story), copied)
    }
}
