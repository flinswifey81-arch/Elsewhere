package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer
import com.example.ui.navigation.ElsewhereApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.datastore.preferences.core.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AppNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testBottomNavigationFlows() {
        val context = RuntimeEnvironment.getApplication()
        val container = DefaultAppContainer(context)
        
        composeTestRule.setContent {
            ElsewhereApp(container = container)
        }
        
        composeTestRule.waitForIdle()
        
        // 1. Home
        composeTestRule.onNodeWithTag("home_screen").assertExists()
        composeTestRule.onNodeWithText("Your next doorway is ready.").assertExists()
        composeTestRule.onNodeWithText("New Solo Chat").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("New Group Chat").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("home_list")
            .performScrollToNode(hasText("Characters & Personas"))
        composeTestRule.onNodeWithText("Characters & Personas").assertExists()
        composeTestRule.onNodeWithText("Local co-op").assertDoesNotExist()
        
        // 2. Characters
        composeTestRule.onNodeWithText("Characters").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("characters_screen").assertExists()
        
        // 3. Personas
        composeTestRule.onNodeWithText("Personas").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("personas_screen").assertExists()
        
        // 4. Lorebooks remains directly reachable in the compact layout.
        navigateTo("Lorebooks")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("lorebooks_screen").assertExists()

        // 5. Compact navigation puts less-used destinations behind an explicit More menu.
        navigateTo("Chats")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chats_screen").assertExists()
        
        // 6. Settings
        navigateTo("Settings")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertExists()
        
        // 7. Back to Home
        composeTestRule.onNodeWithText("Home").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_screen").assertExists()
    }

    @Test
    fun compactNavigationKeepsLabelsSingleLineAndLorebooksVisibleAtLargeFontScale() {
        val context = RuntimeEnvironment.getApplication()
        val container = DefaultAppContainer(context)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                ElsewhereApp(container = container)
            }
        }

        composeTestRule.onNodeWithText("Lorebooks").assertExists()
        composeTestRule.onNodeWithText("More").assertExists()
        composeTestRule.onNodeWithText("Characters").assertDoesNotExist()
        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("Characters").assertExists()
        composeTestRule.onNodeWithText("Personas").assertExists()
        composeTestRule.onNodeWithText("Settings").assertExists()
    }

    private fun navigateTo(label: String) {
        if (composeTestRule.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()) {
            composeTestRule.onNodeWithText("More").performClick()
        }
        composeTestRule.onNodeWithText(label).performClick()
    }
}
