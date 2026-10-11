package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.onNodeWithTag
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.ui.screens.MessageBubble
import com.example.ui.screens.MessageGroup
import com.example.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ProfileAvatarUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun messageAvatarsUseExactPersonaAndCharacterSpeakerIdentity() {
        val persona = message("persona-id", SpeakerType.PERSONA, "Mara", 1)
        val character = message("character-id", SpeakerType.CHARACTER, "Cael", 2)
        composeRule.setContent {
            AppTheme {
                Column {
                    bubble(persona)
                    bubble(character)
                }
            }
        }

        composeRule.onNodeWithTag("message_avatar_PERSONA_persona-id").assertExists()
        composeRule.onNodeWithTag("message_avatar_CHARACTER_character-id").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun bubble(message: MessageEntity) {
        MessageBubble(
            group = MessageGroup(message, listOf(message)),
            onSwitchVariant = {},
            onRegenerate = {},
            onContinue = {},
            onCopy = {},
            onEdit = { _, _ -> },
            onDelete = {},
            onShowMetadata = {}
        )
    }

    private fun message(id: String, type: SpeakerType, name: String, order: Long) = MessageEntity(
        messageId = "message-$id",
        chatId = "chat",
        speakerType = type,
        speakerId = id,
        speakerDisplayNameSnapshot = name,
        content = "Hello",
        orderIndex = order
    )
}
