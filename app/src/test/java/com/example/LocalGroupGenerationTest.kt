package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.MessageEntity
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ProviderRoutingMode
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
import com.example.domain.repository.LorebookRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocalGroupGenerationTest {
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
    fun groupPersonaMessageIsSavedWithoutAutomaticGenerationThenSelectedCharacterRepliesAlone() = runBlocking {
        val provider = SequentialGroupProvider(listOf("B reply"))
        val harness = createHarness(provider)

        harness.viewModel.onDraftChanged("Enter the hall.")
        harness.viewModel.sendMessage()

        val personaOnly = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 1 }
        }
        assertEquals(listOf(SpeakerType.PERSONA), personaOnly.map { it.speakerType })
        assertTrue(provider.requests.isEmpty())

        harness.viewModel.selectGroupCharacter("character-b")
        harness.viewModel.generateGroupResponse()

        val completed = awaitMessages(harness, 2)
        assertEquals("character-b", completed.last().speakerId)
        assertEquals("B reply", completed.last().content)
        assertEquals(1, provider.requests.size)

        val request = provider.requests.single()
        val system = request.first().content
        assertTrue(system.contains("--- CHARACTER: Character B ---"))
        assertTrue(system.contains("B_ONLY_KNOWLEDGE"))
        assertFalse(system.contains("A_ONLY_KNOWLEDGE"))
        assertTrue(system.contains("Respond only as Character B"))
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.USER), request.map { it.role })
        assertEquals("Persona: Enter the hall.", request[1].content)
        assertTrue(request.last().content.startsWith("[DIRECTOR TURN CONTROL"))
        assertTrue(request.last().content.contains("as Character B"))
    }

    @Test
    fun sequentialManualRepliesUseIndependentCallsAndUpdatedSharedHistory() = runBlocking {
        val provider = SequentialGroupProvider(
            responses = listOf("A reply", "B reply"),
            requireDirectorUserTurn = true
        )
        val harness = createHarness(
            provider = provider,
            routingMode = ProviderRoutingMode.PREFER,
            providerEndpoint = "provider-b"
        )

        harness.viewModel.onDraftChanged("Who found the map?")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 1 }
        }

        harness.viewModel.selectGroupCharacter("character-a")
        harness.viewModel.generateGroupResponse()
        awaitMessages(harness, 2)

        harness.viewModel.selectGroupCharacter("character-b")
        harness.viewModel.generateGroupResponse()
        val completed = awaitMessages(harness, 3)

        assertEquals(
            listOf("persona", "character-a", "character-b"),
            completed.map { it.speakerId }
        )
        assertEquals(2, provider.requests.size)
        val secondRequest = provider.requests[1]
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), secondRequest.map { it.role })
        assertEquals("Persona: Who found the map?", secondRequest[1].content)
        assertEquals("Character A: A reply", secondRequest[2].content)
        assertTrue(secondRequest.last().content.startsWith("[DIRECTOR TURN CONTROL"))
        assertTrue(secondRequest.last().content.contains("Respond only as Character B"))
        assertTrue(secondRequest.first().content.contains("B_ONLY_KNOWLEDGE"))
        assertFalse(secondRequest.first().content.contains("A_ONLY_KNOWLEDGE"))
        assertEquals(listOf("mock/model", "mock/model"), provider.options.map { it.modelId })
        provider.options.forEach { options ->
            assertEquals(ProviderRoutingMode.PREFER, options.routing.mode)
            assertEquals(true, options.routing.allowFallback)
            assertEquals(listOf("provider-b"), options.routing.preferredEndpoints)
        }
    }

    @Test
    fun participantControlsPersistOrderMuteRemoveAndReaddWithoutDeletingHistory() = runBlocking {
        val chatRepository = ChatRepository(database.chatDao(), database.messageDao())
        val messageRepository = MessageRepository(database.messageDao())
        database.characterDao().insertCharacter(character("character-a", "Character A", "A_ONLY_KNOWLEDGE"))
        database.characterDao().insertCharacter(character("character-b", "Character B", "B_ONLY_KNOWLEDGE"))
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createGroupChat(
            displayName = "Crossover",
            personaIds = listOf("persona"),
            characterIds = listOf("character-a", "character-b")
        )
        messageRepository.insertMessage(
            MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.CHARACTER,
                speakerId = "character-a",
                speakerDisplayNameSnapshot = "Character A",
                content = "Keep this line.",
                orderIndex = 1L
            )
        )

        chatRepository.moveGroupCharacter(chatId, "character-b", -1)
        assertEquals(
            listOf("character-b", "character-a"),
            database.chatDao().getParticipantsForChatOnce(chatId).map { it.characterId }
        )

        chatRepository.setGroupCharacterActive(chatId, "character-b", false)
        assertFalse(database.chatDao().getChatParticipant(chatId, "character-b")!!.isActive)

        chatRepository.removeGroupCharacter(chatId, "character-a")
        assertEquals(
            listOf("character-b"),
            database.chatDao().getParticipantsForChatOnce(chatId).map { it.characterId }
        )
        assertEquals(
            "Keep this line.",
            messageRepository.getMessagesForChatOnce(chatId).single().content
        )

        chatRepository.addGroupCharacter(chatId, "character-a")
        val restored = database.chatDao().getParticipantsForChatOnce(chatId)
        assertEquals(listOf("character-b", "character-a"), restored.map { it.characterId })
        assertTrue(restored.single { it.characterId == "character-a" }.isActive)
    }

    @Test
    fun characterAddedAfterChatOpenCanBeSelectedAndGeneratedWithoutFallingBackToFirstCharacter() = runBlocking {
        val provider = SequentialGroupProvider(listOf("C reply"))
        val harness = createHarness(provider)
        database.characterDao().insertCharacter(character("character-c", "Character C", "C_ONLY_KNOWLEDGE"))

        harness.chatRepository.addGroupCharacter(harness.chatId, "character-c")
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    state.groupCharacters.any { it.character.characterId == "character-c" }
            }
        }
        harness.viewModel.onDraftChanged("C, take the next turn.")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 1 }
        }

        harness.viewModel.selectGroupCharacter("character-c")
        harness.viewModel.generateGroupResponse()
        val completed = awaitMessages(harness, 2)

        assertEquals("character-c", completed.last().speakerId)
        assertTrue(provider.requests.single().first().content.contains("C_ONLY_KNOWLEDGE"))
        assertFalse(provider.requests.single().first().content.contains("A_ONLY_KNOWLEDGE"))
    }

    @Test
    fun groupRetryRegenerateAndContinueKeepSelectedSpeakerAndNonPersistedDirectorTurn() = runBlocking {
        val provider = SequentialGroupProvider(
            responses = listOf("", "B reply", "B regenerated", " and continued"),
            requireDirectorUserTurn = true
        )
        val harness = createHarness(provider)
        harness.viewModel.onDraftChanged("B, answer this.")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 1 }
        }

        harness.viewModel.selectGroupCharacter("character-b")
        harness.viewModel.generateGroupResponse()
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating && state.generationError != null
            }
        }
        assertEquals(1, harness.messageRepository.getMessagesForChatOnce(harness.chatId).size)

        harness.viewModel.retryGeneration()
        val initialReply = awaitMessages(harness, 2).single { it.speakerType == SpeakerType.CHARACTER }
        assertEquals("character-b", initialReply.speakerId)

        harness.viewModel.regenerateMessage(initialReply.messageId)
        val regenerated = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.isPrimaryVariant && it.content == "B regenerated" }
            }
        }.single { it.isPrimaryVariant && it.content == "B regenerated" }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    !state.isGenerating &&
                    state.messages.any { it.messageId == regenerated.messageId }
            }
        }

        harness.viewModel.continueMessage(regenerated.messageId)
        withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.messageId == regenerated.messageId && it.content == "B regenerated and continued" }
            }
        }

        assertEquals(4, provider.requests.size)
        provider.requests.forEach { request ->
            assertEquals(Role.SYSTEM, request.first().role)
            assertTrue(request.first().content.contains("--- CHARACTER: Character B ---"))
            assertTrue(request.first().content.contains("B_ONLY_KNOWLEDGE"))
            assertFalse(request.first().content.contains("A_ONLY_KNOWLEDGE"))
            assertEquals(Role.USER, request.last().role)
            assertTrue(request.last().content.startsWith("[DIRECTOR TURN CONTROL"))
            assertTrue(request.last().content.contains("Respond only as Character B"))
        }
        assertTrue(provider.requests[2].none { it.content.contains("B reply") })
        assertTrue(provider.requests[3].any {
            it.role == Role.ASSISTANT && it.content.contains("B regenerated")
        })
        assertTrue(provider.requests.last().any {
            it.role == Role.SYSTEM && it.content.contains("Continue the previous message")
        })
        val stored = harness.messageRepository.getMessagesForChatOnce(harness.chatId)
        assertTrue(stored.none { it.content.contains("DIRECTOR TURN CONTROL") })
        assertTrue(stored.filter { it.speakerType == SpeakerType.CHARACTER }.all { it.speakerId == "character-b" })
    }

    @Test
    fun groupLorebookReResolvesBookAndChatCurrentAcrossRegenerateContinueDetachAndReattach() = runBlocking {
        val provider = SequentialGroupProvider(
            responses = listOf(
                "Lore reply",
                "Lore regenerated",
                " and continued",
                "Detached reply",
                "Reattached reply"
            ),
            requireDirectorUserTurn = true
        )
        val harness = createHarness(provider = provider, installLorebook = true)
        harness.viewModel.onDraftChanged("Tell me what the archive says.")
        harness.viewModel.sendMessage()
        withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 1 }
        }

        harness.viewModel.selectGroupCharacter("character-b")
        harness.viewModel.generateGroupResponse()
        val initialReply = awaitMessages(harness, 2).single { it.speakerType == SpeakerType.CHARACTER }
        assertLoreRequest(provider.requests[0], "LORE_BOOK_CURRENT")

        harness.lorebookRepository.setChatCurrentOverride(
            harness.chatId,
            "lore-secret-entry",
            "LORE_REGENERATE_OVERRIDE"
        )
        harness.viewModel.regenerateMessage(initialReply.messageId)
        val regenerated = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.isPrimaryVariant && it.content == "Lore regenerated" }
            }
        }.single { it.isPrimaryVariant && it.content == "Lore regenerated" }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating && state.messages.any { it.messageId == regenerated.messageId }
            }
        }
        assertLoreRequest(provider.requests[1], "LORE_REGENERATE_OVERRIDE")

        harness.lorebookRepository.setChatCurrentOverride(
            harness.chatId,
            "lore-secret-entry",
            "LORE_CONTINUE_OVERRIDE"
        )
        harness.viewModel.continueMessage(regenerated.messageId)
        withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.messageId == regenerated.messageId && it.content == "Lore regenerated and continued" }
            }
        }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating && state.messages.any {
                    it.messageId == regenerated.messageId && it.content == "Lore regenerated and continued"
                }
            }
        }
        assertLoreRequest(provider.requests[2], "LORE_CONTINUE_OVERRIDE")

        harness.lorebookRepository.detachLorebook(harness.chatId, "lore-secret-book")
        harness.viewModel.generateGroupResponse("character-b")
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating && provider.requests.size >= 4
            }
        }
        assertFalse(provider.requests[3].first().content.contains("Shared Lore"))
        assertFalse(provider.requests[3].first().content.contains("LORE_CONTINUE_OVERRIDE"))

        harness.lorebookRepository.attachLorebook(harness.chatId, "lore-secret-book")
        harness.viewModel.generateGroupResponse("character-b")
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating && provider.requests.size >= 5
            }
        }
        assertLoreRequest(provider.requests[4], "LORE_CONTINUE_OVERRIDE")

        provider.requests.forEach { request ->
            assertEquals(Role.USER, request.last().role)
            assertTrue(request.last().content.startsWith("[DIRECTOR TURN CONTROL"))
            listOf(
                "LORE_CANON_SECRET",
                "LORE_PRIVATE_NOTES",
                "LORE_KEY_SECRET",
                "LORE_TAG_SECRET",
                "lore-secret-entry",
                "lore-secret-book",
                "123456789",
                "schema_version"
            ).forEach { forbidden ->
                assertFalse(
                    "Leaked Lorebook metadata anywhere in provider request: $forbidden",
                    request.any { it.content.contains(forbidden) }
                )
            }
        }
        val stored = harness.messageRepository.getMessagesForChatOnce(harness.chatId)
        assertTrue(stored.none { it.content.contains("DIRECTOR TURN CONTROL") })
        assertTrue(stored.filter { it.speakerType == SpeakerType.CHARACTER }.all { it.speakerId == "character-b" })
        assertTrue(stored.any { !it.isPrimaryVariant && it.content == "Lore reply" })
        assertTrue(stored.any { it.isPrimaryVariant && it.content == "Lore regenerated and continued" })
    }

    private fun assertLoreRequest(request: List<RoleplayMessage>, expectedCurrent: String) {
        val system = request.first().content
        assertTrue(system.contains("Shared Lore"))
        assertTrue(system.contains(expectedCurrent))
    }

    private suspend fun awaitMessages(harness: Harness, count: Int) = withTimeout(15_000) {
        harness.viewModel.uiState.first { state ->
            state is ChatUiState.Success && !state.isGenerating && state.messages.size == count
        }
        harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == count }
    }

    private suspend fun createHarness(
        provider: ModelProvider,
        routingMode: ProviderRoutingMode = ProviderRoutingMode.AUTO,
        providerEndpoint: String? = null,
        installLorebook: Boolean = false
    ): Harness {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi)
        val personaRepository = PersonaRepository(database.personaDao(), moshi)
        val chatRepository = ChatRepository(database.chatDao(), database.messageDao())
        val messageRepository = MessageRepository(database.messageDao())
        val memoryRepository = MemoryRepository(database.memoryDao())
        val lorebookRepository = LorebookRepository(database.lorebookDao(), moshi)

        database.characterDao().insertCharacter(character("character-a", "Character A", "A_ONLY_KNOWLEDGE"))
        database.characterDao().insertCharacter(character("character-b", "Character B", "B_ONLY_KNOWLEDGE"))
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createGroupChat(
            displayName = "Crossover",
            personaIds = listOf("persona"),
            characterIds = listOf("character-a", "character-b")
        )
        chatRepository.insertChatSettings(
            ChatSettingsEntity(
                chatId = chatId,
                selectedModelId = "mock/model",
                providerRoutingMode = routingMode,
                providerEndpoint = providerEndpoint
            )
        )
        if (installLorebook) {
            val lorebook = LorebookEntity(
                lorebookId = "lore-secret-book",
                name = "Group Lorebook",
                createdAt = 123456789L,
                updatedAt = 123456789L
            )
            lorebookRepository.saveDefinition(
                lorebook,
                listOf(
                    LorebookEntryEntity(
                        entryId = "lore-secret-entry",
                        lorebookId = lorebook.lorebookId,
                        title = "Shared Lore",
                        activationMode = "always",
                        keys = listOf("LORE_KEY_SECRET"),
                        canonContent = "LORE_CANON_SECRET",
                        currentContent = "LORE_BOOK_CURRENT",
                        tags = listOf("LORE_TAG_SECRET"),
                        notes = "LORE_PRIVATE_NOTES",
                        createdAt = 123456789L,
                        updatedAt = 123456789L
                    )
                )
            )
            lorebookRepository.attachLorebook(chatId, lorebook.lorebookId)
        }

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
                state is ChatUiState.Success &&
                    state.historyLoaded &&
                    state.groupCharacters.size == 2
            }
        }
        return Harness(chatId, viewModel, messageRepository, chatRepository, lorebookRepository)
    }

    private data class Harness(
        val chatId: String,
        val viewModel: ChatDetailViewModel,
        val messageRepository: MessageRepository,
        val chatRepository: ChatRepository,
        val lorebookRepository: LorebookRepository
    )

    private class SequentialGroupProvider(
        private val responses: List<String>,
        private val requireDirectorUserTurn: Boolean = false
    ) : ModelProvider {
        val requests = mutableListOf<List<RoleplayMessage>>()
        val options = mutableListOf<GenerationOptions>()
        private var responseIndex = 0

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            requests += messages
            this@SequentialGroupProvider.options += options
            if (
                requireDirectorUserTurn &&
                (messages.lastOrNull()?.role != Role.USER ||
                    !messages.last().content.startsWith("[DIRECTOR TURN CONTROL"))
            ) {
                emit(StreamEvent.Done(null))
                return@flow
            }
            emit(StreamEvent.Content(responses[responseIndex++]))
            emit(StreamEvent.Done(null))
        }
    }

    private fun character(id: String, name: String, knowledge: String) = CharacterEntity(
        characterId = id,
        displayName = name,
        characterName = name,
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        voiceJson = null,
        behaviorJson = null,
        backstoryJson = null,
        knowledgeJson = "{\"private\":\"$knowledge\"}",
        worldContextJson = null,
        writingRulesJson = null,
        examplesJson = null,
        authorNotesJson = null,
        extensionFieldsJson = null
    )

    private fun persona() = PersonaEntity(
        personaId = "persona",
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
