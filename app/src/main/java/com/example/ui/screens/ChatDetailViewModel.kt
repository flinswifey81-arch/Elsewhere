package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ChatSummary
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.memory.AutomaticMemoryManager
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.ModelRouting
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.PersonaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.UUID

data class MessageGroup(
    val primaryMessage: com.example.data.model.MessageEntity,
    val variants: List<com.example.data.model.MessageEntity>
)

data class GenerationDetailsState(
    val metadata: com.example.data.model.GenerationMetadataEntity? = null,
    val isVisible: Boolean = false
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
        val isGenerating: Boolean = false,
        val streamingContent: String? = null,
        val partialStopped: Boolean = false,
        val generationDetails: com.example.ui.screens.GenerationDetailsState = com.example.ui.screens.GenerationDetailsState(),
        val generationError: String? = null
    ) : ChatUiState()
}

class ChatDetailViewModel(
    private val chatId: String,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val characterRepository: CharacterRepository,
    private val personaRepository: PersonaRepository,
    private val modelProvider: ModelProvider,
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val currentDraft = MutableStateFlow("")
    val draft: StateFlow<String> = currentDraft.asStateFlow()

    private var chatSummary: ChatSummary? = null
    private var currentChatSettings: ChatSettingsEntity? = null
    private var generationJob: Job? = null
    private var sendInProgress = false
    private val compiler = ContextCompilerV1()
    private val memoryManager = AutomaticMemoryManager(memoryRepository)

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val summary = chatRepository.getChatSummaryById(chatId)
            if (summary == null) {
                _uiState.value = ChatUiState.Error
                return@launch
            }
            chatSummary = summary
            
            var settings = chatRepository.getChatSettings(chatId)
            if (settings == null) {
                settings = ChatSettingsEntity(chatId = chatId)
                chatRepository.insertChatSettings(settings)
            }
            currentChatSettings = settings
            currentDraft.value = settings.draftMessage ?: ""

            launch {
                chatRepository.getChatSettingsFlow(chatId).filterNotNull().collect { latestSettings ->
                    currentChatSettings = latestSettings
                    updateSuccessState { it.copy(chatSettings = latestSettings) }
                }
            }

            messageRepository.getMessagesForChat(chatId).collect { messages ->
                val grouped = messages.groupBy { it.variantGroupId ?: it.messageId }.mapNotNull { (_, msgs) ->
                    val primary = msgs.find { it.isPrimaryVariant } ?: msgs.firstOrNull()
                    if (primary != null) MessageGroup(primary, msgs.sortedBy { it.orderIndex }) else null
                }.sortedBy { it.primaryMessage.orderIndex }
                updateSuccessState { it.copy(messages = messages, groupedMessages = grouped, chatSummary = summary, chatSettings = currentChatSettings!!) }
            }
        }
    }

    fun onDraftChanged(newDraft: String) {
        currentDraft.value = newDraft
        viewModelScope.launch {
            currentChatSettings?.let {
                val updated = it.copy(draftMessage = newDraft)
                chatRepository.insertChatSettings(updated)
                currentChatSettings = updated
            }
        }
    }

    fun sendMessage() {
        val text = currentDraft.value
        if (text.isBlank() || sendInProgress) return
        val summary = chatSummary ?: return
        val persona = summary.personas.firstOrNull() ?: return

        sendInProgress = true
        viewModelScope.launch {
            val userMsg = MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = persona.personaId,
                speakerDisplayNameSnapshot = persona.displayName,
                content = text,
                orderIndex = System.currentTimeMillis()
            )
            try {
                messageRepository.insertMessage(userMsg)
                currentChatSettings?.let { settings ->
                    val clearedSettings = settings.copy(draftMessage = "")
                    chatRepository.insertChatSettings(clearedSettings)
                    currentChatSettings = clearedSettings
                }
                currentDraft.value = ""
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updateSuccessState {
                    it.copy(generationError = "The message could not be saved. Your draft was kept.")
                }
                sendInProgress = false
                return@launch
            }

            sendInProgress = false
            try {
                startGeneration(userMsg)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updateSuccessState {
                    it.copy(generationError = "Your message was saved, but generation could not start. You can retry this turn.")
                }
            }
        }
    }

    private suspend fun startGeneration(userMsg: MessageEntity) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val history = state.messages.filter { it.isPrimaryVariant && it.messageId != userMsg.messageId }
        startGenerationInternal(userMsg, history)
    }

    private suspend fun startGenerationInternal(
        currentMessage: MessageEntity,
        history: List<MessageEntity>,
        variantGroupId: String? = null,
        isContinuation: Boolean = false,
        continuationTarget: MessageEntity? = null
    ) {
        val summary = chatSummary ?: return
        val settings = currentChatSettings ?: return
        val character = summary.characters.firstOrNull() ?: return
        val persona = summary.personas.firstOrNull() ?: return
        
        val fullCharacter = characterRepository.getCharacterById(character.characterId) ?: return
        val fullPersona = personaRepository.getPersonaById(persona.personaId) ?: return
        if (settings.providerRoutingMode == com.example.data.model.ProviderRoutingMode.LOCK && settings.providerEndpoint.isNullOrBlank()) {
            updateSuccessState {
                it.copy(generationError = "Locked routing requires a selected provider endpoint.")
            }
            return
        }
        val durableMemories = memoryRepository.getMemoriesOnce(chatId, character.characterId)
        val rollingSummary = memoryRepository.getSummaryOnce(chatId)?.summary

        val contextMessages = compiler.compileSoloContext(
            isContinuation = isContinuation,
            character = fullCharacter,
            persona = fullPersona,
            chatHistory = history,
            currentMessage = currentMessage,
            responseProfile = settings.responseLengthProfile,
            customMin = settings.customMin,
            customTargetMax = settings.customTargetMax,
            customHardMax = settings.customHardMax,
            durableMemories = durableMemories,
            rollingSummary = rollingSummary
        )

        val routing = ModelRouting.fromSettings(settings.providerRoutingMode, settings.providerEndpoint)

        val options = GenerationOptions(
            modelId = settings.selectedModelId ?: "openrouter/auto",
            routing = routing,
            temperature = settings.temperature,
            topP = settings.topP,
            // Character-based length targets remain in ContextCompilerV1. Translating them into a
            // small completion-token cap can exhaust the budget on reasoning before visible text.
            maxTokens = null
        )

        updateSuccessState { it.copy(isGenerating = true, streamingContent = "", partialStopped = false, generationError = null) }

        var generatedContent = ""
        var currentMetadata: GenerationMetadataEntity? = null
        var stopped = false
        var generationError: String? = null
        
        generationJob = viewModelScope.launch {
            try {
                modelProvider.streamResponse(contextMessages, options).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            if (event.text.isNotEmpty()) {
                                generatedContent += event.text
                                updateSuccessState { it.copy(streamingContent = generatedContent) }
                            }
                        }
                        is StreamEvent.Done -> {
                            currentMetadata = event.metadata
                        }
                        is StreamEvent.Error -> {
                            stopped = true
                            generationError = event.exception.message ?: "Generation failed"
                        }
                    }
                }
            } catch(e: kotlinx.coroutines.CancellationException) {
                stopped = true
            } catch (error: Exception) {
                stopped = true
                generationError = error.message ?: "Generation failed"
            }
            
            if (stopped) {
                updateSuccessState {
                    it.copy(
                        isGenerating = false,
                        partialStopped = generatedContent.isNotBlank(),
                        streamingContent = generatedContent.takeIf(String::isNotBlank),
                        generationError = generationError
                    )
                }
            } else if (generatedContent.isBlank()) {
                updateSuccessState {
                    it.copy(
                        isGenerating = false,
                        streamingContent = null,
                        generationError = "The provider returned no response. You can retry this turn."
                    )
                }
            } else {
                saveGeneratedMessage(
                    content = generatedContent,
                    metadata = currentMetadata,
                    character = fullCharacter,
                    variantGroupId = variantGroupId,
                    sourceMessage = currentMessage,
                    contextMessages = history + currentMessage,
                    continuationTarget = continuationTarget
                )
            }
        }
    }

    private suspend fun saveGeneratedMessage(
        content: String,
        metadata: GenerationMetadataEntity?,
        character: CharacterEntity,
        variantGroupId: String? = null,
        sourceMessage: MessageEntity? = null,
        contextMessages: List<MessageEntity> = emptyList(),
        continuationTarget: MessageEntity? = null
    ) {
        if (content.isBlank()) {
            updateSuccessState {
                it.copy(
                    isGenerating = false,
                    streamingContent = null,
                    generationError = "The provider returned no response. You can retry this turn."
                )
            }
            return
        }

        if (continuationTarget != null) {
            val continuedMessage = continuationTarget.copy(content = continuationTarget.content + content)
            messageRepository.updateMessage(continuedMessage)
            metadata?.let { messageRepository.insertGenerationMetadata(it.copy(messageId = continuationTarget.messageId)) }
            updateSuccessState { it.copy(isGenerating = false, streamingContent = null, generationError = null) }
            chatRepository.getChatSummaryById(chatId)?.let { summary ->
                chatRepository.updateChat(summary.chat.copy(updatedAt = System.currentTimeMillis()))
            }
            return
        }

        val msgId = UUID.randomUUID().toString()
        val botMsg = MessageEntity(
            messageId = msgId,
            chatId = chatId,
            speakerType = SpeakerType.CHARACTER,
            speakerId = character.characterId,
            speakerDisplayNameSnapshot = character.displayName,
            content = content,
            orderIndex = System.currentTimeMillis(),
            variantGroupId = variantGroupId ?: msgId,
            isPrimaryVariant = true
        )
        
        if (variantGroupId != null) {
            messageRepository.insertVariantAndSelect(botMsg)
        } else {
            messageRepository.insertMessage(botMsg)
        }

        var memoryWarning: String? = null
        if (sourceMessage?.speakerType == SpeakerType.PERSONA && variantGroupId == null) {
            try {
                memoryManager.updateAfterCompletedTurn(
                    chatId = chatId,
                    characterId = character.characterId,
                    personaMessage = sourceMessage,
                    allPrimaryMessages = (contextMessages + botMsg).filter { it.isPrimaryVariant }
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                memoryWarning = "Response saved, but automatic memory could not be updated."
            }
        }
        
        metadata?.let { 
             messageRepository.insertGenerationMetadata(it.copy(messageId = msgId))
        }
        
        updateSuccessState { it.copy(isGenerating = false, streamingContent = null, generationError = memoryWarning) }
        
        val summary = chatRepository.getChatSummaryById(chatId)
        summary?.let {
            chatRepository.updateChat(it.chat.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
    }
    
    fun discardPartial() {
        updateSuccessState { it.copy(partialStopped = false, streamingContent = null) }
    }
    
    fun keepPartial() {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val content = state.streamingContent ?: return
        val character = state.chatSummary.characters.firstOrNull() ?: return
        
        viewModelScope.launch {
            val fullChar = characterRepository.getCharacterById(character.characterId) ?: return@launch
            saveGeneratedMessage(content, null, fullChar, null)
            updateSuccessState { it.copy(partialStopped = false) }
        }
    }

    fun retryGeneration() {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val lastMessage = state.messages.lastOrNull()
        if (lastMessage?.speakerType == SpeakerType.PERSONA) {
            viewModelScope.launch {
                startGeneration(lastMessage)
            }
        }
    }

    fun regenerateMessage(messageId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val primaryMessages = state.messages.filter { it.isPrimaryVariant || it.messageId == messageId }
        val targetMessage = primaryMessages.find { it.messageId == messageId } ?: return
        val history = primaryMessages.takeWhile { it.messageId != messageId }
        val lastUserMessage = history.lastOrNull { it.speakerType == SpeakerType.PERSONA }
        if (lastUserMessage != null) {
            viewModelScope.launch {
                val newHistory = history.takeWhile { it.messageId != lastUserMessage.messageId }
                startGenerationInternal(lastUserMessage, newHistory, variantGroupId = targetMessage.variantGroupId ?: targetMessage.messageId)
            }
        }
    }

    fun continueMessage(messageId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val primaryMessages = state.messages.filter { it.isPrimaryVariant }
        val targetMessage = primaryMessages.find { it.messageId == messageId && it.speakerType == SpeakerType.CHARACTER } ?: return
        val history = primaryMessages.takeWhile { it.messageId != messageId }
        viewModelScope.launch {
            startGenerationInternal(
                currentMessage = targetMessage,
                history = history,
                isContinuation = true,
                continuationTarget = targetMessage
            )
        }
    }

    fun deleteMessage(messageId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val targetMessage = state.messages.find { it.messageId == messageId } ?: return
        viewModelScope.launch {
            messageRepository.deleteMessage(targetMessage)
        }
    }

    fun deleteChat() {
        if (_uiState.value !is ChatUiState.Success) return
        generationJob?.cancel()
        sendInProgress = false
        viewModelScope.launch {
            try {
                chatRepository.deleteChat(chatId)
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
        viewModelScope.launch {
            messageRepository.setPrimaryVariant(variantGroupId, messageId)
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
        val state = _uiState.value as? ChatUiState.Success ?: return
        val targetMessage = state.messages.find { it.messageId == messageId } ?: return
        viewModelScope.launch {
            messageRepository.updateMessage(targetMessage.copy(content = newContent, editedAt = System.currentTimeMillis()))
        }
    }

    private fun updateSuccessState(update: (ChatUiState.Success) -> ChatUiState.Success) {
        val current = _uiState.value
        if (current is ChatUiState.Success) {
            _uiState.value = update(current)
        } else if (current is ChatUiState.Loading) {
            _uiState.value = update(ChatUiState.Success(
                chatSummary = chatSummary!!,
                messages = emptyList(),
                chatSettings = currentChatSettings!!
            ))
        }
    }
    
    class Factory(
        private val chatId: String,
        private val chatRepository: ChatRepository,
        private val messageRepository: MessageRepository,
        private val characterRepository: CharacterRepository,
        private val personaRepository: PersonaRepository,
        private val modelProvider: ModelProvider,
        private val memoryRepository: MemoryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatDetailViewModel(
                chatId, chatRepository, messageRepository, characterRepository, personaRepository, modelProvider, memoryRepository
            ) as T
        }
    }
}
