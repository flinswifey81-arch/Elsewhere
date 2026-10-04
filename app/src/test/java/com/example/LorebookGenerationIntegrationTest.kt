package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.LorebookRepository
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LorebookGenerationIntegrationTest {
    private lateinit var database: AppDatabase
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

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
    fun soloNormalRetryRegenerateAndContinueAllUseSanitizedActiveLore() = runBlocking {
        val provider = RecordingLoreProvider(listOf("", "First reply", "Regenerated reply", " continued"))
        val harness = createHarness(provider)

        harness.viewModel.onDraftChanged("Begin.")
        harness.viewModel.sendMessage()
        withTimeout(30_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && !it.isGenerating && it.generationError != null }
        }
        harness.viewModel.retryGeneration()
        val firstReply = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.any { message -> message.content == "First reply" } }
        }.single { it.content == "First reply" }

        harness.viewModel.regenerateMessage(firstReply.messageId)
        val regenerated = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.any { message -> message.isPrimaryVariant && message.content == "Regenerated reply" } }
        }.single { it.isPrimaryVariant && it.content == "Regenerated reply" }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating && state.messages.any { it.messageId == regenerated.messageId }
            }
        }

        harness.viewModel.continueMessage(regenerated.messageId)
        withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.messageId == regenerated.messageId && it.content == "Regenerated reply continued" }
            }
        }

        assertEquals(4, provider.requests.size)
        provider.requests.forEach(::assertSanitizedLore)
    }

    @Test
    fun groupNormalRegenerateAndContinueRequestsUseSanitizedLoreAndDirectorBoundary() = runBlocking {
        val lorebookRepository = LorebookRepository(database.lorebookDao(), moshi)
        installLorebook("group-chat", lorebookRepository)
        val current = MessageEntity(
            messageId = "persona-message",
            chatId = "group-chat",
            speakerType = SpeakerType.PERSONA,
            speakerId = "persona-1",
            speakerDisplayNameSnapshot = "Persona",
            content = "Take your turn.",
            orderIndex = 1L
        )
        val activeLore = lorebookRepository.resolveActiveLore("group-chat", emptyList(), current)
        val compiler = ContextCompilerV1()
        fun compile(continuation: Boolean) = compiler.compileGroupCharacterContext(
            isContinuation = continuation,
            character = character(),
            persona = persona(),
            activeCharacterNames = listOf("Character", "Other Character"),
            chatHistory = emptyList(),
            currentMessage = current,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null,
            activeLore = activeLore
        )

        val provider = RecordingLoreProvider(listOf("Group reply", "Group regenerated", " plus"))
        val options = GenerationOptions(modelId = "mock/model")
        provider.streamResponse(compile(continuation = false), options).toList()
        provider.streamResponse(compile(continuation = false), options).toList()
        provider.streamResponse(compile(continuation = true), options).toList()

        assertEquals(3, provider.requests.size)
        provider.requests.forEach { request ->
            assertSanitizedLore(request)
            assertEquals(Role.USER, request.last().role)
            assertTrue(request.last().content.startsWith("[DIRECTOR TURN CONTROL"))
            assertTrue(request.first().content.contains("Respond only as Character"))
        }
        assertTrue(provider.requests.last().any {
            it.role == Role.SYSTEM && it.content.contains("Continue the previous message")
        })
    }

    private fun assertSanitizedLore(request: List<RoleplayMessage>) {
        val system = request.first { it.role == Role.SYSTEM }.content
        assertTrue(system.contains("Provider Lore"))
        assertTrue(system.contains("PROVIDER_CURRENT_ONLY"))
        listOf(
            "PROVIDER_CANON_NEVER_SEND",
            "PROVIDER_NOTES_NEVER_SEND",
            "PROVIDER_KEY_NEVER_SEND",
            "PROVIDER_TAG_NEVER_SEND",
            "secret-entry-id",
            "secret-book-id"
        ).forEach { forbidden -> assertFalse("Provider request leaked $forbidden", system.contains(forbidden)) }
    }

    private suspend fun createHarness(provider: ModelProvider): Harness {
        val characterRepository = CharacterRepository(database.characterDao(), moshi)
        val personaRepository = PersonaRepository(database.personaDao(), moshi)
        val chatRepository = ChatRepository(database.chatDao(), database.messageDao())
        val messageRepository = MessageRepository(database.messageDao())
        val memoryRepository = MemoryRepository(database.memoryDao())
        val lorebookRepository = LorebookRepository(database.lorebookDao(), moshi)
        database.characterDao().insertCharacter(character())
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createSoloChat("Solo", "persona-1", "character-1")
        chatRepository.insertChatSettings(ChatSettingsEntity(chatId = chatId, selectedModelId = "mock/model"))
        installLorebook(chatId, lorebookRepository)
        val viewModel = ChatDetailViewModel(
            chatId = chatId,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            characterRepository = characterRepository,
            personaRepository = personaRepository,
            modelProvider = provider,
            memoryRepository = memoryRepository,
            lorebookRepository = lorebookRepository
        )
        withTimeout(30_000) {
            viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.historyLoaded
            }
        }
        return Harness(chatId, viewModel, messageRepository)
    }

    private suspend fun installLorebook(chatId: String, lorebookRepository: LorebookRepository) {
        val book = LorebookEntity(lorebookId = "secret-book-id", name = "Provider Book")
        val entry = LorebookEntryEntity(
            entryId = "secret-entry-id",
            lorebookId = book.lorebookId,
            title = "Provider Lore",
            activationMode = "always",
            keys = listOf("PROVIDER_KEY_NEVER_SEND"),
            canonContent = "PROVIDER_CANON_NEVER_SEND",
            currentContent = "PROVIDER_CURRENT_ONLY",
            tags = listOf("PROVIDER_TAG_NEVER_SEND"),
            notes = "PROVIDER_NOTES_NEVER_SEND"
        )
        lorebookRepository.saveDefinition(book, listOf(entry))
        lorebookRepository.attachLorebook(chatId, book.lorebookId)
    }

    private data class Harness(
        val chatId: String,
        val viewModel: ChatDetailViewModel,
        val messageRepository: MessageRepository
    )

    private class RecordingLoreProvider(private val responses: List<String>) : ModelProvider {
        val requests = mutableListOf<List<RoleplayMessage>>()
        private var index = 0
        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override fun streamResponse(messages: List<RoleplayMessage>, options: GenerationOptions): Flow<StreamEvent> = flow {
            requests += messages
            emit(StreamEvent.Content(responses[index++]))
            emit(StreamEvent.Done(null))
        }
    }

    private fun character() = CharacterEntity(
        characterId = "character-1",
        displayName = "Character",
        characterName = "Character",
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        voiceJson = null,
        behaviorJson = null,
        backstoryJson = null,
        knowledgeJson = "{\"private\":\"CHARACTER_ONLY\"}",
        worldContextJson = null,
        writingRulesJson = null,
        examplesJson = null,
        authorNotesJson = null,
        extensionFieldsJson = null
    )

    private fun persona() = PersonaEntity(
        personaId = "persona-1",
        displayName = "Persona",
        personaName = "Persona",
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
