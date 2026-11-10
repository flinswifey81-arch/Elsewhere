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
}
