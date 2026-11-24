package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatLorebookAttachmentEntity
import com.example.data.model.ChatLorebookEntryOverlayEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.MessageEntity
import com.example.data.model.NpcBankSettingsEntity
import com.example.data.model.NpcDossier
import com.example.data.model.NpcDossierEntity
import com.example.data.model.NpcDossierUpdateEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ProviderRoutingMode
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.generation.GenerationLifecycleReporter
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.LorebookRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.StoryMemoryRepository
import com.example.domain.memory.StoryMemoryClaim
import com.example.domain.memory.AutomaticMemoryManager
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.NpcRepository
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.story.StoryOutputParser
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegmentType
import com.example.domain.repository.PersonaRepository
import com.example.ui.screens.ChatDetailViewModel
import com.example.ui.screens.ChatUiState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatDetailGenerationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var viewModels: TestViewModelOwner
    private lateinit var dataCoordinator: AppDataMaintenanceCoordinator
    // runBlocking does not advance a virtual TestCoroutineScheduler. Room's
    // asynchronous history Flow must not be starved by a test-only Main dispatcher.
    // Adapted from the isolated three-trial passing PR #46 experiment.
    private val mainDispatcher = Dispatchers.Unconfined

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        dataCoordinator = AppDataMaintenanceCoordinator()
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModels = TestViewModelOwner()
    }

    @After
    fun tearDown() {
        viewModels.close()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun streamedResponseIsPersistedAndCompiledContextIncludesMemory() = runBlocking {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi, dataCoordinator = dataCoordinator)
        val personaRepository = PersonaRepository(database.personaDao(), moshi, dataCoordinator = dataCoordinator)
        val chatRepository = ChatRepository(database.chatDao(), dataCoordinator = dataCoordinator)
        val messageRepository = MessageRepository(database.messageDao(), dataCoordinator)
        val memoryRepository = MemoryRepository(database.memoryDao(), dataCoordinator)

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
        val savedReplyIds = mutableListOf<String>()
        val generationEvents = object : GenerationLifecycleReporter {
            override fun started(chatId: String, chatTitle: String) {}
            override fun replySaved(
                chatId: String, chatTitle: String, speakerName: String, messageId: String
            ) {
                savedReplyIds.add(messageId)
            }
            override fun finished(chatId: String) {}
        }
        val viewModel = viewModels.create(ChatDetailViewModel::class.java) {
            ChatDetailViewModel(
                chatId,
                chatRepository,
                messageRepository,
                characterRepository,
                personaRepository,
                provider,
                memoryRepository,
                dataCoordinator = dataCoordinator,
                generationLifecycle = generationEvents
            )
        }
        withTimeout(15_000) { viewModel.uiState.first { it is ChatUiState.Success } }
        chatRepository.insertChatSettings(
            (viewModel.uiState.value as ChatUiState.Success).chatSettings.copy(
                selectedModelId = "mock/updated-model",
                responseLengthProfile = ResponseLengthProfile.LONG
            )
        )
        withTimeout(15_000) {
            viewModel.uiState.first {
                it is ChatUiState.Success && it.chatSettings.selectedModelId == "mock/updated-model"
            }
        }

        viewModel.onDraftChanged("Remember that I prefer jasmine tea.")
        viewModel.sendMessage()

        val persisted = withTimeout(15_000) {
            messageRepository.getMessagesForChat(chatId).first { it.size == 2 }
        }
        assertEquals(listOf(SpeakerType.PERSONA, SpeakerType.CHARACTER), persisted.map { it.speakerType })
        assertEquals("A quiet answer.", persisted.last().content)
        assertTrue(provider.messages.first { it.role == Role.SYSTEM }.content.contains("meet beneath the clock tower"))
        assertTrue(provider.messages.first { it.role == Role.SYSTEM }.content.contains("escaped the winter court"))
        assertEquals("mock/updated-model", provider.options.modelId)
        assertNull(provider.options.maxTokens)
        val learnedMemories = withTimeout(15_000) {
            memoryRepository.getMemories(chatId, "character-1").first { memories ->
                memories.any { it.content == "jasmine tea" }
            }
        }
        assertTrue(learnedMemories.any { it.content == "jasmine tea" })
        val completedState = withTimeout(15_000) {
            viewModel.uiState.first {
                it is ChatUiState.Success && !it.isGenerating && it.messages.size == 2
            }
        } as ChatUiState.Success
        assertFalse(completedState.isGenerating)

        viewModel.continueMessage(persisted.last().messageId)
        val continuedMessages = withTimeout(15_000) {
            messageRepository.getMessagesForChat(chatId).first { messages ->
                messages.size == 2 && messages.last().content == "A quiet answer.A quiet answer."
            }
        }
        assertEquals(2, continuedMessages.size)
        val continuationRequest = provider.requests.last()
        assertEquals(2, continuationRequest.count { it.role == Role.USER })
        assertEquals(1, continuationRequest.count { it.role == Role.ASSISTANT })
        assertEquals(Role.USER, continuationRequest.last().role)
        assertTrue(continuationRequest.last().content.startsWith("[DIRECTOR TURN CONTROL"))
        assertTrue(continuationRequest.last().content.contains("Continue the previous message"))
        assertTrue(continuationRequest.last().content.contains("Do not invent dialogue"))
        assertFalse(continuationRequest.drop(1).any { it.role == Role.SYSTEM })
        withTimeout(5_000) { while (savedReplyIds.size < 2) yield() }
        // A saved Continue updates the existing message instead of inventing a new chat turn.
        assertEquals(listOf(persisted.last().messageId, persisted.last().messageId), savedReplyIds)
    }

    /**
     * Generation regressions should report whether the provider was reached,
     * instead of surfacing an opaque coroutine timeout. No prompt or RP text
     * is printed: only synthetic test state, counts and the UI error.
     */
    private suspend fun awaitCompletedTurn(
        viewModel: ChatDetailViewModel,
        expectedMessageCount: Int,
        requests: () -> Int
    ) {
        try {
            withTimeout(15_000) {
                viewModel.uiState.first { state ->
                    state is ChatUiState.Success &&
                        ((!state.isGenerating && state.messages.size == expectedMessageCount) ||
                            state.generationError != null)
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(generationDiagnostics(viewModel, expectedMessageCount, requests()), timeout)
        }
        val state = viewModel.uiState.value as? ChatUiState.Success
        assertTrue(
            generationDiagnostics(viewModel, expectedMessageCount, requests()),
            state != null && !state.isGenerating && state.messages.size == expectedMessageCount &&
                state.generationError == null
        )
    }

    private fun generationDiagnostics(
        viewModel: ChatDetailViewModel,
        expectedMessageCount: Int,
        requests: Int
    ): String {
        val state = viewModel.uiState.value as? ChatUiState.Success
        return "Generation did not complete: expectedMessageCount=$expectedMessageCount, " +
            "observedMessageCount=${state?.messages?.size}, requests=$requests, " +
            "historyLoaded=${state?.historyLoaded}, isGenerating=${state?.isGenerating}, " +
            "generationError=${state?.generationError}, partialStopped=${state?.partialStopped}"
    }

    @Test
    fun generatedSummaryOverflowIsDroppedForThisRequestWithoutDeletingSavedData() = runBlocking {
        val provider = RecordingStreamingProvider(contextWindow = 4_096)
        val story = StoryMemoryRepository(database, dataCoordinator)
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL, storyMemoryRepository = story
        )
        val approved = story.saveManual(
            harness.chatId, "PLAYER_APPROVED_SILVER_COMPASS_MEMO: silver compass stays in the lantern room."
        )
        story.setCharacterKnowledge(harness.chatId, approved.memoryId, setOf("character-1"))
        val savedSummary = "OLD_GENERATED_CONTEXT_TO_DROP_" + "s".repeat(12_000)
        harness.memoryRepository.upsertSummary(
            ConversationSummaryEntity(harness.chatId, savedSummary, 0L)
        )
        harness.viewModel.onDraftChanged("I inspect the silver compass in the lantern room.")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 2) { provider.requests.size }
        assertEquals(1, provider.requests.size)
        val sent = provider.requests.single()
        assertTrue(sent.first().content.contains("You are participating in a roleplay."))
        assertTrue(sent.last().content.contains("I inspect the silver compass in the lantern room."))
        assertFalse(sent.first().content.contains("OLD_GENERATED_CONTEXT_TO_DROP_"))
        assertTrue("Reviewed continuity should outrank a generated summary",
            sent.first().content.contains("PLAYER_APPROVED_SILVER_COMPASS_MEMO"))
        assertEquals(savedSummary,
            harness.memoryRepository.getSummaryOnce(harness.chatId)?.summary)
        assertTrue(com.example.domain.compiler.PromptPreflight.inspect(sent, 4_096).allowed)
    }

    @Test
    fun oversizedAuthoredNarratorInstructionsBlockProviderEvenAfterOptionalRecovery() = runBlocking {
        val provider = RecordingStreamingProvider(contextWindow = 2_048)
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            narratorEnabled = true,
            narratorInstructions = "PLAYER_AUTHORED_INSTRUCTIONS_" + "c".repeat(3_500)
        )
        harness.viewModel.onDraftChanged("The storm begins.")
        harness.viewModel.sendMessage()
        val state = withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.generationError?.contains(
                    "No API request was sent"
                ) == true
            }
        } as ChatUiState.Success
        assertTrue(state.generationError.orEmpty().contains("safe input budget"))
        assertTrue(provider.requests.isEmpty())
        // The user's message was saved before attempted generation; no
        // authored instructions were truncated in storage.
        assertTrue(harness.messageRepository.getMessagesForChatOnce(harness.chatId)
            .any { it.content == "The storm begins." })
        assertTrue(harness.chatRepository.getChatSettings(harness.chatId)
            ?.narratorInstructions.orEmpty().contains("PLAYER_AUTHORED_INSTRUCTIONS_"))
    }

    @Test
    fun soloRetryWithLongStoredHistoryKeepsMemorySummaryAndBoundsPrompt() = runBlocking {
        val provider = SequentialResponseProvider(listOf("The compass is still safe."))
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        database.withTransaction {
            for (index in 1..80) {
                database.messageDao().insertMessage(
                    MessageEntity(
                        messageId = "retry-long-$index",
                        chatId = harness.chatId,
                        speakerType = if (index == 80 || index % 2 == 0)
                            SpeakerType.PERSONA else SpeakerType.CHARACTER,
                        speakerId = if (index == 80 || index % 2 == 0)
                            "persona-1" else "character-1",
                        speakerDisplayNameSnapshot = if (index == 80 || index % 2 == 0)
                            "Mara" else "Rowan",
                        content = when (index) {
                            1 -> "VERY_OLD_RETRY_CONTEXT_MARKER"
                            80 -> "I promise to find the compass."
                            else -> "A story scene occurred at waypoint $index."
                        },
                        orderIndex = index.toLong()
                    )
                )
            }
        }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.historyLoaded && state.messages.size == 80
            }
        }

        harness.viewModel.retryGeneration()
        awaitCompletedTurn(harness.viewModel, 81) { provider.requests.size }
        assertEquals(1, provider.requests.size)
        val request = provider.requests.single()
        val prompt = request.joinToString("\n") { it.content }
        assertFalse("Old transcript must not return to bounded context",
            prompt.contains("VERY_OLD_RETRY_CONTEXT_MARKER"))
        assertTrue(prompt.contains("I promise to find the compass."))
        assertTrue(prompt.contains("waypoint 79"))

        val saved = harness.messageRepository.getMessagesForChatOnce(harness.chatId)
            .filter { it.isPrimaryVariant }
        val expected = AutomaticMemoryManager.rollingSummaryTail(saved.dropLast(24))
        val summary = requireNotNull(harness.memoryRepository.getSummaryOnce(harness.chatId))
        assertEquals("Retry must preserve the full-history rolling summary semantics",
            expected, summary.summary)
        assertEquals(saved.dropLast(24).maxOf { it.orderIndex },
            summary.summarizedThroughOrderIndex)
        assertTrue(harness.memoryRepository.getMemoriesOnce(harness.chatId, "character-1")
            .any { it.content == "to find the compass" })
    }

    @Test
    fun storyMemoryRecallsInNextPromptButWackBlocksUntilUserRestoresIt() = runBlocking {
        // Full ViewModel -> Room -> recipient retrieval -> compiler -> provider path.
        // No network/OpenRouter request: SequentialResponseProvider is local.
        val provider = SequentialResponseProvider(listOf(
            "The story continues.", "A fresh chapter.", "The secret is remembered."
        ))
        val stories = StoryMemoryRepository(database, dataCoordinator)
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL, storyMemoryRepository = stories
        )
        val memory = stories.saveManual(
            harness.chatId, "The silver compass is buried under the willow tree.",
            StoryMemoryClaim.PROMISE
        )
        stories.setCharacterKnowledge(harness.chatId, memory.memoryId, setOf("character-1"))

        harness.viewModel.onDraftChanged("Where is our silver compass?")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 2) { provider.requests.size }
        assertEquals(1, provider.requests.size)
        assertTrue(provider.requests.single().first().content.contains("silver compass is buried"))

        harness.viewModel.wackContext()
        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.chatSummary.chat.contextResetOrderIndex != null &&
                    it.inChatToolStatus?.message?.contains("Wack complete") == true
            }
        }
        harness.viewModel.onDraftChanged("Where did we put the compass in this new story?")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 4) { provider.requests.size }
        assertEquals(2, provider.requests.size)
        assertFalse(provider.requests[1].first().content.contains("silver compass is buried"))

        stories.restoreAcrossWack(harness.chatId, memory.memoryId)
        harness.viewModel.onDraftChanged("Do you remember the silver compass?")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 6) { provider.requests.size }
        assertEquals(3, provider.requests.size)
        assertTrue(provider.requests[2].first().content.contains("silver compass is buried"))
    }

    @Test
    fun continueFromCharacterGreetingNeedsNoPersonaTurn() = runBlocking {
        val provider = SequentialResponseProvider(listOf(" And then the door opened."))
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            initialStoredCharacterContent = "The door closed."
        )
        val greeting = harness.messageRepository.getMessagesForChatOnce(harness.chatId).single()
        assertEquals(SpeakerType.CHARACTER, greeting.speakerType)

        harness.viewModel.continueMessage(greeting.messageId)

        val continued = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.size == 1 &&
                    messages.single().messageId == greeting.messageId &&
                    messages.single().content.contains("And then the door opened.")
            }
        }
        assertEquals(1, continued.size)
        assertEquals(0, continued.count { it.speakerType == SpeakerType.PERSONA })
        assertEquals(1, provider.requests.size)
        val prompt = provider.requests.single()
        assertEquals(Role.ASSISTANT, prompt[prompt.lastIndex - 1].role)
        assertEquals(Role.USER, prompt.last().role)
        assertTrue(prompt.last().content.startsWith("[DIRECTOR TURN CONTROL"))
        assertFalse(prompt.drop(1).any { it.role == Role.SYSTEM })
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

        val persisted = withTimeout(15_000) {
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

        val completed = withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && !it.isGenerating && it.generationError != null
            }
        } as ChatUiState.Success
        val persisted = harness.messageRepository.getMessagesForChat(harness.chatId).first()

        assertEquals(1, persisted.size)
        assertEquals(SpeakerType.PERSONA, persisted.single().speakerType)
        assertTrue(persisted.none { it.speakerType == SpeakerType.CHARACTER || it.content.isEmpty() })
        assertEquals(
            "The provider returned no visible response (finish reason: stop). You can retry this turn.",
            completed.generationError
        )
    }

    @Test
    fun repeatedRegenerationDoesNotFeedAssistantPrefixBackIntoContextOrStorage() = runBlocking {
        val provider = SequentialResponseProvider(
            listOf(
                "Rowan: First reply.",
                "Rowan: Second reply.",
                "Rowan: Third reply."
            )
        )
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)

        harness.viewModel.onDraftChanged("Please answer again.")
        harness.viewModel.sendMessage()

        val original = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.count { it.speakerType == SpeakerType.CHARACTER } == 1
            }
        }.single { it.speakerType == SpeakerType.CHARACTER }
        withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    !state.isGenerating &&
                    state.messages.any { it.messageId == original.messageId }
            }
        }

        harness.viewModel.regenerateMessage(original.messageId)
        val second = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.count { it.speakerType == SpeakerType.CHARACTER } == 2 &&
                    messages.any { it.isPrimaryVariant && it.content == "Rowan: Second reply." }
            }
        }.single { it.speakerType == SpeakerType.CHARACTER && it.isPrimaryVariant }
        withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    !state.isGenerating &&
                    state.messages.any { it.isPrimaryVariant && it.messageId == second.messageId }
            }
        }

        harness.viewModel.regenerateMessage(second.messageId)
        val messages = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { stored ->
                stored.count { it.speakerType == SpeakerType.CHARACTER } == 3 &&
                    stored.any { it.isPrimaryVariant && it.content == "Rowan: Third reply." }
            }
        }

        assertEquals(
            listOf("Rowan: First reply.", "Rowan: Second reply.", "Rowan: Third reply."),
            messages.filter { it.speakerType == SpeakerType.CHARACTER }
                .sortedBy { it.createdAt }
                .map { it.content }
        )
        assertEquals("Rowan: Third reply.", messages.single { it.speakerType == SpeakerType.CHARACTER && it.isPrimaryVariant }.content)
        assertTrue(messages.none { it.content.contains("Rowan: Rowan:") })
        assertEquals(3, provider.requests.size)
        provider.requests.forEach { request ->
            assertTrue(request.none { it.role == Role.ASSISTANT })
            assertEquals("Please answer again.", request.last { it.role == Role.USER }.content)
        }
    }

    @Test
    fun normalAndRepeatedRegenerationUseTheSameCleanMultiTurnRequest() = runBlocking {
        val provider = SequentialResponseProvider(
            listOf(
                "Rowan: First current reply.",
                "Rowan: Second current reply.",
                "Rowan: Third current reply."
            )
        )
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        harness.messageRepository.insertMessage(
            MessageEntity(
                messageId = "earlier-persona",
                chatId = harness.chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = "persona-1",
                speakerDisplayNameSnapshot = "Mara",
                content = "Did you find the observatory?",
                orderIndex = 1L
            )
        )
        harness.messageRepository.insertMessage(
            MessageEntity(
                messageId = "earlier-character",
                chatId = harness.chatId,
                speakerType = SpeakerType.CHARACTER,
                speakerId = "character-1",
                speakerDisplayNameSnapshot = "Rowan",
                content = "Rowan: I found it beyond the cedar path.",
                orderIndex = 2L
            )
        )
        withTimeout(30_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.messages.size == 2
            }
        }

        harness.viewModel.onDraftChanged("Take me there before sunrise.")
        harness.viewModel.sendMessage()
        val firstCurrentReply = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.content == "Rowan: First current reply." }
            }
        }.single { it.content == "Rowan: First current reply." }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    !state.isGenerating &&
                    state.messages.any { it.messageId == firstCurrentReply.messageId }
            }
        }

        harness.viewModel.regenerateMessage(firstCurrentReply.messageId)
        val secondCurrentReply = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.isPrimaryVariant && it.content == "Rowan: Second current reply." }
            }
        }.single { it.isPrimaryVariant && it.content == "Rowan: Second current reply." }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    !state.isGenerating &&
                    state.messages.any { it.isPrimaryVariant && it.messageId == secondCurrentReply.messageId }
            }
        }

        harness.viewModel.regenerateMessage(secondCurrentReply.messageId)
        withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.isPrimaryVariant && it.content == "Rowan: Third current reply." }
            }
        }

        assertEquals(3, provider.requests.size)
        val expectedRoles = listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER)
        val expectedConversation = listOf(
            "Did you find the observatory?",
            "Rowan: I found it beyond the cedar path.",
            "Take me there before sunrise."
        )
        provider.requests.forEach { request ->
            assertEquals(expectedRoles, request.map { it.role })
            assertEquals(expectedConversation, request.drop(1).map { it.content })
            assertTrue(request.all { it.name == null })
            assertTrue(request.first().content.contains("Do not prefix the response with the character name or any speaker label."))
        }
        assertEquals(provider.requests[0], provider.requests[1])
        assertEquals(provider.requests[1], provider.requests[2])
    }

    @Test
    fun narratorSoloGenerationUsesOneRequestAndKeepsCharacterSpeakerIdentity() = runBlocking {
        val provider = SequentialResponseProvider(
            listOf(
                """
                    <|elsewhere_story_v1|>
                    <|segment|>{"type":"narration"}
                    Rain whispers against the glass.
                    <|segment|>{"type":"character"}
                    *Rowan listens.* "Only the weather."
                    <|end_story|>
                """.trimIndent()
            )
        )
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            narratorEnabled = true,
            narratorInstructions = "Keep the rain present as quiet scene texture."
        )

        harness.viewModel.onDraftChanged("What do you hear?")
        harness.viewModel.sendMessage()

        val completed = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }

        assertEquals(1, provider.requests.size)
        assertTrue(provider.requests.single().first().content.contains("Keep the rain present as quiet scene texture."))
        assertTrue(provider.requests.single().first().content.contains(StoryOutputParser.START_MARKER))
        assertEquals("character-1", completed.last().speakerId)
        val story = requireNotNull(StoryResponseCodec.decode(completed.last().content))
        assertEquals(listOf(StorySegmentType.NARRATION, StorySegmentType.CHARACTER), story.segments.map { it.type })
        assertFalse(StoryResponseCodec.visibleText(completed.last().content).contains("<|"))
    }

    @Test
    fun narratorSoloUsesNpcBankIdentityInSameSingleRequest() = runBlocking {
        val provider = SequentialResponseProvider(
            listOf(
                """
                    <|elsewhere_story_v1|>
                    <|segment|>{"type":"character"}
                    "The west door stays closed."
                    <|segment|>{"type":"npc","speaker_id":"npc-ashford","speaker_name":"Incorrect name"}
                    *Lady Ashford raises one eyebrow.* "For now."
                    <|segment|>{"type":"narration"}
                    The key turns once in her gloved hand.
                    <|end_story|>
                """.trimIndent()
            )
        )
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            narratorEnabled = true,
            npcBankEnabledInitially = true
        )
        harness.npcRepository.saveManual(
            NpcDossier(
                npcId = "npc-ashford",
                chatId = harness.chatId,
                displayName = "Lady Ashford",
                aliases = emptyList(),
                role = "Hostess",
                appearance = "White gloves",
                personality = "Unflappable",
                relationshipToPersona = "",
                personaRelationships = emptyMap(),
                characterKnowledge = emptyMap(),
                goals = "Protect the west wing",
                secrets = "",
                authorNotes = "",
                keepInScene = true,
                sceneMode = "HERE",
                isManualBase = true,
                createdAt = 1,
                updatedAt = 1
            )
        )

        harness.viewModel.onDraftChanged("What is behind the west door?")
        harness.viewModel.sendMessage()
        val completed = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }

        assertEquals(1, provider.requests.size)
        val story = requireNotNull(StoryResponseCodec.decode(completed.last().content))
        val npc = story.segments.single { it.type == StorySegmentType.NPC }
        assertEquals("npc-ashford", npc.speakerId)
        assertEquals("Lady Ashford", npc.speakerName)
    }

    @Test
    fun previouslyKnownNpcCanBeRecalledWithoutPlayerNamingThemInThePrompt() = runBlocking {
        val provider = SequentialResponseProvider(listOf("Rowan studies the archive shelves."))
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL,
            narratorEnabled = false, npcBankEnabledInitially = true
        )
        harness.npcRepository.saveManual(
            NpcDossier(
                npcId = "archive-voss", chatId = harness.chatId,
                displayName = "Archivist Voss", aliases = emptyList(),
                role = "Court archivist", appearance = "Ink-stained cuffs",
                personality = "Methodical", relationshipToPersona = "",
                personaRelationships = emptyMap(), characterKnowledge = emptyMap(),
                goals = "", secrets = "STORYTELLER_ONLY_SECRET",
                authorNotes = "PRIVATE_AUTHOR_NOTE", keepInScene = false,
                sceneMode = "AUTO", isManualBase = true,
                createdAt = 1L, updatedAt = 1L
            )
        )
        harness.npcRepository.saveManual(
            NpcDossier(
                npcId = "away-guard", chatId = harness.chatId,
                displayName = "Absent Guard", aliases = emptyList(),
                role = "Palace guard", appearance = "", personality = "",
                relationshipToPersona = "",
                personaRelationships = emptyMap(), characterKnowledge = emptyMap(),
                goals = "", secrets = "", authorNotes = "",
                keepInScene = false, sceneMode = "AWAY",
                isManualBase = true, createdAt = 2L, updatedAt = 2L
            )
        )
        harness.viewModel.onDraftChanged("We enter the royal archive and check the records.")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }
        assertEquals(1, provider.requests.size)
        val prompt = provider.requests.single().joinToString("\n") { it.content }
        assertTrue(prompt.contains("[POTENTIAL RECURRING CAST"))
        assertTrue(prompt.contains("Archivist Voss"))
        assertTrue(prompt.contains("Court archivist"))
        assertFalse("Not yet in-scene; must not be a full presence dossier",
            prompt.contains("[NPC id=archive-voss]"))
        assertFalse(prompt.contains("Absent Guard"))
        assertFalse(prompt.contains("STORYTELLER_ONLY_SECRET"))
        assertFalse(prompt.contains("PRIVATE_AUTHOR_NOTE"))
    }

    @Test
    fun narratorNpcBankAndLorebookGenerateTogetherInOneRequest() = runBlocking {
        val provider = SequentialResponseProvider(
            listOf(
                """
                    <|elsewhere_story_v1|>
                    <|segment|>{"type":"narration"}
                    The observatory lens catches a thread of moonlight.
                    <|segment|>{"type":"character"}
                    "The old chart was right."
                    <|segment|>{"type":"npc","speaker_id":"npc-keeper","speaker_name":"Keeper"}
                    "It usually is."
                    <|end_story|>
                """.trimIndent()
            )
        )
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            narratorEnabled = true,
            npcBankEnabledInitially = true
        )
        harness.lorebookRepository.saveDefinition(
            LorebookEntity(lorebookId = "observatory-book", name = "Observatory"),
            listOf(
                LorebookEntryEntity(
                    entryId = "observatory-entry",
                    lorebookId = "observatory-book",
                    title = "Moon Lens",
                    activationMode = "always",
                    currentContent = "The moon lens reveals hidden constellations."
                )
            )
        )
        harness.lorebookRepository.attachLorebook(harness.chatId, "observatory-book")
        harness.npcRepository.saveManual(
            NpcDossier(
                npcId = "npc-keeper",
                chatId = harness.chatId,
                displayName = "Keeper",
                aliases = emptyList(),
                role = "Observatory keeper",
                appearance = "Silver spectacles",
                personality = "Dry and watchful",
                relationshipToPersona = "",
                personaRelationships = emptyMap(),
                characterKnowledge = emptyMap(),
                goals = "Protect the moon lens",
                secrets = "",
                authorNotes = "",
                keepInScene = true,
                sceneMode = "HERE",
                isManualBase = true,
                createdAt = 1,
                updatedAt = 1
            )
        )

        harness.viewModel.onDraftChanged("Use the lens.")
        harness.viewModel.sendMessage()
        val completed = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }

        assertEquals(1, provider.requests.size)
        val systemPrompt = provider.requests.single().first { it.role == Role.SYSTEM }.content
        assertTrue(systemPrompt.contains("The moon lens reveals hidden constellations."))
        assertTrue(systemPrompt.contains("Keeper"))
        assertEquals(
            listOf(StorySegmentType.NARRATION, StorySegmentType.CHARACTER, StorySegmentType.NPC),
            requireNotNull(StoryResponseCodec.decode(completed.last().content)).segments.map { it.type }
        )
    }

    @Test
    fun ensembleNarrationRecoversFromFormatDriftAcrossSixTurnsWithoutContaminatingHistory() = runBlocking {
        fun structured(vararg blocks: Pair<String, String>): String = buildString {
            appendLine(StoryOutputParser.START_MARKER)
            blocks.forEach { (header, content) ->
                appendLine("${StoryOutputParser.SEGMENT_MARKER}$header")
                appendLine(content)
            }
            append(StoryOutputParser.END_MARKER)
        }
        val provider = SequentialResponseProvider(
            listOf(
                structured(
                    "{\"type\":\"narration\"}" to "Rain moves softly against the windows.",
                    "{\"type\":\"character\"}" to "\"The road will flood before midnight.\"",
                    "{\"type\":\"npc\",\"speaker_id\":\"npc-keeper\",\"speaker_name\":\"Keeper\"}" to "\"Then leave before the bells.\""
                ),
                structured(
                    "{\"type\":\"character\"}" to "*Rowan folds the map.* \"We have time.\"",
                    "{\"type\":\"npc\",\"speaker_id\":\"npc-ashford\",\"speaker_name\":\"Lady Ashford\"}" to "\"That is what you said yesterday.\"",
                    "{\"type\":\"narration\"}" to "The Keeper turns away to hide a smile."
                ),
                """
                    Narrator: Thunder rolls beyond the eastern ridge.
                    Character (Rowan): *He glances toward the glass.* "That changes things."
                    NPC (Keeper): "At last, we agree."
                """.trimIndent(),
                "An ordinary reply without narration or a speaker label.",
                structured(
                    "{\"type\":\"narration\"}" to "The first bell sounds.",
                    "{\"type\":\"npc\",\"speaker_id\":\"npc-ashford\",\"speaker_name\":\"Lady Ashford\"}" to "\"Now do you believe me?\"",
                    "{\"type\":\"character\"}" to "\"Enough to start walking.\""
                ),
                structured(
                    "{\"type\":\"character\"}" to "*Rowan opens the door.* \"Stay close.\"",
                    "{\"type\":\"narration\"}" to "Cold rain sweeps into the hall."
                )
            )
        )
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            narratorEnabled = true,
            npcBankEnabledInitially = true
        )
        listOf(
            NpcDossier(
                npcId = "npc-keeper",
                chatId = harness.chatId,
                displayName = "Keeper",
                aliases = emptyList(),
                role = "Gate keeper",
                appearance = "Rain-dark coat",
                personality = "Practical",
                relationshipToPersona = "",
                personaRelationships = emptyMap(),
                characterKnowledge = emptyMap(),
                goals = "Close the eastern road",
                secrets = "",
                authorNotes = "",
                keepInScene = true,
                isManualBase = true,
                createdAt = 1,
                updatedAt = 1
            ),
            NpcDossier(
                npcId = "npc-ashford",
                chatId = harness.chatId,
                displayName = "Lady Ashford",
                aliases = emptyList(),
                role = "Traveler",
                appearance = "Green cloak",
                personality = "Impatient",
                relationshipToPersona = "",
                personaRelationships = emptyMap(),
                characterKnowledge = emptyMap(),
                goals = "Reach the eastern road",
                secrets = "",
                authorNotes = "",
                keepInScene = true,
                isManualBase = true,
                createdAt = 2,
                updatedAt = 2
            )
        ).forEach { harness.npcRepository.saveManual(it) }

        repeat(6) { turn ->
            harness.viewModel.onDraftChanged("Player turn ${turn + 1}")
            harness.viewModel.sendMessage()
            withTimeout(15_000) {
                harness.viewModel.uiState.first { state ->
                    state is ChatUiState.Success &&
                        !state.isGenerating &&
                        state.messages.count { it.speakerType == SpeakerType.CHARACTER } == turn + 1
                }
            }
        }

        assertEquals(6, provider.requests.size)
        val persisted = harness.messageRepository.getMessagesForChat(harness.chatId).first()
            .filter { it.speakerType == SpeakerType.CHARACTER }
        assertEquals(6, persisted.size)
        val persistedNpcNames = persisted.flatMap { message ->
            StoryResponseCodec.decode(message.content)?.segments.orEmpty()
                .filter { it.type == StorySegmentType.NPC }
                .mapNotNull { it.speakerName }
        }.toSet()
        assertTrue(persistedNpcNames.containsAll(setOf("Keeper", "Lady Ashford")))
        val recoveredTranscript = requireNotNull(StoryResponseCodec.decode(persisted[2].content))
        assertEquals(
            listOf(StorySegmentType.NARRATION, StorySegmentType.CHARACTER, StorySegmentType.NPC),
            recoveredTranscript.segments.map { it.type }
        )
        assertTrue(recoveredTranscript.segments.none { it.content.contains("Character (Rowan):") })
        val ordinary = requireNotNull(StoryResponseCodec.decode(persisted[3].content))
        assertEquals(StorySegmentType.CHARACTER, ordinary.segments.single().type)
        assertEquals("An ordinary reply without narration or a speaker label.", ordinary.segments.single().content)
        assertEquals(
            listOf(StorySegmentType.CHARACTER, StorySegmentType.NARRATION),
            requireNotNull(StoryResponseCodec.decode(persisted[5].content)).segments.map { it.type }
        )

        provider.requests.drop(1).forEach { request ->
            val assistantHistory = request.filter { it.role == Role.ASSISTANT }
            assertTrue(assistantHistory.isNotEmpty())
            assistantHistory.forEach { message ->
                assertTrue(message.content.startsWith(StoryOutputParser.START_MARKER))
                assertTrue(message.content.endsWith(StoryOutputParser.END_MARKER))
                assertFalse(message.content.contains("Character (Rowan):"))
                assertFalse(message.content.contains("NPC (Keeper):"))
                assertFalse(message.content.contains("Narrator:"))
            }
        }
    }

    @Test
    fun finalStoryParserFailureRetainsSanitizedGeneratedTextAsPartial() = runBlocking {
        val raw = """
            <|elsewhere_story_v1|>
            <|segment|>{"type":"character"}
            "I can still answer."
            <|end_story|>
        """.trimIndent()
        val provider = SequentialResponseProvider(listOf(raw))
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            narratorEnabled = true,
            storyOutputParserFactory = {
                object : StoryOutputParser() {
                    override fun parse(
                        output: String,
                        primaryCharacter: com.example.domain.story.StorySpeakerIdentity,
                        knownNpcs: List<com.example.domain.story.StorySpeakerIdentity>,
                        final: Boolean
                    ): com.example.domain.story.StoryParseResult {
                        if (final) error("PRIVATE generated text must not enter the error")
                        return super.parse(output, primaryCharacter, knownNpcs, final)
                    }
                }
            }
        )

        harness.viewModel.onDraftChanged("Answer.")
        harness.viewModel.sendMessage()
        val failed = withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.partialStopped }
        } as ChatUiState.Success

        assertEquals(1, provider.requests.size)
        assertTrue(failed.streamingContent.orEmpty().contains("I can still answer."))
        assertFalse(failed.streamingContent.orEmpty().contains("<|"))
        assertTrue(failed.generationError.orEmpty().contains("could not be processed safely"))
        assertFalse(failed.generationError.orEmpty().contains("PRIVATE"))
        assertEquals(1, harness.messageRepository.getMessagesForChat(harness.chatId).first().size)
    }

    @Test
    fun generatedMessagePersistenceFailureRetainsTextAndCanBeRetried() = runBlocking {
        val provider = SequentialResponseProvider(listOf("Unsaved reply."))
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        database.openHelper.writableDatabase.execSQL(
            """
                CREATE TRIGGER fail_generated_message
                BEFORE INSERT ON messages
                WHEN NEW.speakerType = 'CHARACTER'
                BEGIN
                    SELECT RAISE(ABORT, 'forced test failure');
                END
            """.trimIndent()
        )

        harness.viewModel.onDraftChanged("Answer.")
        harness.viewModel.sendMessage()
        val failed = withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.partialStopped }
        } as ChatUiState.Success

        assertEquals("Unsaved reply.", failed.streamingContent)
        assertTrue(failed.generationError.orEmpty().contains("could not be saved"))
        assertEquals(1, harness.messageRepository.getMessagesForChat(harness.chatId).first().size)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_generated_message")
        harness.viewModel.keepPartial()
        val saved = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }
        assertEquals("Unsaved reply.", saved.last().content)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun providerFailureBeforeFirstChunkIsReportedWithoutCrashing() = runBlocking {
        val provider = ThrowingProvider()
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)

        harness.viewModel.onDraftChanged("Answer.")
        harness.viewModel.sendMessage()
        val failed = withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && !it.isGenerating && it.generationError != null
            }
        } as ChatUiState.Success

        assertEquals(1, provider.requestCount)
        assertFalse(failed.partialStopped)
        assertEquals(1, harness.messageRepository.getMessagesForChat(harness.chatId).first().size)
    }

    @Test
    fun narratorDisabledPreservesOrdinaryCharacterResponse() = runBlocking {
        val provider = RecordingStreamingProvider()
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL, narratorEnabled = false)

        harness.viewModel.onDraftChanged("Answer plainly.")
        harness.viewModel.sendMessage()
        val completed = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }

        assertEquals(1, provider.requests.size)
        assertEquals("A quiet answer.", completed.last().content)
        assertEquals(null, StoryResponseCodec.decode(completed.last().content))
        assertFalse(provider.requests.single().first().content.contains(StoryOutputParser.START_MARKER))
    }

    @Test
    fun narratorOffNormalizesPreviousCharacterTranscriptPrefix() = runBlocking {
        val provider = SequentialResponseProvider(listOf(
            "Character (Rowan): *He drops the bags.* \"You're sitting down.\""
        ))
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL, narratorEnabled = false)
        harness.viewModel.onDraftChanged("I'm sleepy.")
        harness.viewModel.sendMessage()
        val completed = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }
        val story = requireNotNull(StoryResponseCodec.decode(completed.last().content))
        assertEquals(StorySegmentType.CHARACTER, story.segments.single().type)
        assertEquals("*He drops the bags.* \"You're sitting down.\"",
            story.segments.single().content)
        assertFalse(StoryResponseCodec.visibleText(completed.last().content).contains("Character (Rowan):"))
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun narratorOffCanDiscoverLabeledNpcWithoutExtraRequest() = runBlocking {
        val provider = SequentialResponseProvider(listOf(
            "Character (Rowan): \"Who are you?\"\nNPC (Liora Fen): \"The courier.\""
        ))
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL, narratorEnabled = false,
            npcBankEnabledInitially = true)
        harness.viewModel.onDraftChanged("Who's at the door?")
        harness.viewModel.sendMessage()
        val completed = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }
        val story = requireNotNull(StoryResponseCodec.decode(completed.last().content))
        assertEquals(listOf(StorySegmentType.CHARACTER, StorySegmentType.NPC),
            story.segments.map { it.type })
        val npc = harness.npcRepository.getProjectedDossiers(harness.chatId).single()
        assertEquals("Liora Fen", npc.displayName)
        val stored = requireNotNull(harness.messageRepository.getMessagesForChatOnce(harness.chatId)
            .singleOrNull { it.speakerType == SpeakerType.CHARACTER })
        assertEquals(npc.npcId, StoryResponseCodec.decode(stored.content)?.segments?.last()?.speakerId)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun naturalMultiSpeakerReplyCreatesNamedNpcWithoutTerminalMetadataOrSecondRequest() = runBlocking {
        val provider = SequentialResponseProvider(listOf(
            "Character (Rowan): \"Is the path open?\"\n" +
                "Mountain Shaper: *The adeptus shakes his head.* \"Not yet.\""
        ))
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL,
            narratorEnabled = true, npcBankEnabledInitially = true
        )
        harness.viewModel.onDraftChanged("Ask the adeptus about the mountain road.")
        harness.viewModel.sendMessage()
        val messages = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }
        val saved = requireNotNull(StoryResponseCodec.decode(messages.last().content))
        assertEquals(listOf(StorySegmentType.CHARACTER, StorySegmentType.NPC),
            saved.segments.map { it.type })
        val bank = harness.npcRepository.getProjectedDossiers(harness.chatId)
        assertEquals(1, bank.size)
        assertEquals("Mountain Shaper", bank.single().displayName)
        assertEquals(bank.single().npcId, saved.segments.last().speakerId)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun explicitPersonaSpeakerCannotBeAutoSavedAsAnNpcInSoloEnsemble() = runBlocking {
        val provider = SequentialResponseProvider(listOf(
            "Character (Rowan): \"Do you recognize the sigil?\"\n" +
                "Mara: \"Yes, it belongs to the old king.\""
        ))
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL,
            narratorEnabled = true, npcBankEnabledInitially = true
        )
        harness.viewModel.onDraftChanged("Look at the sigil.")
        harness.viewModel.sendMessage()
        val stopped = withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating &&
                    state.generationError.orEmpty().contains(
                        "explicit turn for your Persona"
                    )
            }
        } as ChatUiState.Success
        assertTrue(stopped.partialStopped)
        assertTrue(stopped.generationError.orEmpty().contains("not saved"))
        val persisted = harness.messageRepository.getMessagesForChatOnce(harness.chatId)
        assertEquals(1, persisted.size)
        assertEquals(SpeakerType.PERSONA, persisted.single().speakerType)
        assertTrue(harness.npcRepository.getProjectedDossiers(harness.chatId).isEmpty())
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun modelAttributedNaturalNpcDialogueCreatesBankEntryWithoutMetadata() = runBlocking {
        val provider = SequentialResponseProvider(listOf(
            "*The courtyard gates shake as the adeptus approaches.*\n" +
                "\"The mountain road is closed,\" Mountain Shaper warned. " +
                "Rowan looked toward the gate. \"We should find another way.\""
        ))
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL,
            narratorEnabled = true, npcBankEnabledInitially = true
        )
        harness.viewModel.onDraftChanged("Ask about the mountain road.")
        harness.viewModel.sendMessage()
        val messages = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }
        assertEquals(1, provider.requests.size)
        val npc = harness.npcRepository.getProjectedDossiers(harness.chatId)
        assertEquals(listOf("Mountain Shaper"), npc.map { it.displayName })
        assertFalse(npc.single().isManualBase)
        assertTrue(messages.last().content.contains("mountain road is closed"))
    }

    @Test
    fun explicitItalicPersonaActionIsHeldForReviewWithoutCreatingExtraTurn() = runBlocking {
        val provider = SequentialResponseProvider(listOf(
            "*Mara takes the sigil from Rowan.*\n" +
                "Rowan: \"No, leave that where it is.\""
        ))
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL,
            narratorEnabled = true, npcBankEnabledInitially = true
        )
        harness.viewModel.onDraftChanged("Examine the sigil.")
        harness.viewModel.sendMessage()
        val state = withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating &&
                    state.generationError.orEmpty().contains("explicit turn for your Persona")
            }
        } as ChatUiState.Success
        assertTrue(state.partialStopped)
        assertEquals(1, harness.messageRepository.getMessagesForChatOnce(harness.chatId).size)
        assertTrue(harness.npcRepository.getProjectedDossiers(harness.chatId).isEmpty())
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun malformedNarratedOutputIsSavedWithoutExposingControlMarkers() = runBlocking {
        val provider = SequentialResponseProvider(
            listOf(
                """
                    <|elsewhere_story_v1|>
                    <|segment|>{broken}
                    The floorboard creaks behind Rowan.
                    <|segment|>{"type":"character"}
                    *He turns.* "Who's there?"
                """.trimIndent()
            )
        )
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL, narratorEnabled = true)

        harness.viewModel.onDraftChanged("Listen.")
        harness.viewModel.sendMessage()
        val completed = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }

        val visible = StoryResponseCodec.visibleText(completed.last().content)
        assertTrue(visible.contains("The floorboard creaks behind Rowan."))
        assertTrue(visible.contains("Who's there?"))
        assertFalse(visible.contains("<|"))
        assertFalse(visible.contains("broken"))
        assertEquals(1, provider.requests.size)
        val savedWithWarning = withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    !state.isGenerating &&
                    state.generationWarning != null &&
                    state.messages.any { it.messageId == completed.last().messageId }
            }
        } as ChatUiState.Success
        assertNull(savedWithWarning.generationError)
        assertTrue(savedWithWarning.generationWarning.orEmpty().isNotBlank())
    }

    @Test
    fun structuredRegenerationCreatesOneCompleteVariantPerRequest() = runBlocking {
        fun response(line: String) = """
            <|elsewhere_story_v1|>
            <|segment|>{"type":"narration"}
            The room stills.
            <|segment|>{"type":"character"}
            $line
            <|end_story|>
        """.trimIndent()
        val provider = SequentialResponseProvider(listOf(response("First reply."), response("Second reply.")))
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL, narratorEnabled = true)

        harness.viewModel.onDraftChanged("Respond.")
        harness.viewModel.sendMessage()
        val first = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }.last()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating &&
                    state.messages.any { it.messageId == first.messageId }
            }
        }
        yield()
        harness.viewModel.regenerateMessage(first.messageId)
        val variants = withTimeout(15_000) {
            harness.messageRepository.getVariants(first.variantGroupId).first { it.size == 2 }
        }

        assertEquals(2, provider.requests.size)
        assertEquals(1, variants.count { it.isPrimaryVariant })
        assertTrue(variants.all { StoryResponseCodec.decode(it.content)?.segments?.size == 2 })
        assertTrue(
            StoryResponseCodec.visibleText(variants.single { it.isPrimaryVariant }.content)
                .contains("Second reply.")
        )
    }

    @Test
    fun structuredContinueAppendsToTheSameLogicalResponseWithoutRepeatingSegments() = runBlocking {
        val provider = SequentialResponseProvider(
            listOf(
                """
                    <|elsewhere_story_v1|>
                    <|segment|>{"type":"narration"}
                    The room stills.
                    <|segment|>{"type":"character"}
                    "Wait here."
                    <|end_story|>
                """.trimIndent(),
                """
                    <|elsewhere_story_v1|>
                    <|segment|>{"type":"npc","speaker_name":"The Porter"}
                    "The carriage is ready."
                    <|segment|>{"type":"narration"}
                    Rain beads on the open door.
                    <|end_story|>
                """.trimIndent()
            )
        )
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL, narratorEnabled = true)

        harness.viewModel.onDraftChanged("Tell me what happens.")
        harness.viewModel.sendMessage()
        val original = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }.last()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating &&
                    state.messages.any { it.messageId == original.messageId }
            }
        }
        yield()

        harness.viewModel.continueMessage(original.messageId)
        val continued = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.singleOrNull { it.messageId == original.messageId }
                    ?.content
                    ?.let(StoryResponseCodec::decode)
                    ?.segments
                    ?.size == 4
            }
        }.single { it.messageId == original.messageId }

        assertEquals(2, provider.requests.size)
        assertEquals(2, harness.messageRepository.getMessagesForChatOnce(harness.chatId).size)
        val story = requireNotNull(StoryResponseCodec.decode(continued.content))
        assertEquals(
            listOf(
                StorySegmentType.NARRATION,
                StorySegmentType.CHARACTER,
                StorySegmentType.NPC,
                StorySegmentType.NARRATION
            ),
            story.segments.map { it.type }
        )
        assertEquals(1, story.segments.count { it.content == "The room stills." })
        val continuationPrompt = provider.requests.last().joinToString("\n") { it.content }
        assertTrue(continuationPrompt.contains(StoryOutputParser.START_MARKER))
        assertTrue(continuationPrompt.contains("The room stills."))
        assertTrue(continuationPrompt.contains("\"Wait here.\""))
        assertFalse(continuationPrompt.contains("Character (Rowan):"))
        assertFalse(continuationPrompt.contains("Narrator: The room stills."))
    }

    @Test
    fun incompleteStructuredStreamCanBeKeptAsOrderedPartialStory() = runBlocking {
        val provider = CancellablePartialProvider(
            """
                <|elsewhere_story_v1|>
                <|segment|>{"type":"narration"}
                A shadow crosses the doorway.
                <|segment|>{"type":"character"}
                *Rowan reaches for the lamp.*
            """.trimIndent()
        )
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL, narratorEnabled = true)

        harness.viewModel.onDraftChanged("What moved?")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.isGenerating &&
                    StoryResponseCodec.decode(state.streamingContent.orEmpty())?.segments?.size == 2
            }
        }
        harness.viewModel.stopGeneration()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.partialStopped }
        }
        harness.viewModel.keepPartial()
        val saved = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 2 }
        }.last()

        assertEquals(1, provider.requestCount)
        assertEquals(
            listOf(StorySegmentType.NARRATION, StorySegmentType.CHARACTER),
            requireNotNull(StoryResponseCodec.decode(saved.content)).segments.map { it.type }
        )
    }

    @Test
    fun persistedPreferEndpointIsConsumedWithFallbackEnabled() = runBlocking {
        val provider = RecordingStreamingProvider()
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            routingMode = ProviderRoutingMode.PREFER,
            providerEndpoint = "provider-a"
        )

        harness.viewModel.onDraftChanged("Use the preferred provider.")
        harness.viewModel.sendMessage()

        withTimeout(15_000) {
            harness.viewModel.uiState.first { provider.requests.size == 1 }
        }

        assertEquals(ProviderRoutingMode.PREFER, provider.options.routing.mode)
        assertEquals(listOf("provider-a"), provider.options.routing.preferredEndpoints)
        assertTrue(provider.options.routing.allowFallback)
    }

    @Test
    fun persistedLockEndpointIsConsumedWithFallbackDisabled() = runBlocking {
        val provider = RecordingStreamingProvider()
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            routingMode = ProviderRoutingMode.LOCK,
            providerEndpoint = "provider-b"
        )

        harness.viewModel.onDraftChanged("Use only the locked provider.")
        harness.viewModel.sendMessage()

        withTimeout(15_000) {
            harness.viewModel.uiState.first { provider.requests.size == 1 }
        }

        assertEquals(ProviderRoutingMode.LOCK, provider.options.routing.mode)
        assertEquals(listOf("provider-b"), provider.options.routing.preferredEndpoints)
        assertFalse(provider.options.routing.allowFallback)
    }

    @Test
    fun lockWithoutSelectedEndpointFailsLocallyBeforeCallingProvider() = runBlocking {
        val provider = CountingProvider()
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            routingMode = ProviderRoutingMode.LOCK,
            providerEndpoint = null
        )

        harness.viewModel.onDraftChanged("Use the locked provider.")
        harness.viewModel.sendMessage()

        val failedState = withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.generationError == "Locked routing requires a selected provider endpoint."
            }
        } as ChatUiState.Success

        assertEquals("Locked routing requires a selected provider endpoint.", failedState.generationError)
        assertEquals(0, provider.requestCount)
        assertEquals(
            listOf(SpeakerType.PERSONA),
            harness.messageRepository.getMessagesForChat(harness.chatId).first().map { it.speakerType }
        )
    }

    @Test
    fun loadedHistoryPublishesTheSelectedPrimaryVariantForInitialPositioning() = runBlocking {
        val harness = createHarness(CountingProvider(), ResponseLengthProfile.NORMAL)
        val original = MessageEntity(
            messageId = "original-variant",
            chatId = harness.chatId,
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character-1",
            speakerDisplayNameSnapshot = "Rowan",
            content = "Original response",
            orderIndex = 1L,
            variantGroupId = "response-group",
            isPrimaryVariant = false
        )
        val selected = original.copy(
            messageId = "selected-variant",
            content = "Selected response",
            createdAt = original.createdAt + 1,
            isPrimaryVariant = true
        )

        harness.messageRepository.insertMessage(original)
        harness.messageRepository.insertMessage(selected)

        val loaded = withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    state.historyLoaded &&
                    state.groupedMessages.singleOrNull()?.primaryMessage?.messageId == selected.messageId
            }
        } as ChatUiState.Success

        assertTrue(loaded.historyLoaded)
        assertEquals(1, loaded.groupedMessages.size)
        assertEquals("selected-variant", loaded.groupedMessages.single().primaryMessage.messageId)
        assertEquals("Selected response", loaded.groupedMessages.single().primaryMessage.content)
    }

    @Test
    fun chatViewModelPagesOlderCompleteMessageGroupsWithoutProviderCalls() = runBlocking {
        val provider = CountingProvider()
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.historyLoaded
            }
        }
        database.withTransaction {
            for (index in 1..250) {
                database.messageDao().insertMessage(
                    MessageEntity(
                        messageId = "paged-turn-$index",
                        chatId = harness.chatId,
                        speakerType = if (index % 2 == 0)
                            SpeakerType.PERSONA else SpeakerType.CHARACTER,
                        speakerId = if (index % 2 == 0) "persona-1" else "character-1",
                        speakerDisplayNameSnapshot = if (index % 2 == 0) "Mara" else "Rowan",
                        content = "History event $index",
                        orderIndex = index.toLong()
                    )
                )
            }
            database.messageDao().insertMessage(
                MessageEntity(
                    messageId = "paged-older-alternate",
                    chatId = harness.chatId,
                    speakerType = SpeakerType.CHARACTER,
                    speakerId = "character-1",
                    speakerDisplayNameSnapshot = "Rowan",
                    content = "Discarded alternative event 49",
                    orderIndex = 49L,
                    variantGroupId = "paged-turn-49",
                    isPrimaryVariant = false
                )
            )
        }

        val newest = withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.historyLoaded &&
                    state.groupedMessages.size == 100 && state.hasEarlierHistory &&
                    !state.isLoadingEarlierHistory &&
                    state.groupedMessages.last().primaryMessage.messageId == "paged-turn-250"
            }
        } as ChatUiState.Success
        assertEquals("paged-turn-151", newest.groupedMessages.first().primaryMessage.messageId)
        assertEquals(100, newest.messages.size)

        // Image attachments must follow the visible chat page while draft
        // images stay available independently of historical scroll position.
        val testImageDir = java.io.File(context.filesDir, "message_attachments")
            .apply { mkdirs() }
        val metadata = listOf(
            "visible-image" to "paged-turn-240",
            "older-image" to "paged-turn-49",
            "alternate-image" to "paged-older-alternate",
            "pending-draft-image" to null
        )
        metadata.forEach { (id, messageId) ->
            java.io.File(testImageDir, "$id.png").writeBytes(byteArrayOf(1, 2, 3))
            database.messageAttachmentDao().insert(
                MessageAttachmentEntity(
                    attachmentId = id,
                    chatId = harness.chatId,
                    messageId = messageId,
                    relativePath = "message_attachments/$id.png",
                    mimeType = "image/png",
                    byteSize = 3,
                    pixelWidth = 1,
                    pixelHeight = 1,
                    sha256 = "synthetic-$id",
                    sortPosition = 0
                )
            )
        }
        val initialImages = withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    state.attachments.map { it.attachmentId } == listOf("visible-image") &&
                    state.draftAttachments.map { it.attachmentId } ==
                        listOf("pending-draft-image")
            }
        } as ChatUiState.Success
        assertEquals(1, initialImages.attachments.size)
        assertEquals(4, database.messageAttachmentDao().getForChat(harness.chatId).size)

        harness.viewModel.loadEarlierMessages()
        val second = withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.groupedMessages.size == 200 &&
                    !state.isLoadingEarlierHistory
            }
        } as ChatUiState.Success
        assertTrue(second.hasEarlierHistory)
        assertEquals("paged-turn-51", second.groupedMessages.first().primaryMessage.messageId)

        assertEquals(listOf("visible-image"),
            second.attachments.map { it.attachmentId })
        assertEquals(listOf("pending-draft-image"),
            second.draftAttachments.map { it.attachmentId })

        // A new Character reply while reading older turns must not evict
        // the oldest loaded group or pull the reader back to the bottom.
        harness.messageRepository.insertMessage(
            MessageEntity(
                messageId = "paged-turn-251",
                chatId = harness.chatId,
                speakerType = SpeakerType.CHARACTER,
                speakerId = "character-1",
                speakerDisplayNameSnapshot = "Rowan",
                content = "An incoming scene while scrolling",
                orderIndex = 251L
            )
        )
        val preserved = withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    state.groupedMessages.size == 201 &&
                    state.groupedMessages.first().primaryMessage.messageId == "paged-turn-51" &&
                    state.groupedMessages.last().primaryMessage.messageId == "paged-turn-251" &&
                    !state.isLoadingEarlierHistory
            }
        } as ChatUiState.Success
        assertTrue(preserved.hasEarlierHistory)

        harness.viewModel.loadEarlierMessages()
        val all = withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.groupedMessages.size == 251 &&
                    !state.hasEarlierHistory && !state.isLoadingEarlierHistory
            }
        } as ChatUiState.Success
        assertEquals("paged-turn-1", all.groupedMessages.first().primaryMessage.messageId)
        assertEquals("paged-turn-251", all.groupedMessages.last().primaryMessage.messageId)
        assertEquals(252, all.messages.size)
        assertEquals(
            setOf("paged-turn-49", "paged-older-alternate"),
            all.groupedMessages.single {
                it.primaryMessage.messageId == "paged-turn-49"
            }.variants.map { it.messageId }.toSet()
        )
        val allImages = withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    state.groupedMessages.size == 251 &&
                    state.attachments.map { it.attachmentId }.toSet() ==
                        setOf("visible-image", "older-image", "alternate-image") &&
                    state.draftAttachments.map { it.attachmentId } ==
                        listOf("pending-draft-image")
            }
        } as ChatUiState.Success
        assertEquals(3, allImages.attachments.size)

        // Returning to the latest reply releases the hydrated older pages,
        // but those groups and variants remain retrievable from Room.
        harness.viewModel.returnToLatestMessages()
        val collapsed = withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.groupedMessages.size == 100 &&
                    state.groupedMessages.first().primaryMessage.messageId == "paged-turn-152" &&
                    state.groupedMessages.last().primaryMessage.messageId == "paged-turn-251" &&
                    state.hasEarlierHistory && !state.isLoadingEarlierHistory
            }
        } as ChatUiState.Success
        assertEquals(100, collapsed.messages.size)
        val latestImages = withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success &&
                    state.groupedMessages.size == 100 &&
                    state.attachments.map { it.attachmentId } == listOf("visible-image") &&
                    state.draftAttachments.map { it.attachmentId } ==
                        listOf("pending-draft-image")
            }
        } as ChatUiState.Success
        assertEquals(1, latestImages.attachments.size)
        assertEquals(4, database.messageAttachmentDao().getForChat(harness.chatId).size)
        assertEquals(252,
            harness.messageRepository.getMessagesForChatOnce(harness.chatId).size)
        assertEquals(0, provider.requestCount)
    }

    @Test
    fun retryAndRegenerateReuseOriginImageWhileContinueDoesNotResendIt() = runBlocking {
        val provider = RequestSequenceProvider(
            listOf(
                listOf(StreamEvent.Error(IllegalStateException("mock failure"))),
                listOf(StreamEvent.Content("Original image reply."), StreamEvent.Done(null)),
                listOf(StreamEvent.Content("Regenerated image reply."), StreamEvent.Done(null)),
                listOf(StreamEvent.Content(" Continued."), StreamEvent.Done(null))
            )
        )
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        val source = java.io.File(context.cacheDir, "view-model-image.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        harness.viewModel.addDraftImage(Uri.fromFile(source))
        withTimeout(30_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.draftAttachments.size == 1 }
        }

        harness.viewModel.onDraftChanged("What do you see?")
        harness.viewModel.sendMessage()
        withTimeout(30_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && !it.isGenerating && it.generationError == "mock failure"
            }
        }
        harness.viewModel.retryGeneration()
        val original = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.content == "Original image reply." }
            }
        }.single { it.content == "Original image reply." }
        // The saved Room row may precede the asynchronous history collector.
        // A user can only tap Regenerate once the response bubble is visible.
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating &&
                    state.messages.any { it.messageId == original.messageId }
            }
        }

        harness.viewModel.regenerateMessage(original.messageId)
        val regenerated = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.content == "Regenerated image reply." && it.isPrimaryVariant }
            }
        }.single { it.content == "Regenerated image reply." }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating &&
                    state.messages.any {
                        it.messageId == regenerated.messageId && it.isPrimaryVariant
                    }
            }
        }

        harness.viewModel.continueMessage(regenerated.messageId)
        withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.messageId == regenerated.messageId && it.content == "Regenerated image reply. Continued." }
            }
        }

        assertEquals(4, provider.requests.size)
        val firstHash = provider.requests.first().single { it.images.isNotEmpty() }.images.single().sha256
        provider.requests.take(3).forEach { request ->
            val imageMessage = request.single { it.sourceMessageId != null && it.role == Role.USER }
            assertEquals(1, imageMessage.images.size)
            assertEquals(firstHash, imageMessage.images.single().sha256)
        }
        assertTrue(provider.requests.last().all { it.images.isEmpty() })
        val personaMessage = harness.messageRepository.getMessagesForChatOnce(harness.chatId)
            .single { it.speakerType == SpeakerType.PERSONA }
        assertEquals(1, harness.attachmentRepository.getForMessage(personaMessage.messageId).size)
        source.delete()
        Unit
    }

    @Test
    fun regeneratingOlderResponseCompilesNpcContextOnlyFromBeforeTargetTurn() = runBlocking {
        fun reply(goal: String) = "Story $goal\n<elsewhere_npc_updates>" +
            "{\"schema_version\":1,\"updates\":[{\"npc_id\":\"npc\",\"goals\":\"$goal\"}]}" +
            "</elsewhere_npc_updates>"
        val provider = SequentialResponseProvider(listOf(reply("PAST_STATE"), reply("FUTURE_STATE"), "Regenerated safely."))
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        harness.npcRepository.saveManual(
            com.example.data.model.NpcDossier(
                npcId = "npc",
                chatId = harness.chatId,
                displayName = "Mara",
                aliases = emptyList(),
                role = "Guide",
                appearance = "",
                personality = "",
                relationshipToPersona = "",
                personaRelationships = emptyMap(),
                characterKnowledge = emptyMap(),
                goals = "INITIAL_STATE",
                secrets = "",
                authorNotes = "",
                keepInScene = true,
                sceneMode = "HERE",
                isManualBase = true,
                createdAt = 1,
                updatedAt = 1
            )
        )
        harness.npcRepository.setEnabled(harness.chatId, true)

        harness.viewModel.onDraftChanged("First turn")
        harness.viewModel.sendMessage()
        val firstReply = withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating && state.messages.size == 2
            }
        }.let { it as ChatUiState.Success }.messages.single { it.speakerType == SpeakerType.CHARACTER }
        harness.viewModel.onDraftChanged("Second turn")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && !state.isGenerating && state.messages.size == 4
            }
        }

        harness.viewModel.regenerateMessage(firstReply.messageId)
        withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any {
                    it.isPrimaryVariant && (
                        StoryResponseCodec.decode(it.content)?.segments?.singleOrNull()?.content
                            ?: it.content
                        ) == "Regenerated safely."
                }
            }
        }

        val regeneratedPrompt = provider.requests[2].first().content
        assertTrue(regeneratedPrompt.contains("INITIAL_STATE"))
        assertFalse(regeneratedPrompt.contains("PAST_STATE"))
        assertFalse(regeneratedPrompt.contains("FUTURE_STATE"))
    }

    @Test
    fun disabledNpcBankDoesNotParseOrApplyTerminalControlBlock() = runBlocking {
        val raw = "Story text\n<elsewhere_npc_updates>{\"schema_version\":1,\"updates\":[{\"display_name\":\"Mara\"}]}</elsewhere_npc_updates>"
        val harness = createHarness(
            ScriptedStreamingProvider(listOf(StreamEvent.Content(raw), StreamEvent.Done(null))),
            ResponseLengthProfile.NORMAL
        )
        assertFalse(harness.npcRepository.getSettings(harness.chatId).isEnabled)

        harness.viewModel.onDraftChanged("Continue.")
        harness.viewModel.sendMessage()

        val assistant = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.speakerType == SpeakerType.CHARACTER }
            }
        }.single { it.speakerType == SpeakerType.CHARACTER }
        assertEquals(raw, assistant.content)
        assertTrue(harness.npcRepository.getProjectedDossiers(harness.chatId).isEmpty())
    }

    @Test
    fun knownTextOnlyModelBlocksImageGenerationBeforeProviderRequest() = runBlocking {
        val provider = TextOnlyProvider()
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        val source = java.io.File(context.cacheDir, "unsupported-image.png")
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        harness.viewModel.addDraftImage(Uri.fromFile(source))
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.draftAttachments.size == 1 }
        }

        harness.viewModel.onDraftChanged("Can you see this?")
        harness.viewModel.sendMessage()

        val failed = withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.generationError?.contains("does not support image input") == true
            }
        } as ChatUiState.Success
        assertTrue(failed.generationError.orEmpty().contains("vision-capable"))
        // The earlier draft-image import may finish after the newer model-capability error.
        // Its delayed callback must not erase that actionable failure.
        kotlinx.coroutines.delay(100)
        assertTrue(
            (harness.viewModel.uiState.value as? ChatUiState.Success)
                ?.generationError?.contains("does not support image input") == true
        )
        assertEquals(0, provider.requestCount)
        assertEquals(listOf(SpeakerType.PERSONA), harness.messageRepository.getMessagesForChatOnce(harness.chatId).map { it.speakerType })
        source.delete()
        Unit
    }

    @Test
    fun unknownImageCapabilityFailsClosedBeforeProviderRequest() = runBlocking {
        val provider = CountingProvider()
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        val source = java.io.File(context.cacheDir, "unknown-capability-image.png")
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        harness.viewModel.addDraftImage(Uri.fromFile(source))
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.draftAttachments.size == 1 }
        }

        harness.viewModel.onDraftChanged("Can you see this?")
        harness.viewModel.sendMessage()

        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.generationError?.contains("has not been confirmed") == true
            }
        }
        assertEquals(0, provider.requestCount)
        source.delete()
        Unit
    }

    @Test
    fun stoppedNpcStreamNeverDisplaysOrKeepsHiddenMetadata() = runBlocking {
        val hidden = "Visible story\n<elsewhere_npc_updates>{\"schema_version\":1,\"updates\":[{\"display_name\":\"Mara\",\"secrets\":\"NEVER_VISIBLE\"}]}"
        val harness = createHarness(
            ScriptedStreamingProvider(
                listOf(
                    StreamEvent.Content(hidden),
                    StreamEvent.Done(
                        GenerationMetadataEntity(
                            messageId = "provider-partial",
                            requestedModelId = "mock/model",
                            resolvedModelId = "resolved/model",
                            promptTokens = 123,
                            completionTokens = 7,
                            totalTokens = 130,
                            reportedCost = 0.004,
                            finishReason = "provider_partial"
                        )
                    ),
                    StreamEvent.Error(IllegalStateException("stopped"))
                )
            ),
            ResponseLengthProfile.NORMAL
        )
        harness.npcRepository.setEnabled(harness.chatId, true)
        harness.viewModel.onDraftChanged("Continue.")
        harness.viewModel.sendMessage()

        val stopped = withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.partialStopped }
        } as ChatUiState.Success
        assertEquals("Visible story", stopped.streamingContent)
        assertFalse(stopped.streamingContent.orEmpty().contains("NEVER_VISIBLE"))

        harness.viewModel.keepPartial()
        val assistant = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.speakerType == SpeakerType.CHARACTER }
            }
        }.single { it.speakerType == SpeakerType.CHARACTER }
        assertEquals("Visible story", assistant.content)
        assertTrue(harness.npcRepository.getProjectedDossiers(harness.chatId).isEmpty())
        val metadata = requireNotNull(harness.messageRepository.getGenerationMetadata(assistant.messageId))
        assertEquals("mock/model", metadata.requestedModelId)
        assertEquals("resolved/model", metadata.resolvedModelId)
        assertEquals(130, metadata.totalTokens)
        assertEquals(0.004, metadata.reportedCost)
        assertEquals("provider_partial", metadata.finishReason)
        assertTrue(metadata.isPartial)
        assertFalse(metadata.wasUserStopped)
        Unit
    }

    @Test
    fun keepUserStoppedPartialPersistsExplicitStoppedMetadata() = runBlocking {
        val harness = createHarness(CancellablePartialProvider(), ResponseLengthProfile.NORMAL)
        harness.viewModel.onDraftChanged("Begin.")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.isGenerating && it.streamingContent == "Partial response"
            }
        }

        harness.viewModel.stopGeneration()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.partialStopped }
        }
        harness.viewModel.keepPartial()
        // Resolution is single-flight: rapid repeated Keep/Discard taps must be inert.
        harness.viewModel.discardPartial()
        harness.viewModel.keepPartial()
        val assistant = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.speakerType == SpeakerType.CHARACTER }
            }
        }.single { it.speakerType == SpeakerType.CHARACTER }
        val metadata = requireNotNull(harness.messageRepository.getGenerationMetadata(assistant.messageId))
        assertTrue(metadata.isPartial)
        assertTrue(metadata.wasUserStopped)
        assertEquals("user_stopped", metadata.finishReason)
        assertEquals("mock/model", metadata.requestedModelId)
    }

    @Test
    fun enabledNpcBankStripsTerminalMetadataAndAppliesIt() = runBlocking {
        val raw = "Story text\n<elsewhere_npc_updates>{\"schema_version\":1,\"updates\":[{\"display_name\":\"Mara\",\"role\":\"Archivist\"}]}</elsewhere_npc_updates>"
        val harness = createHarness(
            ScriptedStreamingProvider(listOf(StreamEvent.Content(raw), StreamEvent.Done(null))),
            ResponseLengthProfile.NORMAL
        )
        harness.npcRepository.setEnabled(harness.chatId, true)

        harness.viewModel.onDraftChanged("Continue.")
        harness.viewModel.sendMessage()

        val assistant = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.speakerType == SpeakerType.CHARACTER }
            }
        }.single { it.speakerType == SpeakerType.CHARACTER }
        assertEquals("Story text", assistant.content)
        assertEquals("Archivist", harness.npcRepository.getProjectedDossiers(harness.chatId).single().role)
    }

    @Test
    fun finishedMisspelledNpcMarkerIsNotPersistedOrAppliedToExistingNpc() = runBlocking {
        val story = "Mara, wearing ink-stained gloves and a brass chain of office, confronts Rhaien in the western stacks about the missing ledger."
        val raw = "$story\n<elsewisere_npc_updates>"
        val harness = createHarness(
            ScriptedStreamingProvider(listOf(StreamEvent.Content(raw), StreamEvent.Done(null))),
            ResponseLengthProfile.NORMAL
        )
        harness.npcRepository.setEnabled(harness.chatId, true)
        harness.npcRepository.saveManual(
            NpcDossier(
                npcId = "mara-existing",
                chatId = harness.chatId,
                displayName = "Mara Voss",
                aliases = listOf("Mara"),
                role = "Keeper of the western archive",
                appearance = "Ink-stained gloves and a brass chain of office",
                personality = "Measured",
                relationshipToPersona = "",
                personaRelationships = emptyMap(),
                characterKnowledge = emptyMap(),
                goals = "Locate the missing ledger",
                secrets = "",
                authorNotes = "",
                keepInScene = false,
                isManualBase = true,
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        harness.viewModel.onDraftChanged("Continue.")
        harness.viewModel.sendMessage()

        val assistant = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.speakerType == SpeakerType.CHARACTER }
            }
        }.single { it.speakerType == SpeakerType.CHARACTER }
        assertEquals(story, assistant.content)
        val mara = harness.npcRepository.getProjectedDossiers(harness.chatId).single()
        assertEquals("Keeper of the western archive", mara.role)
        assertEquals("Locate the missing ledger", mara.goals)
        assertTrue(database.npcDao().getUpdates(harness.chatId).isEmpty())
    }

    @Test
    fun stoppedTruncatedNpcMarkerIsNotDisplayedOrKept() = runBlocking {
        val harness = createHarness(
            CancellablePartialProvider("Visible story\n<elsewhere_npc_upd"),
            ResponseLengthProfile.NORMAL
        )
        harness.npcRepository.setEnabled(harness.chatId, true)
        harness.viewModel.onDraftChanged("Continue.")
        harness.viewModel.sendMessage()

        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.isGenerating && it.streamingContent == "Visible story"
            }
        }
        harness.viewModel.stopGeneration()
        val stopped = withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.partialStopped }
        } as ChatUiState.Success
        assertEquals("Visible story", stopped.streamingContent)

        harness.viewModel.keepPartial()
        val assistant = withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.speakerType == SpeakerType.CHARACTER }
            }
        }.single { it.speakerType == SpeakerType.CHARACTER }
        assertEquals("Visible story", assistant.content)
        assertTrue(harness.npcRepository.getProjectedDossiers(harness.chatId).isEmpty())
    }

    @Test
    fun loadingEnabledNpcChatHidesPreviouslyPersistedLeakWithoutRewritingHistory() = runBlocking {
        val story = "Mara asks Rhaien about the missing ledger."
        val raw = "$story\n<elsewisere_npc_updates>"
        val provider = RecordingStreamingProvider()
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            npcBankEnabledInitially = true,
            initialStoredCharacterContent = raw
        )

        val loaded = withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.historyLoaded &&
                    state.messages.any { it.speakerType == SpeakerType.CHARACTER && it.content == story }
            }
        } as ChatUiState.Success
        assertFalse(loaded.messages.any { it.content.contains("elsewisere_npc_updates") })
        val stored = harness.messageRepository.getMessagesForChatOnce(harness.chatId)
            .single { it.speakerType == SpeakerType.CHARACTER }
        assertEquals(raw, stored.content)
        assertTrue(harness.npcRepository.getProjectedDossiers(harness.chatId).isEmpty())
        assertTrue(database.npcDao().getUpdates(harness.chatId).isEmpty())

        harness.viewModel.onDraftChanged("Continue without repeating internal metadata.")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { provider.requests.size == 1 }
        }
        assertFalse(provider.requests.single().any { it.content.contains("elsewisere_npc_updates") })
    }

    @Test
    fun loadingDisabledNpcChatStillHidesLegacyLeakFromDisplayAndContext() = runBlocking {
        val story = "Mara asks Rhaien about the missing ledger."
        val raw = "$story\n<elsewisere_npc_updates>"
        val provider = RecordingStreamingProvider()
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            npcBankEnabledInitially = false,
            initialStoredCharacterContent = raw
        )

        val loaded = withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.historyLoaded &&
                    state.messages.any { it.speakerType == SpeakerType.CHARACTER && it.content == story }
            }
        } as ChatUiState.Success
        assertFalse(loaded.messages.any { it.content.contains("elsewisere_npc_updates") })
        assertEquals(
            raw,
            harness.messageRepository.getMessagesForChatOnce(harness.chatId)
                .single { it.speakerType == SpeakerType.CHARACTER }
                .content
        )

        harness.viewModel.onDraftChanged("Continue without repeating internal metadata.")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { provider.requests.size == 1 }
        }
        assertFalse(provider.requests.single().any { it.content.contains("elsewisere_npc_updates") })
        assertTrue(harness.npcRepository.getProjectedDossiers(harness.chatId).isEmpty())
        assertTrue(database.npcDao().getUpdates(harness.chatId).isEmpty())
    }

    @Test
    fun loadingEnabledNpcChatDoesNotRewriteOrdinaryShortLiteralPrefix() = runBlocking {
        val literal = "The inscription ends below.\n<"
        val harness = createHarness(
            provider = RecordingStreamingProvider(),
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            npcBankEnabledInitially = true,
            initialStoredCharacterContent = literal
        )

        val loaded = withTimeout(15_000) {
            harness.viewModel.uiState.first { state ->
                state is ChatUiState.Success && state.historyLoaded &&
                    state.messages.any { it.speakerType == SpeakerType.CHARACTER }
            }
        } as ChatUiState.Success
        assertEquals(literal, loaded.messages.single { it.speakerType == SpeakerType.CHARACTER }.content)
        assertEquals(
            literal,
            harness.messageRepository.getMessagesForChatOnce(harness.chatId)
                .single { it.speakerType == SpeakerType.CHARACTER }
                .content
        )
    }

    @Test
    fun wackPreservesSavedDataAndBlocksPreResetGenerationTools() = runBlocking {
        val provider = RecordingStreamingProvider()
        val harness = createHarness(
            provider = provider,
            responseLengthProfile = ResponseLengthProfile.NORMAL,
            narratorEnabled = true,
            narratorInstructions = "NARRATOR_SETTINGS_STAY"
        )
        val oldPersona = MessageEntity(
            messageId = "old-persona",
            chatId = harness.chatId,
            speakerType = SpeakerType.PERSONA,
            speakerId = "persona-1",
            speakerDisplayNameSnapshot = "Mara",
            content = "OLD_TRANSCRIPT_PERSONA",
            orderIndex = 1L
        )
        val oldCharacter = MessageEntity(
            messageId = "old-character",
            chatId = harness.chatId,
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character-1",
            speakerDisplayNameSnapshot = "Rowan",
            content = "OLD_TRANSCRIPT_CHARACTER",
            orderIndex = 2L,
            variantGroupId = "old-character-group"
        )
        val oldAlternate = oldCharacter.copy(
            messageId = "old-character-alternate",
            content = "OLD_ALTERNATE_VARIANT",
            isPrimaryVariant = false
        )
        harness.messageRepository.insertMessage(oldPersona)
        harness.messageRepository.insertMessage(oldCharacter)
        harness.messageRepository.insertMessage(oldAlternate)
        harness.memoryRepository.insertIfNew(
            RoleplayMemoryEntity(
                chatId = harness.chatId,
                characterId = "character-1",
                category = MemoryCategory.DETAIL,
                content = "DURABLE_MEMORY_STAYS"
            )
        )
        harness.memoryRepository.upsertSummary(
            ConversationSummaryEntity(harness.chatId, "OLD_ROLLING_SUMMARY", 2L)
        )
        database.lorebookDao().insertLorebook(
            LorebookEntity(lorebookId = "wack-lorebook", name = "Preserved Lorebook")
        )
        database.lorebookDao().insertEntry(
            LorebookEntryEntity(
                entryId = "wack-lore-entry",
                lorebookId = "wack-lorebook",
                title = "Preserved Lore",
                activationMode = "manual",
                currentContent = "LORE_STAYS"
            )
        )
        database.lorebookDao().upsertAttachment(
            ChatLorebookAttachmentEntity(harness.chatId, "wack-lorebook")
        )
        database.lorebookDao().upsertOverlay(
            ChatLorebookEntryOverlayEntity(
                chatId = harness.chatId,
                entryId = "wack-lore-entry",
                hasCurrentOverride = true,
                currentContent = "LORE_OVERRIDE_STAYS",
                manualActive = true
            )
        )
        database.npcDao().insertDossier(
            NpcDossierEntity(
                npcId = "wack-npc",
                chatId = harness.chatId,
                displayName = "Preserved NPC",
                role = "Archivist"
            )
        )
        database.npcDao().upsertSettings(NpcBankSettingsEntity(harness.chatId, isEnabled = true))
        database.npcDao().insertUpdates(
            listOf(
                NpcDossierUpdateEntity(
                    updateId = "wack-npc-update",
                    chatId = harness.chatId,
                    npcId = "wack-npc",
                    sourceMessageId = oldCharacter.messageId,
                    variantGroupId = oldCharacter.variantGroupId,
                    sourceOrderIndex = oldCharacter.orderIndex,
                    sourceRevision = 0,
                    fieldName = "role",
                    oldValue = null,
                    newValue = "Keeper",
                    isApplied = true
                )
            )
        )
        val preservedImage = File(
            context.filesDir,
            "elsewhere_message_images/wack-preserved-${System.nanoTime()}.png"
        ).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        database.messageAttachmentDao().insert(
            MessageAttachmentEntity(
                attachmentId = "wack-preserved-attachment",
                chatId = harness.chatId,
                messageId = oldPersona.messageId,
                relativePath = preservedImage.relativeTo(context.filesDir).invariantSeparatorsPath,
                mimeType = "image/png",
                byteSize = preservedImage.length(),
                pixelWidth = 1,
                pixelHeight = 1,
                sha256 = "preserved-image-hash",
                sortPosition = 0
            )
        )
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.messages.size == 3 }
        }

        harness.viewModel.wackContext()
        val resetState = withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success &&
                    it.inChatToolStatus?.kind == com.example.ui.screens.InChatToolStatusKind.SUCCESS
            }
        } as ChatUiState.Success

        val persistedChat = requireNotNull(harness.chatRepository.getChatSummaryById(harness.chatId)).chat
        assertTrue(requireNotNull(persistedChat.contextResetOrderIndex) >= 2L)
        assertEquals(3, harness.messageRepository.getMessagesForChatOnce(harness.chatId).size)
        assertEquals(2, harness.messageRepository.getVariantsOnce(oldCharacter.variantGroupId).size)
        assertEquals(1, harness.memoryRepository.getMemoriesOnce(harness.chatId, "character-1").size)
        assertNull(harness.memoryRepository.getSummaryOnce(harness.chatId))
        assertEquals(3, resetState.messages.size)
        val preservedSettings = requireNotNull(harness.chatRepository.getChatSettings(harness.chatId))
        assertEquals("mock/model", preservedSettings.selectedModelId)
        assertTrue(preservedSettings.narratorEnabled)
        assertEquals("NARRATOR_SETTINGS_STAY", preservedSettings.narratorInstructions)
        assertEquals("wack-lorebook", database.lorebookDao().getAttachments(harness.chatId).single().lorebookId)
        assertEquals(
            "LORE_OVERRIDE_STAYS",
            database.lorebookDao().getOverlays(harness.chatId).single().currentContent
        )
        assertEquals("Preserved NPC", database.npcDao().getDossiers(harness.chatId).single().displayName)
        assertTrue(requireNotNull(database.npcDao().getSettings(harness.chatId)).isEnabled)
        assertEquals("wack-npc-update", database.npcDao().getUpdates(harness.chatId).single().updateId)
        assertEquals(
            "wack-preserved-attachment",
            harness.attachmentRepository.getForChat(harness.chatId).single().attachmentId
        )
        assertTrue(preservedImage.isFile)

        harness.viewModel.retryGeneration()
        harness.viewModel.regenerateMessage(oldCharacter.messageId)
        harness.viewModel.continueMessage(oldCharacter.messageId)
        assertTrue(provider.requests.isEmpty())

        harness.viewModel.onDraftChanged("POST_RESET_PERSONA")
        harness.viewModel.sendMessage()
        withTimeout(15_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { it.size == 5 }
        }
        val postResetMessages = harness.messageRepository.getMessagesForChatOnce(harness.chatId)
            .filter { it.messageId !in setOf(oldPersona.messageId, oldCharacter.messageId, oldAlternate.messageId) }
        assertTrue(postResetMessages.all { it.orderIndex > requireNotNull(persistedChat.contextResetOrderIndex) })
        val requestText = provider.requests.single().joinToString("\n") { it.content }
        assertFalse(requestText.contains("OLD_TRANSCRIPT_PERSONA"))
        assertFalse(requestText.contains("OLD_TRANSCRIPT_CHARACTER"))
        assertFalse(requestText.contains("OLD_ALTERNATE_VARIANT"))
        assertFalse(requestText.contains("OLD_ROLLING_SUMMARY"))
        // Wack keeps the historical durable-memory row in Room, but Stage 1
        // cannot let an origin-unknown legacy fact re-enter generation without
        // an explicit, separately audited return grant.
        assertFalse(requestText.contains("DURABLE_MEMORY_STAYS"))
        assertTrue(requestText.contains("POST_RESET_PERSONA"))
        preservedImage.delete()
        Unit
    }

    @Test
    fun updateMemoryNowInSoloUsesOnlyPostWackMessagesWithoutProviderCall() = runBlocking {
        val provider = CountingProvider()
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        harness.messageRepository.insertMessage(
            MessageEntity(
                messageId = "solo-pre-reset",
                chatId = harness.chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = "persona-1",
                speakerDisplayNameSnapshot = "Mara",
                content = "I prefer old tea.",
                orderIndex = 1L
            )
        )
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.messages.size == 1 }
        }

        harness.viewModel.wackContext()
        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.inChatToolStatus?.kind == com.example.ui.screens.InChatToolStatusKind.SUCCESS
            }
        }
        val boundary = requireNotNull(
            harness.chatRepository.getChatSummaryById(harness.chatId)?.chat?.contextResetOrderIndex
        )
        harness.messageRepository.insertMessage(
            MessageEntity(
                messageId = "solo-post-reset",
                chatId = harness.chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = "persona-1",
                speakerDisplayNameSnapshot = "Mara",
                content = "I prefer new tea.",
                orderIndex = boundary + 1L
            )
        )
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.messages.size == 2 }
        }

        harness.viewModel.updateMemoryNow()
        val status = withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.inChatToolStatus?.message?.contains("saved 1 new durable memory") == true
            }
        } as ChatUiState.Success
        val memories = harness.memoryRepository.getMemoriesOnce(harness.chatId, "character-1")

        assertTrue(status.inChatToolStatus!!.message.contains("Rowan"))
        assertEquals(listOf("new tea"), memories.map { it.content })
        assertEquals(0, provider.requestCount)
    }

    @Test
    fun updateMemoryNowInGroupUpdatesOnlySelectedCharacterAndReportsDeduplication() = runBlocking {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi, dataCoordinator = dataCoordinator)
        val personaRepository = PersonaRepository(database.personaDao(), moshi, dataCoordinator = dataCoordinator)
        val chatRepository = ChatRepository(database.chatDao(), dataCoordinator = dataCoordinator)
        val messageRepository = MessageRepository(database.messageDao(), dataCoordinator)
        val memoryRepository = MemoryRepository(database.memoryDao(), dataCoordinator)
        val provider = CountingProvider()
        database.characterDao().insertCharacter(character())
        database.characterDao().insertCharacter(
            character().copy(characterId = "character-2", displayName = "Iris", characterName = "Iris")
        )
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createGroupChat(
            displayName = "Memory Group",
            personaIds = listOf("persona-1"),
            characterIds = listOf("character-1", "character-2")
        )
        chatRepository.insertChatSettings(ChatSettingsEntity(chatId = chatId, selectedModelId = "mock/model"))
        messageRepository.insertMessage(
            MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = "persona-1",
                speakerDisplayNameSnapshot = "Mara",
                content = "I prefer mint tea.",
                orderIndex = 1L
            )
        )
        val viewModel = viewModels.create(ChatDetailViewModel::class.java) {
            ChatDetailViewModel(
                chatId,
                chatRepository,
                messageRepository,
                characterRepository,
                personaRepository,
                provider,
                memoryRepository,
                dataCoordinator = dataCoordinator
            )
        }
        // The roster and transcript hydrate through separate Room flows.
        // Group readiness alone does not authorize Update Memory Now:
        // beginInChatTool deliberately rejects the action until historyLoaded.
        withTimeout(30_000) {
            viewModel.uiState.first {
                it is ChatUiState.Success && it.groupParticipantsLoaded &&
                    it.historyLoaded && it.groupCharacters.any { participant ->
                        participant.isActive && participant.character.characterId == "character-2"
                    } && it.messages.any { message ->
                        message.speakerType == SpeakerType.PERSONA &&
                            message.content == "I prefer mint tea."
                    }
            }
        }
        viewModel.selectGroupCharacter("character-2")
        assertEquals(
            "character-2",
            (viewModel.uiState.value as ChatUiState.Success).selectedGroupCharacterId
        )
        viewModel.updateMemoryNow()
        val firstStatus = withTimeout(15_000) {
            viewModel.uiState.first {
                it is ChatUiState.Success && it.inChatToolStatus?.message?.contains("saved 1 new durable memory") == true
            }
        } as ChatUiState.Success

        assertTrue(firstStatus.inChatToolStatus!!.message.contains("Iris"))
        assertTrue(memoryRepository.getMemoriesOnce(chatId, "character-1").isEmpty())
        assertEquals("mint tea", memoryRepository.getMemoriesOnce(chatId, "character-2").single().content)
        assertEquals(0, provider.requestCount)

        viewModel.updateMemoryNow()
        val secondStatus = withTimeout(15_000) {
            viewModel.uiState.first {
                it is ChatUiState.Success && it.inChatToolStatus?.message?.contains("No new durable memory was saved") == true
            }
        } as ChatUiState.Success
        assertTrue(secondStatus.inChatToolStatus!!.message.contains("Iris"))
        assertEquals(1, memoryRepository.getMemoriesOnce(chatId, "character-2").size)
    }

    @Test
    fun inChatToolsAreBlockedDuringGenerationAndUnresolvedPartialReply() = runBlocking {
        val harness = createHarness(CancellablePartialProvider(), ResponseLengthProfile.NORMAL)
        harness.viewModel.onDraftChanged("Start a long response.")
        harness.viewModel.sendMessage()
        // This test verifies the generation guard, not the earlier history-loading guard.
        // Both are valid reasons to block tools; wait for the intended state before asserting
        // the exact warning instead of depending on Room's initial history emission timing.
        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.historyLoaded && it.isGenerating
            }
        }

        harness.viewModel.wackContext()
        val generationBlocked = harness.viewModel.uiState.value as ChatUiState.Success
        assertTrue(generationBlocked.inChatToolStatus?.message?.contains("generation to finish") == true)
        assertNull(harness.chatRepository.getChatSummaryById(harness.chatId)?.chat?.contextResetOrderIndex)

        harness.viewModel.stopGeneration()
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.partialStopped }
        }
        harness.viewModel.updateMemoryNow()
        val partialBlocked = harness.viewModel.uiState.value as ChatUiState.Success
        assertTrue(partialBlocked.inChatToolStatus?.message?.contains("unresolved partial reply") == true)
        harness.viewModel.discardPartial()
    }

    @Test
    fun linkedExistingCharacterActsAsSupportingNpcThenCanTakeSoloSpotlight() = runBlocking {
        val provider = RecordingStreamingProvider()
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL, npcBankEnabledInitially = true
        )
        val json = """{"schema_version":1,"character_id":"secondary",
            "display_name":"Captain Varka","character_name":"Varka",
            "aliases":["Captain"],
            "personality":{"summary":"LINKED_SUPPORT_PERSONALITY"},
            "voice":{"tone":"LINKED_SUPPORT_VOICE"},
            "knowledge":{"private":"LINKED_FORBIDDEN_KNOWLEDGE"},
            "author_notes":{"private":"LINKED_FORBIDDEN_NOTE"}}"""
        val characters = CharacterRepository(
            database.characterDao(),
            Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
            dataCoordinator = dataCoordinator
        )
        characters.importCharacterJson(json)
        val newNpcId = harness.npcRepository.addExistingCharacterAsNpc(
            harness.chatId, "secondary"
        )
        harness.npcRepository.setSceneMode(newNpcId, com.example.data.model.NpcSceneMode.HERE)
        harness.viewModel.onDraftChanged("Captain, please give us a clue.")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 2) { provider.requests.size }
        assertEquals("character-1", harness.messageRepository
            .getMessagesForChatOnce(harness.chatId).last().speakerId)
        val firstPrompt = provider.requests.last().first { it.role == Role.SYSTEM }.content
        assertTrue(firstPrompt.contains("LINKED CHARACTER ID: secondary"))
        assertTrue(firstPrompt.contains("LINKED_SUPPORT_PERSONALITY"))
        assertTrue(firstPrompt.contains("LINKED_SUPPORT_VOICE"))
        assertFalse(firstPrompt.contains("LINKED_FORBIDDEN_KNOWLEDGE"))
        assertFalse(firstPrompt.contains("LINKED_FORBIDDEN_NOTE"))

        harness.chatRepository.selectSoloSpotlight(harness.chatId, "secondary")
        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.selectedSoloSpotlightId == "secondary"
            }
        }
        harness.viewModel.onDraftChanged("Captain, this is your turn.")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 4) { provider.requests.size }
        val secondPrompt = provider.requests.last().first { it.role == Role.SYSTEM }.content
        assertTrue(secondPrompt.contains("LINKED_SUPPORT_PERSONALITY"))
        assertFalse(secondPrompt.contains("[NPC id=$newNpcId]"))
        assertEquals("secondary", harness.messageRepository
            .getMessagesForChatOnce(harness.chatId).last().speakerId)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun promotedNpcSpotlightSwitchesRealProviderResponderWithoutRestartingStory() = runBlocking {
        val provider = RecordingStreamingProvider()
        val harness = createHarness(
            provider, ResponseLengthProfile.NORMAL, npcBankEnabledInitially = true
        )
        val npcId = "stable-promoted-npc"
        harness.npcRepository.saveManual(
            NpcDossier(
                npcId = npcId, chatId = harness.chatId,
                displayName = "Mara the Archivist",
                aliases = listOf("Archivist"),
                role = "Keeper of the passage",
                appearance = "Copper braided hair",
                personality = "PROMOTED_PERSONALITY_SENTINEL",
                relationshipToPersona = "PROMOTED_RELATIONSHIP_PRIVATE",
                personaRelationships = emptyMap(),
                characterKnowledge = emptyMap(),
                goals = "PROMOTED_GOAL_SENTINEL",
                secrets = "PROMOTED_SECRET_MUST_NOT_BECOME_GLOBAL",
                authorNotes = "PROMOTED_PRIVATE_AUTHOR_NOTE",
                keepInScene = true,
                isManualBase = true,
                createdAt = 1L,
                updatedAt = 1L,
                sceneMode = "HERE"
            )
        )
        val promoted = harness.npcRepository.promoteNpcToCharacter(harness.chatId, npcId)
        assertEquals(npcId, promoted.characterId)
        assertTrue(promoted.created)

        harness.viewModel.onDraftChanged("Original character responds first.")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 2) { provider.requests.size }
        assertEquals("character-1",
            harness.messageRepository.getMessagesForChatOnce(harness.chatId).last().speakerId)

        harness.chatRepository.selectSoloSpotlight(harness.chatId, npcId)
        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success && it.selectedSoloSpotlightId == npcId
            }
        }
        harness.viewModel.onDraftChanged("Archivist, please answer.")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 4) { provider.requests.size }
        val selectedPrompt = provider.requests.last().first { it.role == Role.SYSTEM }.content
        assertTrue(selectedPrompt.contains("PROMOTED_PERSONALITY_SENTINEL"))
        assertTrue(selectedPrompt.contains("PROMOTED_GOAL_SENTINEL"))
        assertFalse(selectedPrompt.contains("PROMOTED_PRIVATE_AUTHOR_NOTE"))
        // Its own chat-local relationship is allowed in this turn, but is NOT
        // exported as globally reusable Character knowledge.
        assertTrue(selectedPrompt.contains("PROMOTED_RELATIONSHIP_PRIVATE"))
        assertFalse(selectedPrompt.contains("PROMOTED_SECRET_MUST_NOT_BECOME_GLOBAL"))
        val transcript = harness.messageRepository.getMessagesForChatOnce(harness.chatId)
        assertEquals("character-1", transcript[1].speakerId)
        assertEquals(npcId, transcript[3].speakerId)
        assertEquals(2, provider.requests.size)

        harness.chatRepository.selectSoloSpotlight(harness.chatId, "character-1")
        withTimeout(15_000) {
            harness.viewModel.uiState.first {
                it is ChatUiState.Success &&
                    it.selectedSoloSpotlightId == "character-1"
            }
        }
        harness.viewModel.onDraftChanged("Original character, welcome back.")
        harness.viewModel.sendMessage()
        awaitCompletedTurn(harness.viewModel, 6) { provider.requests.size }
        assertEquals("character-1",
            harness.messageRepository.getMessagesForChatOnce(harness.chatId).last().speakerId)
        assertEquals(3, provider.requests.size)
        assertEquals(npcId,
            harness.npcRepository.getProjectedDossiers(harness.chatId).single().npcId)
    }

    @Test
    fun freshEmptyChatHydratesHistoryBeforeAnyMessageOrProviderCall() = runBlocking {
        val provider = CountingProvider()
        val harness = createHarness(provider, ResponseLengthProfile.NORMAL)
        val state = harness.viewModel.uiState.value as ChatUiState.Success
        assertTrue("A new empty chat must finish the initial history query", state.historyLoaded)
        assertTrue(state.messages.isEmpty())
        assertTrue(withTimeout(5_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first().isEmpty()
        })
        assertEquals(0, provider.requestCount)
    }

    private data class Harness(
        val chatId: String,
        val viewModel: ChatDetailViewModel,
        val chatRepository: ChatRepository,
        val messageRepository: MessageRepository,
        val memoryRepository: MemoryRepository,
        val attachmentRepository: MessageAttachmentRepository,
        val npcRepository: NpcRepository,
        val lorebookRepository: LorebookRepository
    )

    private suspend fun createHarness(
        provider: ModelProvider,
        responseLengthProfile: ResponseLengthProfile,
        routingMode: ProviderRoutingMode = ProviderRoutingMode.AUTO,
        providerEndpoint: String? = null,
        narratorEnabled: Boolean = false,
        narratorInstructions: String = "",
        npcBankEnabledInitially: Boolean = false,
        initialStoredCharacterContent: String? = null,
        storyOutputParserFactory: () -> StoryOutputParser = ::StoryOutputParser,
        storyMemoryRepository: StoryMemoryRepository? = null
    ): Harness {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi, dataCoordinator = dataCoordinator)
        val personaRepository = PersonaRepository(database.personaDao(), moshi, dataCoordinator = dataCoordinator)
        val chatRepository = ChatRepository(database.chatDao(), dataCoordinator = dataCoordinator)
        val messageRepository = MessageRepository(database.messageDao(), dataCoordinator)
        val attachmentRepository = MessageAttachmentRepository(context, database, dataCoordinator)
        val npcRepository = NpcRepository(database, moshi, dataCoordinator)
        val npcParser = NpcStructuredOutputParser(moshi)
        val memoryRepository = MemoryRepository(database.memoryDao(), dataCoordinator)
        val lorebookRepository = LorebookRepository(database.lorebookDao(), moshi, dataCoordinator)
        database.characterDao().insertCharacter(character())
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createSoloChat("Cutoff Test", "persona-1", "character-1")
        chatRepository.insertChatSettings(
            ChatSettingsEntity(
                chatId = chatId,
                selectedModelId = "mock/model",
                providerRoutingMode = routingMode,
                providerEndpoint = providerEndpoint,
                responseLengthProfile = responseLengthProfile,
                narratorEnabled = narratorEnabled,
                narratorInstructions = narratorInstructions
            )
        )
        if (npcBankEnabledInitially) npcRepository.setEnabled(chatId, true)
        initialStoredCharacterContent?.let { content ->
            messageRepository.insertMessage(
                MessageEntity(
                    chatId = chatId,
                    speakerType = SpeakerType.CHARACTER,
                    speakerId = "character-1",
                    speakerDisplayNameSnapshot = "Character",
                    content = content,
                    orderIndex = 1L
                )
            )
        }
        val viewModel = viewModels.create(ChatDetailViewModel::class.java) {
            ChatDetailViewModel(
                chatId,
                chatRepository,
                messageRepository,
                characterRepository,
                personaRepository,
                provider,
                memoryRepository,
                lorebookRepository = lorebookRepository,
                messageAttachmentRepository = attachmentRepository,
                npcRepository = npcRepository,
                npcOutputParser = npcParser,
                dataCoordinator = dataCoordinator,
                storyOutputParserFactory = storyOutputParserFactory,
                storyMemoryRepository = storyMemoryRepository
            )
        }
        // ChatUiState.Success is emitted before the Room history collector delivers its
        // first value. Tests must not send messages or invoke tools during that loading
        // window; otherwise their expected events can race initial history hydration.
        withTimeout(15_000) {
            viewModel.uiState.first { it is ChatUiState.Success && it.historyLoaded }
        }
        return Harness(
            chatId,
            viewModel,
            chatRepository,
            messageRepository,
            memoryRepository,
            attachmentRepository,
            npcRepository,
            lorebookRepository
        )
    }

    private class RecordingStreamingProvider(
        private val contextWindow: Int? = null
    ) : ModelProvider {
        lateinit var messages: List<RoleplayMessage>
        lateinit var options: GenerationOptions
        val requests = mutableListOf<List<RoleplayMessage>>()

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override suspend fun supportsImageInput(modelId: String): Boolean = true
        override fun cachedContextLength(modelId: String): Int? = contextWindow

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

    private class SequentialResponseProvider(
        private val responses: List<String>
    ) : ModelProvider {
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

    private class RequestSequenceProvider(
        private val scripts: List<List<StreamEvent>>
    ) : ModelProvider {
        val requests = mutableListOf<List<RoleplayMessage>>()
        private var index = 0

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override suspend fun supportsImageInput(modelId: String): Boolean = true

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            requests += messages
            scripts[index++].forEach { emit(it) }
        }
    }

    private class CountingProvider : ModelProvider {
        var requestCount = 0

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            requestCount += 1
        }
    }

    private class ThrowingProvider : ModelProvider {
        var requestCount = 0

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            requestCount += 1
            error("provider failed before first chunk")
        }
    }

    private class CancellablePartialProvider(
        private val content: String = "Partial response"
    ) : ModelProvider {
        var requestCount = 0
        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow {
            requestCount += 1
            emit(StreamEvent.Content(content))
            awaitCancellation()
        }
    }

    private class TextOnlyProvider : ModelProvider {
        var requestCount = 0

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override suspend fun supportsImageInput(modelId: String): Boolean = false

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = flow { requestCount++ }
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
