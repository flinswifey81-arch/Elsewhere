package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.di.DefaultAppContainer
import com.example.ui.navigation.ElsewhereApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AppNavigationTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun fivePrimaryDestinationsStayReachableWithoutOverlappingPopup() {
        val container = DefaultAppContainer(RuntimeEnvironment.getApplication())
        composeTestRule.setContent { ElsewhereApp(container = container) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_screen").assertExists()
        composeTestRule.onNodeWithText("Your next doorway is ready.").assertExists()
        val matchedBanner = listOf("purple", "ocean_blue", "forest_green", "rose", "slate").any { palette ->
            composeTestRule.onAllNodesWithTag("elsewhere_banner_" + palette).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertTrue("Expected a themed Elsewhere banner", matchedBanner)
        composeTestRule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_library_link"))
        composeTestRule.onNodeWithTag("home_library_link").assertExists()
        composeTestRule.onNodeWithText("More").assertDoesNotExist()

        composeTestRule.onNodeWithTag("bottom_nav_chats").performClick()
        composeTestRule.onNodeWithTag("chats_screen").assertExists()

        composeTestRule.onNodeWithTag("bottom_nav_create").performClick()
        composeTestRule.onNodeWithTag("create_hub").assertExists()
        composeTestRule.onNodeWithTag("create_solo_hub").assertExists()
        composeTestRule.onNodeWithTag("create_scenario_hub").assertExists()

        composeTestRule.onNodeWithTag("bottom_nav_worlds").performClick()
        composeTestRule.onNodeWithTag("worlds_list").assertExists()

        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.onNodeWithTag("library_hub").assertExists()
        composeTestRule.onNodeWithTag("hub_characters").performClick()
        composeTestRule.onNodeWithTag("characters_screen").assertExists()

        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.onNodeWithTag("library_hub").assertExists()
        composeTestRule.onNodeWithTag("library_hub").performScrollToNode(hasTestTag("hub_personas"))
        composeTestRule.onNodeWithTag("hub_personas").performClick()
        composeTestRule.onNodeWithTag("personas_screen").assertExists()

        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.onNodeWithTag("library_hub").assertExists()
        composeTestRule.onNodeWithTag("library_hub").performScrollToNode(hasTestTag("hub_lorebooks"))
        composeTestRule.onNodeWithTag("hub_lorebooks").performClick()
        composeTestRule.onNodeWithTag("lorebooks_screen").assertExists()

        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.onNodeWithTag("library_hub").performScrollToNode(hasTestTag("hub_npc_bank"))
        composeTestRule.onNodeWithTag("hub_npc_bank").performClick()
        composeTestRule.onNodeWithTag("npc_bank_chat_picker").assertExists()
        composeTestRule.onNodeWithText("No chats yet. Start a story first, then return here to manage its NPC Bank.").assertExists()

        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.onNodeWithTag("library_hub").assertExists()
        composeTestRule.onNodeWithTag("library_hub").performScrollToNode(hasTestTag("hub_settings"))
        composeTestRule.onNodeWithTag("hub_settings").performClick()
        composeTestRule.onNodeWithTag("settings_screen").assertExists()

        composeTestRule.onNodeWithTag("bottom_nav_home").performClick()
        composeTestRule.onNodeWithTag("home_screen").assertExists()
    }

    @Test
    fun largeTextNeverHidesPrimaryDestinationsBehindOverflow() {
        val container = DefaultAppContainer(RuntimeEnvironment.getApplication())
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                ElsewhereApp(container = container)
            }
        }
        listOf("home", "chats", "create", "worlds", "library").forEach { route ->
            composeTestRule.onNodeWithTag("bottom_nav_" + route).assertExists()
        }
        composeTestRule.onNodeWithText("More").assertDoesNotExist()
        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.onNodeWithTag("hub_characters").assertExists()
        composeTestRule.onNodeWithTag("hub_personas").assertExists()
    }
}
