package com.example.domain.compiler

import com.example.domain.provider.RoleplayMessage

/**
 * First conservative budget recovery pass: remove only old transcript turns.
 * Never edit system/canon, current turn, director instructions or attachments.
 * Callers must use the returned list for the provider request and co-op hash.
 */
data class PromptHistoryTrimResult(
    val messages: List<RoleplayMessage>,
    val removedHistoricalTurns: Int,
    val decision: PromptPreflightDecision
)

object PromptHistoryTrimmer {
    fun fit(
        messages: List<RoleplayMessage>,
        modelContextWindowTokens: Int?,
        configuredInputCeilingTokens: Int = 12_000,
        protectedRecentTurns: Int = 6
    ): PromptHistoryTrimResult {
        require(protectedRecentTurns >= 1)
        val working = messages.toMutableList()
        var decision = PromptPreflight.inspect(working, modelContextWindowTokens, configuredInputCeilingTokens)
        var removed = 0
        while (!decision.allowed) {
            val historyIndices = working.indices.filter { working[it].sourceMessageId != null }
            val removable = historyIndices.dropLast(protectedRecentTurns).firstOrNull { index ->
                working[index].images.isEmpty()
            } ?: break
            working.removeAt(removable)
            removed++
            decision = PromptPreflight.inspect(working, modelContextWindowTokens, configuredInputCeilingTokens)
        }
        return PromptHistoryTrimResult(working.toList(), removed, decision)
    }
}
