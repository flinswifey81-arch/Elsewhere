#!/bin/bash
sed -i '/data class Success(/a \
        val groupedMessages: List<com.example.ui.screens.MessageGroup> = emptyList(),' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

sed -i '/sealed class ChatUiState {/i \
data class MessageGroup(\n    val primaryMessage: com.example.data.model.MessageEntity,\n    val variants: List<com.example.data.model.MessageEntity>\n)\n' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

sed -i 's/updateSuccessState { it.copy(messages = messages, chatSummary = summary, chatSettings = currentChatSettings!!) }/val grouped = messages.groupBy { it.variantGroupId ?: it.messageId }.mapNotNull { (_, msgs) ->\n                    val primary = msgs.find { it.isPrimaryVariant } ?: msgs.firstOrNull()\n                    if (primary != null) MessageGroup(primary, msgs.sortedBy { it.orderIndex }) else null\n                }.sortedBy { it.primaryMessage.orderIndex }\n                updateSuccessState { it.copy(messages = messages, groupedMessages = grouped, chatSummary = summary, chatSettings = currentChatSettings!!) }/g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt

