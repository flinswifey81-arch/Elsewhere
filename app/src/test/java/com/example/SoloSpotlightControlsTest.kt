package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.CharacterEntity
import com.example.ui.screens.GroupCharacterState
import com.example.ui.screens.SoloSpotlightControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SoloSpotlightControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun character(id: String, name: String) = CharacterEntity(
        characterId = id, displayName = name, characterName = name,
        aliases = emptyList(), importSchemaVersion = 1,
        identityJson = null, appearanceJson = null, personalityJson = null,
        voiceJson = null, behaviorJson = null, backstoryJson = null,
        knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
        examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
    )

    @Test fun soloSpotlightCanChoosePromotedNpcAndReturnToOriginal() {
        var selected = "original"
        compose.setContent {
            var focus by remember { mutableStateOf("original") }
            MaterialTheme {
                SoloSpotlightControls(
                    characters = listOf(
                        GroupCharacterState(character("original", "Original"), true, 0),
                        GroupCharacterState(character("npc", "Mara"), true, 1)
                    ),
                    selectedId = focus,
                    isGenerating = false,
                    onSelect = { focus = it; selected = it }
                )
            }
        }
        compose.onNodeWithTag("solo_spotlight_toggle").performClick()
        compose.onNodeWithTag("solo_spotlight_select_npc").assertIsEnabled().performClick()
        assertEquals("npc", selected)
        compose.onNodeWithText("Character spotlight: Mara").assertExists()
        compose.onNodeWithTag("solo_spotlight_select_original").performClick()
        assertEquals("original", selected)
    }

    @Test fun spotlightChangesAreDisabledDuringGeneration() {
        var changes = 0
        compose.setContent {
            MaterialTheme {
                SoloSpotlightControls(
                    characters = listOf(
                        GroupCharacterState(character("original", "Original"), true, 0),
                        GroupCharacterState(character("npc", "Mara"), true, 1)
                    ),
                    selectedId = "original", isGenerating = true,
                    onSelect = { changes++ }
                )
            }
        }
        compose.onNodeWithTag("solo_spotlight_toggle").performClick()
        compose.onNodeWithTag("solo_spotlight_select_npc").assertIsNotEnabled()
        assertEquals(0, changes)
    }
}
