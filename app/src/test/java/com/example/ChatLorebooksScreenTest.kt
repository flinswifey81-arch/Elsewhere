package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
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
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("manual_lore_ui-entry").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Edit Chat Current").assertExists()
        composeRule.onNodeWithTag("manual_lore_ui-entry").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking {
                repository.getChatLorebookStates("ui-chat")
                    .single { it.lorebook.lorebookId == "ui-book" }
                    .overlays["ui-entry"]?.manualActive == true
            }
        }
    }
}
