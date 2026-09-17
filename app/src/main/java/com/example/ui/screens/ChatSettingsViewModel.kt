package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ProviderRoutingMode
import com.example.data.model.ResponseLengthProfile
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatSettingsUiState(
    val settings: ChatSettingsEntity? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val models: List<OpenRouterModel> = emptyList(),
    val filteredModels: List<OpenRouterModel> = emptyList(),
    val searchQuery: String = "",
    val endpoints: List<ProviderEndpoint> = emptyList(),
    val isFetchingModels: Boolean = false,
    val isFetchingEndpoints: Boolean = false,
    val customLengthError: String? = null
)

class ChatSettingsViewModel(
    private val chatId: String,
    private val chatRepository: ChatRepository,
    private val modelProvider: ModelProvider
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatSettingsUiState())
    val uiState: StateFlow<ChatSettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        fetchModels()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            try {
                var settings = chatRepository.getChatSettings(chatId)
                if (settings == null) {
                    settings = ChatSettingsEntity(chatId = chatId)
                    chatRepository.insertChatSettings(settings)
                }
                _uiState.update { it.copy(settings = settings, isLoading = false) }
                settings.selectedModelId?.let { fetchEndpoints(it) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun fetchModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingModels = true) }
            try {
                val models = modelProvider.getModels()
                _uiState.update { state -> 
                    state.copy(
                        models = models,
                        filteredModels = models.filter { it.name.contains(state.searchQuery, ignoreCase = true) || it.id.contains(state.searchQuery, ignoreCase = true) },
                        isFetchingModels = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isFetchingModels = false, error = "Failed to fetch models: ${e.message}") }
            }
        }
    }

    private fun fetchEndpoints(modelId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingEndpoints = true) }
            try {
                val endpoints = modelProvider.getEndpoints(modelId)
                _uiState.update { it.copy(endpoints = endpoints, isFetchingEndpoints = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isFetchingEndpoints = false, error = "Failed to fetch endpoints: ${e.message}") }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredModels = state.models.filter { it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
            )
        }
    }

    fun onModelSelected(modelId: String) {
        updateSettings { it.copy(selectedModelId = modelId) }
        fetchEndpoints(modelId)
    }

    fun onRoutingModeSelected(mode: ProviderRoutingMode) {
        updateSettings { it.copy(providerRoutingMode = mode) }
    }

    fun onEndpointSelected(endpoint: String?) {
        updateSettings { it.copy(providerEndpoint = endpoint) }
    }

    fun onTemperatureChanged(temp: Float?) {
        updateSettings { it.copy(temperature = temp) }
    }

    fun onTopPChanged(topP: Float?) {
        updateSettings { it.copy(topP = topP) }
    }

    fun onResponseProfileSelected(profile: ResponseLengthProfile) {
        updateSettings { it.copy(responseLengthProfile = profile) }
    }

    fun onCustomLengthsChanged(min: Int, target: Int, max: Int) {
        if (isValidCustomLengths(min, target, max)) {
            updateSettings { 
                it.copy(
                    customMin = min,
                    customTargetMax = target,
                    customHardMax = max
                ) 
            }
            _uiState.update { it.copy(customLengthError = null) }
        } else {
            _uiState.update {
                it.copy(customLengthError = "Use positive values with minimum ≤ target ≤ hard maximum (up to 20,000 characters).")
            }
        }
    }

    private fun updateSettings(updater: (ChatSettingsEntity) -> ChatSettingsEntity) {
        val currentSettings = _uiState.value.settings ?: return
        val newSettings = updater(currentSettings)
        _uiState.update { it.copy(settings = newSettings) }
        viewModelScope.launch {
            chatRepository.insertChatSettings(newSettings)
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val chatId: String,
        private val chatRepository: ChatRepository,
        private val modelProvider: ModelProvider
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatSettingsViewModel(chatId, chatRepository, modelProvider) as T
        }
    }
}

internal fun isValidCustomLengths(min: Int, target: Int, max: Int): Boolean =
    min > 0 && min <= target && target <= max && max <= 20_000
