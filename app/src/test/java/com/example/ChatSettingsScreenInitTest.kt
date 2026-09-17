package com.example

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.ChatRepository
import com.example.ui.screens.ChatSettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ChatSettingsScreenInitTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun settingsScreenCreatesDefaultsAndRendersWithoutApiKeyOrCatalog() {
        val offlineProvider = object : ModelProvider {
            override suspend fun getModels(): List<OpenRouterModel> =
                throw IllegalStateException("API key not configured")

            override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()

            override fun streamResponse(
                messages: List<RoleplayMessage>,
                options: GenerationOptions
            ): Flow<StreamEvent> = emptyFlow()
        }

        composeTestRule.setContent {
            ChatSettingsScreen(
                chatId = "chat-without-settings",
                chatRepository = ChatRepository(database.chatDao()),
                modelProvider = offlineProvider,
                onNavigateBack = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("chat_settings_content").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("chat_settings_content").assertExists()
        composeTestRule.onNodeWithText("Generation Parameters").assertExists()
        composeTestRule.onNodeWithText("Failed to fetch models: API key not configured").assertExists()
    }
}
