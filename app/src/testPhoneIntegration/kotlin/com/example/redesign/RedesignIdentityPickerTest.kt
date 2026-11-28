package com.example.redesign

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.example.data.model.ManualPersonaFields
import com.example.di.DefaultAppContainer
import com.example.domain.repository.ProfileImageKind
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.theme.AppTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
@Config(sdk = [33], qualifiers = "w393dp-h851dp", manifest = Config.NONE)
class RedesignIdentityPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createRouteUploadsToTheRightSameNameProfileAndCanStartWithoutAGreeting() {
        val context = RuntimeEnvironment.getApplication()
        val app = DefaultAppContainer(context)
        val image = File(context.cacheDir, "sample-picker.png")
        Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.rgb(63, 123, 147))
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Uri.fromFile(image))
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val persona = runBlocking {
            fun card(id: String, label: String, opening: String) = """
                {"schema_version":1,"character_id":"$id","display_name":"$label","character_name":"Alex","first_mes":"$opening"}
            """.trimIndent()
            app.characterRepository.importCharacterJson(card("alex-idol", "Idol AU", "The stage opens."))
            app.characterRepository.importCharacterJson(card("alex-archivist", "Archivist AU", "The vault opens."))
            app.personaRepository.createManualPersona(ManualPersonaFields(name = "Rin"))
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AppTheme { ElsewhereApp(container = app) }
            }
        }
        compose.onNodeWithTag("bottom_nav_create").performClick()
        compose.onNodeWithTag("create_solo_hub").performClick()
        awaitTag("picker_identity_$persona")
        compose.onNodeWithTag("picker_identity_$persona").performClick()
        compose.onNodeWithText("Next").performClick()
        awaitTag("picker_identity_alex-archivist")
        compose.onNodeWithTag("picker_edit_alex-archivist", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("replace_profile_image").performClick()
        compose.waitUntil(10_000) {
            app.profileImageRepository.currentImage(ProfileImageKind.CHARACTER, "alex-archivist").file?.isFile == true
        }
        assertNull(app.profileImageRepository.currentImage(ProfileImageKind.CHARACTER, "alex-idol").file)
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Portrait for Archivist AU", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().captureRoboImage(filePath = "build/outputs/roborazzi/ui-review/redesign_identity_picker.png")
        compose.onNodeWithText("Grid").performClick()
        compose.onNodeWithTag("picker_search").performTextInput("Archivist")
        compose.onNodeWithTag("picker_identity_alex-idol").assertDoesNotExist()
        compose.onNodeWithTag("picker_identity_alex-archivist").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Read full greeting").performClick()
        compose.onNodeWithText("Greeting 1").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("No greeting").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("picker_identity_alex-archivist").assertIsSelected()
        compose.onNodeWithTag("picker_edit_alex-archivist", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Replace").assertExists()
        compose.onNodeWithTag("remove_profile_image").performClick()
        compose.onNodeWithText("Remove portrait").performClick()
        compose.waitUntil(10_000) {
            app.profileImageRepository.currentImage(ProfileImageKind.CHARACTER, "alex-archivist").file == null
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("picker_identity_alex-idol").assertDoesNotExist()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("No greeting").assertIsSelected()
        compose.onNodeWithText("Create Chat").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Create Chat").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Chat with Archivist AU").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            val chat = app.chatRepository.getAllChats().first().single()
            val summary = checkNotNull(app.chatRepository.getChatSummaryById(chat.chatId))
            assertEquals("alex-archivist", summary.characters.single().characterId)
            assertTrue(app.messageRepository.getMessagesForChatOnce(chat.chatId).isEmpty())
        }
        image.delete()
    }

    @Test fun filtersSameNameCharactersUsingTheLibraryCollection() {
        val app = DefaultAppContainer(RuntimeEnvironment.getApplication())
        val persona = runBlocking {
            fun json(id: String, label: String) = """
                {"schema_version":1,"character_id":"$id","display_name":"$label","character_name":"Alex"}
            """.trimIndent()
            app.characterRepository.importCharacterJson(json("alex-stage", "Alex on Stage"))
            app.characterRepository.importCharacterJson(json("alex-garage", "Alex in Garage"))
            app.personaRepository.createManualPersona(ManualPersonaFields(name = "Rin"))
        }
        compose.setContent { AppTheme { ElsewhereApp(container = app) } }
        compose.onNodeWithTag("bottom_nav_library").performClick()
        awaitTag("character_item_alex-garage")
        compose.onNodeWithText("New collection").performClick()
        awaitTag("collection_name")
        compose.onNodeWithTag("collection_name").performTextReplacement("After Hours")
        compose.onNodeWithTag("collection_member_alex-garage").performClick()
        compose.onNodeWithText("Save collection").performClick()
        awaitTag("collection_filter")
        compose.onNodeWithTag("bottom_nav_create").performClick()
        compose.onNodeWithTag("create_solo_hub").performClick()
        awaitTag("picker_identity_$persona")
        compose.onNodeWithTag("picker_identity_$persona").performClick()
        compose.onNodeWithText("Next").performClick()
        awaitTag("picker_identity_alex-stage")
        awaitTag("collection_filter")
        compose.onNodeWithTag("collection_filter").performClick()
        compose.onNodeWithTag("collection_option_After Hours").performClick()
        compose.onNodeWithTag("picker_identity_alex-stage").assertDoesNotExist()
        compose.onNodeWithTag("picker_identity_alex-garage").performClick().assertIsSelected()
        compose.onNodeWithTag("collection_filter").performClick()
        compose.onNodeWithText("All collections").performClick()
        compose.onNodeWithTag("picker_identity_alex-stage").assertExists()
        compose.onNodeWithTag("picker_identity_alex-garage").assertIsSelected()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
