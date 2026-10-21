package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ManualCharacterFields
import com.example.data.model.ManualPersonaFields
import com.example.data.model.MessageEntity
import com.example.data.model.ScenarioDefinition
import com.example.data.model.SpeakerType
import com.example.di.AppContainer
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.ScenarioRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.story.StoryResponse
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegment
import com.example.domain.story.StorySegmentType
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.theme.AppTheme
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ChatOpeningRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var container: AppContainer
    private lateinit var soloEmptyId: String
    private lateinit var soloOrdinaryId: String
    private lateinit var soloStructuredId: String
    private lateinit var groupId: String
    private lateinit var scenarioId: String

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = AppDataMaintenanceCoordinator()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val characters = CharacterRepository(database.characterDao(), moshi, dataCoordinator = coordinator)
        val personas = PersonaRepository(database.personaDao(), moshi, dataCoordinator = coordinator)
        val chats = ChatRepository(database.chatDao(), database.messageDao(), coordinator)
        val messages = MessageRepository(database.messageDao(), coordinator)
        val memories = MemoryRepository(database.memoryDao(), coordinator)
        val scenarios = ScenarioRepository(database, coordinator)
        val characterId = characters.createManualCharacter(ManualCharacterFields(name = "Rowan"))
        val secondCharacterId = characters.createManualCharacter(ManualCharacterFields(name = "Ash"))
        val personaId = personas.createManualPersona(ManualPersonaFields(name = "Mira"))

        soloEmptyId = chats.createSoloChat("New Solo", personaId, characterId)
        chats.insertChatSettings(ChatSettingsEntity(chatId = soloEmptyId, narratorEnabled = false))

        soloOrdinaryId = chats.createSoloChat("Ordinary Solo", personaId, characterId)
        chats.insertChatSettings(ChatSettingsEntity(chatId = soloOrdinaryId, narratorEnabled = true))
        messages.insertMessage(
            message(soloOrdinaryId, characterId, "An ordinary restored message.")
        )

        soloStructuredId = chats.createSoloChat("Structured Solo", personaId, characterId)
        chats.insertChatSettings(ChatSettingsEntity(chatId = soloStructuredId, narratorEnabled = true))
        messages.insertMessage(
            message(
                soloStructuredId,
                characterId,
                StoryResponseCodec.encode(
                    StoryResponse(
                        segments = listOf(
                            StorySegment(StorySegmentType.NARRATION, "The restored fire burns low."),
                            StorySegment(
                                StorySegmentType.CHARACTER,
                                "\"You made it.\"",
                                characterId,
                                "Rowan"
                            )
                        )
                    )
                )
            )
        )

        groupId = chats.createGroupChat(
            displayName = "Group Opening",
            personaIds = listOf(personaId),
            characterIds = listOf(characterId, secondCharacterId)
        )
        chats.insertChatSettings(ChatSettingsEntity(chatId = groupId, narratorEnabled = true))
        messages.insertMessage(message(groupId, characterId, "The group is still here."))

        scenarios.save(
            "opening-regression",
            ScenarioDefinition(
                title = "Scenario Opening",
                premise = "A test scene.",
                openingMessage = "The scenario doors open."
            )
        )
        scenarioId = scenarios.start("opening-regression", personaId)

        container = object : AppContainer {
            override val modelProvider: ModelProvider = NoCallProvider()
            override val characterRepository = characters
            override val personaRepository = personas
            override val settingsRepository = SettingsRepository(context)
            override val appearanceRepository = AppearanceRepository(context, coordinator)
            override val chatRepository = chats
            override val messageRepository = messages
            override val memoryRepository = memories
            override val scenarioRepository = scenarios
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun newlyCreatedSoloChatOpensWithNarratorDisabled() {
        open(soloEmptyId, "New Solo")
        waitForText("Type a message...")
        composeRule.onNodeWithText("Type a message...").assertExists()
    }

    @Test
    fun ordinaryRestoredSoloChatOpensWithNarratorEnabled() {
        open(soloOrdinaryId, "Ordinary Solo")
        waitForText("An ordinary restored message.")
        composeRule.onNodeWithText("An ordinary restored message.").assertExists()
    }

    @Test
    fun structuredNarrationChatOpensAndRendersSavedSegments() {
        open(soloStructuredId, "Structured Solo")
        waitForText("The restored fire burns low.")
        waitForText("\"You made it.\"")
        composeRule.onNodeWithText("The restored fire burns low.").assertExists()
        composeRule.onNodeWithText("\"You made it.\"").assertExists()
    }

    @Test
    fun groupChatOpensWithoutUsingEnsembleGeneration() {
        open(groupId, "Group Opening")
        waitForText("The group is still here.")
        composeRule.onNodeWithText("The group is still here.").assertExists()
    }

    @Test
    fun scenarioChatOpensOnItsExistingRuntime() {
        open(scenarioId, "Scenario Opening")
        waitForText("The scenario doors open.")
        composeRule.onNodeWithText("The scenario doors open.").assertExists()
    }

    private fun open(chatId: String, title: String) {
        composeRule.setContent {
            AppTheme {
                ChatDetailScreen(
                    chatId = chatId,
                    appContainer = container,
                    onNavigateBack = {},
                    onChatDeleted = {}
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).assertExists()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun message(chatId: String, characterId: String, content: String) = MessageEntity(
        chatId = chatId,
        speakerType = SpeakerType.CHARACTER,
        speakerId = characterId,
        speakerDisplayNameSnapshot = "Rowan",
        content = content,
        orderIndex = 1L
    )

    private class NoCallProvider : ModelProvider {
        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            error("Opening a chat must not call the provider")
        }
    }
}
