package com.example.redesign

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.CampaignPlaythroughField
import com.example.data.model.ManualCharacterFields
import com.example.data.model.ManualPersonaFields
import com.example.di.DefaultAppContainer
import com.example.ui.screens.CampaignPlaythroughStateSection
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
class RedesignContinuityEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completePlaythroughFactCanBeViewedChangedAndSavedToTheSameEpisode() {
        val original = "A".repeat(11_988) + "\nFinal line!"
        val edited = original.replace("Final line!", "Final late!")
        assertEquals(12_000, original.length)
        assertEquals(12_000, edited.length)
        val app = DefaultAppContainer(RuntimeEnvironment.getApplication())
        val repo = app.campaignRepository
        val chapter = runBlocking {
            val world = app.worldRepository.createWorld("Night city")
            val campaign = repo.createCampaign(world.worldId, "Continuity fixture")
            val cast = app.characterRepository.createManualCharacter(ManualCharacterFields(name = "Archivist"))
            val player = app.personaRepository.createManualPersona(ManualPersonaFields(name = "Rin"))
            val chat = app.chatRepository.createSoloChat("Closing hour", player, cast, "Archivist", null)
            repo.addChapter(campaign.campaignId, chat, "Closing hour").also {
                repo.setPlaythroughState(campaign.campaignId, it.chapterId, CampaignPlaythroughField.LOCATION,
                    "Archive", original)
            }
        }
        val id = chapter.campaignId
        compose.setContent {
            AppTheme {
                val facts by repo.observeContinuityItems(id).collectAsState(emptyList())
                val scope = rememberCoroutineScope()
                Column {
                    CampaignPlaythroughStateSection(id, listOf(chapter), facts, false) { episode, field, subject, text, share, saved ->
                        scope.launch {
                            repo.setPlaythroughState(id, episode, field, subject, text, share)
                            saved()
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithTag("open_long_text_What is true now?").performClick()
        val body = compose.onNodeWithTag("long_text_body")
        assertEquals(original, body.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        body.performTextReplacement(edited)
        compose.onNodeWithTag("long_text_done").performClick()
        assertEquals(original, runBlocking { repo.playthroughStateForChapter(id, chapter.chapterId).single().details })
        compose.onNodeWithTag("save_campaign_playthrough_state").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("save_campaign_playthrough_state").fetchSemanticsNodes().isEmpty()
        }
        val stored = runBlocking { repo.playthroughStateForChapter(id, chapter.chapterId).single() }
        assertEquals(edited, stored.details)
        assertEquals("", stored.resolution) // Editing did not implicitly share the fact.
    }
}
