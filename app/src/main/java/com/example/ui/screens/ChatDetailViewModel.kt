package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ChatSummary
import com.example.data.model.ChatType
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.generation.GenerationLifecycleReporter
import com.example.domain.memory.AutomaticMemoryManager
import com.example.domain.memory.ContextResetPolicy
import com.example.domain.memory.LegacyMemoryEligibilityFilter
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryActorRef
import com.example.domain.repository.StoryMemoryRepository
import com.example.domain.memory.StoryMemoryPromptBudget
import com.example.domain.compiler.PromptOptionalContextFitter
import com.example.domain.compiler.PromptOptionalContextChoices
import com.example.domain.compiler.PromptOptionalContextFit
import com.example.domain.memory.StoryMemoryRecord
import com.example.domain.coop.CoopGenerationCommand
import com.example.domain.coop.CoopGenerationRequestPayload
import com.example.domain.coop.CoopGenerationAuthorization
import com.example.domain.coop.CoopGenerationMetadataPayload
import com.example.domain.coop.CoopSessionManager
import com.example.domain.coop.CoopCharacterRosterItem
import com.example.domain.coop.CoopCharacterRosterPayload
import com.example.data.model.CoopRole
import com.example.data.model.CoopConnectionState
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.ModelRouting
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.npc.NpcSceneCastResolver
import com.example.domain.npc.NpcScenePresence
import com.example.domain.story.StoryOutputParser
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySpeakerIdentity
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.LorebookRepository
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.NpcRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.AppDataRevision
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class MessageGroup(
    val primaryMessage: com.example.data.model.MessageEntity,
    val variants: List<com.example.data.model.MessageEntity>
)

data class GroupCharacterState(
    val character: CharacterEntity,
    val isActive: Boolean,
    val sortPosition: Int
)

data class GenerationDetailsState(
    val metadata: com.example.data.model.GenerationMetadataEntity? = null,
    val isVisible: Boolean = false
)

enum class InChatToolStatusKind { WORKING, SUCCESS, ERROR }

data class InChatToolStatus(
    val kind: InChatToolStatusKind,
    val message: String
)

private data class ResponseSpeaker(val characterId: String, val displayName: String, val type: SpeakerType = SpeakerType.CHARACTER)

private data class PreparedGeneration(
    val character: ResponseSpeaker,
    val messages: List<RoleplayMessage>,
    val options: GenerationOptions,
    val npcBankEnabled: Boolean = false,
    val activePersonaId: String? = null,
    val ensembleNarrationEnabled: Boolean = false,
    val knownNpcSpeakers: List<StorySpeakerIdentity> = emptyList(),
    val coLeadRebalanceActive: Boolean = false,
    val contextReductionNotice: String? = null
)

private data class PendingPartialGeneration(
    val character: ResponseSpeaker,
    val variantGroupId: String?,
    val sourceMessage: MessageEntity,
    val contextMessages: List<MessageEntity>,
    val continuationTarget: MessageEntity?,
    val npcBankEnabled: Boolean,
    val activePersonaId: String?,
    val dataRevision: AppDataRevision,
    val coLeadRebalanceActive: Boolean = false,
    val metadata: GenerationMetadataEntity? = null,
    val attemptedMessage: MessageEntity? = null,
    val publishedMessageId: String? = null,
    val publishedMetadata: GenerationMetadataEntity? = null
)

private data class CoopGenerationInput(
    val currentMessage: MessageEntity,
    val history: List<MessageEntity>,
    val isContinuation: Boolean = false,
    val continuationTarget: MessageEntity? = null,
    val variantGroupId: String? = null
)

sealed class ChatUiState {
    object Loading : ChatUiState()
    object Error : ChatUiState()
    object Deleted : ChatUiState()
    data class Success(
        val groupedMessages: List<com.example.ui.screens.MessageGroup> = emptyList(),
        val chatSummary: ChatSummary,
        val messages: List<MessageEntity>,
        val chatSettings: ChatSettingsEntity,
        val historyLoaded: Boolean = false,
        val hasEarlierHistory: Boolean = false,
        val isLoadingEarlierHistory: Boolean = false,
        val groupCharacters: List<GroupCharacterState> = emptyList(),
        val availableGroupCharacters: List<CharacterEntity> = emptyList(),
        val groupParticipantsLoaded: Boolean = false,
        val selectedGroupCharacterId: String? = null,
        val generationCharacterId: String? = null,
        val isGenerating: Boolean = false,
        val streamingContent: String? = null,
        val partialStopped: Boolean = false,
        val partialResolutionInProgress: Boolean = false,
        val generationDetails: com.example.ui.screens.GenerationDetailsState = com.example.ui.screens.GenerationDetailsState(),
        val generationError: String? = null,
        val generationWarning: String? = null,
        val inChatToolStatus: InChatToolStatus? = null,
        val attachments: List<MessageAttachmentEntity> = emptyList(),
        val draftAttachments: List<MessageAttachmentEntity> = emptyList()
    ) : ChatUiState()
}

class ChatDetailViewModel(
    private val chatId: String,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val characterRepository: CharacterRepository,
    private val personaRepository: PersonaRepository,
    private val modelProvider: ModelProvider,
    private val memoryRepository: MemoryRepository,
    private val lorebookRepository: LorebookRepository? = null,
    private val coopSessionManager: CoopSessionManager? = null,
    private val messageAttachmentRepository: MessageAttachmentRepository? = null,
    private val npcRepository: NpcRepository? = null,
    private val npcOutputParser: NpcStructuredOutputParser? = null,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global,
    private val scenarioRepository: com.example.domain.repository.ScenarioRepository? = null,
    private val storyOutputParserFactory: () -> StoryOutputParser = ::StoryOutputParser,
    private val generationLifecycle: GenerationLifecycleReporter? = null,
    private val storyMemoryRepository: StoryMemoryRepository? = null
) : ViewModel() {

    private companion object {
        const val HISTORY_GROUP_PAGE_SIZE = 100
        const val INITIAL_VISIBLE_GROUPS = HISTORY_GROUP_PAGE_SIZE
        const val MAX_VISIBLE_HISTORY_GROUPS = Int.MAX_VALUE - 1
    }

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val currentDraft = MutableStateFlow("")
    val draft: StateFlow<String> = currentDraft.asStateFlow()

    private var chatSummary: ChatSummary? = null
    private var currentChatSettings: ChatSettingsEntity? = null
    private var selectedGroupCharacterId: String? = null
    private var generationJob: Job? = null
    private var sendJob: Job? = null
    private var loadJob: Job? = null
    private var pendingPartialGeneration: PendingPartialGeneration? = null
    private var generationStartInProgress = false
    private var sendInProgress = false
    private var inChatToolInProgress = false
    private var partialResolutionInProgress = false
    private var activeCoopGenerationRequestId: String? = null
    private var activeCoopGenerationExecutionId: String? = null
    private var coopContextAuthorizerToken: String? = null
    private val draftSaveMutex = Mutex()
    private val visibleHistoryGroupLimit = MutableStateFlow(INITIAL_VISIBLE_GROUPS)
    private val compiler = ContextCompilerV1()
    private val memoryManager = AutomaticMemoryManager(memoryRepository)
    private val parsedNpcOutput = npcOutputParser
    // Parsing is generation-only work. Keeping it lazy ensures opening any existing chat does not
    // initialize the structured-output stack before a narrated response is actually generated.
    private val storyOutputParser by lazy(LazyThreadSafetyMode.NONE, storyOutputParserFactory)

    init {
        loadData()
        viewModelScope.launch {
            var observedRevision = dataCoordinator.captureRevision()
            dataCoordinator.state.collect { maintenance ->
                if (maintenance.revision == observedRevision) return@collect
                observedRevision = maintenance.revision
                invalidateForDataReplacement()
                if (!maintenance.isReplacingData) loadData()
            }
        }
        coopSessionManager?.let { manager ->
            coopContextAuthorizerToken = manager.registerGenerationContextAuthorizer(
                chatId,
                ::authorizeCoopRequest
            )
            viewModelScope.launch {
                manager.generationCommands.collect { command ->
                    if (command.chatId != chatId) return@collect
                    try {
                        executeCoopGeneration(command)
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        // One lost acknowledgement must not permanently kill this chat's command
                        // collector. A later distinct approved execution must still be observable.
                        updateSuccessState {
                            it.copy(
                                generationError = error.message
                                    ?: "The room could not confirm the generation result."
                            )
                        }
                    }
                }
            }
        }
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val summary = chatRepository.getChatSummaryById(chatId)
            if (summary == null) {
                _uiState.value = ChatUiState.Error
                return@launch
            }
            chatSummary = summary
            if (summary.chat.chatType == ChatType.GROUP) {
                coopSessionManager?.load(chatId)
            }
            if (summary.chat.chatType == ChatType.GROUP) {
                selectedGroupCharacterId = summary.characters.firstOrNull()?.characterId
            }
            
            var settings = chatRepository.getChatSettings(chatId)
            if (settings == null) {
                settings = ChatSettingsEntity(chatId = chatId)
                chatRepository.insertChatSettings(settings)
            }
            currentChatSettings = settings
            currentDraft.value = settings.draftMessage ?: ""

            // Establish the mutable success state before any child collectors can emit. Room flows
            // may deliver their first value immediately; dropping that value while still Loading
            // leaves Group participants empty until the database happens to change again.
            _uiState.value = ChatUiState.Success(
                chatSummary = summary,
                messages = emptyList(),
                chatSettings = settings,
                historyLoaded = false,
                selectedGroupCharacterId = selectedGroupCharacterId
            )

            launch {
                chatRepository.getChatSettingsFlow(chatId).filterNotNull().collect { latestSettings ->
                    currentChatSettings = latestSettings
                    updateSuccessState { it.copy(chatSettings = latestSettings) }
                }
            }

            if (summary.chat.chatType == ChatType.GROUP) {
                launch {
                    characterRepository.getAllCharacters().collect { characters ->
                        updateSuccessState { it.copy(availableGroupCharacters = characters) }
                    }
                }
                launch {
                    chatRepository.getParticipantsForChat(chatId).collect { participants ->
                        val groupCharacters = buildList {
                            participants.sortedBy { it.sortPosition }.forEach { participant ->
                                characterRepository.getCharacterById(participant.characterId)?.let { character ->
                                    add(
                                        GroupCharacterState(
                                            character = character,
                                            isActive = participant.isActive,
                                            sortPosition = participant.sortPosition
                                        )
                                    )
                                }
                            }
                        }
                        val activeIds = groupCharacters.filter { it.isActive }.map { it.character.characterId }
                        if (selectedGroupCharacterId !in activeIds) {
                            selectedGroupCharacterId = activeIds.firstOrNull()
                        }
                        updateSuccessState {
                            it.copy(
                                groupCharacters = groupCharacters,
                                groupParticipantsLoaded = true,
                                selectedGroupCharacterId = selectedGroupCharacterId
                            )
                        }
                    }
                }
            }

            messageAttachmentRepository?.let { repository ->
                launch { runCatching { repository.reconcileOrphanFilesForChatOpenOnce() } }
                launch {
                    // Image metadata follows the same visible logical-group
                    // window as the transcript, rather than hydrating every
                    // image in a 10k-message chat. Drafts remain available at
                    // all times, including when no history has loaded.
                    visibleHistoryGroupLimit.collectLatest { groupLimit ->
                        repository.observeVisibleForChat(chatId, groupLimit)
                            .collect { attachments ->
                                updateSuccessState {
                                    it.copy(
                                        attachments = attachments.filter { attachment -> attachment.messageId != null },
                                        draftAttachments = attachments.filter { attachment -> attachment.messageId == null }
                                    )
                                }
                            }
                    }
                }
            }

            // Room returns complete variant groups, never a raw row slice.
            // Querying 101 logical groups keeps the ViewModel from hydrating
            // a 10k-turn archive merely to display the newest 100 bubbles.
            var observedLogicalGroupCount: Int? = null
            visibleHistoryGroupLimit.collectLatest { groupLimit ->
                messageRepository.observeRecentMessageGroups(chatId, groupLimit)
                    .collect { rows ->
                        val totalGroups = messageRepository.countLogicalMessageGroups(chatId)
                        val retainedLimit = retainVisibleScrollbackAfterInsert(
                            groupLimit, observedLogicalGroupCount, totalGroups,
                            olderPagesLoaded = groupLimit > INITIAL_VISIBLE_GROUPS
                        )
                        observedLogicalGroupCount = totalGroups
                        if (retainedLimit > groupLimit) {
                            // An incoming response must not evict the oldest
                            // bubble someone is currently reading. Expand the
                            // window first, then publish the refreshed page.
                            visibleHistoryGroupLimit.value = retainedLimit
                            return@collect
                        }
                        val selected = selectVisibleChatHistory(
                            hidePersistedNpcControlRegions(rows), groupLimit
                        )
                        updateSuccessState {
                            it.copy(
                                messages = selected.messages,
                                groupedMessages = selected.groupedMessages,
                                hasEarlierHistory = selected.hasEarlierGroups,
                                isLoadingEarlierHistory = false,
                                chatSummary = chatSummary ?: summary,
                                chatSettings = currentChatSettings!!,
                                historyLoaded = true
                            )
                        }
                    }
            }
        }
    }

    private fun invalidateForDataReplacement() {
        generationJob?.cancel()
        generationJob = null
        sendJob?.cancel()
        sendJob = null
        loadJob?.cancel()
        loadJob = null
        pendingPartialGeneration = null
        generationStartInProgress = false
        sendInProgress = false
        inChatToolInProgress = false
        partialResolutionInProgress = false
        activeCoopGenerationRequestId = null
        activeCoopGenerationExecutionId = null
        visibleHistoryGroupLimit.value = INITIAL_VISIBLE_GROUPS
        chatSummary = null
        currentChatSettings = null
        selectedGroupCharacterId = null
        currentDraft.value = ""
        _uiState.value = ChatUiState.Loading
    }

    private fun hidePersistedNpcControlRegions(
        messages: List<MessageEntity>
    ): List<MessageEntity> =
        messages.map { message ->
            if (message.speakerType == SpeakerType.CHARACTER) {
                message.copy(content = NpcStructuredOutputParser.finalVisibleStoryText(message.content))
            } else message
        }

    /** A further batch of complete message groups is retained for scrollback.
     * Older variants, editing, and Regenerate work once their group is visible.
     * Full archival history remains in Room and is never discarded.
     */
    fun loadEarlierMessages() {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (!state.historyLoaded || !state.hasEarlierHistory ||
            state.isLoadingEarlierHistory) return
        updateSuccessState { it.copy(isLoadingEarlierHistory = true) }
        visibleHistoryGroupLimit.update { limit ->
            (limit.toLong() + HISTORY_GROUP_PAGE_SIZE)
                .coerceAtMost(MAX_VISIBLE_HISTORY_GROUPS.toLong()).toInt()
        }
    }

    /**
     * The reader returned to the newest messages. Release older UI pages so
     * a long open chat stays bounded during ordinary conversation. Nothing
     * is deleted: scrolling up fetches the same archival groups from Room.
     */
    fun returnToLatestMessages() {
        if (visibleHistoryGroupLimit.value > INITIAL_VISIBLE_GROUPS) {
            visibleHistoryGroupLimit.value = INITIAL_VISIBLE_GROUPS
        }
    }

    fun onDraftChanged(newDraft: String) {
        currentDraft.value = newDraft
        viewModelScope.launch {
            draftSaveMutex.withLock {
                currentChatSettings?.let {
                    val updated = it.copy(draftMessage = newDraft)
                    chatRepository.insertChatSettings(updated)
                    currentChatSettings = updated
                }
            }
        }
    }

    fun wackContext() {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (rejectPrivateContextToolDuringCoop()) return
        if (!beginInChatTool(state, "Wacking old context…")) return
        viewModelScope.launch {
            try {
                val updatedChat = chatRepository.establishContextReset(chatId)
                    ?: throw IllegalStateException("The chat no longer exists.")
                val updatedSummary = (chatSummary ?: state.chatSummary).copy(chat = updatedChat)
                chatSummary = updatedSummary
                // Clear the tool gate before showing a terminal status: an immediate next action
                // must not be silently rejected after the UI says the tool has finished.
                inChatToolInProgress = false
                updateSuccessState {
                    it.copy(
                        chatSummary = updatedSummary,
                        inChatToolStatus = InChatToolStatus(
                            InChatToolStatusKind.SUCCESS,
                            "Wack complete. The visible transcript and saved data remain, while future context starts after this boundary."
                        )
                    )
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Clear the tool gate before showing a terminal status: an immediate next action
                // must not be silently rejected after the UI says the tool has finished.
                inChatToolInProgress = false
                updateSuccessState {
                    it.copy(
                        inChatToolStatus = InChatToolStatus(
                            InChatToolStatusKind.ERROR,
                            error.message ?: "Wack could not establish the context boundary."
                        )
                    )
                }
            } finally {
                inChatToolInProgress = false
            }
        }
    }

    fun updateMemoryNow() {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (rejectPrivateContextToolDuringCoop()) return
        if (!beginInChatTool(state, "Updating local memory…")) return
        viewModelScope.launch {
            try {
                val summary = chatSummary ?: state.chatSummary
                val characterId = when (summary.chat.chatType) {
                    ChatType.SCENARIO -> "narrator:$chatId"
                    ChatType.SOLO -> summary.characters.singleOrNull()?.characterId
                        ?: throw IllegalStateException("This Solo chat does not have exactly one Character to update.")
                    ChatType.GROUP -> selectedGroupCharacterId?.takeIf { selectedId ->
                        state.groupCharacters.any { participant ->
                            participant.isActive && participant.character.characterId == selectedId
                        }
                    } ?: throw IllegalStateException("Select an active Character before updating memory.")
                }
                // Stream selected Persona turns from Room instead of copying a
                // full transcript into the UI tool. The coordinator keeps
                // Wack, restore and variant changes from interleaving pages.
                val result = dataCoordinator.withMutation {
                    memoryManager.updateFromStoredConversation(
                        chatId = chatId,
                        characterId = characterId,
                        messageRepository = messageRepository,
                        resetBoundary = summary.chat.contextResetOrderIndex
                    )
                }
                val characterName = summary.characters
                    .firstOrNull { it.characterId == characterId }
                    ?.displayName
                    ?: if (summary.chat.chatType == ChatType.SCENARIO) "this scenario" else "the selected Character"
                val message = when {
                    result.savedMemoryCount > 0 && result.rollingSummaryUpdated ->
                        "Memory updated for $characterName: saved ${result.savedMemoryCount} new durable ${if (result.savedMemoryCount == 1) "memory" else "memories"} and refreshed the rolling summary."
                    result.savedMemoryCount > 0 ->
                        "Memory updated for $characterName: saved ${result.savedMemoryCount} new durable ${if (result.savedMemoryCount == 1) "memory" else "memories"}."
                    result.rollingSummaryUpdated ->
                        "Memory update finished for $characterName. No new durable memory was saved; the rolling summary was refreshed."
                    else ->
                        "Memory update finished for $characterName. No new durable memory was saved, and no rolling summary was needed."
                }
                // Clear the tool gate before showing a terminal status: an immediate next action
                // must not be silently rejected after the UI says the tool has finished.
                inChatToolInProgress = false
                updateSuccessState {
                    it.copy(inChatToolStatus = InChatToolStatus(InChatToolStatusKind.SUCCESS, message))
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Clear the tool gate before showing a terminal status: an immediate next action
                // must not be silently rejected after the UI says the tool has finished.
                inChatToolInProgress = false
                updateSuccessState {
                    it.copy(
                        inChatToolStatus = InChatToolStatus(
                            InChatToolStatusKind.ERROR,
                            error.message ?: "Local memory could not be updated."
                        )
                    )
                }
            } finally {
                inChatToolInProgress = false
            }
        }
    }

    private fun beginInChatTool(state: ChatUiState.Success, workingMessage: String): Boolean {
        if (inChatToolInProgress) return false
        val blockedMessage = when {
            !state.historyLoaded -> "Wait for chat history to finish loading."
            sendInProgress -> "Wait for the pending message save to finish."
            state.isGenerating || generationStartInProgress || generationJob?.isActive == true ->
                "Wait for generation to finish before using this tool."
            state.partialStopped || pendingPartialGeneration != null ->
                "Keep or discard the unresolved partial reply before using this tool."
            else -> null
        }
        if (blockedMessage != null) {
            updateSuccessState {
                it.copy(inChatToolStatus = InChatToolStatus(InChatToolStatusKind.ERROR, blockedMessage))
            }
            return false
        }
        inChatToolInProgress = true
        updateSuccessState {
            it.copy(inChatToolStatus = InChatToolStatus(InChatToolStatusKind.WORKING, workingMessage))
        }
        return true
    }

    private fun rejectPrivateContextToolDuringCoop(): Boolean {
        val configured = coopSessionManager?.state?.value?.room?.chatId == chatId
        if (!configured) return false
        updateSuccessState {
            it.copy(
                inChatToolStatus = InChatToolStatus(
                    InChatToolStatusKind.ERROR,
                    "End local co-op before changing this device's private context or memory. This action never changes a partner's reset boundary or memory."
                )
            )
        }
        return true
    }

    private fun nextOrderIndex(boundary: Long?): Long {
        val minimum = when (boundary) {
            null -> Long.MIN_VALUE
            Long.MAX_VALUE -> Long.MAX_VALUE
            else -> boundary + 1
        }
        return maxOf(System.currentTimeMillis(), minimum)
    }

    fun sendMessage() {
        val text = currentDraft.value
        val state = _uiState.value as? ChatUiState.Success ?: return
        if ((text.isBlank() && state.draftAttachments.isEmpty()) || sendInProgress || inChatToolInProgress) return
        if (!canMutateMessageNow()) return
        val summary = chatSummary ?: return
        val activeCoopRoom = coopSessionManager?.state?.value?.room?.takeIf { it.chatId == chatId }
        if (activeCoopRoom != null && activeCoopRoom.connectionState != CoopConnectionState.CONNECTED) {
            updateSuccessState {
                it.copy(generationError = "Host is offline. Co-op sending is suspended until reconnect.")
            }
            return
        }
        val coopRoom = coopSessionManager?.state?.value?.room?.takeIf { it.chatId == chatId }
        if (coopRoom != null && state.draftAttachments.isNotEmpty()) {
            updateSuccessState {
                it.copy(generationError = "Image attachments are unavailable in local co-op until bounded binary media sync is implemented.")
            }
            return
        }
        val ownedPersonaId = coopRoom?.let { room ->
            coopSessionManager.state.value.personaOwnerships
                .firstOrNull { it.participantId == room.localParticipantId && it.isActive }
                ?.personaId
        }
        val persona = if (coopRoom != null) {
            summary.personas.firstOrNull { it.personaId == ownedPersonaId }
                ?: run {
                    updateSuccessState {
                        it.copy(generationError = "This device does not own an active Persona in the co-op room.")
                    }
                    return
                }
        } else {
            summary.personas.firstOrNull() ?: return
        }

        val dataRevision = dataCoordinator.captureRevision()
        sendInProgress = true
        sendJob = viewModelScope.launch {
            val localParticipant = coopRoom?.let { room ->
                coopSessionManager?.state?.value?.participants
                    ?.firstOrNull { it.participantId == room.localParticipantId }
            }
            val userMsg = MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = persona.personaId,
                speakerDisplayNameSnapshot = persona.displayName,
                content = text,
                orderIndex = nextOrderIndex(summary.chat.contextResetOrderIndex),
                responsibleParticipantId = localParticipant?.participantId,
                responsibleDeviceId = localParticipant?.deviceId
            )
            try {
                if (coopRoom != null) {
                    requireNotNull(coopSessionManager).publishMessage(userMsg)
                } else {
                    dataCoordinator.withMutation(dataRevision) {
                        if (state.draftAttachments.isNotEmpty()) {
                            requireNotNull(messageAttachmentRepository) { "Image attachment storage is unavailable." }
                                .persistMessageWithDrafts(userMsg)
                        } else {
                            messageRepository.insertMessage(userMsg)
                        }
                        currentChatSettings?.let { settings ->
                            val clearedSettings = settings.copy(draftMessage = "")
                            chatRepository.insertChatSettings(clearedSettings)
                            currentChatSettings = clearedSettings
                        }
                    }
                }
                if (coopRoom != null) {
                    dataCoordinator.withMutation(dataRevision) {
                        currentChatSettings?.let { settings ->
                            val clearedSettings = settings.copy(draftMessage = "")
                            chatRepository.insertChatSettings(clearedSettings)
                            currentChatSettings = clearedSettings
                        }
                    }
                }
                currentDraft.value = ""
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                updateSuccessState {
                    it.copy(
                        generationError = if (coopRoom != null) {
                            "The host did not confirm this message: ${error.message ?: "unknown error"}. Your draft was kept."
                        } else {
                            "The message could not be saved. Your draft was kept."
                        }
                    )
                }
                sendInProgress = false
                return@launch
            }

            sendInProgress = false
            updateSuccessState { it.copy(generationError = null, generationWarning = null) }
            if (summary.chat.chatType == ChatType.GROUP) {
                dataCoordinator.withMutation(dataRevision) {
                    chatRepository.touchChatTimestamp(chatId)
                }
                return@launch
            }
            try {
                startGeneration(userMsg, dataRevision)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updateSuccessState {
                    it.copy(generationError = "Your message was saved, but generation could not start. You can retry this turn.")
                }
            }
        }
    }

    fun addDraftImage(uri: android.net.Uri) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (state.isGenerating) return
        if (coopSessionManager?.state?.value?.room?.chatId == chatId) {
            updateSuccessState {
                it.copy(generationError = "Image attachments are unavailable in local co-op until media sync is implemented.")
            }
            return
        }
        val repository = messageAttachmentRepository ?: run {
            updateSuccessState { it.copy(generationError = "Image attachment storage is unavailable.") }
            return
        }
        viewModelScope.launch {
            runCatching { repository.importDraftImage(chatId, uri) }
                .onSuccess { updateSuccessState { it.copy(generationError = null) } }
                .onFailure { error -> updateSuccessState { it.copy(generationError = error.message ?: "The image could not be attached.") } }
        }
    }

    fun removeDraftImage(attachmentId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val attachment = state.draftAttachments.firstOrNull { it.attachmentId == attachmentId } ?: return
        viewModelScope.launch {
            runCatching { messageAttachmentRepository?.removeDraft(attachment) }
                .onFailure { error -> updateSuccessState { it.copy(generationError = error.message ?: "The image could not be removed.") } }
        }
    }

    override fun onCleared() {
        coopContextAuthorizerToken?.let { token ->
            coopSessionManager?.unregisterGenerationContextAuthorizer(chatId, token)
        }
        super.onCleared()
    }

    private suspend fun startGeneration(
        userMsg: MessageEntity,
        dataRevision: AppDataRevision = dataCoordinator.captureRevision()
    ) {
        val currentState = _uiState.value as? ChatUiState.Success ?: return
        if (inChatToolInProgress || generationStartInProgress || currentState.isGenerating) return
        generationStartInProgress = true
        try {
            val state = _uiState.value as? ChatUiState.Success ?: return
            // The freshly persisted Persona turn is the latest selected
            // message. Normal prompt assembly needs only recent history;
            // legacy automatic-memory maintenance now reads its own source
            // from Room after successful persistence.
            val latest = messageRepository.getLatestPrimaryWindow(chatId, 25)
            val history = if (latest.lastOrNull()?.messageId == userMsg.messageId) {
                latest.dropLast(1)
            } else {
                // Preserve the established behavior for an unexpected ordering
                // race (e.g. an imported chat with equal order indices).
                state.messages.filter {
                    it.isPrimaryVariant && it.messageId != userMsg.messageId
                }
            }
            startGenerationInternal(userMsg, history, dataRevision = dataRevision)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            pendingPartialGeneration = null
            updateSuccessState {
                it.copy(
                    isGenerating = false,
                    streamingContent = null,
                    generationCharacterId = null,
                    generationError = "The response could not be prepared. No provider request was sent."
                )
            }
        } finally {
            generationStartInProgress = false
        }
    }

    fun selectGroupCharacter(characterId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (
            state.chatSummary.chat.chatType != ChatType.GROUP ||
            state.isGenerating ||
            generationStartInProgress ||
            inChatToolInProgress
        ) return
        if (state.groupCharacters.none { it.isActive && it.character.characterId == characterId }) return

        selectedGroupCharacterId = characterId
        updateSuccessState { it.copy(selectedGroupCharacterId = characterId) }
    }

    fun setGroupCharacterActive(characterId: String, isActive: Boolean) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (state.chatSummary.chat.chatType != ChatType.GROUP || state.isGenerating) return
        if (!canEditCoopRoster()) return
        viewModelScope.launch {
            chatRepository.setGroupCharacterActive(chatId, characterId, isActive)
            publishCurrentRoster()
        }
    }

    fun moveGroupCharacter(characterId: String, direction: Int) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (state.chatSummary.chat.chatType != ChatType.GROUP || state.isGenerating) return
        if (direction !in setOf(-1, 1)) return
        if (!canEditCoopRoster()) return
        viewModelScope.launch {
            chatRepository.moveGroupCharacter(chatId, characterId, direction)
            publishCurrentRoster()
        }
    }

    fun removeGroupCharacter(characterId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (state.chatSummary.chat.chatType != ChatType.GROUP || state.isGenerating) return
        if (!canEditCoopRoster()) return
        viewModelScope.launch {
            chatRepository.removeGroupCharacter(chatId, characterId)
            publishCurrentRoster()
        }
    }

    fun addGroupCharacter(characterId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (state.chatSummary.chat.chatType != ChatType.GROUP || state.isGenerating) return
        if (state.groupCharacters.any { it.character.characterId == characterId }) return
        if (!canEditCoopRoster()) return
        viewModelScope.launch {
            chatRepository.addGroupCharacter(chatId, characterId)
            publishCurrentRoster()
        }
    }

    private fun canEditCoopRoster(): Boolean {
        val room = coopSessionManager?.state?.value?.room?.takeIf { it.chatId == chatId } ?: return true
        updateSuccessState {
            it.copy(generationError = "End local co-op before changing the AI Character roster. The room roster is frozen for bounded testing.")
        }
        return false
    }

    private suspend fun publishCurrentRoster() {
        val manager = coopSessionManager ?: return
        val participants = chatRepository.getParticipantsForChatOnce(chatId)
        val roster = participants.sortedBy { it.sortPosition }.mapNotNull { participant ->
            characterRepository.getCharacterById(participant.characterId)?.let { character ->
                CoopCharacterRosterItem(
                    characterId = participant.characterId,
                    displayNameSnapshot = character.displayName,
                    isActive = participant.isActive,
                    sortPosition = participant.sortPosition
                )
            }
        }
        runCatching { manager.publishCharacterRoster(chatId, CoopCharacterRosterPayload(roster)) }
            .onFailure { error ->
                updateSuccessState { it.copy(generationError = "Roster changed locally but co-op sync failed: ${error.message}") }
            }
    }

    fun generateGroupResponse(characterId: String? = null) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (
            state.chatSummary.chat.chatType != ChatType.GROUP ||
            state.isGenerating ||
            generationStartInProgress ||
            inChatToolInProgress
        ) return
        val selectedId = characterId
            ?: state.generationCharacterId
            ?: state.selectedGroupCharacterId
            ?: return
        if (state.groupCharacters.none { it.isActive && it.character.characterId == selectedId }) return

        selectedGroupCharacterId = selectedId
        updateSuccessState { it.copy(selectedGroupCharacterId = selectedId, generationError = null) }
        generationStartInProgress = true
        viewModelScope.launch {
            try {
                if (coopSessionManager?.requestGeneration(chatId, selectedId, "NORMAL") == true) {
                    return@launch
                }
                // A Group Character responding to another Character cannot
                // trigger legacy Persona-memory extraction. It only needs
                // recent context for prompt assembly, not a transcript-sized
                // read. Co-op keeps the full synchronized-history contract.
                val inCoopRoom = coopSessionManager?.state?.value?.room?.chatId == chatId
                val boundedSnapshot = if (!inCoopRoom) {
                    messageRepository.getSelectedGroupGenerationWindow(
                        chatId, chatSummary?.chat?.contextResetOrderIndex, 25
                    )
                } else null
                val recentPrimary = boundedSnapshot?.messages.orEmpty()
                val recentCurrent = recentPrimary.lastOrNull()
                val (currentMessage, selectedHistory) =
                    if (recentCurrent != null && !inCoopRoom) {
                        val boundedCurrent = requireNotNull(recentCurrent)
                        val latestPersona = boundedSnapshot?.latestPersona
                        val windowHistory = recentPrimary.dropLast(1)
                        val hasPersona = windowHistory.any { it.speakerType == SpeakerType.PERSONA }
                        // Preserve the selected Persona after 24+ AI turns
                        // without allowing pre-Wack Persona history to return.
                        val history = if (!hasPersona && latestPersona != null &&
                            latestPersona.messageId != boundedCurrent.messageId &&
                            latestPersona.orderIndex <= boundedCurrent.orderIndex
                        ) listOf(latestPersona) + windowHistory else windowHistory
                        hidePersistedNpcControlRegions(listOf(boundedCurrent)).single() to
                            hidePersistedNpcControlRegions(history)
                    } else {
                        // Co-op retains its full synchronized-history contract.
                        // Local Persona turns already use the bounded window;
                        // their automatic memory is maintained separately.
                        val full = hidePersistedNpcControlRegions(
                            messageRepository.getMessagesForChatOnce(chatId)
                        ).filter { it.isPrimaryVariant }
                        full.lastOrNull() to full.dropLast(1)
                    }
                if (currentMessage == null) {
                    updateSuccessState {
                        it.copy(generationError = "Send a Persona message before generating a Character reply.")
                    }
                    return@launch
                }
                startGenerationInternal(
                    currentMessage = currentMessage,
                    history = selectedHistory,
                    characterId = selectedId
                )
            } catch (error: Exception) {
                updateSuccessState {
                    it.copy(generationError = error.message ?: "Co-op generation could not be requested")
                }
            } finally {
                generationStartInProgress = false
            }
        }
    }

    private suspend fun executeCoopGeneration(command: CoopGenerationCommand) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val manager = coopSessionManager ?: return
        // Claim before any failure/completion side effect so duplicate retained ViewModels are inert.
        if (!manager.tryClaimGenerationExecution(command.executionId)) return
        if (state.isGenerating || generationStartInProgress || inChatToolInProgress) {
            manager.finishGeneration(
                chatId,
                command.request.requestId,
                messageId = null,
                error = "Assigned payer is already generating."
            )
            return
        }
        if (state.groupCharacters.none {
                it.isActive && it.character.characterId == command.request.characterId
            }) {
            manager.finishGeneration(
                chatId,
                command.request.requestId,
                messageId = null,
                error = "Requested Character is unavailable or inactive on the payer device."
            )
            return
        }
        generationStartInProgress = true
        generationJob = null
        activeCoopGenerationRequestId = command.request.requestId
        activeCoopGenerationExecutionId = command.executionId
        try {
            val input = resolveCoopGenerationInput(command.request)
            val localFingerprint = authorizeCoopRequest(command.request).contextFingerprint
            val hostFingerprint = command.request.contextFingerprint
                ?: throw IllegalStateException("Host did not authorize a generation context fingerprint")
            if (localFingerprint != hostFingerprint) {
                throw IllegalStateException(
                    "Generation blocked: this payer's Character, Persona, Lorebook, memory, model settings, or room history does not match the host. No provider request was sent."
                )
            }
            startGenerationInternal(
                currentMessage = input.currentMessage,
                history = input.history,
                variantGroupId = input.variantGroupId,
                isContinuation = input.isContinuation,
                continuationTarget = input.continuationTarget,
                characterId = command.request.characterId
            )
            if (generationJob == null) {
                throw IllegalStateException("The payer device could not prepare this generation. Check its local Character, Persona, Lorebook, and model settings.")
            }
        } catch (error: Exception) {
            val completionError = finishCoopGenerationWithError(
                if (error.message?.startsWith("Generation blocked:") == true) {
                    "CONTEXT_MISMATCH"
                } else {
                    "GENERATION_FAILED"
                }
            )
            updateSuccessState {
                it.copy(
                    generationError = if (completionError == null) {
                        error.message ?: "Unable to start generation"
                    } else {
                        "${error.message ?: "Unable to start generation"} The room has not acknowledged the failure; reconnect and resolve it before another generation."
                    }
                )
            }
        } finally {
            generationStartInProgress = false
        }
    }

    private suspend fun startGenerationInternal(
        currentMessage: MessageEntity,
        history: List<MessageEntity>,
        variantGroupId: String? = null,
        isContinuation: Boolean = false,
        continuationTarget: MessageEntity? = null,
        characterId: String? = null,
        dataRevision: AppDataRevision = dataCoordinator.captureRevision()
    ) {
        if (inChatToolInProgress) return
        val boundary = chatSummary?.chat?.contextResetOrderIndex
        if (!ContextResetPolicy.includes(currentMessage, boundary)) {
            updateSuccessState {
                it.copy(
                    generationError = "This message is before the Wack context boundary. Retry, Regenerate, and Continue cannot use pre-reset messages."
                )
            }
            return
        }
        dataCoordinator.withMutation(dataRevision) { Unit }
        // Most stories have no Wack boundary. Reuse the existing ordered list
        // instead of allocating a 10k-turn copy for every generation.
        val scopedHistory = if (boundary == null) history
            else history.filter { ContextResetPolicy.includes(it, boundary) }
        val prepared = prepareGeneration(
            currentMessage = currentMessage,
            history = scopedHistory,
            isContinuation = isContinuation,
            characterId = characterId
        ) ?: return
        val fullCharacter = prepared.character
        updateSuccessState {
            it.copy(
                isGenerating = true,
                streamingContent = "",
                partialStopped = false,
                partialResolutionInProgress = false,
                generationError = null,
                generationWarning = prepared.contextReductionNotice,
                generationCharacterId = fullCharacter.characterId
            )
        }
        partialResolutionInProgress = false

        var generatedContent = ""
        var currentMetadata: GenerationMetadataEntity? = null
        var stopped = false
        var wasUserStopped = false
        var generationError: String? = null
        // One immutable view is shared by completion and Keep Partial; avoid
        // copying the full historical list twice for a single generation.
        val turnContextMessages = com.example.domain.compiler.GenerationHistoryWithCurrent(
            scopedHistory, currentMessage
        )
        pendingPartialGeneration = PendingPartialGeneration(
            character = fullCharacter,
            variantGroupId = variantGroupId,
            sourceMessage = currentMessage,
            contextMessages = turnContextMessages,
            continuationTarget = continuationTarget,
            npcBankEnabled = prepared.npcBankEnabled,
            activePersonaId = prepared.activePersonaId,
            dataRevision = dataRevision,
            coLeadRebalanceActive = prepared.coLeadRebalanceActive
        )
        
        runCatching {
            generationLifecycle?.started(chatId, chatSummary?.chat?.displayName ?: "Chat")
        }
        generationJob = viewModelScope.launch {
            // Generation preparation is complete once the provider job starts. Clear this before
            // any terminal UI state can be published so an immediately tapped Retry is not lost.
            generationStartInProgress = false
            try {
                try {
                modelProvider.streamResponse(prepared.messages, prepared.options).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            if (event.text.isNotEmpty()) {
                                generatedContent += event.text
                                val visibleContent = visibleGeneratedContent(
                                    generatedContent,
                                    prepared
                                )
                                updateSuccessState { it.copy(streamingContent = visibleContent) }
                            }
                        }
                    is StreamEvent.Metadata -> {
                        currentMetadata = event.metadata
                    }
                        is StreamEvent.Done -> {
                        currentMetadata = event.metadata ?: currentMetadata
                        }
                        is StreamEvent.Error -> {
                            stopped = true
                            generationError = event.exception.message ?: "Generation failed"
                        }
                    }
                }
            } catch(e: kotlinx.coroutines.CancellationException) {
                stopped = true
                wasUserStopped = true
            } catch (error: Exception) {
                stopped = true
                generationError = error.message ?: "Generation failed"
            }
            // Publish terminal UI state only after the single-flight handle is released. Otherwise
            // a tap in the tiny window between isGenerating=false and coroutine completion is
            // incorrectly rejected as if generation were still running.
            generationJob = null

            try {
                if (stopped) {
                    val visiblePartial = visibleGeneratedContent(generatedContent, prepared)
                    retainPartialGeneration(
                        visibleContent = visiblePartial,
                        metadata = currentMetadata,
                        requestedModelId = prepared.options.modelId,
                        wasUserStopped = wasUserStopped,
                        errorMessage = generationError
                    )
                    if (!hasVisibleContent(visiblePartial)) {
                        finishCoopGenerationWithError(generationError ?: "Generation stopped")
                    }
                } else if (generatedContent.isBlank()) {
                    pendingPartialGeneration = null
                    val finishReason = currentMetadata?.finishReason?.takeIf(String::isNotBlank)
                    updateSuccessState {
                        it.copy(
                            isGenerating = false,
                            streamingContent = null,
                            generationCharacterId = null,
                            generationError = finishReason?.let {
                                "The provider returned no visible response (finish reason: $it). You can retry this turn."
                            } ?: "The provider returned no response. You can retry this turn."
                        )
                    }
                    finishCoopGenerationWithError("Provider returned no visible response")
                } else {
                    val parsed = if (prepared.npcBankEnabled && parsedNpcOutput != null) {
                        parsedNpcOutput.parse(generatedContent)
                    } else {
                        com.example.domain.npc.ParsedNpcOutput(generatedContent)
                    }
                    // A narrator-off reply may still imitate earlier typed speaker labels.
                    // Normalize clear transcript output, but preserve ordinary Solo prose.
                    val explicitSpeakerFormat = prepared.character.type == SpeakerType.CHARACTER &&
                        StoryOutputParser.hasExplicitSpeakerFormatting(
                            parsed.storyText, fullCharacter.displayName
                        )
                    val storyParsed = if (prepared.ensembleNarrationEnabled || explicitSpeakerFormat) {
                        storyOutputParser.parse(
                            output = parsed.storyText,
                            primaryCharacter = StorySpeakerIdentity(fullCharacter.characterId, fullCharacter.displayName),
                            knownNpcs = prepared.knownNpcSpeakers,
                            final = true
                        )
                    } else null
                    val persistedContent = storyParsed?.response?.let(StoryResponseCodec::encode)
                        ?: parsed.storyText
                    val formatWarning = listOfNotNull(parsed.warning, storyParsed?.warning)
                        .joinToString(" ")
                        .takeIf(String::isNotBlank)
                    val saved = saveGeneratedMessage(
                        content = persistedContent,
                        metadata = currentMetadata,
                        character = fullCharacter,
                        variantGroupId = variantGroupId,
                        sourceMessage = currentMessage,
                        contextMessages = turnContextMessages,
                        continuationTarget = continuationTarget,
                        npcUpdates = parsed.updates,
                        npcWarning = formatWarning,
                        activePersonaId = prepared.activePersonaId,
                        coLeadRebalanceActive = prepared.coLeadRebalanceActive,
                        dataRevision = dataRevision
                    )
                    if (saved) pendingPartialGeneration = null
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                val visiblePartial = safeVisibleGeneratedContent(generatedContent, prepared)
                retainPartialGeneration(
                    visibleContent = visiblePartial,
                    metadata = currentMetadata,
                    requestedModelId = prepared.options.modelId,
                    wasUserStopped = false,
                    errorMessage = "The response could not be processed safely. Generated text was retained so you can keep or discard it."
                )
            }
            } finally {
                runCatching { generationLifecycle?.finished(chatId) }
            }
        }
    }

    private fun retainPartialGeneration(
        visibleContent: String,
        metadata: GenerationMetadataEntity?,
        requestedModelId: String,
        wasUserStopped: Boolean,
        errorMessage: String?
    ) {
        val hasContent = hasVisibleContent(visibleContent)
        if (!hasContent) {
            pendingPartialGeneration = null
        } else {
            val partialMetadata = (metadata ?: GenerationMetadataEntity(
                messageId = "pending-partial",
                requestedModelId = requestedModelId
            )).copy(
                isPartial = true,
                wasUserStopped = wasUserStopped,
                finishReason = metadata?.finishReason
                    ?: if (wasUserStopped) "user_stopped" else "partial_error"
            )
            pendingPartialGeneration = pendingPartialGeneration?.copy(metadata = partialMetadata)
        }
        updateSuccessState {
            it.copy(
                isGenerating = false,
                partialStopped = hasContent,
                partialResolutionInProgress = false,
                streamingContent = visibleContent.takeIf { hasContent },
                generationCharacterId = if (hasContent) it.generationCharacterId else null,
                generationError = errorMessage
            )
        }
    }

    private fun safeVisibleGeneratedContent(content: String, prepared: PreparedGeneration): String {
        return runCatching { visibleGeneratedContent(content, prepared) }.getOrElse {
            val storyText = if (prepared.npcBankEnabled) {
                runCatching { NpcStructuredOutputParser.visibleStoryText(content) }.getOrDefault(content)
            } else content
            if (prepared.ensembleNarrationEnabled) {
                StoryOutputParser.fallbackVisibleText(storyText)
            } else storyText
        }
    }

    private fun searchScene(history: List<MessageEntity>, current: MessageEntity): String =
        (history.takeLast(6) + current).joinToString(" ") { it.content }.takeLast(4_000)

    /**
     * One shared, offline preflight for Solo, Group, Scenario and co-op.
     * Rebuild optional generated continuity sections, NEVER delete substrings
     * from potentially user-authored system or World content. The final
     * returned messages are also used in the co-op context fingerprint.
     */
    private fun fitPromptBudget(
        modelId: String,
        available: PromptOptionalContextChoices,
        compile: (PromptOptionalContextChoices) -> List<RoleplayMessage>
    ): PromptOptionalContextFit? {
        val result = PromptOptionalContextFitter.fit(
            available = available,
            modelContextWindowTokens = modelProvider.cachedContextLength(modelId),
            compile = compile
        )
        if (result.decision.allowed) return result
        updateSuccessState {
            it.copy(generationError =
                "Context exceeds the safe input budget (estimated ${result.decision.estimatedInputTokens} tokens; " +
                "limit ${result.decision.inputCeilingTokens}), even after excluding optional generated " +
                "memories, summaries and old turns. Reduce authored lore or context before retrying. " +
                "No API request was sent.")
        }
        return null
    }

    private suspend fun prepareScenario(current: MessageEntity, history: List<MessageEntity>, continuation: Boolean): PreparedGeneration? {
        val settings = chatRepository.getEffectiveChatSettings(chatId) ?: currentChatSettings ?: return null
        val session = scenarioRepository?.session(chatId)
        if (session == null) {
            updateSuccessState { it.copy(generationError = "This playthrough's scenario configuration is unavailable.") }
            return null
        }
        val latestPersonaMessage = current.takeIf { it.speakerType == SpeakerType.PERSONA }
            ?: history.lastOrNull { it.speakerType == SpeakerType.PERSONA }
        val personaId = latestPersonaMessage?.speakerId
            ?: chatSummary?.personas?.firstOrNull()?.personaId ?: return null
        val persona = personaRepository.getPersonaById(personaId) ?: return null
        if (settings.providerRoutingMode == com.example.data.model.ProviderRoutingMode.LOCK && settings.providerEndpoint.isNullOrBlank()) {
            updateSuccessState { it.copy(generationError = "Locked routing requires a selected provider endpoint.") }
            return null
        }
        val modelId = settings.selectedModelId ?: "openrouter/auto"
        val source = latestPersonaMessage?.takeUnless { continuation }
        val images = source?.let { messageAttachmentRepository?.providerImagesForMessage(it.messageId) }.orEmpty()
        if (images.isNotEmpty() && modelProvider.supportsImageInput(modelId) != true) {
            updateSuccessState { it.copy(generationError = "Choose a model with confirmed image input support, then retry.") }
            return null
        }
        val summary = ContextResetPolicy.eligibleSummary(memoryRepository.getSummaryOnce(chatId), chatSummary?.chat?.contextResetOrderIndex)
            ?.takeIf { it.summarizedThroughOrderIndex < current.orderIndex }?.summary
        val selectedStoryMemories = storyMemoryRepository?.retrieveForGeneration(
            chatId = chatId,
            recipient = StoryActorRef(StoryActorKind.NARRATOR, "narrator:$chatId"),
            sceneText = searchScene(history, current)
        )?.selected.orEmpty()
        val scenarioLegacyCandidates = memoryRepository.getMemoriesOnce(chatId, "narrator:$chatId")
        val legacyMemories = LegacyMemoryEligibilityFilter.eligible(
            memories = scenarioLegacyCandidates,
            chatId = chatId,
            recipient = StoryActorRef(StoryActorKind.NARRATOR, "narrator:$chatId"),
            boundary = chatSummary?.chat?.contextResetOrderIndex,
            archivedMessages = messageRepository.getLegacyMemoryProvenance(chatId, scenarioLegacyCandidates)
        )
        val activeLore = lorebookRepository?.resolveActiveLore(
            chatId, history, current, AutomaticMemoryManager.DEFAULT_RECENT_MESSAGE_LIMIT
        ).orEmpty()
        val worldContext = scenarioRepository?.worldContextForChat(chatId)
        val scenarioDefinition = com.example.data.model.ScenarioCodec.decode(session.definitionJson)
        // The link is a stable authored Character ID, NOT a name match and never a
        // license to pull history or knowledge from that Character's other chats.
        val linkedCharacters = mutableMapOf<String, com.example.data.model.CharacterEntity>()
        for (linkedId in scenarioDefinition.npcs.mapNotNull { it.sourceCharacterId }.distinct()) {
            val definition = characterRepository.getCharacterById(linkedId)
            if (definition == null) {
                val npcName = scenarioDefinition.npcs.first { it.sourceCharacterId == linkedId }.name
                updateSuccessState {
                    it.copy(generationError =
                        "Scenario NPC $npcName links a Character that no longer exists. " +
                        "Edit this playthrough to replace or unlink that Character. " +
                        "No model request was sent.")
                }
                return null
            }
            linkedCharacters[linkedId] = definition
        }
        val scenarioCompiler = com.example.domain.compiler.ScenarioContextCompiler()
        fun compileWithMemories(
            story: List<StoryMemoryRecord>,
            includeLegacy: Boolean = true,
            includeSummary: Boolean = true
        ) = scenarioCompiler.compile(
            scenarioDefinition, persona, settings, history, current,
            summary = if (includeSummary) summary else null,
            memories = if (includeLegacy) legacyMemories else emptyList(),
            lore = activeLore,
            continuation = continuation, worldContext = worldContext,
            storyMemories = story,
            linkedCharacters = linkedCharacters
        )
        // Admit reviewed story memories against the protected authored baseline.
        // Generated summaries/legacy memory are lower priority and may be
        // excluded by the final shared prompt fitter if space is tight.
        val baseline = compileWithMemories(
            emptyList(), includeLegacy = false, includeSummary = false
        )
        val allowedStoryMemories = StoryMemoryPromptBudget.select(
            baseline, selectedStoryMemories, modelProvider.cachedContextLength(modelId)
        ).selected
        val fitted = fitPromptBudget(
            modelId = modelId,
            available = PromptOptionalContextChoices(
                includeLegacyMemories = legacyMemories.isNotEmpty(),
                includeStoryMemories = allowedStoryMemories.isNotEmpty(),
                includeSummary = !summary.isNullOrBlank()
            )
        ) { choices ->
            compileWithMemories(
                if (choices.includeStoryMemories) allowedStoryMemories else emptyList(),
                includeLegacy = choices.includeLegacyMemories,
                includeSummary = choices.includeSummary
            ).map {
                if (it.sourceMessageId == source?.messageId && images.isNotEmpty()) {
                    it.copy(images = images)
                } else it
            }
        } ?: return null
        return PreparedGeneration(
            ResponseSpeaker("narrator:$chatId", "Narrator", SpeakerType.NARRATOR),
            fitted.messages,
            GenerationOptions(
                modelId = modelId,
                routing = ModelRouting.fromSettings(
                    settings.providerRoutingMode, settings.providerEndpoint
                ),
                temperature = settings.temperature, topP = settings.topP, maxTokens = null
            ),
            activePersonaId = personaId,
            contextReductionNotice = fitted.countsOnlyNotice()
        )
    }

    private suspend fun prepareGeneration(
        currentMessage: MessageEntity,
        history: List<MessageEntity>,
        isContinuation: Boolean,
        characterId: String?
    ): PreparedGeneration? {
        val summary = chatSummary ?: return null
        val settings = chatRepository.getEffectiveChatSettings(chatId) ?: currentChatSettings ?: return null
        if (summary.chat.chatType == ChatType.SCENARIO) return prepareScenario(currentMessage, history, isContinuation)
        val resolvedCharacterId = characterId ?: summary.characters.firstOrNull()?.characterId ?: return null
        val fullCharacter = characterRepository.getCharacterById(resolvedCharacterId) ?: return null
        val relevantPersonaMessage = currentMessage.takeIf { it.speakerType == SpeakerType.PERSONA }
            ?: history.lastOrNull { it.speakerType == SpeakerType.PERSONA }
        val relevantPersonaId = relevantPersonaMessage?.speakerId
            ?: summary.personas.firstOrNull()?.personaId
            ?: return null
        val fullPersona = personaRepository.getPersonaById(relevantPersonaId)
            ?: relevantPersonaMessage?.let { message ->
                PersonaEntity(
                    personaId = message.speakerId,
                    displayName = message.speakerDisplayNameSnapshot,
                    personaName = message.speakerDisplayNameSnapshot,
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
            ?: return null
        if (settings.providerRoutingMode == com.example.data.model.ProviderRoutingMode.LOCK && settings.providerEndpoint.isNullOrBlank()) {
            updateSuccessState {
                it.copy(generationError = "Locked routing requires a selected provider endpoint.")
            }
            return null
        }
        val legacyCandidates = memoryRepository.getMemoriesOnce(chatId, fullCharacter.characterId)
        val durableMemories = LegacyMemoryEligibilityFilter.eligible(
            memories = legacyCandidates,
            chatId = chatId,
            recipient = StoryActorRef(StoryActorKind.CHARACTER, fullCharacter.characterId),
            boundary = summary.chat.contextResetOrderIndex,
            archivedMessages = messageRepository.getLegacyMemoryProvenance(chatId, legacyCandidates)
        )
        val rollingSummary = ContextResetPolicy.eligibleSummary(
            memoryRepository.getSummaryOnce(chatId),
            summary.chat.contextResetOrderIndex
        )
            ?.summary
        val activeLore = lorebookRepository?.resolveActiveLore(
            chatId = chatId,
            chatHistory = history,
            currentMessage = currentMessage,
            recentMessageLimit = AutomaticMemoryManager.DEFAULT_RECENT_MESSAGE_LIMIT
        ).orEmpty()
        val activeCoopRoom = coopSessionManager?.state?.value?.room?.takeIf { it.chatId == chatId }
        // Co-op remains on its previous shared-context contract until both
        // devices agree on memory source and recipient eligibility.
        val selectedStoryMemories = if (activeCoopRoom == null) {
            storyMemoryRepository?.retrieveForGeneration(
                chatId = chatId,
                recipient = StoryActorRef(StoryActorKind.CHARACTER, fullCharacter.characterId),
                sceneText = searchScene(history, currentMessage),
                activeActors = setOf(
                    StoryActorRef(StoryActorKind.CHARACTER, fullCharacter.characterId),
                    StoryActorRef(StoryActorKind.PERSONA, fullPersona.personaId)
                )
            )?.selected.orEmpty()
        } else emptyList()
        val npcBankEnabled = activeCoopRoom == null && npcRepository?.getSettings(chatId)?.isEnabled == true
        val projectedNpcDossiers = if (npcBankEnabled) {
            npcRepository?.getProjectedDossiers(
                chatId, afterOrderIndex = summary.chat.contextResetOrderIndex
            ).orEmpty()
        } else emptyList()
        // Scene-cast resolution looks at only 24 selected turns. Avoid copying
        // the full archive just to construct its input. An unselected current
        // variant must not consume one of those 24 selected history slots.
        val recentScene = com.example.domain.compiler.RecentPrimaryMessageWindow.take(
            history, if (currentMessage.isPrimaryVariant) 23 else 24
        ) + currentMessage
        val sceneCast = NpcSceneCastResolver.resolve(projectedNpcDossiers, recentScene)
        val presentNpcs = sceneCast.filter { it.presence == NpcScenePresence.PRESENT }.map { it.dossier }
        // NPC Bank stores continuity; the active Scene Cast controls whether we need speaker splitting.
        // Without any present NPC, ordinary Solo prose stays untouched.
        val ensembleNarrationEnabled = summary.chat.chatType == ChatType.SOLO &&
            (settings.narratorEnabled || presentNpcs.isNotEmpty()) && activeCoopRoom == null
        val knownNpcSpeakers = presentNpcs.map { dossier ->
            StorySpeakerIdentity(dossier.npcId, dossier.displayName)
        }
        val coLeadNpcNames = if (ensembleNarrationEnabled && settings.coLeadEnsembleEnabled) {
            presentNpcs.filter { it.keepInScene }
                .take(NpcRepository.SOLO_ENSEMBLE_NPC_CONTEXT_LIMIT)
                .map { it.displayName }
        } else emptyList()
        val coLeadRebalanceActive = settings.coLeadRebalanceTurnsRemaining > 0 && coLeadNpcNames.isNotEmpty()
        val sceneInstructions = if (npcBankEnabled && sceneCast.isNotEmpty()) {
            val present = presentNpcs.map { it.displayName }
            val absent = sceneCast.filter { it.presence == NpcScenePresence.AWAY }.map { it.dossier.displayName }
            buildString {
                append("[SCENE CAST — physically present supporting characters] ")
                append("Present: ").append(present.joinToString(", ").ifEmpty { "none confirmed" }).append(". ")
                if (absent.isNotEmpty()) append("Absent: ").append(absent.joinToString(", ")).append(". ")
                append("Other remembered NPCs have unknown presence. ")
                append("Absent or unknown NPCs must not appear or speak until an actual entrance. ")
                append("Pinned Co-Leads have priority but are not teleported into scenes.")
            }
        } else null
        val activeNpcDossiers = if (npcBankEnabled) {
            // The NPC selector uses a List, but does not need a new copy of
            // the entire transcript merely to append the current turn.
            val npcContext = com.example.domain.compiler.GenerationHistoryWithCurrent(
                history, currentMessage
            )
            npcRepository?.relevantContext(
                chatId = chatId,
                recentMessages = npcContext,
                observerCharacterId = fullCharacter.characterId,
                activePersonaId = fullPersona.personaId,
                isGroup = summary.chat.chatType == ChatType.GROUP,
                asOfOrderIndex = npcContext.maxOfOrNull { it.orderIndex },
                afterOrderIndex = summary.chat.contextResetOrderIndex,
                limit = if (ensembleNarrationEnabled) {
                    NpcRepository.SOLO_ENSEMBLE_NPC_CONTEXT_LIMIT
                } else {
                    NpcRepository.DEFAULT_NPC_CONTEXT_LIMIT
                }
            )
        } else null
        val supportingCast = listOfNotNull(sceneInstructions, activeNpcDossiers)
            .joinToString("\n\n").takeIf(String::isNotBlank)

        // Solo and Group episodes share their World's approved setting and continuity
        // just as Scenario episodes do. This is a local Room read, not another model call.
        val activeWorldContext = scenarioRepository?.worldContextForChat(chatId)
        fun compileWithMemories(
            story: List<StoryMemoryRecord>,
            includeLegacy: Boolean = true,
            includeSummary: Boolean = true
        ) =
            if (summary.chat.chatType == ChatType.GROUP) {
            val state = _uiState.value as? ChatUiState.Success
            val activeCharacterNames = state?.groupCharacters
                ?.filter { it.isActive }
                ?.map { it.character.displayName }
                ?.takeIf { it.isNotEmpty() }
                ?: summary.characters.map { it.displayName }
            compiler.compileGroupCharacterContext(
                isContinuation = isContinuation,
                character = fullCharacter,
                persona = fullPersona,
                activeCharacterNames = activeCharacterNames,
                chatHistory = history,
                currentMessage = currentMessage,
                responseProfile = settings.responseLengthProfile,
                customMin = settings.customMin,
                customTargetMax = settings.customTargetMax,
                customHardMax = settings.customHardMax,
                durableMemories = if (includeLegacy) durableMemories else emptyList(),
                storyMemories = story,
                rollingSummary = if (includeSummary) rollingSummary else null,
                activeLore = activeLore,
                worldContext = activeWorldContext,
                supportingCastContext = supportingCast,
                npcBankEnabled = npcBankEnabled,
                narratorEnabled = settings.narratorEnabled,
                narratorInstructions = settings.narratorInstructions
            )
        } else {
            compiler.compileSoloContext(
                isContinuation = isContinuation,
                character = fullCharacter,
                persona = fullPersona,
                chatHistory = history,
                currentMessage = currentMessage,
                responseProfile = settings.responseLengthProfile,
                customMin = settings.customMin,
                customTargetMax = settings.customTargetMax,
                customHardMax = settings.customHardMax,
                durableMemories = if (includeLegacy) durableMemories else emptyList(),
                storyMemories = story,
                rollingSummary = if (includeSummary) rollingSummary else null,
                activeLore = activeLore,
                worldContext = activeWorldContext,
                supportingCastContext = supportingCast,
                npcBankEnabled = npcBankEnabled,
                narratorEnabled = settings.narratorEnabled,
                narratorInstructions = settings.narratorInstructions,
                ensembleNarrationEnabled = ensembleNarrationEnabled,
                knownNpcSpeakers = knownNpcSpeakers,
                coLeadNpcNames = coLeadNpcNames,
                coLeadRebalanceActive = coLeadRebalanceActive
            )
        }

        val modelId = settings.selectedModelId ?: "openrouter/auto"
        // Admit reviewed story memories against the protected authored baseline.
        // Generated summaries/legacy memory are lower priority and may be
        // excluded by the final shared prompt fitter if space is tight.
        val baseline = compileWithMemories(
            emptyList(), includeLegacy = false, includeSummary = false
        )
        val allowedStoryMemories = StoryMemoryPromptBudget.select(
            baseline, selectedStoryMemories, modelProvider.cachedContextLength(modelId)
        ).selected
        val imageSourceMessage = relevantPersonaMessage?.takeIf { !isContinuation && activeCoopRoom == null }
        val currentImages = imageSourceMessage?.let { source ->
            messageAttachmentRepository?.providerImagesForMessage(source.messageId).orEmpty()
        }.orEmpty()
        if (currentImages.isNotEmpty()) {
            when (modelProvider.supportsImageInput(modelId)) {
                false -> {
                    updateSuccessState {
                        it.copy(generationError = "The selected model does not support image input. Choose a vision-capable model or remove the images.")
                    }
                    return null
                }
                null -> {
                    updateSuccessState {
                        it.copy(
                            generationError = "Image support has not been confirmed for the selected model. Refresh the model catalog and choose a model that advertises image input, then retry this turn."
                        )
                    }
                    return null
                }
                true -> Unit
            }
        }
        val fitted = fitPromptBudget(
            modelId = modelId,
            available = PromptOptionalContextChoices(
                includeLegacyMemories = durableMemories.isNotEmpty(),
                includeStoryMemories = allowedStoryMemories.isNotEmpty(),
                includeSummary = !rollingSummary.isNullOrBlank()
            )
        ) { choices ->
            val context = compileWithMemories(
                if (choices.includeStoryMemories) allowedStoryMemories else emptyList(),
                includeLegacy = choices.includeLegacyMemories,
                includeSummary = choices.includeSummary
            )
            if (currentImages.isEmpty()) context else context.map { message ->
                if (message.sourceMessageId == imageSourceMessage?.messageId) {
                    message.copy(images = currentImages)
                } else message
            }
        } ?: return null

        val routing = ModelRouting.fromSettings(settings.providerRoutingMode, settings.providerEndpoint)

        val options = GenerationOptions(
            modelId = modelId,
            routing = routing,
            temperature = settings.temperature,
            topP = settings.topP,
            // Character-based length targets remain in ContextCompilerV1. Translating them into a
            // small completion-token cap can exhaust the budget on reasoning before visible text.
            maxTokens = null
        )
        return PreparedGeneration(
            character = ResponseSpeaker(fullCharacter.characterId, fullCharacter.displayName),
            messages = fitted.messages,
            options = options,
            npcBankEnabled = npcBankEnabled,
            activePersonaId = fullPersona.personaId,
            ensembleNarrationEnabled = ensembleNarrationEnabled,
            knownNpcSpeakers = knownNpcSpeakers,
            coLeadRebalanceActive = coLeadRebalanceActive,
            contextReductionNotice = fitted.countsOnlyNotice()
        )
    }

    private suspend fun resolveCoopGenerationInput(
        request: CoopGenerationRequestPayload
    ): CoopGenerationInput {
        val boundary = chatSummary?.chat?.contextResetOrderIndex
        val allMessages = hidePersistedNpcControlRegions(
            messageRepository.getMessagesForChatOnce(chatId)
        ).filter { ContextResetPolicy.includes(it, boundary) }
        val primaryMessages = allMessages.filter { it.isPrimaryVariant }
        return when (request.operation) {
            "NORMAL" -> {
                val current = primaryMessages.lastOrNull()
                    ?: throw IllegalStateException("No message is available for generation")
                CoopGenerationInput(current, primaryMessages.dropLast(1))
            }
            "REGENERATE" -> {
                val target = allMessages.firstOrNull { it.messageId == request.targetMessageId }
                    ?: throw IllegalStateException("Regeneration target is unavailable")
                require(target.speakerType == SpeakerType.CHARACTER) {
                    "Only a Character response can be regenerated"
                }
                val timeline = allMessages.filter { it.isPrimaryVariant || it.messageId == target.messageId }
                val beforeTarget = timeline.takeWhile { it.messageId != target.messageId }
                val current = beforeTarget.lastOrNull()
                    ?: throw IllegalStateException("Regeneration has no preceding message")
                CoopGenerationInput(
                    currentMessage = current,
                    history = beforeTarget.dropLast(1),
                    variantGroupId = target.variantGroupId
                )
            }
            "CONTINUE" -> {
                val target = allMessages.firstOrNull {
                    it.messageId == request.targetMessageId && it.speakerType == SpeakerType.CHARACTER
                } ?: throw IllegalStateException("Continuation target is unavailable")
                CoopGenerationInput(
                    currentMessage = target,
                    history = primaryMessages.filter { it.orderIndex < target.orderIndex },
                    isContinuation = true,
                    continuationTarget = target
                )
            }
            else -> throw IllegalStateException("Unsupported co-op generation operation")
        }
    }

    private suspend fun authorizeCoopRequest(
        request: CoopGenerationRequestPayload
    ): CoopGenerationAuthorization {
        val input = resolveCoopGenerationInput(request)
        val prepared = prepareGeneration(
            currentMessage = input.currentMessage,
            history = input.history,
            isContinuation = input.isContinuation,
            characterId = request.characterId
        ) ?: throw IllegalStateException(
            "Unable to compile co-op context. Check the local Character, Persona, Lorebook, memory, and model settings."
        )
        val canonical = buildString {
            fun part(value: String?) {
                val text = value ?: "<null>"
                append(text.length).append(':').append(text).append('|')
            }
            part("elsewhere-coop-context-v1")
            part(prepared.character.characterId)
            prepared.messages.forEach { message ->
                part(message.role.name)
                part(message.name)
                part(message.content)
                message.images.forEach { image -> part(image.sha256) }
            }
            part(prepared.options.modelId)
            part(prepared.options.routing.mode.name)
            part(prepared.options.routing.allowFallback.toString())
            prepared.options.routing.preferredEndpoints.forEach(::part)
            part(prepared.options.maxTokens?.toString())
            part(prepared.options.temperature?.toRawBits()?.toString())
            part(prepared.options.topP?.toRawBits()?.toString())
            prepared.options.stopSequences.forEach(::part)
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return CoopGenerationAuthorization(
            contextFingerprint = fingerprint,
            modelId = prepared.options.modelId,
            providerRoutingMode = prepared.options.routing.mode.name,
            providerEndpoint = prepared.options.routing.preferredEndpoints.firstOrNull()
        )
    }

    private suspend fun saveGeneratedMessage(
        content: String,
        metadata: GenerationMetadataEntity?,
        character: ResponseSpeaker,
        variantGroupId: String? = null,
        sourceMessage: MessageEntity? = null,
        contextMessages: List<MessageEntity> = emptyList(),
        continuationTarget: MessageEntity? = null,
        npcUpdates: List<com.example.domain.npc.NpcStructuredUpdate> = emptyList(),
        npcWarning: String? = null,
        activePersonaId: String? = null,
        coLeadRebalanceActive: Boolean = false,
        dataRevision: AppDataRevision,
        retainPartialOnCoopFailure: Boolean = false
    ): Boolean {
        val isCoopGeneration = activeCoopGenerationRequestId != null
        if (!hasVisibleContent(content)) {
            updateSuccessState {
                it.copy(
                    isGenerating = false,
                    streamingContent = null,
                    generationError = "The provider returned no response. You can retry this turn."
                )
            }
            finishCoopGenerationWithError("Provider returned no response")
            return false
        }

        if (continuationTarget != null) {
            val continuedMessage = continuationTarget.copy(
                content = StoryResponseCodec.append(
                    existing = continuationTarget.content,
                    addition = content,
                    primaryCharacter = StorySpeakerIdentity(character.characterId, character.displayName)
                )
            )
            val persistedMetadata = metadata?.copy(messageId = continuationTarget.messageId)
            var npcUpdateWarning = npcWarning
            val syncError = if (isCoopGeneration) {
                runCatching {
                    requireNotNull(coopSessionManager).publishMessage(
                        continuedMessage,
                        activeCoopGenerationExecutionId
                    )
                }.exceptionOrNull()
            } else {
                try {
                    dataCoordinator.withMutation(dataRevision) {
                        if (npcRepository != null) {
                            try {
                                val report = npcRepository.persistContinuationAtomically(
                                    continuedMessage,
                                    persistedMetadata,
                                    npcUpdates,
                                    activePersonaId,
                                    skipInvalidNpcMetadata = true
                                )
                                report.warning("Continuation")?.let { npcUpdateWarning = it }
                            } catch (npcError: Exception) {
                                if (npcUpdates.isEmpty()) throw npcError
                                npcRepository.persistContinuationAtomically(
                                    continuedMessage,
                                    persistedMetadata,
                                    emptyList(),
                                    activePersonaId
                                )
                                npcUpdateWarning = "Continuation saved, but invalid NPC changes were ignored."
                            }
                        } else {
                            messageRepository.updateMessage(continuedMessage)
                            persistedMetadata?.let { messageRepository.insertGenerationMetadata(it) }
                        }
                    }
                    null
                } catch (error: Exception) {
                    retainPartialGeneration(
                        visibleContent = content,
                        metadata = metadata,
                        requestedModelId = metadata?.requestedModelId ?: "unknown",
                        wasUserStopped = false,
                        errorMessage = "The continuation could not be saved. Generated text was retained so you can try again or discard it."
                    )
                    finishCoopGenerationWithError(error.message ?: "Continuation persistence failed")
                    return false
                }
            }
            if (!isCoopGeneration && npcUpdates.isNotEmpty() && npcRepository == null) {
                    npcUpdateWarning = "Continuation saved, but malformed NPC changes were ignored."
            }
            if (syncError != null && retainPartialOnCoopFailure) {
                updateSuccessState {
                    it.copy(
                        generationError = "The partial continuation could not be confirmed by the room: ${syncError.message}. The partial reply was retained."
                    )
                }
                return false
            }
            if (isCoopGeneration && retainPartialOnCoopFailure) {
                pendingPartialGeneration = pendingPartialGeneration?.copy(
                    publishedMessageId = continuedMessage.messageId,
                    publishedMetadata = persistedMetadata
                )
            }
            val completionError = if (syncError == null) {
                finishCoopGenerationSuccessfully(continuedMessage.messageId, persistedMetadata)
            } else {
                finishCoopGenerationWithError("Continuation could not be synchronized")
            }
            if (completionError != null && retainPartialOnCoopFailure) {
                updateSuccessState {
                    it.copy(
                        generationError = "The saved partial continuation is waiting for room acknowledgement. The partial reply was retained."
                    )
                }
                return false
            }
            // A completed response must release the pending-partial gate BEFORE
            // publishing isGenerating=false. Otherwise an immediate Wack/Retry
            // observes a finished UI but is incorrectly rejected as unresolved.
            if (syncError == null && completionError == null && !retainPartialOnCoopFailure) {
                pendingPartialGeneration = null
            }
            updateSuccessState {
                it.copy(
                    isGenerating = false,
                    streamingContent = null,
                    generationCharacterId = null,
                    generationError = syncError?.let { "The approved continuation could not be confirmed by the room: ${it.message}" },
                    generationWarning = if (syncError == null) npcUpdateWarning else null
                )
            }
            if (syncError == null && completionError == null && !isCoopGeneration &&
                !retainPartialOnCoopFailure && metadata?.isPartial != true
            ) {
                runCatching {
                    generationLifecycle?.replySaved(
                        chatId, chatSummary?.chat?.displayName ?: "Chat",
                        character.displayName, continuedMessage.messageId
                    )
                }
            }
            refreshChatTimestamp(dataRevision)
            return syncError == null && completionError == null
        }

        val priorAttempt = if (isCoopGeneration && retainPartialOnCoopFailure) {
            pendingPartialGeneration?.attemptedMessage
        } else null
        val botMsg = priorAttempt ?: run {
            val msgId = UUID.randomUUID().toString()
            val storyOrderIndex = variantGroupId
                ?.let { messageRepository.getVariantsOnce(it).firstOrNull()?.orderIndex }
                ?: maxOf(
                    nextOrderIndex(chatSummary?.chat?.contextResetOrderIndex),
                    sourceMessage?.orderIndex?.let { sourceOrder ->
                        if (sourceOrder == Long.MAX_VALUE) Long.MAX_VALUE else sourceOrder + 1
                    } ?: Long.MIN_VALUE
                )
            MessageEntity(
                messageId = msgId,
                chatId = chatId,
                speakerType = character.type,
                speakerId = character.characterId,
                speakerDisplayNameSnapshot = character.displayName,
                content = content,
                orderIndex = storyOrderIndex,
                variantGroupId = variantGroupId ?: msgId,
                isPrimaryVariant = true
            )
        }
        val msgId = botMsg.messageId
        
        val persistedMetadata = metadata?.copy(messageId = msgId)
        if (isCoopGeneration && retainPartialOnCoopFailure) {
            // Persist the complete attempt before network publication. If the host commits the
            // ordered result but its acknowledgement is lost, Keep Partial must retry identical
            // timestamps, ordering, variant data, content, and ID—not merely the same message ID.
            pendingPartialGeneration = pendingPartialGeneration?.copy(attemptedMessage = botMsg)
        }
        var memoryWarning: String? = npcWarning
        val syncError = if (isCoopGeneration) {
            runCatching {
                requireNotNull(coopSessionManager).publishMessage(botMsg, activeCoopGenerationExecutionId)
            }.exceptionOrNull()
        } else {
            try {
                dataCoordinator.withMutation(dataRevision) {
                    if (npcRepository != null) {
                        try {
                            val report = npcRepository.persistGeneratedMessageAtomically(
                                message = botMsg,
                                metadata = persistedMetadata,
                                isNewVariant = variantGroupId != null,
                                updates = npcUpdates,
                                activePersonaId = activePersonaId,
                                skipInvalidNpcMetadata = true
                            )
                            report.warning("Response")?.let { memoryWarning = it }
                        } catch (npcError: Exception) {
                            if (npcUpdates.isEmpty()) throw npcError
                            npcRepository.persistGeneratedMessageAtomically(
                                message = botMsg,
                                metadata = persistedMetadata,
                                isNewVariant = variantGroupId != null,
                                updates = emptyList(),
                                activePersonaId = activePersonaId
                            )
                            memoryWarning = "Response saved, but invalid NPC changes were ignored."
                        }
                    } else {
                        if (variantGroupId != null) messageRepository.insertVariantAndSelect(botMsg)
                        else messageRepository.insertMessage(botMsg)
                        persistedMetadata?.let { messageRepository.insertGenerationMetadata(it) }
                        if (npcUpdates.isNotEmpty()) memoryWarning = "Response saved, but NPC changes could not be stored."
                    }
                }
                null
            } catch (error: Exception) {
                retainPartialGeneration(
                    visibleContent = content,
                    metadata = metadata,
                    requestedModelId = metadata?.requestedModelId ?: "unknown",
                    wasUserStopped = false,
                    errorMessage = "The generated response could not be saved. Generated text was retained so you can try again or discard it."
                )
                finishCoopGenerationWithError(error.message ?: "Response persistence failed")
                return false
            }
        }

        if (!isCoopGeneration && sourceMessage?.speakerType == SpeakerType.PERSONA && variantGroupId == null) {
            try {
                dataCoordinator.withMutation(dataRevision) {
                    memoryManager.updateAfterCompletedTurnFromStoredHistory(
                        chatId = chatId,
                        characterId = character.characterId,
                        personaMessage = sourceMessage,
                        messageRepository = messageRepository,
                        resetBoundary = chatSummary?.chat?.contextResetOrderIndex
                    )
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                memoryWarning = "Response saved, but automatic memory could not be updated."
            }
        }
        
        if (syncError != null && retainPartialOnCoopFailure) {
            updateSuccessState {
                it.copy(
                    generationError = "The partial response could not be confirmed by the room: ${syncError.message}. The partial reply was retained."
                )
            }
            return false
        }
        if (isCoopGeneration && retainPartialOnCoopFailure) {
            pendingPartialGeneration = pendingPartialGeneration?.copy(
                publishedMessageId = botMsg.messageId,
                publishedMetadata = persistedMetadata
            )
        }
        val completionError = if (syncError == null) {
            finishCoopGenerationSuccessfully(botMsg.messageId, persistedMetadata)
        } else {
            finishCoopGenerationWithError("Generated response could not be synchronized")
        }
        if (completionError != null && retainPartialOnCoopFailure) {
            updateSuccessState {
                it.copy(
                    generationError = "The saved partial response is waiting for room acknowledgement. The partial reply was retained."
                )
            }
            return false
        }
        // Match the continuation path: terminal UI state is observable immediately,
        // so the pending generation gate must already be clear on full success.
        if (syncError == null && completionError == null && !retainPartialOnCoopFailure) {
            pendingPartialGeneration = null
        }
        updateSuccessState {
            it.copy(
                isGenerating = false,
                streamingContent = null,
                generationCharacterId = null,
                generationError = syncError?.let { "The approved response could not be confirmed by the room: ${it.message}" },
                generationWarning = if (syncError == null) memoryWarning else null
            )
        }
        
        // Only successfully saved NEW Persona replies count. Continue, Regenerate,
        // failed provider calls, and partial attempts do not use up rebalance guidance.
        if (coLeadRebalanceActive && !retainPartialOnCoopFailure && !isCoopGeneration &&
            syncError == null && completionError == null && continuationTarget == null && variantGroupId == null &&
            sourceMessage?.speakerType == SpeakerType.PERSONA
        ) {
            try {
                chatRepository.consumeCoLeadRebalanceTurn(chatId)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The reply is already saved. Never lose it over a counter update.
            }
        }
        if (syncError == null && completionError == null && !isCoopGeneration &&
            !retainPartialOnCoopFailure && metadata?.isPartial != true
        ) {
            runCatching {
                generationLifecycle?.replySaved(
                    chatId, chatSummary?.chat?.displayName ?: "Chat",
                    character.displayName, botMsg.messageId
                )
            }
        }
        refreshChatTimestamp(dataRevision)
        return syncError == null && completionError == null
    }

    private suspend fun refreshChatTimestamp(dataRevision: AppDataRevision) {
        try {
            // Never read an old ChatEntity and write it back after a Wack.
            // A concurrent reset must not be erased just to update the list timestamp.
            dataCoordinator.withMutation(dataRevision) {
                chatRepository.touchChatTimestamp(chatId)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            updateSuccessState { state ->
                state.copy(
                    generationWarning = state.generationWarning
                        ?: "The response was saved, but the chat's updated time could not be refreshed."
                )
            }
        }
    }

    private suspend fun finishCoopGenerationSuccessfully(
        messageId: String,
        metadata: GenerationMetadataEntity?
    ): Throwable? {
        val requestId = activeCoopGenerationRequestId ?: return null
        val error = runCatching {
            requireNotNull(coopSessionManager).finishGeneration(
                chatId,
                requestId,
                messageId,
                metadata = metadata?.let(CoopGenerationMetadataPayload::from)
            )
        }.exceptionOrNull()
        if (error == null) {
            activeCoopGenerationRequestId = null
            activeCoopGenerationExecutionId = null
        }
        return error
    }

    private suspend fun finishCoopGenerationWithError(message: String): Throwable? {
        val requestId = activeCoopGenerationRequestId ?: return null
        val error = runCatching {
            requireNotNull(coopSessionManager).finishGeneration(chatId, requestId, null, message)
        }.exceptionOrNull()
        if (error == null) {
            activeCoopGenerationRequestId = null
            activeCoopGenerationExecutionId = null
        }
        return error
    }

    /**
     * Navigation must not destroy a chat's generation job, unsaved send, or unresolved partial.
     * The application-scoped ViewModel owner consults this before releasing a hidden chat.
     */
    fun hasPendingGenerationWork(): Boolean {
        val state = _uiState.value as? ChatUiState.Success
        return sendInProgress || generationStartInProgress ||
            sendJob?.isActive == true || generationJob?.isActive == true ||
            state?.isGenerating == true || state?.partialStopped == true ||
            partialResolutionInProgress || inChatToolInProgress
    }

    fun stopGeneration() {
        generationJob?.cancel()
    }

    fun approveCoopGeneration(requestId: String) {
        viewModelScope.launch {
            runCatching { coopSessionManager?.approvePendingGeneration(requestId) }
                .onFailure { error ->
                    updateSuccessState {
                        it.copy(generationError = error.message ?: "Unable to approve co-op generation")
                    }
                }
        }
    }

    fun declineCoopGeneration(requestId: String) {
        viewModelScope.launch {
            runCatching { coopSessionManager?.declinePendingGeneration(requestId) }
                .onFailure { error ->
                    updateSuccessState {
                        it.copy(generationError = error.message ?: "Unable to decline co-op generation")
                    }
                }
        }
    }
    
    fun discardPartial() {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (!state.partialStopped || partialResolutionInProgress) return
        partialResolutionInProgress = true
        updateSuccessState { it.copy(partialResolutionInProgress = true, generationError = null) }
        viewModelScope.launch {
            val completionError = if (activeCoopGenerationRequestId != null) {
                finishCoopGenerationWithError("Partial response was discarded by the payer")
            } else null
            if (completionError == null) {
                pendingPartialGeneration = null
                updateSuccessState {
                    it.copy(
                        partialStopped = false,
                        partialResolutionInProgress = false,
                        streamingContent = null,
                        generationCharacterId = null
                    )
                }
            } else {
                updateSuccessState {
                    it.copy(
                        partialResolutionInProgress = false,
                        generationError = "Discard could not be confirmed by the room. The partial reply was retained."
                    )
                }
            }
            partialResolutionInProgress = false
        }
    }
    
    fun keepPartial() {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (!state.partialStopped || partialResolutionInProgress) return
        val content = state.streamingContent ?: return
        val pending = pendingPartialGeneration ?: return
        partialResolutionInProgress = true
        updateSuccessState { it.copy(partialResolutionInProgress = true, generationError = null) }
        viewModelScope.launch {
            val resolved = if (pending.publishedMessageId != null) {
                val completionError = finishCoopGenerationSuccessfully(
                    pending.publishedMessageId,
                    pending.publishedMetadata
                )
                if (completionError != null) {
                    updateSuccessState {
                        it.copy(
                            generationError = "The saved partial is waiting for room acknowledgement. Try Keep Partial again after reconnecting."
                        )
                    }
                    false
                } else true
            } else {
                saveGeneratedMessage(
                    content = content,
                    metadata = pending.metadata,
                    character = pending.character,
                    variantGroupId = pending.variantGroupId,
                    sourceMessage = pending.sourceMessage,
                    contextMessages = pending.contextMessages,
                    continuationTarget = pending.continuationTarget,
                    npcUpdates = emptyList(),
                    activePersonaId = pending.activePersonaId,
                    coLeadRebalanceActive = pending.coLeadRebalanceActive,
                    dataRevision = pending.dataRevision,
                    retainPartialOnCoopFailure = true
                )
            }
            if (resolved) {
                pendingPartialGeneration = null
                updateSuccessState {
                    it.copy(
                        partialStopped = false,
                        partialResolutionInProgress = false,
                        streamingContent = null,
                        generationCharacterId = null
                    )
                }
            } else {
                updateSuccessState { it.copy(partialResolutionInProgress = false) }
            }
            partialResolutionInProgress = false
        }
    }

    private fun visibleGeneratedContent(content: String, prepared: PreparedGeneration): String {
        val storyText = if (prepared.npcBankEnabled) {
            NpcStructuredOutputParser.visibleStoryText(content)
        } else content
        if (!prepared.ensembleNarrationEnabled &&
            (prepared.character.type != SpeakerType.CHARACTER ||
                !StoryOutputParser.hasExplicitSpeakerFormatting(storyText, prepared.character.displayName))
        ) return storyText
        val parsed = storyOutputParser.parse(
            output = storyText,
            primaryCharacter = StorySpeakerIdentity(
                prepared.character.characterId,
                prepared.character.displayName
            ),
            knownNpcs = prepared.knownNpcSpeakers,
            final = false
        )
        return StoryResponseCodec.encode(parsed.response)
    }

    private fun hasVisibleContent(content: String): Boolean =
        StoryResponseCodec.visibleText(content).isNotBlank()

    fun retryGeneration() {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (inChatToolInProgress || generationStartInProgress || state.isGenerating) return
        // Wack already marks the visible tail as out of context. Reject this known
        // invalid Retry synchronously, before claiming the generation-start gate:
        // otherwise the old-message lookup can race and silently block a new Send.
        val resetBoundary = state.chatSummary.chat.contextResetOrderIndex
        val visibleTail = state.messages.lastOrNull { it.isPrimaryVariant }
        if (resetBoundary != null && visibleTail != null &&
            !ContextResetPolicy.includes(visibleTail, resetBoundary)
        ) {
            updateSuccessState {
                it.copy(generationError = "Retry is unavailable because the last message is before the Wack context boundary.")
            }
            return
        }
        if (state.chatSummary.chat.chatType == ChatType.GROUP) {
            generateGroupResponse(state.generationCharacterId ?: state.selectedGroupCharacterId)
            return
        }
        if (state.chatSummary.chat.chatType == ChatType.SCENARIO) {
            if (state.partialStopped || partialResolutionInProgress) return
            generationStartInProgress = true
            val revision = dataCoordinator.captureRevision()
            viewModelScope.launch {
                try {
                    // A fast provider failure can precede Room's UI emission of the saved player turn.
                    val messages = ContextResetPolicy.eligiblePrimaryMessages(
                        messageRepository.getLatestPrimaryWindow(chatId, 25), chatSummary?.chat?.contextResetOrderIndex)
                    val current = messages.lastOrNull()?.takeIf { it.speakerType == SpeakerType.PERSONA } ?: return@launch
                    startGenerationInternal(current, messages.dropLast(1), dataRevision = revision)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    updateSuccessState { it.copy(generationError = "Unable to retry narration: ${error.message}") }
                } finally { generationStartInProgress = false }
            }
            return
        }
        // A fast provider failure may be reported before Room's Flow has emitted the
        // saved Persona turn to the UI. Read persisted history (as Scenario retry does)
        // instead of relying on state.messages, otherwise Retry silently does nothing.
        generationStartInProgress = true
        val revision = dataCoordinator.captureRevision()
        viewModelScope.launch {
            try {
                val primaryMessages = messageRepository.getLatestPrimaryWindow(chatId, 25)
                val lastMessage = primaryMessages.lastOrNull() ?: return@launch
                val boundary = chatSummary?.chat?.contextResetOrderIndex
                if (!ContextResetPolicy.includes(lastMessage, boundary)) {
                    updateSuccessState {
                        it.copy(generationError = "Retry is unavailable because the last message is before the Wack context boundary.")
                    }
                    return@launch
                }
                if (lastMessage.speakerType != SpeakerType.PERSONA) return@launch
                val history = ContextResetPolicy.eligiblePrimaryMessages(primaryMessages, boundary)
                val current = history.lastOrNull() ?: return@launch
                startGenerationInternal(current, history.dropLast(1), dataRevision = revision)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateSuccessState { it.copy(generationError = "Unable to retry response: ${error.message}") }
            } finally {
                generationStartInProgress = false
            }
        }
    }

    fun regenerateMessage(messageId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (inChatToolInProgress || generationStartInProgress || state.isGenerating) return
        val targetMessage = state.messages.lastOrNull { it.messageId == messageId } ?: return
        val boundary = chatSummary?.chat?.contextResetOrderIndex
        if (!ContextResetPolicy.includes(targetMessage, boundary)) {
            updateSuccessState {
                it.copy(generationError = "Regenerate is unavailable for messages before the Wack context boundary.")
            }
            return
        }
        val group = state.chatSummary.chat.chatType == ChatType.GROUP
        generationStartInProgress = true
        viewModelScope.launch {
            try {
                if (group && coopSessionManager?.requestGeneration(
                        chatId,
                        targetMessage.speakerId,
                        "REGENERATE",
                        targetMessage.messageId
                    ) == true
                ) return@launch

                // Old replies can be visible even when their earlier Persona
                // is outside the hydrated UI page. Recover the exact selected
                // context from Room as-of-target, not from a truncated window.
                val selection = if (state.hasEarlierHistory ||
                    state.isLoadingEarlierHistory) {
                    messageRepository.selectHistoricalAction(
                        chatId, messageId, isGroup = group,
                        isContinuation = false, resetBoundary = boundary
                    )
                } else {
                    com.example.domain.compiler.HistoricalGenerationWindow.regenerate(
                        state.messages, messageId, group, boundary
                    )
                }
                if (selection == null) {
                    updateSuccessState {
                        it.copy(generationError =
                            "Regenerate could not find the eligible story context preceding this reply.")
                    }
                    return@launch
                }
                startGenerationInternal(
                    currentMessage = selection.current,
                    history = selection.history,
                    variantGroupId = selection.target.variantGroupId ?: selection.target.messageId,
                    characterId = selection.target.speakerId
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateSuccessState {
                    it.copy(generationError = "Regenerate could not start: ${error.message ?: "unknown error"}.")
                }
            } finally {
                generationStartInProgress = false
            }
        }
    }

    fun continueMessage(messageId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        if (inChatToolInProgress || generationStartInProgress || state.isGenerating) return
        val targetMessage = state.messages.lastOrNull {
            it.messageId == messageId && it.isPrimaryVariant &&
                (it.speakerType == SpeakerType.CHARACTER || it.speakerType == SpeakerType.NARRATOR)
        } ?: return
        val boundary = chatSummary?.chat?.contextResetOrderIndex
        if (!ContextResetPolicy.includes(targetMessage, boundary)) {
            updateSuccessState {
                it.copy(generationError = "Continue is unavailable for messages before the Wack context boundary.")
            }
            return
        }
        generationStartInProgress = true
        viewModelScope.launch {
            try {
                if (
                    state.chatSummary.chat.chatType == ChatType.GROUP &&
                    coopSessionManager?.requestGeneration(
                        chatId,
                        targetMessage.speakerId,
                        "CONTINUE",
                        targetMessage.messageId
                    ) == true
                ) return@launch

                val selection = if (state.hasEarlierHistory ||
                    state.isLoadingEarlierHistory) {
                    messageRepository.selectHistoricalAction(
                        chatId, messageId,
                        isGroup = state.chatSummary.chat.chatType == ChatType.GROUP,
                        isContinuation = true, resetBoundary = boundary
                    )
                } else {
                    com.example.domain.compiler.HistoricalGenerationWindow.continueFrom(
                        state.messages, messageId, boundary
                    )
                }
                if (selection == null) {
                    updateSuccessState {
                        it.copy(generationError =
                            "Continue could not find the selected reply in the current story.")
                    }
                    return@launch
                }
                startGenerationInternal(
                    currentMessage = selection.target,
                    history = selection.history,
                    isContinuation = true,
                    continuationTarget = selection.target,
                    characterId = selection.target.speakerId
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateSuccessState {
                    it.copy(generationError = "Continue could not start: ${error.message ?: "unknown error"}. The existing reply was not changed.")
                }
            } finally {
                generationStartInProgress = false
            }
        }
    }

    fun deleteMessage(messageId: String) {
        if (!canMutateMessageNow()) return
        val state = _uiState.value as? ChatUiState.Success ?: return
        val targetMessage = state.messages.find { it.messageId == messageId } ?: return
        if (!canMutateCoop()) return
        viewModelScope.launch {
            val synchronized = runCatching {
                coopSessionManager?.publishMessageDelete(chatId, messageId) == true
            }.onFailure { error ->
                updateSuccessState { it.copy(generationError = "Delete was not synchronized: ${error.message}") }
            }.getOrDefault(false)
            if (!synchronized) {
                if (messageAttachmentRepository != null) {
                    messageAttachmentRepository.deleteMessageAndOwnedFiles(targetMessage)
                } else if (npcRepository != null) {
                    npcRepository.deleteMessageAndRepair(targetMessage)
                } else {
                    messageRepository.deleteMessage(targetMessage)
                }
            }
        }
    }

    fun deleteChat() {
        if (_uiState.value !is ChatUiState.Success) return
        generationJob?.cancel()
        sendInProgress = false
        viewModelScope.launch {
            try {
                coopSessionManager?.leaveRoom(chatId)
                if (messageAttachmentRepository != null) {
                    messageAttachmentRepository.deleteChatAndOwnedFiles(chatId)
                } else {
                    chatRepository.deleteChat(chatId)
                }
                chatSummary = null
                currentChatSettings = null
                currentDraft.value = ""
                _uiState.value = ChatUiState.Deleted
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updateSuccessState {
                    it.copy(generationError = "The chat could not be deleted.")
                }
            }
        }
    }

    fun switchVariant(variantGroupId: String, messageId: String) {
        if (!canMutateMessageNow()) return
        if (!canMutateCoop()) return
        if (coopSessionManager?.state?.value?.room?.chatId == chatId) {
            updateSuccessState {
                it.copy(generationError = "Manual variant switching is unavailable during local co-op. Regenerate creates a synchronized primary variant.")
            }
            return
        }
        viewModelScope.launch {
            if (npcRepository != null) npcRepository.selectVariant(variantGroupId, messageId)
            else messageRepository.setPrimaryVariant(variantGroupId, messageId)
            messageRepository.getVariantsOnce(variantGroupId).forEach { variant ->
                runCatching { coopSessionManager?.publishMessage(variant) }
                    .onFailure { error ->
                        updateSuccessState { it.copy(generationError = "Variant changed locally but co-op sync failed: ${error.message}") }
                    }
            }
        }
    }

    fun showGenerationDetails(messageId: String) {
        viewModelScope.launch {
            val meta = messageRepository.getGenerationMetadata(messageId)
            updateSuccessState { it.copy(generationDetails = com.example.ui.screens.GenerationDetailsState(metadata = meta, isVisible = true)) }
        }
    }

    fun hideGenerationDetails() {
        updateSuccessState { it.copy(generationDetails = com.example.ui.screens.GenerationDetailsState(isVisible = false)) }
    }

    fun editMessage(messageId: String, newContent: String) {
        if (!canMutateMessageNow()) return
        val state = _uiState.value as? ChatUiState.Success ?: return
        val targetMessage = state.messages.find { it.messageId == messageId } ?: return
        if (!canMutateCoop()) return
        viewModelScope.launch {
            val edited = targetMessage.copy(content = newContent, editedAt = System.currentTimeMillis())
            val room = coopSessionManager?.state?.value?.room?.takeIf { it.chatId == chatId }
            if (room == null) {
                messageRepository.updateMessage(edited)
            } else {
                runCatching { requireNotNull(coopSessionManager).publishMessage(edited) }
                    .onFailure { error ->
                        updateSuccessState {
                            it.copy(generationError = "The host did not confirm this edit: ${error.message}")
                        }
                    }
                }
        }
    }

    private fun updateSuccessState(update: (ChatUiState.Success) -> ChatUiState.Success) {
        // Room history, settings, generation and attachment collectors can emit
        // on different threads (notably in Robolectric with Unconfined Main).
        // An ordinary read-then-write loses concurrent updates, potentially
        // resetting historyLoaded or isGenerating and leaving the UI stuck.
        // StateFlow.update performs an atomic compare-and-set retry.
        _uiState.update { current ->
            when (current) {
                is ChatUiState.Success -> update(current)
                is ChatUiState.Loading -> {
                    val summary = chatSummary
                    val settings = currentChatSettings
                    if (summary == null || settings == null) current
                    else update(ChatUiState.Success(
                        chatSummary = summary,
                        messages = emptyList(),
                        chatSettings = settings,
                        selectedGroupCharacterId = selectedGroupCharacterId
                    ))
                }
                else -> current
            }
        }
    }

    private fun canMutateCoop(): Boolean {
        val room = coopSessionManager?.state?.value?.room?.takeIf { it.chatId == chatId } ?: return true
        if (room.connectionState == CoopConnectionState.CONNECTED) return true
        updateSuccessState {
            it.copy(generationError = "Host is offline. Co-op changes are suspended until reconnect.")
        }
        return false
    }

    private fun canMutateMessageNow(): Boolean {
        val state = _uiState.value as? ChatUiState.Success ?: return false
        val roomGenerationActive = coopSessionManager?.state?.value?.room
            ?.takeIf { it.chatId == chatId }
            ?.activeGenerationRequestId != null
        if (!state.isGenerating && !state.partialStopped && !generationStartInProgress &&
            generationJob?.isActive != true && !roomGenerationActive
        ) return true
        updateSuccessState {
            it.copy(
                generationError = if (state.partialStopped) {
                    "Keep or discard the unresolved partial reply before changing messages."
                } else if (roomGenerationActive) {
                    "The room transcript is frozen until the active co-op generation is resolved."
                } else {
                    "Wait for generation to finish before changing messages."
                }
            )
        }
        return false
    }
    
    class Factory(
        private val chatId: String,
        private val chatRepository: ChatRepository,
        private val messageRepository: MessageRepository,
        private val characterRepository: CharacterRepository,
        private val personaRepository: PersonaRepository,
        private val modelProvider: ModelProvider,
        private val memoryRepository: MemoryRepository,
        private val lorebookRepository: LorebookRepository? = null,
        private val coopSessionManager: CoopSessionManager? = null,
        private val messageAttachmentRepository: MessageAttachmentRepository? = null,
        private val npcRepository: NpcRepository? = null,
        private val npcOutputParser: NpcStructuredOutputParser? = null,
        private val scenarioRepository: com.example.domain.repository.ScenarioRepository? = null,
        private val generationLifecycle: GenerationLifecycleReporter? = null,
        private val storyMemoryRepository: StoryMemoryRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatDetailViewModel(
                chatId, chatRepository, messageRepository, characterRepository, personaRepository, modelProvider, memoryRepository,
                lorebookRepository,
                coopSessionManager,
                messageAttachmentRepository,
                npcRepository,
                npcOutputParser,
                scenarioRepository = scenarioRepository,
                generationLifecycle = generationLifecycle,
                storyMemoryRepository = storyMemoryRepository
            ) as T
        }
    }
}
