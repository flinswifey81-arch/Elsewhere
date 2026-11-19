package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.StoryMemoryRepository
import com.example.ui.screens.StoryMemoriesScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** UI uses a separate, disposable Room database; no provider requests. */
@RunWith(RobolectricTestRunner::class)
class StoryMemoriesScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var database: AppDatabase
    private lateinit var repository: StoryMemoryRepository
    private lateinit var chats: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = StoryMemoryRepository(database)
        chats = ChatRepository(database.chatDao(), database.messageDao())
        runBlocking {
            database.chatDao().insertChat(
                ChatEntity(chatId = "ui-story", displayName = "UI Story", chatType = ChatType.SOLO)
            )
        }
    }

    @After fun tearDown() = database.close()

    @Test
    fun manualMemoryCanBeSavedPinnedAndHiddenWithoutAProviderCall() {
        composeRule.setContent {
            MaterialTheme {
                StoryMemoriesScreen("ui-story", repository, chats, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("story_memories_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Save new Story Memory").performClick()
        composeRule.onNodeWithText("What should Elsewhere remember?")
            .performTextInput("The fictional silver compass belongs to Mara.")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { database.storyMemoryDao().forChat("ui-story").size == 1 }
        }
        composeRule.onNodeWithText("The fictional silver compass belongs to Mara.").assertExists()
        composeRule.onNodeWithText("Pin").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { database.storyMemoryDao().forChat("ui-story").single().pinned }
        }
        composeRule.onNodeWithText("Hide").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { database.storyMemoryDao().forChat("ui-story").single().hidden }
        }
        val stored = runBlocking { database.storyMemoryDao().forChat("ui-story").single() }
        assertTrue(stored.pinned && stored.hidden)
        assertEquals("STORYTELLER_ONLY", stored.visibility)
    }

    @Test
    fun restoreAfterWackRequiresAnExplicitConfirmationAndRestoresOnlyOneMemory() {
        runBlocking {
            database.chatDao().insertChat(
                ChatEntity(chatId = "ui-story", displayName = "UI Story", chatType = ChatType.SOLO,
                    contextResetOrderIndex = 100L)
            )
            val old = MessageEntity(
                messageId = "old", chatId = "ui-story",
                speakerType = SpeakerType.CHARACTER, speakerId = "rowan",
                speakerDisplayNameSnapshot = "Rowan",
                content = "A hidden letter.", orderIndex = 17L
            )
            val other = old.copy(messageId = "other", orderIndex = 20L, content = "A lost key.",
                variantGroupId = "other")
            database.messageDao().insertMessage(old)
            database.messageDao().insertMessage(other)
            repository.saveFromMessage("ui-story", "old")
            repository.saveFromMessage("ui-story", "other")
        }
        composeRule.setContent {
            MaterialTheme {
                StoryMemoriesScreen("ui-story", repository, chats, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Restore after Wack").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { database.storyMemoryDao().getAllReturns().isEmpty() })
        composeRule.onAllNodesWithText("Restore after Wack")[0].performClick()
        composeRule.onNodeWithText("Restore this one memory").assertExists()
        assertTrue(runBlocking { database.storyMemoryDao().getAllReturns().isEmpty() })
        composeRule.onNodeWithText("Restore this one memory").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { database.storyMemoryDao().getAllReturns().size == 1 }
        }
        assertEquals(1, runBlocking { database.storyMemoryDao().getAllReturns().size })
        assertEquals(2, runBlocking { database.storyMemoryDao().forChat("ui-story").size })
    }

    @Test
    fun recordRelationshipChangeShowsHistoryAndSupersedesOnlyByPlayerAction() {
        runBlocking {
            database.characterDao().insertCharacter(
                com.example.data.model.CharacterEntity(
                    characterId = "rowan", displayName = "Rowan", characterName = "Rowan",
                    aliases = emptyList(), importSchemaVersion = 1,
                    identityJson = null, appearanceJson = null, personalityJson = null,
                    voiceJson = null, behaviorJson = null, backstoryJson = null,
                    knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
                    examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
                )
            )
            database.personaDao().insertPersona(
                com.example.data.model.PersonaEntity(
                    personaId = "mara", displayName = "Mara", personaName = "Mara",
                    aliases = emptyList(), importSchemaVersion = 1,
                    identityJson = null, appearanceJson = null, personalityJson = null,
                    roleplayProfileJson = null, backgroundJson = null, worldContextJson = null,
                    privateNotesJson = null, extensionFieldsJson = null
                )
            )
            database.chatDao().insertChatParticipant(
                com.example.data.model.ChatParticipantEntity(chatId = "ui-story", characterId = "rowan")
            )
            database.chatDao().insertChatPersonaParticipant(
                com.example.data.model.ChatPersonaParticipantEntity(chatId = "ui-story", personaId = "mara")
            )
        }
        composeRule.setContent {
            MaterialTheme {
                StoryMemoriesScreen("ui-story", repository, chats, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("record_relationship_change")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("record_relationship_change").performClick()
        composeRule.onNodeWithText("From: Mara (Persona)").assertExists()
        composeRule.onNodeWithText("To: Rowan (Character)").assertExists()
        composeRule.onNodeWithText("How has this relationship changed?")
            .performTextInput("Mara distrusts Rowan after the betrayal.")
        composeRule.onNodeWithText("Save relationship state").performClick()
        composeRule.waitUntil(15_000) {
            runBlocking {
                repository.getRelationshipHistory("ui-story").size == 1
            }
        }
        val first = runBlocking { repository.getRelationshipHistory("ui-story").single() }
        assertEquals("ACTIVE", first.memory.lifecycle)
        assertEquals("STORYTELLER_ONLY", first.memory.visibility)
        composeRule.onNodeWithText("Relationship: Mara (Persona) → Rowan (Character)").assertExists()
        composeRule.onNodeWithText("Record a change").performClick()
        composeRule.onNodeWithText("How has this relationship changed?")
            .performTextInput("Rowan apologized, and Mara forgives him.")
        composeRule.onNodeWithText("Save relationship state").performClick()
        composeRule.waitUntil(15_000) {
            runBlocking {
                repository.getRelationshipHistory("ui-story").let {
                    it.size == 2 && it.first().memory.lifecycle == "SUPERSEDED" &&
                        it.last().memory.lifecycle == "ACTIVE"
                }
            }
        }
        val history = runBlocking { repository.getRelationshipHistory("ui-story") }
        assertEquals("Mara distrusts Rowan after the betrayal.", history.first().memory.content)
        assertEquals("Rowan apologized, and Mara forgives him.", history.last().memory.content)
        assertTrue(runBlocking {
            repository.getCharacterKnowers("ui-story", history.last().memory.memoryId).isEmpty()
        })
    }

    @Test
    fun knowledgeGrantRequiresExplicitCheckboxAndPersistsToSelectedCharacter() {
        runBlocking {
            database.characterDao().insertCharacter(
                com.example.data.model.CharacterEntity(
                    characterId = "rowan", displayName = "Rowan", characterName = "Rowan",
                    aliases = emptyList(), importSchemaVersion = 1,
                    identityJson = null, appearanceJson = null, personalityJson = null,
                    voiceJson = null, behaviorJson = null, backstoryJson = null,
                    knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
                    examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
                )
            )
            database.chatDao().insertChatParticipant(
                com.example.data.model.ChatParticipantEntity(chatId = "ui-story", characterId = "rowan")
            )
            repository.saveManual("ui-story", "Rowan must remember the silver compass.")
        }
        composeRule.setContent {
            MaterialTheme {
                StoryMemoriesScreen("ui-story", repository, chats, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Who knows this?").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking {
            database.storyMemoryDao().forChat("ui-story").single().visibility == "STORYTELLER_ONLY"
        })
        composeRule.onNodeWithText("Who knows this?").performClick()
        composeRule.onNodeWithTag("memory_knowledge_rowan").performClick()
        composeRule.onNodeWithText("Save knowledge").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { database.storyMemoryDao().forChat("ui-story").single().visibility == "CHARACTER_ONLY" }
        }
        assertEquals(
            setOf("rowan"),
            runBlocking {
                val memory = database.storyMemoryDao().forChat("ui-story").single()
                repository.getCharacterKnowers("ui-story", memory.memoryId)
            }
        )
    }
}
