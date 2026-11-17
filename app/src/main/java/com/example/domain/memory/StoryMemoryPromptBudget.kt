package com.example.domain.memory

import com.example.domain.provider.RoleplayMessage

/**
 * Additive prompt-cost guard for local story memories. It NEVER trims authored
 * Character/Persona instructions, World canon, Lorebooks, or recent history.
 * If baseline context is large, only the optional retrieved memories are skipped.
 *
 * Byte-to-token estimates are conservative diagnostics, NOT provider billing or
 * proof of a strict tokenizer bound. Unknown model windows use a modest fallback.
 * A later unified assembler must enforce exact full-request budgets.
 */
data class StoryMemoryBudgetDecision(
    val selected: List<StoryMemoryRecord>,
    val omittedForBudget: Int,
    val approximateBaselineTokens: Int,
    val approximateInputCeilingTokens: Int,
    val windowKnown: Boolean
)

object StoryMemoryPromptBudget {
    private const val DEFAULT_MODEL_WINDOW = 16_384
    private const val DEFAULT_INPUT_CEILING = 12_000
    private const val MAX_ADDED_MEMORY_CHARS = 4_000

    fun select(
        baselineMessages: List<RoleplayMessage>,
        rankedMemories: List<StoryMemoryRecord>,
        modelContextWindowTokens: Int?,
        configuredInputCeilingTokens: Int = DEFAULT_INPUT_CEILING
    ): StoryMemoryBudgetDecision {
        require(configuredInputCeilingTokens in 1_024..200_000)
        val known = modelContextWindowTokens != null && modelContextWindowTokens >= 2_048
        val window = if (known) modelContextWindowTokens!! else DEFAULT_MODEL_WINDOW
        val reservedOutput = minOf(4_096, maxOf(1_024, window / 4))
        val ceiling = minOf(configuredInputCeilingTokens, (window - reservedOutput).coerceAtLeast(0))
        val base = approximateTokens(baselineMessages)
        var remainingTokens = (ceiling - base).coerceAtLeast(0)
        var remainingChars = MAX_ADDED_MEMORY_CHARS
        val selected = mutableListOf<StoryMemoryRecord>()
        var omitted = 0
        for (memory in rankedMemories) {
            // Include formatting and annotation overhead in the estimate.
            val cost = approximateTextTokens(memory.content) + 64
            if (memory.content.length > remainingChars || cost > remainingTokens) {
                omitted++
                continue
            }
            selected += memory
            remainingTokens -= cost
            remainingChars -= memory.content.length
        }
        return StoryMemoryBudgetDecision(
            selected = selected,
            omittedForBudget = omitted,
            approximateBaselineTokens = base,
            approximateInputCeilingTokens = ceiling,
            windowKnown = known
        )
    }

    /** Conservative local heuristic. Actual tokens depend on the provider tokenizer. */
    fun approximateTokens(messages: List<RoleplayMessage>): Int =
        messages.sumOf { message ->
            approximateTextTokens(message.content) + 48 + message.images.size * 1_024
        }

    private fun approximateTextTokens(text: String): Int =
        (text.toByteArray(Charsets.UTF_8).size + 1) / 2
}
