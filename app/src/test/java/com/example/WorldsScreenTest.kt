package com.example

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.WorldEntity
import com.example.data.model.WorldCastEntity
import com.example.data.model.WorldContinuityEventEntity
import com.example.ui.screens.WorldDetailsDialog
import com.example.ui.screens.WorldCastDialog
import com.example.ui.screens.WorldEventDialog
import com.example.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class WorldsScreenTest {
    @get:Rule val compose = createComposeRule()

    // Robolectric Compose idling can loop indefinitely with a synthetic small-window\n    // qualifier; phone-width coverage belongs in device instrumentation tests.\n    @Test\n    fun worldCanBeCreatedManually() {
        var saved: WorldEntity? = null
        compose.setContent {
            var draft by remember { mutableStateOf(WorldEntity(worldId = "test", title = "")) }
            AppTheme {
                WorldDetailsDialog(draft, true, false, { draft = it }, {}, { saved = draft })
            }
        }
        compose.onNodeWithText("World title").performTextInput("Mondstadt")
        compose.onNodeWithTag("save_world").assertIsEnabled().performClick()
        assertEquals("Mondstadt", saved?.title)
    }

    @Test
    fun worldCastIsNotForcedIntoAnAiCharacterRole() {
        var saved: WorldCastEntity? = null
        compose.setContent {
            var draft by remember { mutableStateOf(WorldCastEntity(worldId = "mondstadt", name = "")) }
            AppTheme {
                WorldCastDialog(draft, true, false, { draft = it }, {}, { saved = draft })
            }
        }
        compose.onNodeWithText("Name").performTextInput("Sucrose")
        compose.onNodeWithTag("save_world_cast").assertIsEnabled().performClick()
        assertEquals("Sucrose", saved?.name)
        assertEquals("manual", saved?.provenanceKind)
    }

    @Test
    fun continuityNeedsTitleAndFactsBeforeItCanBeSavedAsDraft() {
        var saved: WorldContinuityEventEntity? = null
        compose.setContent {
            var draft by remember { mutableStateOf(WorldContinuityEventEntity(worldId = "mondstadt", title = "", facts = "")) }
            AppTheme {
                WorldEventDialog(draft, true, false, { draft = it }, {}, { saved = draft })
            }
        }
        compose.onNodeWithTag("save_world_event").assertIsNotEnabled()
        compose.onNodeWithText("Event title").performTextInput("City Hall hearing")
        compose.onNodeWithTag("save_world_event").assertIsNotEnabled()
        compose.onNodeWithText("What happened").performTextInput("Venti meets the constituents.")
        compose.onNodeWithTag("save_world_event").assertIsEnabled().performClick()
        assertEquals("DRAFT", saved?.status)
    }
}
