package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.compiler.ScenarioContextCompiler
import com.example.domain.provider.*
import com.example.domain.repository.*
import com.example.ui.screens.ChatDetailViewModel
import com.example.ui.screens.ChatUiState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScenarioModeTest {
    private lateinit var db: AppDatabase
    private lateinit var scenarios: ScenarioRepository
    private lateinit var owner: TestViewModelOwner
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val coordinator = AppDataMaintenanceCoordinator()
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        scenarios = ScenarioRepository(db, coordinator)
        owner = TestViewModelOwner()
    }
    @After fun cleanup() { owner.close(); db.close(); Dispatchers.resetMain() }
    private suspend fun persona(id: String = "player"): PersonaEntity {
        val repo = PersonaRepository(db.personaDao(), moshi, dataCoordinator = coordinator)
        repo.importPersonaJson("""{"schema_version":1,"persona_id":"$id","display_name":"Player","persona_name":"Mira","aliases":[],"identity":{"occupation":"Investigator"},"private_notes":{"secret":"PRIVATE_SENTINEL"}}""")
        return requireNotNull(repo.getPersonaById(id))
    }
    private fun definition() = ScenarioDefinition(title = "Court", premise = "A masked ball.", openingMessage = "The doors open.",
        npcs = (1..4).map { ScenarioNpc(name = "Guest $it", personality = "Reserved $it", background = "From house $it") })

    @Test fun minimalQuickSceneAndDetailedSandboxRoundTrip() = runBlocking {
        val quick = ScenarioDefinition(title = "Encounter", premise = "A stranger arrives")
        scenarios.save("quick", quick)
        assertEquals(quick, ScenarioCodec.decode(scenarios.get("quick")!!.definitionJson))
        val sandbox = definition().copy(experience = ScenarioExperience.SANDBOX, setting = "Old city", locations = "Harbor", factions = "Court", worldRules = "Magic has a price", instructions = "Slow pace", categories = "Fantasy", tags = "Mystery")
        scenarios.save("world", sandbox)
        assertEquals(sandbox, ScenarioCodec.decode(scenarios.get("world")!!.definitionJson))
        assertEquals(4, ScenarioCodec.decode(scenarios.get("world")!!.definitionJson).npcs.size)
    }

    @Test fun sessionsAreIndependentAndTemplateEditsDoNotRewriteThem() = runBlocking {
        persona(); persona("second")
        scenarios.save("world", definition())
        val first = scenarios.start("world", "player")
        val second = scenarios.start("world", "second")
        assertNotEquals(first, second)
        assertEquals("player", db.chatDao().getChatSummaryById(first)!!.personas.single().personaId)
        assertEquals("second", db.chatDao().getChatSummaryById(second)!!.personas.single().personaId)
        assertTrue(db.chatDao().getChatSummaryById(first)!!.characters.isEmpty())
        val original = scenarios.session(first)!!.definitionJson
        scenarios.save("world", definition().copy(premise = "An entirely new premise", openingMessage = "Different opening"))
        assertEquals(original, scenarios.session(first)!!.definitionJson)
        assertEquals(original, scenarios.session(second)!!.definitionJson)
        val third = scenarios.start("world", "player")
        assertNotEquals(original, scenarios.session(third)!!.definitionJson)
        val messages = MessageRepository(db.messageDao(), coordinator)
        assertEquals("The doors open.", messages.getMessagesForChatOnce(first).single().content)
        assertEquals("Different opening", messages.getMessagesForChatOnce(third).single().content)
        val chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
        chats.deleteChat(first)
        assertNull(scenarios.session(first))
        assertNotNull(scenarios.session(second))
    }

    @Test fun promptSeparatesPersonaAndNpcsAndExcludesDisplayMetadata() = runBlocking {
        val p = persona()
        val d = definition().copy(publicDescription = "PUBLIC_SENTINEL", displayTitle = "DISPLAY_SENTINEL", tags = "TAG_SENTINEL", narrationStyle = "Lyrical", instructions = "Maintain suspense")
        val current = MessageEntity(chatId = "chat", speakerType = SpeakerType.PERSONA, speakerId = p.personaId, speakerDisplayNameSnapshot = "Mira", content = "I enter.", orderIndex = 2)
        val old = current.copy(messageId = "old", speakerType = SpeakerType.NARRATOR, content = "A guest smiles", orderIndex = 1)
        val output = ScenarioContextCompiler().compile(d, p, ChatSettingsEntity("chat"), listOf(old), current)
        val system = output.first().content
        listOf("PUBLIC_SENTINEL", "DISPLAY_SENTINEL", "TAG_SENTINEL", "PRIVATE_SENTINEL").forEach { assertFalse(system.contains(it)) }
        listOf("Guest 1", "Guest 4", "Mira", "Lyrical", "Maintain suspense", "Never invent the player's dialogue", "ONE response").forEach { assertTrue(it, system.contains(it)) }
        assertEquals(listOf(Role.SYSTEM, Role.ASSISTANT, Role.USER), output.map { it.role })
        assertEquals(current.messageId, output.last().sourceMessageId)
    }

    @Test fun historyIsBoundedAndAlternateVariantsExcluded() = runBlocking {
        val p = persona()
        val history = (0..39).map { MessageEntity(chatId = "c", speakerType = SpeakerType.NARRATOR, speakerId = "n", speakerDisplayNameSnapshot = "Narrator", content = "turn $it", orderIndex = it.toLong(), isPrimaryVariant = it != 39) }
        val output = ScenarioContextCompiler().compile(definition(), p, ChatSettingsEntity("c"), history, history.last().copy(content = "current"), summary = "earlier facts")
        assertEquals(26, output.size)
        assertTrue(output.first().content.contains("earlier facts"))
        assertFalse(output.any { it.content == "Narrator: turn 39" })
    }

    private suspend fun model(
        provider: RecordingProvider,
        scenarioDefinition: ScenarioDefinition = definition()
    ): Pair<String, ChatDetailViewModel> {
        persona(); scenarios.save("s", scenarioDefinition)
        val id = scenarios.start("s", "player")
        val chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
        chats.insertChatSettings(ChatSettingsEntity(id, selectedModelId = "chosen/model", temperature = 0.7f, topP = 0.8f))
        val vm = owner.create(ChatDetailViewModel::class.java) { ChatDetailViewModel(id, chats,
            MessageRepository(db.messageDao(), coordinator), CharacterRepository(db.characterDao(), moshi), PersonaRepository(db.personaDao(), moshi), provider,
            MemoryRepository(db.memoryDao(), coordinator), dataCoordinator = coordinator, scenarioRepository = scenarios) }
        withTimeout(15000) { vm.uiState.first { it is ChatUiState.Success && it.historyLoaded } }
        return id to vm
    }
    private suspend fun awaitMessages(vm: ChatDetailViewModel, count: Int) = withTimeout(15000) {
        vm.uiState.first { it is ChatUiState.Success && it.messages.size == count && !it.isGenerating }
    }
    @Test fun scenarioWithoutOpeningMessageWaitsForPlayerAndUsesOneRequest() = runBlocking {
        val provider = RecordingProvider()
        val (id, vm) = model(provider, definition().copy(openingMessage = ""))
        assertTrue(MessageRepository(db.messageDao(), coordinator).getMessagesForChatOnce(id).isEmpty())
        assertTrue(provider.requests.isEmpty())

        vm.onDraftChanged("I step into the ballroom."); vm.sendMessage()
        awaitMessages(vm, 2)

        val messages = MessageRepository(db.messageDao(), coordinator).getMessagesForChatOnce(id)
        assertEquals(listOf(SpeakerType.PERSONA, SpeakerType.NARRATOR), messages.map { it.speakerType })
        assertEquals(1, provider.requests.size)
    }
    @Test fun oneRequestPortraysMultipleNpcsAndPreservesModelSettingsAndNarratorIdentity() = runBlocking {
        val provider = RecordingProvider()
        val (id, vm) = model(provider)
        vm.onDraftChanged("I greet the guests."); vm.sendMessage()
        awaitMessages(vm, 3)
        assertEquals(1, provider.requests.size)
        assertEquals("chosen/model", provider.options.single().modelId)
        assertEquals(0.7f, provider.options.single().temperature!!, 0.0f)
        val response = MessageRepository(db.messageDao(), coordinator).getMessagesForChatOnce(id).last()
        assertEquals(SpeakerType.NARRATOR, response.speakerType)
        assertEquals("narrator:$id", response.speakerId)
        assertTrue(response.content.contains("Guest 1")); assertTrue(response.content.contains("Guest 2"))
        assertTrue(provider.requests.single().first().content.contains("collaborative narrator"))
        assertFalse(provider.requests.single().first().content.contains("Portray the character described below"))
        vm.regenerateMessage(response.messageId)
        awaitMessages(vm, 4)
        assertEquals(2, provider.requests.size)
        assertFalse(provider.requests.last().any { it.sourceMessageId == response.messageId })
        val active = MessageRepository(db.messageDao(), coordinator).getMessagesForChatOnce(id).last { it.isPrimaryVariant && it.speakerType == SpeakerType.NARRATOR }
        vm.continueMessage(active.messageId)
        withTimeout(15000) { vm.uiState.first { it is ChatUiState.Success && !it.isGenerating && it.messages.any { m -> m.messageId == active.messageId && m.content.length > active.content.length } } }
        assertEquals(3, provider.requests.size)
    }
    @Test fun failedNarrationRetryDoesNotDuplicatePlayerTurn() = runBlocking {
        val provider = RecordingProvider(failFirst = true)
        val (id, vm) = model(provider)
        vm.onDraftChanged("I enter."); vm.sendMessage()
        withTimeout(15000) { vm.uiState.first { it is ChatUiState.Success && it.generationError != null } }
        vm.retryGeneration(); awaitMessages(vm, 3)
        assertEquals(2, provider.requests.size)
        assertEquals(1, MessageRepository(db.messageDao(), coordinator).getMessagesForChatOnce(id).count { it.speakerType == SpeakerType.PERSONA })
    }
    @Test fun lockedRoutingWithoutEndpointMakesNoProviderCall() = runBlocking {
        val provider = RecordingProvider()
        val (id, vm) = model(provider)
        val chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
        chats.insertChatSettings(requireNotNull(chats.getChatSettings(id)).copy(providerRoutingMode = ProviderRoutingMode.LOCK, providerEndpoint = null))
        withTimeout(15000) { vm.uiState.first { it is ChatUiState.Success && it.chatSettings.providerRoutingMode == ProviderRoutingMode.LOCK } }
        vm.onDraftChanged("I enter."); vm.sendMessage()
        withTimeout(15000) { vm.uiState.first { it is ChatUiState.Success && it.generationError?.contains("Locked routing") == true } }
        assertTrue(provider.requests.isEmpty())
    }
    @Test fun stoppedPartialNarrationKeepsNarratorIdentity() = runBlocking {
        val provider = RecordingProvider(waitForStop = true)
        val (id, vm) = model(provider)
        vm.onDraftChanged("I enter."); vm.sendMessage()
        withTimeout(15000) { vm.uiState.first { it is ChatUiState.Success && it.streamingContent?.isNotBlank() == true } }
        vm.stopGeneration()
        withTimeout(15000) { vm.uiState.first { it is ChatUiState.Success && it.partialStopped } }
        vm.keepPartial()
        awaitMessages(vm, 3)
        val message = MessageRepository(db.messageDao(), coordinator).getMessagesForChatOnce(id).last()
        assertEquals(SpeakerType.NARRATOR, message.speakerType)
        assertEquals("narrator:$id", message.speakerId)
        assertEquals(1, provider.requests.size)
    }
    private class RecordingProvider(val failFirst: Boolean = false, val waitForStop: Boolean = false): ModelProvider {
        val requests = mutableListOf<List<RoleplayMessage>>()
        val options = mutableListOf<GenerationOptions>()
        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override fun streamResponse(messages: List<RoleplayMessage>, options: GenerationOptions): Flow<StreamEvent> = flow {
            requests += messages; this@RecordingProvider.options += options
            if (failFirst && requests.size == 1) emit(StreamEvent.Error(IllegalStateException("Try again")))
            else { emit(StreamEvent.Content("Guest 1: Welcome. Guest 2: Good evening.")); if (waitForStop) awaitCancellation() else emit(StreamEvent.Done(null)) }
        }
    }
}
