package com.example.redesign

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.runtime.*
import java.io.File
import com.example.domain.repository.ProfileImageKind
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.di.DefaultAppContainer
import com.example.data.model.ManualCharacterFields
import com.example.data.model.ManualPersonaFields
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.ColorPalette
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.theme.AppTheme
import com.example.ui.theme.RedesignPreviewTokens
import com.example.ui.theme.getDarkColorScheme
import com.example.ui.theme.getLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
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
@Config(sdk=[33], qualifiers="w393dp-h851dp", manifest=Config.NONE)
class RedesignHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeHomeUsesRealStoryRoutesAndKeepsTheBrandAcrossThemes() {
        val app=DefaultAppContainer(RuntimeEnvironment.getApplication())
        val storyId = runBlocking {
            app.appearanceRepository.updateColorPalette(ColorPalette.OCEAN_BLUE)
            val lead = app.characterRepository.createManualCharacter(ManualCharacterFields(name="Alex"))
            val persona = app.personaRepository.createManualPersona(ManualPersonaFields(name="Rin"))
            val id=app.chatRepository.createSoloChat("Garage at midnight",persona,lead,"Alex",null)
            val ctx=RuntimeEnvironment.getApplication()
            val photo=File(ctx.cacheDir,"redesign-test-cover.png")
            Bitmap.createBitmap(393,222,Bitmap.Config.ARGB_8888).let { bitmap ->
                val cool=AndroidColor.rgb(45,91,116)
                val warm=AndroidColor.rgb(230,163,154)
                for (x in 0 until 393) for (y in 0 until 222) {
                    bitmap.setPixel(x,y,if ((x-260)*(x-260)+(y-74)*(y-74)<1600) warm else cool)
                }
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            try { app.profileImageRepository.replaceImage(ProfileImageKind.CHAT_COVER,id,Uri.fromFile(photo)) }
            finally { photo.delete() }
            id
        }
        var dark by mutableStateOf(true)
        compose.setContent {
            val appearance by app.appearanceRepository.appearanceSettings.collectAsState(initial=AppearanceSettings())
            AppTheme(darkTheme=dark, appearanceSettings=appearance) {
                ElsewhereApp(container=app)
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("home_continue_story").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("elsewhere_banner_ocean_blue").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("elsewhere_banner_ocean_blue").assertExists()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("cover_art_CHAT_COVER_$storyId",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("redesign_home_moonlit_dark")
        compose.runOnIdle { dark=false }
        shot("redesign_home_moonlit_light")
        compose.onNodeWithTag("home_continue_story").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Garage at midnight").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bottom_nav_library").performClick()
        compose.onNodeWithTag("library_hub").assertExists()
        compose.onNodeWithTag("bottom_nav_chats").performClick()
        compose.onNodeWithText("Garage at midnight").assertExists()
        compose.onNodeWithTag("cover_actions_CHAT_COVER_$storyId",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Replace cover image").assertExists()
        compose.onNodeWithText("Remove cover").assertExists()
        compose.onNodeWithText("Remove cover").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("bottom_nav_worlds").performClick()
        compose.onNodeWithTag("worlds_list").assertExists()
    }

    @Test fun approvedThemeTokensHaveReadableTextAndSlateRemainsAvailable() {
        fun contrast(a: Color,b: Color): Float {
            val x=a.luminance()
            val y=b.luminance()
            return (maxOf(x,y)+0.05f)/(minOf(x,y)+0.05f)
        }
        for (palette in ColorPalette.selectable) for (dark in listOf(true,false)) {
            val original=if(dark)getDarkColorScheme(palette) else getLightColorScheme(palette)
            val scheme=RedesignPreviewTokens.apply(original,palette,dark)
            assertTrue(contrast(scheme.onBackground,scheme.background)>=4.5f)
            assertTrue(contrast(scheme.onSurface,scheme.surface)>=4.5f)
            assertTrue(contrast(scheme.onPrimary,scheme.primary)>=4.5f)
            if(palette==ColorPalette.SLATE) assertTrue(original===scheme)
        }
    }

    private fun shot(name:String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(filePath="build/outputs/roborazzi/ui-review/$name.png")
    }
}
