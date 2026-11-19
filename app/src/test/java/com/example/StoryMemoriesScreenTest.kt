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
        // Waiting for the Room value alone does not guarantee that the
        // previous UI action has cleared its temporary working state.
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithText("Pin").assertIsEnabled() }.isSuccess
        }
        val memoryId = runBlocking { database.storyMemoryDao().forChat("ui-story").single().memoryId }
        composeRule.onNodeWithTag("story_memory_pin_$memoryId")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { database.storyMemoryDao().forChat("ui-story").single().pinned }
        }
        // Verify that Room's update was observed and recomposed, not just
        // committed. Otherwise an older enabled control can absorb a tap
        // while the preceding action is still finishing.
        composeRule.waitUntil(30_000) {
            runCatching {
                composeRule.onNodeWithText("Unpin").assertIsEnabled()
                composeRule.onNodeWithText("Hide").assertIsEnabled()
            }.isSuccess
        }
        composeRule.onNodeWithTag("story_memory_visibility_$memoryId")
            .performScrollTo().assertIsDisplayed().performClick()
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
        val firstMemoryId = runBlocking {
            database.storyMemoryDao().forChat("ui-story").first().memoryId
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("restore_memory_$firstMemoryId")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { database.storyMemoryDao().getAllReturns().isEmpty() })
        // The relationship controls make the top of this LazyColumn taller.
        // The restore action may be composed but off-screen; a touch click
        // must not be injected until it is actually visible to the player.
        composeRule.onNodeWithTag("restore_memory_$firstMemoryId", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("story_memory_restore_dialog", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Restore this one memory", useUnmergedTree = true).assertExists()
        assertTrue(runBlocking { database.storyMemoryDao().getAllReturns().isEmpty() })
        composeRule.onNodeWithText("Restore this one memory", useUnmergedTree = true).performClick()
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
        // CI runners also execute Room/Robolectric setup concurrently.
        // This checks that the actual UI becomes ready, not elapsed time.
        composeRule.waitUntil(45_000) {
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
    fun playerRecordsAndClosesSpecificPromiseFromStoryMemoriesScreen() {
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
                    roleplayProfileJson = null, backgroundJson = null,
                    worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
                )
            )
            database.chatDao().insertChatParticipant(
                com.example.data.model.ChatParticipantEntity(
                    chatId = "ui-story", characterId = "rowan"
                )
            )
            database.chatDao().insertChatPersonaParticipant(
                com.example.data.model.ChatPersonaParticipantEntity(
                    chatId = "ui-story", personaId = "mara"
                )
            )
        }
        composeRule.setContent {
            MaterialTheme {
                StoryMemoriesScreen("ui-story", repository, chats, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("record_story_promise")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("record_story_promise").performClick()
        composeRule.onNodeWithText("From: Mara (Persona)").assertExists()
        composeRule.onNodeWithText("To: Rowan (Character)").assertExists()
        composeRule.onNodeWithText("What was promised?")
            .performTextInput("Mara promises Rowan the silver key.")
        composeRule.onNodeWithText("Save promise").performClick()
        composeRule.waitUntil(15_000) {
            runBlocking { repository.getPromiseHistory("ui-story").size == 1 }
        }
        val first = runBlocking { repository.getPromiseHistory("ui-story").single() }
        assertEquals("ACTIVE", first.memory.lifecycle)
        composeRule.onNodeWithTag("close_promise_${first.memory.memoryId}", useUnmergedTree = true)
            .performScrollTo().performClick()
        composeRule.onNodeWithTag("record_promise_outcome_dialog", useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithText("What happened?").performTextInput(
            "Mara gave Rowan the silver key."
        )
        composeRule.onNodeWithText("Save outcome").performClick()
        composeRule.waitUntil(15_000) {
            runBlocking { repository.getPromiseHistory("ui-story").size == 2 }
        }
        val history = runBlocking { repository.getPromiseHistory("ui-story") }
        assertEquals("SUPERSEDED", history.first().memory.lifecycle)
        assertEquals("RESOLVED", history.last().memory.lifecycle)
        assertEquals("KEPT", history.last().memory.promiseOutcome)
        assertEquals(first.memory.memoryId, history.last().memory.previousStateMemoryId)
        assertEquals("STORYTELLER_ONLY", history.last().memory.visibility)
    }

    @Test
    fun characterPerspectiveDialogRecordsPlayerOnlySubjectiveState() {
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
                com.example.data.model.ChatParticipantEntity(
                    chatId = "ui-story", characterId = "rowan"
                )
            )
        }
        val memory = runBlocking {
            repository.saveManual("ui-story", "The silver key is in the old watch.")
        }
        composeRule.setContent {
            MaterialTheme {
                StoryMemoriesScreen("ui-story", repository, chats, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("story_memory_perspectives_${memory.memoryId}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("story_memory_perspectives_${memory.memoryId}")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("story_memory_perspective_dialog").assertExists()
        composeRule.onNodeWithText("Character: Rowan").assertExists()
        composeRule.onNodeWithText("State: knows").performClick()
        composeRule.onNodeWithText("suspects").performClick()
        composeRule.onNodeWithText("Save perspective").performClick()
        composeRule.waitUntil(15_000) {
            runBlocking {
                repository.getCurrentCharacterPerspectives("ui-story", memory.memoryId)
                    .singleOrNull()?.stance == "SUSPECTS"
            }
        }
        // Recording a perspective must not grant character access by itself.
        assertTrue(runBlocking {
            repository.getCharacterKnowers("ui-story", memory.memoryId).isEmpty()
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
        composeRule.onNodeWithText("Who knows this?").performScrollTo()
            .assertIsDisplayed().performClick()
        // The Character list is loaded in a separate LaunchedEffect. Wait
        // for the dialog's actual checkbox instead of racing its UI state.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("memory_knowledge_rowan")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memory_knowledge_rowan")
            .assertIsDisplayed().performClick()
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
