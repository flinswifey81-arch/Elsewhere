#!/bin/bash
sed -i '/sealed class ChatUiState {/i \
data class GenerationDetailsState(\n    val metadata: com.example.data.model.GenerationMetadataEntity? = null,\n    val isVisible: Boolean = false\n)\n' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

sed -i '/val partialStopped: Boolean = false/a \
        , val generationDetails: com.example.ui.screens.GenerationDetailsState = com.example.ui.screens.GenerationDetailsState()' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

sed -i '/fun editMessage/i \
    fun showGenerationDetails(messageId: String) {\n        viewModelScope.launch {\n            val meta = messageRepository.getGenerationMetadata(messageId)\n            updateSuccessState { it.copy(generationDetails = com.example.ui.screens.GenerationDetailsState(metadata = meta, isVisible = true)) }\n        }\n    }\n\n    fun hideGenerationDetails() {\n        updateSuccessState { it.copy(generationDetails = com.example.ui.screens.GenerationDetailsState(isVisible = false)) }\n    }\n' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

