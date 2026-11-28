package com.example.redesign

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.ManualPersonaFields
import com.example.di.DefaultAppContainer
import com.example.ui.navigation.ElsewhereApp
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h851dp", manifest = Config.NONE)
class RedesignLibraryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsOpenRealResourcesAndFilterByMoreThanSharedStoryName() {
        val app = DefaultAppContainer(RuntimeEnvironment.getApplication())
        val npcId = runBlocking {
            for ((id, label) in listOf("river" to "Mara · Rivers AU", "street" to "Mara · Street AU")) {
                app.characterRepository.importCharacterJson("""
                    {"schema_version":1,"character_id":"$id","display_name":"$label","character_name":"Mara"}
                """.trimIndent())
            }
            app.personaRepository.createManualPersona(ManualPersonaFields(name = "Sky"))
            val lore = app.lorebookRepository.newLorebook("A Field Guide")
            app.lorebookRepository.saveDefinition(lore.lorebook, lore.entries)
            val bank = app.npcRepository.importLibraryBank("After Dark", """
                {"schema_version":1,"npcs":[{"npc_id":"mara","display_name":"Mara","role":"Archivist"}]}
            """.trimIndent())
            checkNotNull(app.npcRepository.getLibraryBank(bank)).entries.single().libraryNpcId
        }
        compose.setContent { AppTheme(darkTheme = true) { ElsewhereApp(container = app) } }
        compose.onNodeWithTag("bottom_nav_library").performClick()
        awaitTag("character_item_river")
        awaitTag("character_item_street")
        compose.onRoot().captureRoboImage(filePath = "build/outputs/roborazzi/ui-review/redesign_library_characters.png")
        compose.onNodeWithTag("library_character_search").performTextInput("mara rivers")
        compose.onNodeWithTag("character_item_river").assertExists()
        compose.onNodeWithTag("character_item_street").assertDoesNotExist()
        compose.onNodeWithText("List view").performClick()
        compose.onNodeWithTag("character_list").assertExists()
        compose.onNodeWithTag("hub_personas").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Sky").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("new_persona_fab").assertHasClickAction()
        compose.onNodeWithTag("import_persona_fab").assertHasClickAction()
        compose.onNodeWithTag("hub_lorebooks").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("A Field Guide").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hub_npc_bank").performScrollTo().performClick()
        awaitTag("library_npc_$npcId")
        compose.onNodeWithContentDescription("Edit Library portrait for Mara").performClick()
        compose.onNodeWithText("New chats start with this portrait. Existing chats keep their own copies.").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Chat NPCs").performClick()
        awaitTag("npc_bank_chat_picker")
        compose.onNodeWithTag("bottom_nav_library").performClick()
        compose.onNodeWithTag("hub_characters").performClick()
        compose.onNodeWithTag("character_list").assertExists()
        compose.onNodeWithTag("character_item_street").assertDoesNotExist()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
