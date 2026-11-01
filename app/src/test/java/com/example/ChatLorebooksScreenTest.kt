package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import com.example.domain.repository.LorebookRepository
import com.example.ui.screens.ChatLorebooksScreen
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatLorebooksScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var repository: LorebookRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LorebookRepository(
            database.lorebookDao(),
            Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bulkAttachAndEnableButtonsChangeOnlyChatAttachments() {
        runBlocking {
            for (index in 0..3) {
                repository.saveDefinition(
                    LorebookEntity(lorebookId = "bulk-$index", name = "Bulk $index"),
                    emptyList()
                )
            }
            repository.attachLorebook("bulk-chat", "bulk-0")
            repository.setAttachmentEnabled("bulk-chat", "bulk-0", false)
        }

        composeRule.setContent {
            MaterialTheme {
                ChatLorebooksScreen(chatId = "bulk-chat", repository = repository, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("attach_all_lorebooks").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("attach_all_lorebooks").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking {
                repository.getChatLorebookStates("bulk-chat").count { it.attachment != null } == 4
            }
        }
        composeRule.onNodeWithTag("enable_all_lorebooks").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking {
                repository.getChatLorebookStates("bulk-chat").all { it.attachment?.isEnabled == true }
            }
        }
        // Other chats are not affected.
        assertEquals(0, runBlocking {
            repository.getChatLorebookStates("another-chat").count { it.attachment != null }
        })
    }

    @Test
    fun attachingNearBottomDoesNotRecreateListOrJumpToTop() {
        runBlocking {
            repeat(21) { index ->
                repository.saveDefinition(
                    LorebookEntity(lorebookId = "row-${index.toString().padStart(2, '0')}", name = "Row ${index.toString().padStart(2, '0')}"),
                    emptyList()
                )
            }
        }
        composeRule.setContent {
            MaterialTheme {
                ChatLorebooksScreen(chatId = "scroll-chat", repository = repository, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("chat_lorebooks_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("chat_lorebooks_list").performScrollToIndex(19)
        composeRule.onNodeWithTag("attach_lorebook_row-18").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking {
                repository.getChatLorebookStates("scroll-chat").single { it.lorebook.lorebookId == "row-18" }.attachment != null
            }
        }
        composeRule.onNodeWithTag("chat_lorebooks_list").assertExists()
        composeRule.onNodeWithTag("lorebook_card_row-18").assertIsDisplayed()
    }

    @Test
    fun chatScreenAttachesBookAndPersistsManualActivation() {
        val book = LorebookEntity(lorebookId = "ui-book", name = "UI Book")
        runBlocking {
            repository.saveDefinition(
                book,
                listOf(
                    LorebookEntryEntity(
                        entryId = "ui-entry",
                        lorebookId = book.lorebookId,
                        title = "Manual Scene",
                        activationMode = "manual",
                        currentContent = "Scene fact"
                    )
                )
            )
        }

        composeRule.setContent {
            MaterialTheme {
                ChatLorebooksScreen(chatId = "ui-chat", repository = repository, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("chat_lorebooks_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("chat_lorebooks_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("attach_lorebook_ui-book").performClick()
        // Attached books remain collapsed until the player explicitly expands one.
        composeRule.onNodeWithTag("manual_lore_ui-entry").assertDoesNotExist()
        composeRule.onNodeWithTag("expand_lorebook_ui-book").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("manual_lore_ui-entry").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Edit Chat Current").assertExists()
        // Data refresh can render the expanded entry before the preceding attachment
        // write has released its in-flight UI lock. Wait until interaction is available.
        composeRule.waitUntil(10_000) {
            runCatching {
                composeRule.onNodeWithTag("manual_lore_ui-entry").assertIsEnabled()
            }.isSuccess
        }
        composeRule.onNodeWithTag("manual_lore_ui-entry").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(10_000) {
            runBlocking {
                repository.getChatLorebookStates("ui-chat")
                    .single { it.lorebook.lorebookId == "ui-book" }
                    .overlays["ui-entry"]?.manualActive == true
            }
        }
        // Check that Compose renders the saved state, not merely that Room persisted it.
        // On failure expose the UI toggle semantics instead of swallowing assertions.
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("manual_lore_ui-entry")
                    .fetchSemanticsNodes()
                    .any { node ->
                        node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
                    }
            }
        } catch (timeout: ComposeTimeoutException) {
            val nodes = composeRule.onAllNodesWithTag("manual_lore_ui-entry").fetchSemanticsNodes()
            val displayedStates = nodes.map { it.config.getOrNull(SemanticsProperties.ToggleableState) }
            throw AssertionError(
                "Manual activation saved in Room, but UI switch did not show On. " +
                    "Found ${nodes.size} switch node(s), with toggle states: $displayedStates.",
                timeout
            )
        }
        composeRule.onNodeWithTag("manual_lore_ui-entry").assertIsOn()
    }
}
