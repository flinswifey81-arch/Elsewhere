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
        
        // 2. Characters
        composeTestRule.onNodeWithText("Characters").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("characters_screen").assertExists()
        
        // 3. Personas
        composeTestRule.onNodeWithText("Personas").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("personas_screen").assertExists()
        
        // 4. Chats
        composeTestRule.onNodeWithText("Chats").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chats_screen").assertExists()
        
        // 5. Settings
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertExists()
        
        // 6. Back to Home
        composeTestRule.onNodeWithText("Home").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_screen").assertExists()
    }
}
