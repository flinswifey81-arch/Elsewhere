package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.CharacterEntity
import com.example.ui.screens.GroupCharacterControls
import com.example.ui.screens.GroupCharacterState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupCharacterControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun startsCollapsedAndExpandsWithoutLosingSpeakerOrActions() {
        val selectedId = mutableStateOf("aha")
        var generatedId: String? = null
        val characters = listOf(
            GroupCharacterState(character("aha", "Aha"), isActive = true, sortPosition = 0),
            GroupCharacterState(character("zhongli", "Zhongli"), isActive = true, sortPosition = 1)
        )

        composeRule.setContent {
            MaterialTheme {
                GroupCharacterControls(
                    characters = characters,
                    availableCharacters = characters.map { it.character },
                    selectedCharacterId = selectedId.value,
                    isGenerating = false,
                    canGenerate = true,
                    onSelectCharacter = { selectedId.value = it },
                    onGenerate = { generatedId = it },
                    onSetActive = { _, _ -> },
                    onMove = { _, _ -> },
                    onRemove = {},
                    onAdd = {}
                )
            }
        }

        composeRule.onNodeWithTag("group_controls_toggle").assertIsDisplayed()
        composeRule.onNodeWithText("Next: Aha", substring = true).assertExists()
        composeRule.onNodeWithTag("group_controls_content").assertDoesNotExist()
        composeRule.onNodeWithTag("group_controls_toggle").performClick()
        composeRule.onNodeWithTag("group_controls_content").assertExists()
        composeRule.onNodeWithText("Zhongli").performClick()
        composeRule.onNodeWithTag("group_controls_generate").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals("zhongli", selectedId.value)
            assertEquals("zhongli", generatedId)
        }
        composeRule.onNodeWithText("Manage Characters").performClick()
        composeRule.onNodeWithText("Manage Group Characters").assertExists()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithTag("group_controls_toggle").performClick()
        composeRule.onNodeWithTag("group_controls_content").assertDoesNotExist()
        composeRule.onNodeWithText("Next: Zhongli", substring = true).assertExists()
    }

    @Test
    fun expandedControlsRespectGenerateAvailability() {
        composeRule.setContent {
            MaterialTheme {
                GroupCharacterControls(
                    characters = listOf(GroupCharacterState(character("aha", "Aha"), true, 0)),
                    availableCharacters = emptyList(),
                    selectedCharacterId = "aha",
                    isGenerating = false,
                    canGenerate = false,
                    onSelectCharacter = {},
                    onGenerate = {},
                    onSetActive = { _, _ -> },
                    onMove = { _, _ -> },
                    onRemove = {},
                    onAdd = {}
                )
            }
        }
        composeRule.onNodeWithTag("group_controls_toggle").performClick()
        composeRule.onNodeWithTag("group_controls_generate").assertIsNotEnabled()
    }

    private fun character(id: String, name: String) = CharacterEntity(
        characterId = id,
        displayName = name,
        characterName = name,
        aliases = emptyList(),
        importSchemaVersion = 4,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        voiceJson = null,
        behaviorJson = null,
        backstoryJson = null,
        knowledgeJson = null,
        worldContextJson = null,
        writingRulesJson = null,
        examplesJson = null,
        authorNotesJson = null,
        extensionFieldsJson = null
    )
}
