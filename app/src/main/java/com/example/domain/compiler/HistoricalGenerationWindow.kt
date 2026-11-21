package com.example.domain.compiler

import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType

/**
 * Prepares Regenerate / Continue from a persisted, chronologically ordered
 * UI history without allocating a second full-size selected-variant list.
 *
 * The target is resolved by messageId, not orderIndex: multiple variants
 * may share a chronological position and an unselected variant may itself
 * be the Regenerate target. All earlier source ordering is preserved.
 */
object HistoricalGenerationWindow {
    data class Selection(
        val target: MessageEntity,
        val current: MessageEntity,
        val history: List<MessageEntity>
    )

    fun regenerate(
        messages: List<MessageEntity>,
        targetMessageId: String,
        isGroup: Boolean,
        resetBoundary: Long?,
        recentLimit: Int = 24
    ): Selection? {
        require(recentLimit >= 0)
        val targetIndex = findFromEnd(messages, messages.size - 1) {
            it.messageId == targetMessageId
        }
        if (targetIndex < 0) return null

        val currentIndex = findFromEnd(messages, targetIndex - 1) {
            it.isPrimaryVariant && (isGroup || it.speakerType == SpeakerType.PERSONA)
        }
        if (currentIndex < 0) return null

        val current = messages[currentIndex]
        val recentHistory = selectedBefore(messages, currentIndex, recentLimit)
        val history = if (current.speakerType == SpeakerType.PERSONA) recentHistory
            else withPersonaIdentity(messages, currentIndex, resetBoundary, recentHistory)
        return Selection(messages[targetIndex], current, history)
    }

    fun continueFrom(
        messages: List<MessageEntity>,
        targetMessageId: String,
        resetBoundary: Long?,
        recentLimit: Int = 24
    ): Selection? {
        require(recentLimit >= 0)
        val targetIndex = findFromEnd(messages, messages.size - 1) {
            it.messageId == targetMessageId &&
                it.isPrimaryVariant &&
                (it.speakerType == SpeakerType.CHARACTER || it.speakerType == SpeakerType.NARRATOR)
        }
        if (targetIndex < 0) return null

        val current = messages[targetIndex]
        val recentHistory = selectedBefore(messages, targetIndex, recentLimit)
        val history = withPersonaIdentity(messages, targetIndex, resetBoundary, recentHistory)
        return Selection(current, current, history)
    }

    private fun selectedBefore(
        messages: List<MessageEntity>,
        exclusiveIndex: Int,
        limit: Int
    ): List<MessageEntity> {
        if (limit == 0) return emptyList()
        val recent = ArrayList<MessageEntity>(minOf(limit, exclusiveIndex))
        for (index in exclusiveIndex - 1 downTo 0) {
            val row = messages[index]
            if (!row.isPrimaryVariant) continue
            recent.add(row)
            if (recent.size == limit) break
        }
        recent.reverse()
        return recent
    }

    /**
     * The existing compiler uses the last 24 turns for conversation context,
     * but character/Scenario selection uses the most recent Persona even when
     * they spoke earlier. A distant Persona anchor is intentionally placed
     * ahead of those 24 turns, where it can inform identity without expanding
     * the compiled prompt. Never resurrect a Persona before Wack.
     */
    private fun withPersonaIdentity(
        messages: List<MessageEntity>,
        exclusiveIndex: Int,
        resetBoundary: Long?,
        recent: List<MessageEntity>
    ): List<MessageEntity> {
        if (recent.any { it.speakerType == SpeakerType.PERSONA }) return recent
        val index = findFromEnd(messages, exclusiveIndex - 1) { row ->
            row.isPrimaryVariant && row.speakerType == SpeakerType.PERSONA &&
                (resetBoundary == null || row.orderIndex > resetBoundary)
        }
        if (index < 0) return recent
        return listOf(messages[index]) + recent
    }

    private inline fun findFromEnd(
        messages: List<MessageEntity>,
        fromIndex: Int,
        predicate: (MessageEntity) -> Boolean
    ): Int {
        for (index in fromIndex downTo 0) {
            if (predicate(messages[index])) return index
        }
        return -1
    }
}
