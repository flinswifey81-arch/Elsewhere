package com.example.redesign

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.EngineDefaultsEntity
import com.example.data.model.ManualCharacterFields
import com.example.data.model.ManualPersonaFields
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer
import com.example.domain.provider.*
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class RedesignStoryPhoneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun storyMenuOpensRealPhoneAndTextingUsesChatOverrideWithoutPouringInTheScene() {
        val app = DefaultAppContainer(RuntimeEnvironment.getApplication())
        val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<List<RoleplayMessage>, GenerationOptions>>()
        val container = object : AppContainer by app {
            override val modelProvider: ModelProvider = object : ModelProvider {
                override suspend fun getModels() = emptyList<OpenRouterModel>()
                override suspend fun getEndpoints(modelId: String) = emptyList<ProviderEndpoint>()
                override fun streamResponse(messages: List<RoleplayMessage>, options: GenerationOptions): kotlinx.coroutines.flow.Flow<StreamEvent> {
                    sent.add(messages to options)
                    return flowOf(StreamEvent.Content("Meet me in the garage."), StreamEvent.Done(null))
                }
            }
        }
        val lead = runBlocking {
            val world = app.worldRepository.createWorld("Two timelines")
            val campaign = app.campaignRepository.createCampaign(world.worldId, "Night shifts")
            val character = app.characterRepository.createManualCharacter(ManualCharacterFields(name = "Alex"))
            val persona = app.personaRepository.createManualPersona(ManualPersonaFields(name = "Rin"))
            val chat = app.chatRepository.createSoloChat("Workshop story", persona, character, "Alex", "Secret opening scene.")
            app.campaignRepository.addChapter(campaign.campaignId, chat, "Test episode")
            app.chatRepository.saveEngineDefaults(EngineDefaultsEntity(selectedModelId = "fake/global"))
            val settings = checkNotNull(app.chatRepository.getChatSettings(chat))
            app.chatRepository.insertChatSettings(settings.copy(useGlobalEngineDefaults = false, selectedModelId = "fake/override"))
            character
        }
        compose.setContent { AppTheme { ElsewhereApp(container = container) } }
        awaitTag("home_continue_story")
        compose.onNodeWithTag("home_continue_story").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("Chat navigation and actions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Chat navigation and actions").performClick()
        compose.onNodeWithTag("open_persona_phone").performClick()
        awaitTag("phone_texting")
        compose.onNodeWithTag("phone_world_pulse").performClick()
        awaitTag("phone_pulse_feeds")
        assertEquals(0, sent.size)
        compose.onNodeWithContentDescription("Back on the phone").performClick()
        compose.onNodeWithTag("phone_texting").performClick()
        compose.onNodeWithTag("phone_contact_character:$lead").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodes(hasTestTag("phone_text_composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("phone_text_composer").performTextInput("Where are you?")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Meet me in the garage.").fetchSemanticsNodes().isNotEmpty() }
        val (messages, options) = sent.single()
        assertEquals("fake/override", options.modelId)
        assertEquals("Where are you?", messages.last().content)
        assertFalse(messages.any { "Secret opening scene." in it.content })
        compose.onNodeWithTag("phone_return_story").performClick()
        compose.onNodeWithTag("persona_phone").assertDoesNotExist()
        compose.onNodeWithText("Meet me in the garage.").assertDoesNotExist()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
