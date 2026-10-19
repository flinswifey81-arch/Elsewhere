package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.model.ManualCharacterFields
import com.example.data.model.ManualPersonaFields
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.di.AppContainer
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.screens.ChatDetailScreen
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class HistoryOnlyChatNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var container: AppContainer
    private lateinit var chatId: String

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi)
        val personaRepository = PersonaRepository(database.personaDao(), moshi)
        val chatRepository = ChatRepository(database.chatDao(), database.messageDao())
        val messageRepository = MessageRepository(database.messageDao())
        val memoryRepository = MemoryRepository(database.memoryDao())

        val characterId = characterRepository.createManualCharacter(ManualCharacterFields(name = "Deleted Character"))
        val personaId = personaRepository.createManualPersona(ManualPersonaFields(name = "Persona"))
        chatId = chatRepository.createSoloChat("History", personaId, characterId)
        messageRepository.insertMessage(
            MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.CHARACTER,
                speakerId = characterId,
                speakerDisplayNameSnapshot = "Deleted Character",
                content = "Preserved line",
                orderIndex = 1L
            )
        )
        characterRepository.deleteCharacterPermanently(characterId)

        container = object : AppContainer {
            override val modelProvider: ModelProvider = NoOpProvider()
            override val characterRepository = characterRepository
            override val personaRepository = personaRepository
            override val settingsRepository = SettingsRepository(context)
            override val appearanceRepository = AppearanceRepository(context)
            override val chatRepository = chatRepository
            override val messageRepository = messageRepository
            override val memoryRepository = memoryRepository
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun openingDependentSoloChatKeepsHistoryAndDisablesNewInput() {
        composeTestRule.setContent {
            MaterialTheme {
                ChatDetailScreen(
                    chatId = chatId,
                    appContainer = container,
                    onNavigateBack = {},
                    onChatDeleted = {}
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("History only", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("History only", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Preserved line").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type a message...").assertDoesNotExist()
    }

    private class NoOpProvider : ModelProvider {
        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = emptyFlow()
    }
}
