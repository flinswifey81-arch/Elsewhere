package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatDetailGenerationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var viewModels: TestViewModelOwner
    private lateinit var dataCoordinator: AppDataMaintenanceCoordinator
    private val mainDispatcher = UnconfinedTestDispatcher()

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
        assertTrue(continuationPrompt.contains("Narrator: The room stills."))
        assertTrue(continuationPrompt.contains("Character (Rowan): \"Wait here.\""))
        assertFalse(continuationPrompt.contains("elsewhere-story-v1"))
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
        withTimeout(30_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && !it.isGenerating }
        }

        harness.viewModel.regenerateMessage(original.messageId)
        val regenerated = withTimeout(30_000) {
            harness.messageRepository.getMessagesForChat(harness.chatId).first { messages ->
                messages.any { it.content == "Regenerated image reply." && it.isPrimaryVariant }
            }
        }.single { it.content == "Regenerated image reply." }
        withTimeout(30_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && !it.isGenerating }
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
                messages.any { it.content == "Regenerated safely." && it.isPrimaryVariant }
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
        assertTrue(requestText.contains("DURABLE_MEMORY_STAYS"))
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
        withTimeout(15_000) {
            viewModel.uiState.first { it is ChatUiState.Success && it.groupParticipantsLoaded }
        }
        viewModel.selectGroupCharacter("character-2")
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
        withTimeout(15_000) {
            harness.viewModel.uiState.first { it is ChatUiState.Success && it.isGenerating }
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
        storyOutputParserFactory: () -> StoryOutputParser = ::StoryOutputParser
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
                storyOutputParserFactory = storyOutputParserFactory
            )
        }
        withTimeout(15_000) { viewModel.uiState.first { it is ChatUiState.Success } }
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

    private class RecordingStreamingProvider : ModelProvider {
        lateinit var messages: List<RoleplayMessage>
        lateinit var options: GenerationOptions
        val requests = mutableListOf<List<RoleplayMessage>>()

        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override suspend fun supportsImageInput(modelId: String): Boolean = true

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
