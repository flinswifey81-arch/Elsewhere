package com.example.domain.compiler

import com.example.domain.provider.RoleplayMessage
import com.example.domain.memory.StoryMemoryPromptBudget

/**
 * Local, zero-provider-call safety preflight. This deliberately does not claim
 * tokenizer-exact enforcement; the estimate is conservative and includes an
 * image allowance. The full section-aware trimmer is a separate milestone.
 */
data class PromptPreflightDecision(
    val estimatedInputTokens: Int,
    val estimatedTotalTokens: Long,
    val inputCeilingTokens: Int,
    val outputReserveTokens: Int,
    val modelWindowKnown: Boolean,
    val allowed: Boolean
)

object PromptPreflight {
    private const val UNKNOWN_WINDOW = 16_384
    private const val DEFAULT_INPUT_CEILING = 12_000
    fun inspect(
        messages: List<RoleplayMessage>,
        modelContextWindowTokens: Int?,
        configuredInputCeilingTokens: Int = DEFAULT_INPUT_CEILING,
        outputReserveTokens: Int? = null
    ): PromptPreflightDecision {
        require(configuredInputCeilingTokens in 1_024..200_000)
        val known = modelContextWindowTokens != null && modelContextWindowTokens >= 2_048
        val window = if (known) modelContextWindowTokens!! else UNKNOWN_WINDOW
        val reserve = outputReserveTokens ?: minOf(4_096, maxOf(1_024, window / 4))
        require(reserve > 0)
        val ceiling = minOf(configuredInputCeilingTokens, (window - reserve).coerceAtLeast(0))
        val estimate = StoryMemoryPromptBudget.approximateTokens(messages)
        return PromptPreflightDecision(
            estimatedInputTokens = estimate,
            estimatedTotalTokens = estimate.toLong() + reserve,
            inputCeilingTokens = ceiling,
            outputReserveTokens = reserve,
            modelWindowKnown = known,
            allowed = estimate <= ceiling
        )
    }
}
