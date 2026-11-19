package com.example.domain.memory

import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.story.StoryResponseCodec

data class AutomaticMemoryUpdateResult(
    val savedMemoryCount: Int,
    val rollingSummaryUpdated: Boolean
)

class AutomaticMemoryManager(
    private val repository: MemoryRepository,
    private val recentMessageLimit: Int = 24
) {
    suspend fun updateAfterCompletedTurn(
        chatId: String,
        characterId: String,
        personaMessage: MessageEntity,
        allPrimaryMessages: List<MessageEntity>
    ): AutomaticMemoryUpdateResult = updateFromMessages(
        chatId = chatId,
        characterId = characterId,
        personaMessages = listOf(personaMessage),
        allPrimaryMessages = allPrimaryMessages
    )

    /**
     * Production completed-turn path. Prompt preparation no longer has to
     * carry the entire transcript just for the legacy rolling summary.
     * Read selected history newest-first in local pages until the complete
     * retained 12k-character summary suffix is available, then feed the
     * same deterministic updater as before. No model/API calls.
     *
     * Memory maintenance uses the persisted selected branch after the
     * generated reply is saved, scoped strictly to the active Wack boundary.
     */
    suspend fun updateAfterCompletedTurnFromStoredHistory(
        chatId: String,
        characterId: String,
        personaMessage: MessageEntity,
        messageRepository: MessageRepository,
        resetBoundary: Long?
    ): AutomaticMemoryUpdateResult {
        val messages = loadSummarySource(
            chatId, resetBoundary, messageRepository
        )
        return updateAfterCompletedTurn(
            chatId = chatId,
            characterId = characterId,
            personaMessage = personaMessage,
            allPrimaryMessages = messages
        )
    }

    internal suspend fun loadSummarySource(
        chatId: String,
        resetBoundary: Long?,
        messages: MessageRepository,
        pageSize: Int = 128
    ): List<MessageEntity> {
        require(pageSize in 1..256) { "Summary page size must be 1..256." }
        val newestFirst = mutableListOf<MessageEntity>()
        while (true) {
            val page = messages.getSelectedHistoryReversePage(
                chatId = chatId,
                resetBoundary = resetBoundary,
                offset = newestFirst.size,
                pageSize = pageSize
            )
            if (page.isEmpty()) break
            newestFirst.addAll(page)
            // Keep the most recent turns outside the summary. The suffix is
            // complete as soon as the older portion fills 12k characters.
            // If it never fills, continue all the way to the start of chat.
            if (newestFirst.size > recentMessageLimit &&
                rollingSummaryTail(
                    newestFirst.subList(recentMessageLimit, newestFirst.size).asReversed()
                ).length >= MAX_SUMMARY_CHARS
            ) break
            if (page.size < pageSize) break
        }
        newestFirst.reverse()
        return newestFirst
    }

    /**
     * Explicit "Update Memory Now" reads the selected transcript in small,
     * local Room pages. Unlike automatic capture, this operation deliberately
     * inspects ALL eligible Persona turns for deterministic fact extraction,
     * but never materializes the full archive in memory or makes model calls.
     */
    suspend fun updateFromStoredConversation(
        chatId: String,
        characterId: String,
        messageRepository: MessageRepository,
        resetBoundary: Long?
    ): AutomaticMemoryUpdateResult {
        var saved = 0
        var offset = 0
        while (true) {
            val page = messageRepository.getSelectedHistoryForwardPage(
                chatId, resetBoundary, offset, pageSize = 128
            )
            if (page.isEmpty()) break
            saved += persistPersonaMemories(
                chatId, characterId,
                page.filter { it.speakerType == com.example.data.model.SpeakerType.PERSONA }
            )
            offset += page.size
            if (page.size < 128) break
        }
        val summarySource = loadSummarySource(
            chatId, resetBoundary, messageRepository
        )
        return AutomaticMemoryUpdateResult(
            savedMemoryCount = saved,
            rollingSummaryUpdated = refreshRollingSummary(chatId, summarySource)
        )
    }

    suspend fun updateFromConversation(
        chatId: String,
        characterId: String,
        allPrimaryMessages: List<MessageEntity>
    ): AutomaticMemoryUpdateResult = updateFromMessages(
        chatId = chatId,
        characterId = characterId,
        personaMessages = allPrimaryMessages.filter { it.speakerType == com.example.data.model.SpeakerType.PERSONA },
        allPrimaryMessages = allPrimaryMessages
    )

    private suspend fun updateFromMessages(
        chatId: String,
        characterId: String,
        personaMessages: List<MessageEntity>,
        allPrimaryMessages: List<MessageEntity>
    ): AutomaticMemoryUpdateResult {
        val savedMemoryCount = persistPersonaMemories(chatId, characterId, personaMessages)
        val summaryUpdated = refreshRollingSummary(chatId, allPrimaryMessages)
        return AutomaticMemoryUpdateResult(savedMemoryCount, summaryUpdated)
    }

    private suspend fun persistPersonaMemories(
        chatId: String,
        characterId: String,
        personaMessages: Iterable<MessageEntity>
    ): Int {
        var saved = 0
        personaMessages.forEach { personaMessage ->
            extractExplicitMemories(personaMessage.content).forEach { (category, content) ->
                if (repository.insertIfNew(
                    RoleplayMemoryEntity(
                        chatId = chatId,
                        characterId = characterId,
                        category = category,
                        content = content,
                        sourceMessageId = personaMessage.messageId
                    )
                )) saved++
            }
        }
        return saved
    }

    private suspend fun refreshRollingSummary(
        chatId: String,
        allPrimaryMessages: List<MessageEntity>
    ): Boolean {
        val cutoffIndex = allPrimaryMessages.size - recentMessageLimit
        if (cutoffIndex <= 0) {
            return false
        }

        // A view avoids copying the entire older archive. The summary itself
        // only retains the final 12,000 characters, so formatting 10,000 past
        // turns and discarding nearly all of them is unnecessary.
        val olderMessages = allPrimaryMessages.subList(0, cutoffIndex)
        if (olderMessages.isEmpty()) return false

        val combined = rollingSummaryTail(olderMessages)

        repository.upsertSummary(
            ConversationSummaryEntity(
                chatId = chatId,
                summary = combined,
                summarizedThroughOrderIndex = olderMessages.maxOf { it.orderIndex }
            )
        )
        return true
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

        /**
         * Identical to formatting every archived turn and taking the last
         * MAX_SUMMARY_CHARS, but visits only as many trailing messages as are
         * needed to cover the retained suffix. Earlier turns cannot change
         * the ending once the retained text is long enough.
         */
        internal fun rollingSummaryTail(olderMessages: List<MessageEntity>): String {
            val retained = ArrayDeque<String>()
            var retainedChars = 0
            for (index in olderMessages.indices.reversed()) {
                val message = olderMessages[index]
                val compact = StoryResponseCodec.promptTranscript(
                    NpcStructuredOutputParser.finalVisibleStoryText(message.content)
                ).replace(Regex("\\s+"), " ").trim().take(280)
                val line = "${message.speakerDisplayNameSnapshot}: $compact"
                val additionalChars = line.length + if (retained.isEmpty()) 0 else 1
                retained.addFirst(line)
                retainedChars += additionalChars
                if (retainedChars >= MAX_SUMMARY_CHARS) break
            }
            return retained.joinToString("\n").takeLast(MAX_SUMMARY_CHARS)
        }
    }
}
