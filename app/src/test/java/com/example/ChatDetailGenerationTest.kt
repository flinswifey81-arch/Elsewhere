package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.PersonaEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.PersonaRepository
import com.example.ui.screens.ChatDetailViewModel
import com.example.ui.screens.ChatUiState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatDetailGenerationTest {
    private lateinit var database: AppDatabase
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun streamedResponseIsPersistedAndCompiledContextIncludesMemory() = runBlocking {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi)
        val personaRepository = PersonaRepository(database.personaDao(), moshi)
        val chatRepository = ChatRepository(database.chatDao())
        val messageRepository = MessageRepository(database.messageDao())
        val memoryRepository = MemoryRepository(database.memoryDao())

        database.characterDao().insertCharacter(character())
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createSoloChat("Daily RP", "persona-1", "character-1")
        chatRepository.insertChatSettings(ChatSettingsEntity(chatId = chatId, selectedModelId = "mock/model"))
        memoryRepository.insertIfNew(
            RoleplayMemoryEntity(
                chatId = chatId,
                characterId = "character-1",
                category = MemoryCategory.PROMISE,
                content = "meet beneath the clock tower"
            )
        )
        memoryRepository.upsertSummary(
            ConversationSummaryEntity(chatId, "They escaped the winter court.", 1L)
        )

        val provider = RecordingStreamingProvider()
        val viewModel = ChatDetailViewModel(
            chatId,
            chatRepository,
            messageRepository,
            characterRepository,
            personaRepository,
            provider,
            memoryRepository
        )
        withTimeout(5_000) { viewModel.uiState.first { it is ChatUiState.Success } }
        chatRepository.insertChatSettings(
            (viewModel.uiState.value as ChatUiState.Success).chatSettings.copy(
                selectedModelId = "mock/updated-model",
                responseLengthProfile = ResponseLengthProfile.LONG
            )
        )
        withTimeout(5_000) {
            viewModel.uiState.first {
                it is ChatUiState.Success && it.chatSettings.selectedModelId == "mock/updated-model"
            }
        }

        viewModel.onDraftChanged("Remember that I prefer jasmine tea.")
        viewModel.sendMessage()

        val persisted = withTimeout(5_000) {
            messageRepository.getMessagesForChat(chatId).first { it.size == 2 }
        }
        assertEquals(listOf(SpeakerType.PERSONA, SpeakerType.CHARACTER), persisted.map { it.speakerType })
        assertEquals("A quiet answer.", persisted.last().content)
        assertTrue(provider.messages.first { it.role == Role.SYSTEM }.content.contains("meet beneath the clock tower"))
        assertTrue(provider.messages.first { it.role == Role.SYSTEM }.content.contains("escaped the winter court"))
        assertEquals("mock/updated-model", provider.options.modelId)
        assertNull(provider.options.maxTokens)
        val learnedMemories = withTimeout(5_000) {
            memoryRepository.getMemories(chatId, "character-1").first { memories ->
                memories.any { it.content == "jasmine tea" }
            }
        }
        assertTrue(learnedMemories.any { it.content == "jasmine tea" })
        val completedState = withTimeout(5_000) {
            viewModel.uiState.first {
                it is ChatUiState.Success && !it.isGenerating && it.messages.size == 2
            }
        } as ChatUiState.Success
        assertFalse(completedState.isGenerating)

        viewModel.continueMessage(persisted.last().messageId)
        val continuedMessages = withTimeout(5_000) {
            messageRepository.getMessagesForChat(chatId).first { messages ->
                messages.size == 2 && messages.last().content == "A quiet answer.A quiet answer."
            }
        }
        assertEquals(2, continuedMessages.size)
        val continuationRequest = provider.requests.last()
        assertEquals(1, continuationRequest.count { it.role == Role.USER })
        assertEquals(1, continuationRequest.count { it.role == Role.ASSISTANT })
        assertTrue(continuationRequest.last().content.contains("Continue the previous message"))
    }

    @Test
    fun lengthFinishKeepsEveryReceivedChunkAndPersistsFinishReason() = runBlocking {
        val receivedText = "Opening line.\n" + "x".repeat(2_000) + "\nFinal received line."
        val provider = ScriptedStreamingProvider(
            listOf(
                StreamEvent.Content(receivedText.take(700)),
                StreamEvent.Content(receivedText.drop(700)),
                StreamEvent.Done(
                    GenerationMetadataEntity(
                        messageId = "provider-placeholder",
                        requestedModelId = "mock/model",
                        finishReason = "length"
                    )
                )
            )
        )
        val harness = createHarness(provider, ResponseLengthProfile.SHORT)

        harness.viewModel.onDraftChanged("Tell me everything you can.")
        harness.viewModel.sendMessage()

        val persisted = withTimeout(5_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }
        val assistant = persisted.single { it.speakerType == SpeakerType.CHARACTER }
        assertEquals(receivedText, assistant.content)
        assertTrue(assistant.content.length > 1_000)
        assertNull(provider.options.maxTokens)
        assertEquals(
            "length",
            harness.messageRepository.getGenerationMetadata(assistant.messageId)?.finishReason
        )
    }

    @Test
    fun successfulEmptyStreamDoesNotPersistAnAssistantMessage() = runBlocking {
        val provider = ScriptedStreamingProvider(
            listOf(
                StreamEvent.Content(""),
                StreamEvent.Done(
                    GenerationMetadataEntity(
                        messageId = "provider-placeholder",
                        requestedModelId = "mock/model",
                        finishReason = "stop"
                    )
                )
            )
        )
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)

        harness.viewModel.onDraftChanged("Are you there?")
        harness.viewModel.sendMessage()

        val completed = withTimeout(5_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && !it.isGenerating && it.generationError != null
            }
        } as ChatUiState.Success
        val persisted = harness.messageRepository.getMessagesForChat(harness.chatId).first()

        assertEquals(1, persisted.size)
        assertEquals(SpeakerType.PERSONA, persisted.single().speakerType)
        assertTrue(persisted.none { it.speakerType == SpeakerType.CHARACTER || it.content.isEmpty() })
        assertEquals("The provider returned no response. You can retry this turn.", completed.generationError)
    }

    private data class Harness(
        val chatId: String,
        val viewModel: ChatDetailViewModel,
        val messageRepository: MessageRepository
    )

    private suspend fun createHarness(
        provider: ModelProvider,
        responseLengthProfile: ResponseLengthProfile
    ): Harness {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi)
        val personaRepository = PersonaRepository(database.personaDao(), moshi)
        val chatRepository = ChatRepository(database.chatDao())
        val messageRepository = MessageRepository(database.messageDao())
        val memoryRepository = MemoryRepository(database.memoryDao())
        database.characterDao().insertCharacter(character())
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createSoloChat("Cutoff Test", "persona-1", "character-1")
        chatRepository.insertChatSettings(
            ChatSettingsEntity(
                chatId = chatId,
                selectedModelId = "mock/model",
                responseLengthProfile = responseLengthProfile
            )
        )
        val viewModel = ChatDetailViewModel(
            chatId,
            chatRepository,
            messageRepository,
            characterRepository,
            personaRepository,
            provider,
            memoryRepository
        )
        withTimeout(5_000) { viewModel.uiState.first { it is ChatUiState.Success } }
        return Harness(chatId, viewModel, messageRepository)
    }

    private class RecordingStreamingProvider : ModelProvider {
        lateinit var messages: List<RoleplayMessage>
        lateinit var options: GenerationOptions
        val requests = mutableListOf<List<RoleplayMessage>>()

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            this@RecordingStreamingProvider.messages = messages
            this@RecordingStreamingProvider.options = options
            requests += messages
            emit(StreamEvent.Content("A quiet "))
            emit(StreamEvent.Content("answer."))
            emit(StreamEvent.Done(null))
        }
    }

    private class ScriptedStreamingProvider(
        private val events: List<StreamEvent>
    ) : ModelProvider {
        lateinit var options: GenerationOptions

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            this@ScriptedStreamingProvider.options = options
            events.forEach { emit(it) }
        }
    }

    private fun character() = CharacterEntity(
        characterId = "character-1",
        displayName = "Rowan",
        characterName = "Rowan",
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        voiceJson = null,
        behaviorJson = null,
        backstoryJson = null,
        knowledgeJson = null,
        worldContextJson = null,
        writingRulesJson = null,
        examplesJson = null,
        authorNotesJson = null,
        extensionFieldsJson = null
    )

    private fun persona() = PersonaEntity(
        personaId = "persona-1",
        displayName = "Mara",
        personaName = "Mara",
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        roleplayProfileJson = null,
        backgroundJson = null,
        worldContextJson = null,
        privateNotesJson = null,
        extensionFieldsJson = null
    )
}
