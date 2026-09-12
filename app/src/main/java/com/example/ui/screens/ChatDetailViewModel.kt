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
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.ModelRouting
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.PersonaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class Success(
        val groupedMessages: List<com.example.ui.screens.MessageGroup> = emptyList(),
        val chatSummary: ChatSummary,
        val messages: List<MessageEntity>,
        val chatSettings: ChatSettingsEntity,
        val isGenerating: Boolean = false,
        val streamingContent: String? = null,
        val partialStopped: Boolean = false
        , val generationDetails: com.example.ui.screens.GenerationDetailsState = com.example.ui.screens.GenerationDetailsState()
    ) : ChatUiState()
}

class ChatDetailViewModel(
    private val chatId: String,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val characterRepository: CharacterRepository,
    private val personaRepository: PersonaRepository,
    private val modelProvider: ModelProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val currentDraft = MutableStateFlow("")
    val draft: StateFlow<String> = currentDraft.asStateFlow()

    private var chatSummary: ChatSummary? = null
    private var currentChatSettings: ChatSettingsEntity? = null
    private var generationJob: Job? = null
    private val compiler = ContextCompilerV1()

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
        if (text.isBlank()) return
        val summary = chatSummary ?: return
        val persona = summary.personas.firstOrNull() ?: return
        
        viewModelScope.launch {
            val userMsg = MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = persona.personaId,
                speakerDisplayNameSnapshot = persona.displayName,
                content = text,
                orderIndex = System.currentTimeMillis()
            )
            messageRepository.insertMessage(userMsg)
            onDraftChanged("") 
            
            startGeneration(userMsg)
        }
    }

    private suspend fun startGeneration(userMsg: MessageEntity) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val history = state.messages.filter { it.messageId != userMsg.messageId }
        startGenerationInternal(userMsg, history)
    }

    private suspend fun startGenerationInternal(
        currentMessage: MessageEntity,
        history: List<MessageEntity>,
        variantGroupId: String? = null,
        isContinuation: Boolean = false
    ) {
        val summary = chatSummary ?: return
        val settings = currentChatSettings ?: return
        val character = summary.characters.firstOrNull() ?: return
        val persona = summary.personas.firstOrNull() ?: return
        
        val fullCharacter = characterRepository.getCharacterById(character.characterId) ?: return
        val fullPersona = personaRepository.getPersonaById(persona.personaId) ?: return

        val contextMessages = compiler.compileSoloContext(
            isContinuation = isContinuation,
            character = fullCharacter,
            persona = fullPersona,
            chatHistory = history,
            currentMessage = currentMessage,
            responseProfile = settings.responseLengthProfile,
            customMin = settings.customMin,
            customTargetMax = settings.customTargetMax,
            customHardMax = settings.customHardMax
        )

        val routing = ModelRouting(
            mode = settings.providerRoutingMode,
            preferredEndpoints = settings.providerEndpoint?.let { listOf(it) } ?: emptyList()
        )

        val options = GenerationOptions(
            modelId = settings.selectedModelId ?: "openrouter/auto",
            routing = routing,
            temperature = settings.temperature,
            topP = settings.topP,
            maxTokens = when(settings.responseLengthProfile) {
                ResponseLengthProfile.SHORT -> 350
                ResponseLengthProfile.NORMAL -> 650
                ResponseLengthProfile.LONG -> 1000
                ResponseLengthProfile.CUSTOM -> (settings.customHardMax ?: 1900) / 3 
            }
        )

        updateSuccessState { it.copy(isGenerating = true, streamingContent = "", partialStopped = false) }

        var generatedContent = ""
        var currentMetadata: GenerationMetadataEntity? = null
        var stopped = false
        
        generationJob = viewModelScope.launch {
            try {
                modelProvider.streamResponse(contextMessages, options).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            generatedContent += event.text
                            updateSuccessState { it.copy(streamingContent = generatedContent) }
                        }
                        is StreamEvent.Done -> {
                            currentMetadata = event.metadata
                        }
                        is StreamEvent.Error -> {
                            stopped = true
                        }
                    }
                }
            } catch(e: kotlinx.coroutines.CancellationException) {
                stopped = true
            }
            
            if (stopped) {
                updateSuccessState { it.copy(isGenerating = false, partialStopped = true, streamingContent = generatedContent) }
            } else {
                saveGeneratedMessage(generatedContent, currentMetadata, fullCharacter, variantGroupId)
            }
        }
    }

    private suspend fun saveGeneratedMessage(
        content: String,
        metadata: GenerationMetadataEntity?,
        character: CharacterEntity,
        variantGroupId: String? = null
    ) {
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
            messageRepository.setPrimaryVariant(variantGroupId, msgId)
        }
        
        messageRepository.insertMessage(botMsg)
        
        metadata?.let { 
             messageRepository.insertGenerationMetadata(it.copy(messageId = msgId))
        }
        
        updateSuccessState { it.copy(isGenerating = false, streamingContent = null) }
        
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
        val targetMessage = state.messages.find { it.messageId == messageId } ?: return
        val history = state.messages.takeWhile { it.messageId != messageId }
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
        val targetMessage = state.messages.find { it.messageId == messageId } ?: return
        val history = state.messages.takeWhile { it.messageId != messageId }
        viewModelScope.launch {
            startGenerationInternal(targetMessage, history, isContinuation = true)
        }
    }

    fun deleteMessage(messageId: String) {
        val state = _uiState.value as? ChatUiState.Success ?: return
        val targetMessage = state.messages.find { it.messageId == messageId } ?: return
        viewModelScope.launch {
            messageRepository.deleteMessage(targetMessage)
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
        private val modelProvider: ModelProvider
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatDetailViewModel(
                chatId, chatRepository, messageRepository, characterRepository, personaRepository, modelProvider
            ) as T
        }
    }
}
