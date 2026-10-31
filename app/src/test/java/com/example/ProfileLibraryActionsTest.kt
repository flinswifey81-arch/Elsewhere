package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.PersonaRepository
import com.example.ui.components.ProfileLibraryActions
import com.example.ui.screens.CharactersScreen
import com.example.ui.screens.PersonasScreen
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileLibraryActionsTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        database?.close()
    }

    @Test
    fun actionsStartCollapsedAndCollapseAfterEitherSelection() {
        var created = 0
        var imported = 0
        compose.setContent {
            if (visible.value) MaterialTheme {
                ProfileLibraryActions("Character", { created++ }, { imported++ })
            }
        }
        compose.onNodeWithTag("new_character_fab").assertDoesNotExist()
        compose.onNodeWithTag("import_character_fab").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add Character").performClick()
        compose.onNodeWithTag("new_character_fab").performClick()
        compose.onNodeWithTag("import_character_fab").assertDoesNotExist()
        compose.onNodeWithTag("character_actions").performClick()
        compose.onNodeWithTag("import_character_fab").performClick()
        compose.onNodeWithTag("new_character_fab").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, created)
            assertEquals(1, imported)
        }
    }

    @Test
    fun charactersCanScrollLastCardAboveCollapsedActionsAndSelectIt(): Unit = runBlocking {
        val db = openDatabase()
        val repository = CharacterRepository(db.characterDao(), Moshi.Builder().build())
        repeat(12) { i -> repository.importCharacterJson("""{"character_id":"c$i","name":"Character $i"}""") }
        var selected: String? = null
        compose.setContent {
            if (visible.value) MaterialTheme {
                Box(Modifier.size(360.dp, 560.dp)) {
                    CharactersScreen(repository, onCharacterSelected = { selected = it })
                }
            }
        }
        compose.waitUntil { compose.onAllNodesWithTag("character_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("character_list").performScrollToIndex(11)
        val last = db.characterDao().getAllCharacters().first().last()
        val card = compose.onNodeWithTag("character_item_${last.characterId}")
        card.assertIsDisplayed()
        assertTrue(card.getUnclippedBoundsInRoot().bottom <= compose.onNodeWithTag("character_actions").getUnclippedBoundsInRoot().top)
        card.performClick()
        compose.runOnIdle { assertEquals(last.characterId, selected) }
        compose.onNodeWithTag("character_actions").performClick()
        compose.onNodeWithTag("import_character_fab").performClick()
        compose.onNodeWithText("Choose JSON Files").assertIsDisplayed()
    }

    @Test
    fun personasCanScrollLastCardAboveCollapsedActionsAndCreate(): Unit = runBlocking {
        val db = openDatabase()
        val repository = PersonaRepository(db.personaDao(), Moshi.Builder().build())
        repeat(12) { i -> repository.importPersonaJson("""{"schema_version":1,"persona_id":"p$i","display_name":"Persona $i","persona_name":"Persona $i"}""") }
        compose.setContent {
            if (visible.value) MaterialTheme {
                Box(Modifier.size(360.dp, 560.dp)) {
                    PersonasScreen(repository, onPersonaSelected = {})
                }
            }
        }
        compose.waitUntil { compose.onAllNodesWithTag("persona_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("persona_list").performScrollToIndex(11)
        val last = db.personaDao().getAllPersonas().first().last()
        val card = compose.onNodeWithTag("persona_item_${last.personaId}")
        card.assertIsDisplayed()
        assertTrue(card.getUnclippedBoundsInRoot().bottom <= compose.onNodeWithTag("persona_actions").getUnclippedBoundsInRoot().top)
        compose.onNodeWithTag("persona_actions").performClick()
        compose.onNodeWithTag("new_persona_fab").performClick()
        compose.onNodeWithText("New Persona").assertIsDisplayed()
    }

    private fun openDatabase(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
    ).allowMainThreadQueries().build().also { database = it }
}
