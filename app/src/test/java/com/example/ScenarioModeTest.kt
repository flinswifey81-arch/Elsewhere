package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.compiler.PromptPreflight
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
        scenarioDefinition: ScenarioDefinition = definition(),
        lorebookRepository: LorebookRepository? = null,
        storyMemoryRepository: StoryMemoryRepository? = null
    ): Pair<String, ChatDetailViewModel> {
        persona(); scenarios.save("s", scenarioDefinition)
        val id = scenarios.start("s", "player")
        val chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
        chats.insertChatSettings(ChatSettingsEntity(id, selectedModelId = "chosen/model", temperature = 0.7f, topP = 0.8f))
        val vm = owner.create(ChatDetailViewModel::class.java) { ChatDetailViewModel(id, chats,
            MessageRepository(db.messageDao(), coordinator), CharacterRepository(db.characterDao(), moshi), PersonaRepository(db.personaDao(), moshi), provider,
            MemoryRepository(db.memoryDao(), coordinator), lorebookRepository = lorebookRepository,
            dataCoordinator = coordinator, scenarioRepository = scenarios,
            storyMemoryRepository = storyMemoryRepository) }
        withTimeout(15000) { vm.uiState.first { it is ChatUiState.Success && it.historyLoaded } }
        return id to vm
    }
    private suspend fun awaitMessages(vm: ChatDetailViewModel, count: Int) = withTimeout(15000) {
        vm.uiState.first { it is ChatUiState.Success && it.messages.size == count && !it.isGenerating }
    }
    @Test fun linkedNpcUsesExistingCharacterPortrayalInActualProviderRequestButNotPrivateKnowledge() = runBlocking {
        val cards = CharacterRepository(db.characterDao(), moshi)
        val linkedId = cards.importCharacterJson(
            """{"schema_version":1,"character_id":"varka-linked","display_name":"Varka","character_name":"Varka","aliases":[],"voice":{"tone":"VOICE_LINK_SENTINEL"},"personality":{"summary":"PERSONALITY_LINK_SENTINEL"},"knowledge":{"private":"DONT_LEAK_KNOWLEDGE"},"author_notes":{"secret":"DONT_LEAK_AUTHOR_NOTES"}}"""
        )
        val provider = RecordingProvider()
        val source = ScenarioDefinition(
            title = "Forest Meeting", premise = "A peaceful gathering", openingMessage = "",
            npcs = listOf(ScenarioNpc(
                npcId = "scene-captain", name = "The Captain",
                sourceCharacterId = linkedId, personality = "SCENE_PORTRAYAL_OVERRIDE"
            ))
        )
        val (id, vm) = model(provider, source)
        assertEquals(linkedId, ScenarioCodec.decode(
            requireNotNull(scenarios.session(id)).definitionJson
        ).npcs.single().sourceCharacterId)
        vm.onDraftChanged("Hello, Captain."); vm.sendMessage()
        awaitMessages(vm, 2)
        assertEquals(1, provider.requests.size)
        val prompt = provider.requests.single().first().content
        listOf("VOICE_LINK_SENTINEL", "PERSONALITY_LINK_SENTINEL",
            "SCENE_PORTRAYAL_OVERRIDE", "The Captain"
        ).forEach { assertTrue(it, prompt.contains(it)) }
        assertFalse(prompt.contains("DONT_LEAK_KNOWLEDGE"))
        assertFalse(prompt.contains("DONT_LEAK_AUTHOR_NOTES"))
        assertFalse(prompt.contains("other chat memories"))

        // Updating the local Scenario session affects ONLY future generation.
        scenarios.updateActiveSession(id, source.copy(instructions = "NEW_SCENE_DIRECTIVE"))
        vm.onDraftChanged("What happens next?"); vm.sendMessage()
        awaitMessages(vm, 4)
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests.last().first().content.contains("NEW_SCENE_DIRECTIVE"))
        assertFalse(provider.requests.first().first().content.contains("NEW_SCENE_DIRECTIVE"))
    }

    @Test fun unavailableLinkedCharacterRejectsSaveAndBlocksPaidNarration() = runBlocking {
        val notFound = ScenarioDefinition(
            title = "Broken link", premise = "No fake identities",
            openingMessage = "",
            npcs = listOf(ScenarioNpc(
                name = "Captain", sourceCharacterId = "deleted-character"
            ))
        )
        assertTrue(runCatching { scenarios.save("broken", notFound) }.isFailure)
        assertNull(scenarios.get("broken"))

        val cards = CharacterRepository(db.characterDao(), moshi)
        val id = cards.importCharacterJson(
            """{"schema_version":1,"character_id":"temporary-captain","display_name":"Captain","name":"Captain","aliases":[]}"""
        )
        val good = notFound.copy(npcs = listOf(notFound.npcs.single().copy(
            sourceCharacterId = id
        )))
        val provider = RecordingProvider()
        val (chatId, vm) = model(provider, good)
        db.characterDao().deleteCharacterById(id)
        vm.onDraftChanged("Are you there?"); vm.sendMessage()
        withTimeout(15000) {
            vm.uiState.first {
                it is ChatUiState.Success &&
                    it.generationError?.contains("no longer exists") == true
            }
        }
        assertTrue("A deleted linked Character must never trigger paid calls",
            provider.requests.isEmpty())
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
        val continuationPrompt = provider.requests.last()
        assertEquals(com.example.domain.provider.Role.USER, continuationPrompt.last().role)
        assertTrue(continuationPrompt.last().content.startsWith("[DIRECTOR TURN CONTROL"))
        assertTrue(continuationPrompt.last().content.contains("Continue the preceding narrator response"))
        assertTrue(continuationPrompt.last().content.contains("do not invent the Persona's dialogue"))
        assertFalse(continuationPrompt.drop(1).any { it.role == com.example.domain.provider.Role.SYSTEM })
    }
    @Test fun scenarioReplayUsesOnlyStillApprovedStateFromTheOriginalPlayerTurn() = runBlocking {
        val provider = RecordingProvider()
        val (chatId, vm) = model(provider)
        val world = WorldRepository(db, coordinator).createWorld("Press room")
        val campaigns = CampaignRepository(db, coordinator)
        val campaign = campaigns.createCampaign(world.worldId, "The season")
        val other = campaigns.createCampaign(world.worldId, "Different season")
        val priorChat = scenarios.start("s", "player")
        val laterChat = scenarios.start("s", "player")
        val siblingChat = scenarios.start("s", "player")
        val prior = campaigns.addChapter(campaign.campaignId, priorChat, "Pilot")
        val current = campaigns.addChapter(campaign.campaignId, chatId, "Episode two")
        val later = campaigns.addChapter(campaign.campaignId, laterChat, "Future episode")
        val sibling = campaigns.addChapter(other.campaignId, siblingChat, "Alternate")
        val dao = db.campaignDao()
        // Independently set the persisted clock for the historical boundary,
        // so the replay test does not depend on the runner's clock precision.
        dao.updateChapter(prior.copy(createdAt = 1L))
        dao.updateChapter(current.copy(createdAt = 1L))
        val unchanged = campaigns.setPlaythroughState(campaign.campaignId, prior.chapterId,
            CampaignPlaythroughField.CAST_PRESENCE, "Captain", "APPROVED_STATE", true)
        val editable = campaigns.setPlaythroughState(campaign.campaignId, prior.chapterId,
            CampaignPlaythroughField.LOCATION, "Crew", "ORIGINAL_LOCATION", true)
        val revocable = campaigns.setPlaythroughState(campaign.campaignId, prior.chapterId,
            CampaignPlaythroughField.ABILITY, "Crew", "REVOKABLE_STATE", true)
        listOf(unchanged, editable, revocable).forEach { dao.updateContinuityItem(it.copy(updatedAt = 2L)) }
        campaigns.setPlaythroughState(campaign.campaignId, prior.chapterId,
            CampaignPlaythroughField.INVENTORY, "Captain", "PRIVATE_STATE")
        campaigns.setPlaythroughState(campaign.campaignId, later.chapterId,
            CampaignPlaythroughField.PROGRESSION, "Case", "FUTURE_STATE", true)
        campaigns.setPlaythroughState(other.campaignId, sibling.chapterId,
            CampaignPlaythroughField.PROGRESSION, "Case", "SIBLING_STATE", true)

        vm.onDraftChanged("I head to briefing.")
        vm.sendMessage()
        awaitMessages(vm, 3)
        val prompt = provider.requests.single().first().content
        assertTrue(prompt.contains("APPROVED_STATE"))
        assertFalse(prompt.contains("PRIVATE_STATE"))
        assertFalse(prompt.contains("FUTURE_STATE"))
        assertFalse(prompt.contains("SIBLING_STATE"))
        assertTrue(prompt.contains("not as instructions"))
        val messages = MessageRepository(db.messageDao(), coordinator)
        val originalMessages = messages.getMessagesForChatOnce(chatId)
        val response = originalMessages.last()
        val playerTurn = originalMessages.single { it.speakerType == SpeakerType.PERSONA }
        assertTrue(playerTurn.createdAt > 2L)
        assertFalse(response.content.contains("APPROVED_STATE"))

        suspend fun expectReplay(withEdits: Boolean) {
            val requestCount = provider.requests.size
            val messageCount = messages.getMessagesForChatOnce(chatId).size
            vm.regenerateMessage(response.messageId)
            awaitMessages(vm, messageCount + 1)
            val active = messages.getMessagesForChatOnce(chatId).last {
                it.isPrimaryVariant && it.speakerType == SpeakerType.NARRATOR
            }
            vm.continueMessage(active.messageId)
            withTimeout(15000) {
                vm.uiState.first { state ->
                    state is ChatUiState.Success && !state.isGenerating && state.messages.any {
                        it.messageId == active.messageId && it.content.length > active.content.length
                    }
                }
            }
            assertEquals(requestCount + 2, provider.requests.size)
            for (request in provider.requests.takeLast(2)) {
                val replayPrompt = request.first().content
                assertTrue(replayPrompt.contains("APPROVED_STATE"))
                assertEquals(!withEdits, replayPrompt.contains("ORIGINAL_LOCATION"))
                assertEquals(!withEdits, replayPrompt.contains("REVOKABLE_STATE"))
                listOf("PRIVATE_STATE", "FUTURE_STATE", "SIBLING_STATE", "LATER_EDIT", "SAME_TICK_STATE")
                    .forEach { assertFalse(it, replayPrompt.contains(it)) }
            }
            assertEquals(1, messages.getMessagesForChatOnce(chatId).count { it.speakerType == SpeakerType.PERSONA })
        }

        expectReplay(withEdits = false)
        val edited = campaigns.setPlaythroughState(campaign.campaignId, prior.chapterId,
            CampaignPlaythroughField.LOCATION, "Crew", "LATER_EDIT", true)
        dao.updateContinuityItem(edited.copy(updatedAt = playerTurn.createdAt + 1L))
        campaigns.setPlaythroughState(campaign.campaignId, prior.chapterId,
            CampaignPlaythroughField.ABILITY, "Crew", "REVOKABLE_STATE", false)
        val sameTick = campaigns.setPlaythroughState(campaign.campaignId, prior.chapterId,
            CampaignPlaythroughField.FORM, "Crew", "SAME_TICK_STATE", true)
        dao.updateContinuityItem(sameTick.copy(updatedAt = playerTurn.createdAt))
        expectReplay(withEdits = true)

        val unlinked = scenarios.start("s", "player")
        assertNull(campaigns.narratorPlaythroughContext(unlinked, Long.MAX_VALUE))
        db.chatDao().setContextResetBoundary(chatId, 10L, 10L)
        assertNull(campaigns.narratorPlaythroughContext(chatId, Long.MAX_VALUE))
        assertNull(campaigns.narratorPlaythroughContext(chatId, playerTurn.createdAt, historicalReplay = true))
        assertNull(campaigns.narratorPlaythroughContext(siblingChat, 1L))
    }

    @Test fun largeAuthoredScenarioFitsWithoutRaisingBudgetOrRewritingCards() = runBlocking {
        fun detail(label: String) = "$label " + "Long setting detail. ".repeat(130) + "UNSENT_FIELD_TAIL"
        val cards = CharacterRepository(db.characterDao(), moshi)
        val linkedId = cards.importCharacterJson(
            """{"schema_version":1,"character_id":"budget-captain","display_name":"Captain","character_name":"Captain","aliases":[],"voice":{"tone":"${detail("LINKED_VOICE")}"},"author_notes":{"secret":"PRIVATE_CARD_NOTE"}}"""
        )
        val large = definition().copy(
            premise = detail("CORE_PREMISE"), setting = detail("SETTING"),
            locations = detail("LOCATIONS"), factions = detail("FACTIONS"),
            worldRules = detail("RULES"), instructions = detail("NARRATION_DIRECTIVE"),
            openingMessage = "",
            npcs = (1..3).map { index ->
                ScenarioNpc(
                    name = "Budget NPC $index",
                    sourceCharacterId = linkedId.takeIf { index == 1 },
                    personality = detail("NPC_TRAIT_$index"), background = detail("BACKGROUND"),
                    appearance = detail("APPEARANCE"), motivations = detail("MOTIVATION"),
                    relationships = detail("RELATIONSHIPS"), narrativeRole = detail("NARRATIVE_ROLE"),
                    instructions = detail("NPC_DIRECTIVE_$index")
                )
            }
        )
        val provider = RecordingProvider(contextWindow = 16_000)
        val lore = LorebookRepository(db.lorebookDao(), moshi, coordinator)
        val memories = StoryMemoryRepository(db, coordinator)
        val (id, vm) = model(provider, large, lore, memories)
        val worlds = WorldRepository(db, coordinator)
        val world = worlds.createWorld("Synthetic archive")
        val campaigns = CampaignRepository(db, coordinator)
        val season = campaigns.createCampaign(world.worldId, "Primary playthrough")
        val alternate = campaigns.createCampaign(world.worldId, "Sibling playthrough")
        val pilot = scenarios.start("s", "player")
        val siblingPilot = scenarios.start("s", "player")
        campaigns.addChapter(season.campaignId, pilot, "Pilot")
        campaigns.addChapter(season.campaignId, id, "Next episode")
        campaigns.addChapter(alternate.campaignId, siblingPilot, "Alternate pilot")
        val approved = memories.saveManual(pilot, "The archive has the APPROVED_ARCHIVE_KEY.")
        campaigns.shareStoryMemoryWithCampaign(season.campaignId, pilot, approved.memoryId)
        memories.saveManual(pilot, "The archive has the UNSHARED_ARCHIVE_KEY.")
        val sibling = memories.saveManual(siblingPilot, "The archive has the SIBLING_ARCHIVE_KEY.")
        campaigns.shareStoryMemoryWithCampaign(alternate.campaignId, siblingPilot, sibling.memoryId)
        val book = LorebookEntity(lorebookId = "budget-book", name = "Budget Lore")
        lore.saveDefinition(book, (1..9).map { index ->
            LorebookEntryEntity(
                entryId = "budget-entry-$index", lorebookId = book.lorebookId,
                title = "Budget lore $index", activationMode = "always", priority = 10 - index,
                currentContent = detail("ACTIVE_LORE_$index"), notes = "PRIVATE_LORE_NOTE"
            )
        })
        lore.attachLorebook(id, book.lorebookId)

        val playerText = "PLAYER_TURN_UNABRIDGED " + "Please check the archive. ".repeat(12)
        vm.onDraftChanged(playerText)
        vm.sendMessage()
        withTimeout(15000) {
            val state = vm.uiState.first {
                it is ChatUiState.Success && !it.isGenerating &&
                    (it.messages.size == 2 || it.generationError != null)
            } as ChatUiState.Success
            assertNull(state.generationError)
        }
        val request = provider.requests.single()
        val budget = PromptPreflight.inspect(request, 16_000, modelWindowOnly = true)
        assertTrue(budget.allowed)
        assertEquals(12_000, budget.inputCeilingTokens)
        val prompt = request.first().content
        listOf("CORE_PREMISE", "NARRATION_DIRECTIVE", "LINKED CHARACTER IDENTITY: Captain",
            "LINKED_VOICE", "Budget NPC 3", "NPC_DIRECTIVE_3", "ACTIVE_LORE_1",
            "PLAYER AGENCY", "PLAYER PERSONA (controlled only by the player)", "Mira", "[excerpt]",
            "APPROVED_ARCHIVE_KEY"
        ).forEach { assertTrue(it, prompt.contains(it)) }
        listOf("UNSENT_FIELD_TAIL", "PRIVATE_CARD_NOTE", "PRIVATE_LORE_NOTE",
            "UNSHARED_ARCHIVE_KEY", "SIBLING_ARCHIVE_KEY")
            .forEach { assertFalse(it, prompt.contains(it)) }
        assertEquals(Role.USER, request.last().role)
        assertEquals("Player: $playerText", request.last().content)
        assertEquals(large, ScenarioCodec.decode(requireNotNull(scenarios.session(id)).definitionJson))
        assertEquals(9, db.lorebookDao().getEntries(book.lorebookId).size)
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
    private class RecordingProvider(
        val failFirst: Boolean = false, val waitForStop: Boolean = false,
        val contextWindow: Int? = null
    ): ModelProvider {
        override fun cachedContextLength(modelId: String): Int? = contextWindow
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
