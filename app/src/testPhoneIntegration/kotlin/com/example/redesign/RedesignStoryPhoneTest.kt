package com.example.redesign

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.EngineDefaultsEntity
import com.example.data.model.ManualCharacterFields
import com.example.data.model.ManualPersonaFields
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer
import com.example.domain.provider.*
import com.example.phone.StoryPhoneActions
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    val response = if (options.modelId == "fake/pulse")
                        """{"posts":[{"accountId":"board","eventId":"workshop","kind":"reaction","text":"I’ll come to the workshop."}]}"""
                    else "Meet me in the garage."
                    return flowOf(StreamEvent.Content(response), StreamEvent.Done(null))
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
            checkNotNull(app.pulseModelSettings).saveModel("fake/pulse")
            val phone = checkNotNull(app.storyPhone)
            val finalPost = "Last bulletin: the full final sentence must be visible above the bottom edge."
            val filler = (1..9).joinToString(",") { index ->
                val body = if (index == 9) finalPost else "Garage bulletin $index. " + "Workshop details are still being posted. ".repeat(6)
                """{"id":"post-$index","accountId":"board","kind":"ambient","text":"$body"}"""
            }
            val pack = """{"format":"elsewhere.world-pulse.feed-pack","version":1,"name":"Workshop Demo","platforms":[{"id":"board","name":"Community Board","style":"forum"}],"accounts":[{"id":"board","platformId":"board","handle":"board","displayName":"Board Regular","role":"Mechanic"}],"starterState":{"publicEvents":[{"id":"workshop","visibility":"public","summary":"A workshop was announced."}],"posts":[$filler]}}"""
            phone.importReviewedPack(phone.previewFeedPack(StoryPhoneActions.Target(chat, persona), pack.toByteArray()), true)
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
        awaitTag("pulse_refresh")
        compose.onNodeWithTag("pulse_refresh").performClick()
        compose.onNodeWithTag("pulse_event_workshop").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodes(hasTestTag("pulse_generate") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pulse_generate").performClick()
        awaitTag("pulse_publish")
        compose.onNodeWithTag("pulse_publish").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("I’ll come to the workshop.").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("fake/pulse", sent.single().second.modelId)
        assertFalse(sent.single().first.any { "Secret opening scene." in it.content })
        val feed = compose.onNodeWithTag("phone_pulse_feeds")
        val last = "Last bulletin: the full final sentence must be visible above the bottom edge."
        compose.onNodeWithTag("bottom_nav_library").assertDoesNotExist()
        feed.performTouchInput { swipeUp() }
        feed.performScrollToNode(hasText(last))
        val lastBounds = compose.onNodeWithText(last, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val feedBounds = feed.getUnclippedBoundsInRoot()
        assertTrue("The entire last post body must fit in the feed viewport", lastBounds.bottom <= feedBounds.bottom)
        assertTrue(lastBounds.top >= feedBounds.top)

        compose.onNodeWithContentDescription("Back on the phone").performClick()
        compose.onNodeWithTag("phone_texting").performClick()
        compose.onNodeWithTag("phone_contact_character:$lead").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodes(hasTestTag("phone_text_composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("phone_text_composer").performTextInput("Where are you?")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Meet me in the garage.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("fake/pulse", "fake/override"), sent.map { it.second.modelId })
        val (messages, options) = sent.last()
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
