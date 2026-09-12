package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.screens.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.content.Context

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class SettingsFunctionalTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSettingsFunctionalControls() {
        val context = RuntimeEnvironment.getApplication()
        val settingsRepository = SettingsRepository(context)
        val appearanceRepository = AppearanceRepository(context)
        
        composeTestRule.setContent {
            SettingsScreen(
                settingsRepository = settingsRepository,
                appearanceRepository = appearanceRepository
            )
        }
        
        composeTestRule.waitForIdle()
        
        // Appearance: Theme Mode
        composeTestRule.onNodeWithText("Theme Mode").assertExists()
        composeTestRule.onNodeWithText("Dark").performClick()
        
        // Appearance: Accent Preset
        composeTestRule.onNodeWithText("Accent Color").assertExists()
        composeTestRule.onNodeWithText("Sage").performClick()
        
        composeTestRule.waitForIdle()
        
        // Model & API
        composeTestRule.onNodeWithText("Model & API").assertExists()
        
        // Since isLoaded is async, we wait for it
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("API Key").fetchSemanticsNodes().isNotEmpty()
        }
        
        // Test API Key save
        composeTestRule.onNodeWithTag("api_key_input").performTextInput("sk-test-key")
        composeTestRule.onNodeWithTag("save_key_button").performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify key is removed
        composeTestRule.onNodeWithText("Remove Key").assertExists()
        composeTestRule.onNodeWithText("Remove Key").performClick()
        composeTestRule.waitForIdle()
        
        // Back to normal
        composeTestRule.onNodeWithTag("api_key_input").assertExists()
    }
}
