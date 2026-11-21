package com.example.domain.compiler

import com.example.domain.provider.RoleplayMessage

/**
 * Explicit toggles for generated, optional continuity material ONLY.
 * Never use these toggles for Character/Persona/World/Lorebook/Scenario canon,
 * supporting cast instructions, director controls, or recent transcript.
 *
 * The caller must rebuild the entire prompt with these toggles rather than
 * editing arbitrary system-message strings: authored content may itself
 * contain text that resembles compiler section headings.
 */
data class PromptOptionalContextChoices(
    val includeLegacyMemories: Boolean,
    val includeStoryMemories: Boolean,
    val includeSummary: Boolean
)

data class PromptOptionalContextFit(
    val messages: List<RoleplayMessage>,
    val historyTurnsRemoved: Int,
    val legacyMemoriesOmitted: Boolean,
    val storyMemoriesOmitted: Boolean,
    val summaryOmitted: Boolean,
    val decision: PromptPreflightDecision
) {
    /** Only flags and counts; never disclose the prompt or excluded memory text. */
    fun countsOnlyNotice(): String? {
        val changes = buildList {
            if (historyTurnsRemoved > 0) add("$historyTurnsRemoved older transcript turns")
            if (legacyMemoriesOmitted) add("legacy generated memory")
            if (storyMemoriesOmitted) add("optional approved story memories")
            if (summaryOmitted) add("generated conversation summary")
        }
        return if (changes.isEmpty()) null
        else "Context budget: omitted ${changes.joinToString(", ")} for this request only. " +
            "Saved chat data and memories were not changed."
    }
}

/**
 * Stage 2 additive recovery:
 * 1. Trim older, unprotected history using the existing shared safe preflight.
 * 2. On overflow, rebuild without legacy generated memory, then generated
 *    summary, then approved retrieved story memories as a last resort.
 * 3. If authored/system/recent context still cannot fit, fail closed BEFORE
 *    any provider request. Do not truncate source text or canon.
 *
 * Recompilation also preserves the original provenance/recipient approval
 * decisions; callers must provide already eligible memory lists.
 * No provider calls, model metadata fetches, DB writes or prompts are logged.
 */
object PromptOptionalContextFitter {
    fun fit(
        available: PromptOptionalContextChoices,
        modelContextWindowTokens: Int?,
        configuredInputCeilingTokens: Int = 12_000,
        protectedRecentTurns: Int = 6,
        compile: (PromptOptionalContextChoices) -> List<RoleplayMessage>
    ): PromptOptionalContextFit {
        var choices = available
        while (true) {
            val trimmed = PromptHistoryTrimmer.fit(
                messages = compile(choices),
                modelContextWindowTokens = modelContextWindowTokens,
                configuredInputCeilingTokens = configuredInputCeilingTokens,
                protectedRecentTurns = protectedRecentTurns
            )
            if (trimmed.decision.allowed ||
                !(choices.includeLegacyMemories ||
                    choices.includeStoryMemories || choices.includeSummary)
            ) {
                return PromptOptionalContextFit(
                    messages = trimmed.messages,
                    historyTurnsRemoved = trimmed.removedHistoricalTurns,
                    legacyMemoriesOmitted =
                        available.includeLegacyMemories && !choices.includeLegacyMemories,
                    storyMemoriesOmitted =
                        available.includeStoryMemories && !choices.includeStoryMemories,
                    summaryOmitted = available.includeSummary && !choices.includeSummary,
                    decision = trimmed.decision
                )
            }
            choices = when {
                choices.includeLegacyMemories ->
                    choices.copy(includeLegacyMemories = false)
                choices.includeSummary ->
                    choices.copy(includeSummary = false)
                else -> choices.copy(includeStoryMemories = false)
            }
        }
    }
}
