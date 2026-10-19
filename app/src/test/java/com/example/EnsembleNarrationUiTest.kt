package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.story.StoryResponse
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegment
import com.example.domain.story.StorySegmentType
import com.example.ui.screens.MessageBubble
import com.example.ui.screens.MessageGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class EnsembleNarrationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun narrationIsStandaloneWhileCharacterAndNpcKeepIdentifiableBubbles() {
        val content = StoryResponseCodec.encode(
            StoryResponse(
                segments = listOf(
                    StorySegment(StorySegmentType.NARRATION, "The fire drops to a low red glow."),
                    StorySegment(StorySegmentType.CHARACTER, "*He looks away.* \"Not yet.\"", "character-1", "Rowan"),
                    StorySegment(StorySegmentType.NPC, "\"Then when?\"", "npc-ashford", "Lady Ashford")
                )
            )
        )
        val message = MessageEntity(
            messageId = "ensemble",
            chatId = "chat",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character-1",
            speakerDisplayNameSnapshot = "Rowan",
            content = content,
            orderIndex = 1
        )
        var copied = ""
        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    group = MessageGroup(message, listOf(message)),
                    onSwitchVariant = {},
                    onRegenerate = {},
                    onContinue = {},
                    onCopy = { copied = it },
                    onEdit = { _, _ -> },
                    onDelete = {},
                    onShowMetadata = {}
                )
            }
        }

        composeRule.onNodeWithTag("story_narration_0").assertExists()
        composeRule.onNodeWithTag("story_speaker_CHARACTER_1").assertExists()
        composeRule.onNodeWithTag("story_speaker_NPC_2").assertExists()
        composeRule.onNodeWithText("Rowan").assertExists()
        composeRule.onNodeWithText("Lady Ashford").assertExists()
        composeRule.onNodeWithTag("message_avatar_NARRATOR_narrator").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Copy").performClick()
        composeRule.runOnIdle {
            assertTrue(copied.contains("The fire drops to a low red glow."))
            assertTrue(copied.contains("Lady Ashford:"))
            assertFalse(copied.contains("elsewhere-story-v1"))
        }
    }
}
