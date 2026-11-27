package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.model.ManualPersonaFields
import com.example.data.model.SpeakerType
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.PersonaRepository
import com.example.ui.screens.CreateSoloChatScreen
import com.example.ui.theme.AppTheme
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class SoloGreetingPickerUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: AppDatabase
    private lateinit var chats: ChatRepository
    private lateinit var personas: PersonaRepository
    private lateinit var characters: CharacterRepository
    private val createdChat = AtomicReference<String?>(null)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val coordinator = AppDataMaintenanceCoordinator()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        chats = ChatRepository(database.chatDao(), database.messageDao(), coordinator)
        personas = PersonaRepository(database.personaDao(), moshi, dataCoordinator = coordinator)
        characters = CharacterRepository(database.characterDao(), moshi, dataCoordinator = coordinator)
        runBlocking {
            personas.createManualPersona(ManualPersonaFields(name = "Story Player"))
            characters.importCharacterJson("""
                {
                    "schema_version":1,
                    "character_id":"character-rin",
                    "display_name":"Rin the Archivist",
                    "character_name":"Rin",
                    "first_mes":"Default opening",
                    "alternate_greetings":["A different opening"]
                }
            """.trimIndent())
        }
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun noGreetingStartsAnEmptyChatAndSurvivesRevisitingTheSameCharacter() {
        openPicker()
        compose.onNodeWithText("Default opening").assertIsSelected()
        compose.onNodeWithText("No greeting").performClick().assertIsSelected()

        compose.onNodeWithContentDescription("Back").performClick()
        chooseCharacter()
        compose.onNodeWithText("No greeting").assertIsSelected()

        val newChatId = createChat()
        runBlocking {
            assertNotNull(database.chatDao().getChatById(newChatId))
            assertTrue(database.messageDao().getMessagesForChatOnce(newChatId).isEmpty())
        }
    }

    @Test fun keepingTheDefaultStillCreatesOneCharacterMessage() {
        openPicker()
        compose.onNodeWithText("Default opening").assertIsSelected()

        val newChatId = createChat()
        runBlocking {
            val first = database.messageDao().getMessagesForChatOnce(newChatId).single()
            assertEquals("Default opening", first.content)
            assertEquals("character-rin", first.speakerId)
            assertEquals(SpeakerType.CHARACTER, first.speakerType)
        }
    }

    private fun openPicker() {
        compose.setContent {
            AppTheme {
                CreateSoloChatScreen(chats, personas, characters, createdChat::set, {})
            }
        }
        waitForText("Story Player")
        compose.onNodeWithText("Story Player").performClick()
        compose.onNodeWithText("Next").performClick()
        chooseCharacter()
    }

    private fun chooseCharacter() {
        waitForText("Rin the Archivist")
        compose.onNodeWithText("Rin the Archivist").performClick()
        compose.onNodeWithText("Next").performClick()
        waitForText("No greeting")
    }

    private fun createChat(): String {
        compose.onNodeWithText("Create Chat").performClick()
        compose.waitUntil(5_000) { createdChat.get() != null }
        return requireNotNull(createdChat.get())
    }

    private fun waitForText(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
