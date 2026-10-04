package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
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
        assertEquals(listOf(Role.SYSTEM, Role.USER), request.map { it.role })
        assertEquals("Persona: Enter the hall.", request.last().content)
    }

    @Test
    fun sequentialManualRepliesUseIndependentCallsAndUpdatedSharedHistory() = runBlocking {
        val provider = SequentialGroupProvider(listOf("A reply", "B reply"))
        val harness = createHarness(provider)

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
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT), secondRequest.map { it.role })
        assertEquals("Persona: Who found the map?", secondRequest[1].content)
        assertEquals("Character A: A reply", secondRequest[2].content)
        assertTrue(secondRequest.first().content.contains("B_ONLY_KNOWLEDGE"))
        assertFalse(secondRequest.first().content.contains("A_ONLY_KNOWLEDGE"))
    }

    @Test
    fun participantControlsPersistOrderMuteRemoveAndReaddWithoutDeletingHistory() = runBlocking {
        val harness = createHarness(SequentialGroupProvider(emptyList()))
        harness.messageRepository.insertMessage(
            MessageEntity(
                chatId = harness.chatId,
                speakerType = SpeakerType.CHARACTER,
                speakerId = "character-a",
                speakerDisplayNameSnapshot = "Character A",
                content = "Keep this line.",
                orderIndex = 1L
            )
        )

        harness.chatRepository.moveGroupCharacter(harness.chatId, "character-b", -1)
        assertEquals(
            listOf("character-b", "character-a"),
            database.chatDao().getParticipantsForChatOnce(harness.chatId).map { it.characterId }
        )

        harness.chatRepository.setGroupCharacterActive(harness.chatId, "character-b", false)
        assertFalse(database.chatDao().getChatParticipant(harness.chatId, "character-b")!!.isActive)

        harness.chatRepository.removeGroupCharacter(harness.chatId, "character-a")
        assertEquals(
            listOf("character-b"),
            database.chatDao().getParticipantsForChatOnce(harness.chatId).map { it.characterId }
        )
        assertEquals(
            "Keep this line.",
            harness.messageRepository.getMessagesForChatOnce(harness.chatId).single().content
        )

        harness.chatRepository.addGroupCharacter(harness.chatId, "character-a")
        val restored = database.chatDao().getParticipantsForChatOnce(harness.chatId)
        assertEquals(listOf("character-b", "character-a"), restored.map { it.characterId })
        assertTrue(restored.single { it.characterId == "character-a" }.isActive)
    }

    private suspend fun awaitMessages(harness: Harness, count: Int) = withTimeout(15_000) {
        harness.viewModel.uiState.first { state ->
            state is ChatUiState.Success && !state.isGenerating && state.messages.size == count
        }
        harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == count }
    }

    private suspend fun createHarness(provider: ModelProvider): Harness {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi)
        val personaRepository = PersonaRepository(database.personaDao(), moshi)
        val chatRepository = ChatRepository(database.chatDao(), database.messageDao())
        val messageRepository = MessageRepository(database.messageDao())
        val memoryRepository = MemoryRepository(database.memoryDao())

        database.characterDao().insertCharacter(character("character-a", "Character A", "A_ONLY_KNOWLEDGE"))
        database.characterDao().insertCharacter(character("character-b", "Character B", "B_ONLY_KNOWLEDGE"))
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createGroupChat(
            displayName = "Crossover",
            personaIds = listOf("persona"),
            characterIds = listOf("character-a", "character-b")
        )
        chatRepository.insertChatSettings(
            ChatSettingsEntity(chatId = chatId, selectedModelId = "mock/model")
        )

        val viewModel = ChatDetailViewModel(
            chatId = chatId,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            characterRepository = characterRepository,
            personaRepository = personaRepository,
            modelProvider = provider,
            memoryRepository = memoryRepository
        )
        withTimeout(15_000) {
            viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    state.historyLoaded &&
                    state.groupCharacters.size == 2
            }
        }
        return Harness(chatId, viewModel, messageRepository, chatRepository)
    }

    private data class Harness(
        val chatId: String,
        val viewModel: ChatDetailViewModel,
        val messageRepository: MessageRepository,
        val chatRepository: ChatRepository
    )

    private class SequentialGroupProvider(private val responses: List<String>) : ModelProvider {
        val requests = mutableListOf<List<RoleplayMessage>>()
        private var responseIndex = 0

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            requests += messages
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
