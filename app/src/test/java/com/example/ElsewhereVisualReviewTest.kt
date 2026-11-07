package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.di.DefaultAppContainer
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.ColorPalette
import com.example.domain.repository.ThemeMode
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.AppTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Offline images of the actual Compose UI, not a mockup.
 * They use a disposable, empty test database and never access the user's chats.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h851dp", manifest = Config.NONE)
class ElsewhereVisualReviewTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/ui-review/" + name + ".png"
        )
    }

    @Test
    fun homeInAllTenPaletteAppearances() {
        val app = DefaultAppContainer(RuntimeEnvironment.getApplication())
        var selected by mutableStateOf(ColorPalette.OCEAN_BLUE)
        var dark by mutableStateOf(true)
        compose.setContent {
            AppTheme(
                darkTheme = dark,
                appearanceSettings = AppearanceSettings(
                    themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                    colorPalette = selected
                )
            ) {
                Surface(Modifier.fillMaxSize()) {
                    HomeScreen(
                        chatRepository = app.chatRepository,
                        onNavigateToChat = {}, onNavigateToLibrary = {},
                        onNavigateToLorebooks = {}, onNavigateToSettings = {},
                        onNavigateToChats = {}, onNewSoloChat = {},
                        onNewGroupChat = {}, onScenarios = {},
                        palette = selected
                    )
                }
            }
        }
        for (palette in ColorPalette.selectable) {
            for (isDark in listOf(true, false)) {
                compose.runOnIdle {
                    selected = palette
                    dark = isDark
                }
                compose.onNodeWithTag("elsewhere_banner_" + palette.storageId).assertExists()
                val modeName = if (isDark) "dark" else "light"
                shot("home_" + palette.storageId + "_" + modeName)
            }
        }
    }

    @Test
    fun navigationAndSettingsInOceanBlue() {
        val app = DefaultAppContainer(RuntimeEnvironment.getApplication())
        runBlocking {
            app.appearanceRepository.updateColorPalette(ColorPalette.OCEAN_BLUE)
            app.appearanceRepository.updateThemeMode(ThemeMode.DARK)
        }
        compose.setContent {
            val settings by app.appearanceRepository.appearanceSettings.collectAsState(
                initial = AppearanceSettings()
            )
            AppTheme(darkTheme = true, appearanceSettings = settings) {
                ElsewhereApp(container = app)
            }
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("elsewhere_banner_ocean_blue")
                .fetchSemanticsNodes().isNotEmpty()
        }
        shot("navigation_home_ocean_blue")
        compose.onNodeWithTag("bottom_nav_create").performClick()
        compose.onNodeWithTag("create_hub").assertExists()
        shot("navigation_create")
        compose.onNodeWithTag("bottom_nav_library").performClick()
        compose.onNodeWithTag("library_hub").assertExists()
        shot("navigation_library")
        compose.onNodeWithTag("library_hub").performScrollToNode(hasTestTag("hub_settings"))
        compose.onNodeWithTag("hub_settings").performClick()
        compose.onNodeWithTag("settings_screen").assertExists()
        shot("settings_appearance")
        compose.onNodeWithTag("settings_tabs")
            .performScrollToNode(hasTestTag("settings_tab_connection"))
        compose.onNodeWithTag("settings_tab_connection").performClick()
        shot("settings_connection")
        compose.onNodeWithTag("settings_tabs")
            .performScrollToNode(hasTestTag("settings_tab_about"))
        compose.onNodeWithTag("settings_tab_about").performClick()
        shot("settings_about")
    }
}
