#!/bin/bash
sed -i 's/private suspend fun startGeneration(userMsg: MessageEntity) {/private suspend fun startGeneration(userMsg: MessageEntity) {\n        val state = _uiState.value as? ChatUiState.Success ?: return\n        val history = state.messages.filter { it.messageId != userMsg.messageId }\n        startGenerationInternal(userMsg, history)\n    }\n\n    private suspend fun startGenerationInternal(currentMessage: MessageEntity, history: List<MessageEntity>, variantGroupId: String? = null, isContinuation: Boolean = false) {/g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

# Remove the old history extraction inside startGenerationInternal
sed -i 's/val state = _uiState.value as? ChatUiState.Success ?: return//g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt
sed -i 's/val history = state.messages.filter { it.messageId != userMsg.messageId }//g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

# Replace userMsg with currentMessage
sed -i 's/currentMessage = userMsg/currentMessage = currentMessage/g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt
sed -i 's/val contextMessages = compiler.compileSoloContext(/val contextMessages = compiler.compileSoloContext(\n            isContinuation = isContinuation,/g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

# Pass variantGroupId to saveGeneratedMessage
sed -i 's/saveGeneratedMessage(generatedContent, currentMetadata, fullCharacter)/saveGeneratedMessage(generatedContent, currentMetadata, fullCharacter, variantGroupId)/g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt
sed -i 's/saveGeneratedMessage(content, null, fullChar)/saveGeneratedMessage(content, null, fullChar, null)/g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

sed -i 's/    private suspend fun saveGeneratedMessage(/    private suspend fun saveGeneratedMessage(\n        content: String,\n        metadata: GenerationMetadataEntity?,\n        character: CharacterEntity,\n        variantGroupId: String? = null\n    ) {/g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

# Now remove the old signature
sed -i '/    private suspend fun saveGeneratedMessage(/,/    ) {/ {
    /    private suspend fun saveGeneratedMessage(/! {
        /    ) {/!d
    }
}' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

