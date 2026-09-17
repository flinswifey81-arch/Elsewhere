package com.example.domain.memory

import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.domain.repository.MemoryRepository

class AutomaticMemoryManager(
    private val repository: MemoryRepository,
    private val recentMessageLimit: Int = 24
) {
    suspend fun updateAfterCompletedTurn(
        chatId: String,
        characterId: String,
        personaMessage: MessageEntity,
        allPrimaryMessages: List<MessageEntity>
    ) {
        extractExplicitMemories(personaMessage.content).forEach { (category, content) ->
            repository.insertIfNew(
                RoleplayMemoryEntity(
                    chatId = chatId,
                    characterId = characterId,
                    category = category,
                    content = content,
                    sourceMessageId = personaMessage.messageId
                )
            )
        }

        val cutoffIndex = allPrimaryMessages.size - recentMessageLimit
        if (cutoffIndex <= 0) return

        val olderMessages = allPrimaryMessages.take(cutoffIndex)
        if (olderMessages.isEmpty()) return

        val combined = olderMessages.joinToString("\n") { message ->
            val compact = message.content.replace(Regex("\\s+"), " ").trim().take(280)
            "${message.speakerDisplayNameSnapshot}: $compact"
        }
            .takeLast(MAX_SUMMARY_CHARS)

        repository.upsertSummary(
            ConversationSummaryEntity(
                chatId = chatId,
                summary = combined,
                summarizedThroughOrderIndex = olderMessages.maxOf { it.orderIndex }
            )
        )
    }

    internal fun extractExplicitMemories(text: String): List<Pair<MemoryCategory, String>> {
        if (containsSensitiveMaterial(text)) return emptyList()

        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.length !in 4..500) return emptyList()

        val specificPatterns = listOf(
            MemoryCategory.IDENTITY to Regex("(?i)\\bmy name is (.{2,100})"),
            MemoryCategory.PREFERENCE to Regex("(?i)\\bi (?:really )?(?:like|love|prefer) (.{3,180})"),
            MemoryCategory.BOUNDARY to Regex("(?i)\\bi (?:do not|don't|cannot|can't|hate) (.{3,180})"),
            MemoryCategory.PROMISE to Regex("(?i)\\bi promise (.{3,200})"),
            MemoryCategory.RELATIONSHIP to Regex("(?i)\\b(?:we are|you are my|i am your) (.{3,180})"),
            MemoryCategory.LOCATION to Regex("(?i)\\b(?:i live in|my home is|we live in) (.{3,180})")
        )

        val specificMatches = specificPatterns.mapNotNull { (category, regex) ->
            val value = regex.find(normalized)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.trimEnd('.', '!', '?')
                ?.takeIf { it.length >= 3 }
                ?: return@mapNotNull null
            category to value
        }.distinctBy { it.second.lowercase() }
        if (specificMatches.isNotEmpty()) return specificMatches

        val remembered = Regex("(?i)\\bremember (?:that )?(.{3,240})")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trimEnd('.', '!', '?')
            ?.takeIf { it.length >= 3 }
        return remembered?.let { listOf(MemoryCategory.DETAIL to it) }.orEmpty()
    }

    private fun containsSensitiveMaterial(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("api key", "apikey", "access token", "password", "authorization:", "bearer ", "sk-")
            .any(lower::contains)
    }

    companion object {
        const val DEFAULT_RECENT_MESSAGE_LIMIT = 24
        private const val MAX_SUMMARY_CHARS = 12_000
    }
}
