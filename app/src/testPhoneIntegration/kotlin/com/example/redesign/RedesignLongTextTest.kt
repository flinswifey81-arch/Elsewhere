package com.example.redesign

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.di.DefaultAppContainer
import com.example.ui.components.ResourceTextField
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.theme.AppTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h851dp", manifest = Config.NONE)
class RedesignLongTextTest {
    @get:Rule val compose = createComposeRule()

    @Test fun importedGreetingUsesCompactPreviewAndReallySavesItsEditedLastLine() {
        val original = (1..172).joinToString("\n") { n ->
            "Line $n · Long scenes stay complete, even with café and 星."
        } + "\nFINAL LINE 🌙"
        val appended = "\nAfter midnight: don't lose this. 星"
        val updated = original + appended
        val app = DefaultAppContainer(RuntimeEnvironment.getApplication())
        val adapter = Moshi.Builder().build().adapter(Map::class.java)
        val id = runBlocking {
            app.characterRepository.importCharacterJson(adapter.toJson(
                mapOf("schema_version" to 1, "character_id" to "long-text-review",
                    "display_name" to "Archivist", "character_name" to "Archivist",
                    "first_mes" to original, "alternate_greetings" to listOf("Keep this alternate."))
            ))
        }
        compose.setContent { AppTheme { ElsewhereApp(container = app) } }
        compose.onNodeWithTag("bottom_nav_library").performClick()
        compose.onNodeWithTag("hub_characters").performClick()
        val editTag = "edit_character_$id"
        awaitTag(editTag)
        compose.onNodeWithTag(editTag).performClick()
        val greetingTag = "open_long_text_Initial Message"
        val form = compose.onNodeWithTag("character_editor_fields")
        form.performScrollToNode(hasTestTag(greetingTag))
        val preview = compose.onNodeWithTag(greetingTag)
        // The 170+ line field must not push the next field beyond a whole screen.
        assertTrue(preview.getUnclippedBoundsInRoot().height <= 170.dp)
        preview.performClick()
        val body = compose.onNodeWithTag("long_text_body")
        assertEquals(original, body.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithContentDescription("Find in text").performClick()
        compose.onNodeWithTag("long_text_query").performTextInput("FINAL LINE")
        compose.onNodeWithText("Next match").performClick()
        val at = original.indexOf("FINAL LINE")
        assertEquals(TextRange(at, at + "FINAL LINE".length),
            body.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithContentDescription("Find in text").performClick()
        compose.onNodeWithContentDescription("Jump to end").performClick()
        assertEquals(TextRange(original.length), body.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        body.performTextInput(appended)
        compose.onNodeWithTag("long_text_done").assertIsDisplayed()
        compose.onAllNodesWithTag("focused_resource_editor").onFirst().captureRoboImage(
            filePath = "build/outputs/roborazzi/ui-review/redesign_long_text.png")
        compose.onNodeWithTag("long_text_done").performClick()
        assertEquals(original, runBlocking {
            app.characterRepository.getOpeningMessages(requireNotNull(app.characterRepository.getCharacterById(id))).first()
        })
        compose.onNodeWithTag("save_character").performClick()
        awaitTag(editTag)
        val saved = runBlocking {
            app.characterRepository.getOpeningMessages(requireNotNull(app.characterRepository.getCharacterById(id)))
        }
        assertEquals(listOf(updated, "Keep this alternate."), saved)
        compose.onNodeWithTag(editTag).performClick()
        form.performScrollToNode(hasTestTag(greetingTag))
        preview.performClick()
        assertEquals(updated, body.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun oversizedPasteStaysInTheEditorUntilFixedAndBackDoesNotChangeParent() {
        val original = "A".repeat(11_990) + "\nFinal end"
        check(original.length == 12_000)
        var form by mutableStateOf(original)
        compose.setContent {
            AppTheme {
                ResourceTextField(form, { form = it }, "Playthrough state",
                    modifier = Modifier.fillMaxWidth(), maxLength = 12_000)
            }
        }
        compose.onNodeWithTag("open_long_text_Playthrough state").performClick()
        compose.onNodeWithContentDescription("Jump to end").performClick()
        val body = compose.onNodeWithTag("long_text_body")
        body.performTextInput(" another line")
        assertEquals(original + " another line", body.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("long_text_done").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back to form").performClick()
        compose.onNodeWithText("Keep editing").performClick()
        body.assertExists()
        compose.onNodeWithContentDescription("Back to form").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        compose.onNodeWithTag("long_text_body").assertDoesNotExist()
        assertEquals(original, form)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
