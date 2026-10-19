package com.example

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.*
import com.example.ui.screens.ScenarioEditor
import com.example.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ScenarioEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test @Config(qualifiers = "w360dp-h800dp")
    fun quickSceneMinimalFieldsCanBeSavedOnPhone() {
        var saved: ScenarioDefinition? = null
        compose.setContent {
            var draft by remember { mutableStateOf(ScenarioDefinition()) }
            AppTheme { ScenarioEditor(draft, { draft = it }, { saved = draft }, {}) }
        }
        compose.onNodeWithText("Scenario Title").performTextInput("Ball")
        compose.onNodeWithText("Premise / Scene Setup").performTextInput("An unexpected guest arrives.")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Save scenario"))
        compose.onNodeWithText("Save scenario").assertIsEnabled().performClick()
        assertEquals("Ball", saved?.title)
        assertTrue(saved!!.npcs.isEmpty())
        assertEquals("", saved!!.openingMessage)
    }
    @Test @Config(qualifiers = "w1200dp-h900dp")
    fun sandboxWorldbuildingAndFourNpcsRemainEditableOnWideWindow() {
        var saved: ScenarioDefinition? = null
        compose.setContent {
            var draft by remember { mutableStateOf(ScenarioDefinition(experience = ScenarioExperience.SANDBOX, title = "City", premise = "Explore", npcs = (1..4).map { ScenarioNpc(name = "NPC $it") })) }
            AppTheme { ScenarioEditor(draft, { draft = it }, { saved = draft }, {}) }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("World and Setting Details"))
        compose.onNodeWithText("World and Setting Details").performTextInput("A city on the sea")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Save scenario"))
        compose.onNodeWithText("Save scenario").performClick()
        assertEquals("A city on the sea", saved?.setting)
        assertEquals(4, saved?.npcs?.size)
    }
}
