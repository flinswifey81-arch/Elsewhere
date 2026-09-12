package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.di.DefaultAppContainer
import com.example.ui.screens.ChatSettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ChatSettingsScreenInitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSettingsScreenDoesNotCrashOnOpen() {
        val context = RuntimeEnvironment.getApplication()
        val container = DefaultAppContainer(context)
        
        composeTestRule.setContent {
            ChatSettingsScreen(
                chatId = UUID.randomUUID().toString(),
                appContainer = container,
                onNavigateBack = {}
            )
        }
        
        composeTestRule.waitForIdle()
    }
}
