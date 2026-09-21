package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.ui.screens.MessageBubble
import com.example.ui.screens.MessageGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class MessageCopyActionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun personaCopyPassesExactRawContentWithoutEnteringEditOrDelete() {
        val raw = "  **bold** and *italic*\n{char} keeps {user}\n  "
        val message = message(
            messageId = "persona-message",
            speakerType = SpeakerType.PERSONA,
            speakerName = "Shai",
            content = raw
        )
        var copied: String? = null
        var switchedTo: String? = null
        var edited = false
        var deleted = false

        setMessageBubble(
            group = MessageGroup(message, listOf(message)),
            onSwitchVariant = { switchedTo = it },
            onCopy = { copied = it },
            onEdit = { _, _ -> edited = true },
            onDelete = { deleted = true }
        )

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Copy").performClick()

        composeTestRule.runOnIdle {
            assertEquals(raw, copied)
            assertFalse(copied!!.startsWith("Shai:"))
            assertEquals(raw, message.content)
            assertNull(switchedTo)
            assertFalse(edited)
            assertFalse(deleted)
        }
        composeTestRule.onNodeWithText("Save").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete Message").assertDoesNotExist()
    }

    @Test
    fun activeRegeneratedCharacterVariantCopiesItsExactStoredContent() {
        val original = message(
            messageId = "original",
            speakerType = SpeakerType.CHARACTER,
            speakerName = "Miki",
            content = "Original response",
            isPrimary = false
        )
        val rawActiveVariant = "*New action*\n\nExact  spacing and {char}."
        val activeVariant = message(
            messageId = "regenerated",
            speakerType = SpeakerType.CHARACTER,
            speakerName = "Miki",
            content = rawActiveVariant,
            isPrimary = true
        )
        var copied: String? = null

        setMessageBubble(
            group = MessageGroup(activeVariant, listOf(original, activeVariant)),
            onCopy = { copied = it }
        )

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Copy").performClick()

        composeTestRule.runOnIdle {
            assertEquals(rawActiveVariant, copied)
            assertFalse(copied!!.startsWith("Miki:"))
            assertEquals("Original response", original.content)
            assertEquals(rawActiveVariant, activeVariant.content)
        }
    }

    @Test
    fun existingEditAndDeleteActionsRemainAvailable() {
        val message = message(
            messageId = "editable-message",
            speakerType = SpeakerType.PERSONA,
            speakerName = "Shai",
            content = "Original"
        )
        var editedId: String? = null
        var editedContent: String? = null
        var deletedId: String? = null

        setMessageBubble(
            group = MessageGroup(message, listOf(message)),
            onEdit = { id, content ->
                editedId = id
                editedContent = content
            },
            onDelete = { deletedId = it }
        )

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Save").assertExists()
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("Edited")
        composeTestRule.onNodeWithText("Save").performClick()

        composeTestRule.runOnIdle {
            assertEquals("editable-message", editedId)
            assertEquals("Edited", editedContent)
        }

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNodeWithText("Delete Message").assertExists()
        composeTestRule.onNodeWithText("Delete").performClick()

        composeTestRule.runOnIdle {
            assertEquals("editable-message", deletedId)
            assertEquals("Original", message.content)
        }
    }

    private fun setMessageBubble(
        group: MessageGroup,
        onSwitchVariant: (String) -> Unit = {},
        onCopy: (String) -> Unit = {},
        onEdit: (String, String) -> Unit = { _, _ -> },
        onDelete: (String) -> Unit = {}
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    group = group,
                    onSwitchVariant = onSwitchVariant,
                    onRegenerate = {},
                    onContinue = {},
                    onCopy = onCopy,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onShowMetadata = {}
                )
            }
        }
    }

    private fun message(
        messageId: String,
        speakerType: SpeakerType,
        speakerName: String,
        content: String,
        isPrimary: Boolean = true
    ) = MessageEntity(
        messageId = messageId,
        chatId = "chat",
        speakerType = speakerType,
        speakerId = "speaker",
        speakerDisplayNameSnapshot = speakerName,
        content = content,
        orderIndex = 1L,
        variantGroupId = "variant-group",
        isPrimaryVariant = isPrimary
    )
}
