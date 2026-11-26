package com.example.domain.compiler

import com.example.domain.provider.RoleplayMessage

/** Fits whole, explicitly shared entries against the actual Scenario request. */
internal object ScenarioPromptBudget {
    fun fit(
        available: PromptOptionalContextChoices,
        entries: List<String>,
        modelWindow: Int?,
        modelWindowOnly: Boolean,
        compile: (PromptOptionalContextChoices, String?, ScenarioPromptDetail) -> List<RoleplayMessage>
    ): PromptOptionalContextFit {
        fun fitAt(detail: ScenarioPromptDetail, selected: List<String>): PromptOptionalContextFit {
            val context = selected.takeIf { it.isNotEmpty() }?.joinToString("\n")
            return PromptOptionalContextFitter.fit(
                available, modelWindow, modelWindowOnly = modelWindowOnly
            ) { choices -> compile(choices, context, detail) }
                .copy(scenarioExcerptsUsed = detail != ScenarioPromptDetail.FULL)
        }

        val tiers = ScenarioPromptDetail.entries
        var result = fitAt(tiers.first(), entries)
        if (result.decision.allowed) return result
        for (tier in tiers.drop(1)) {
            result = fitAt(tier, entries)
            if (result.decision.allowed) return result
        }
        if (entries.isEmpty()) return result

        // Ordinary generated memory/summary is shed by the fitter first.
        // If even the smallest authored tier cannot fit all shared state, keep
        // the newest-episode entries that fit whole. An oversized entry need
        // not block a smaller one, and we never rewrite or excerpt saved facts.
        val smallest = tiers.last()
        var partial = fitAt(smallest, emptyList())
        if (!partial.decision.allowed) return partial
        val selected = mutableListOf<String>()
        for (entry in entries) {
            if (entry.toByteArray(Charsets.UTF_8).size.toLong() > partial.decision.inputCeilingTokens * 2L) continue
            selected += entry
            val candidate = fitAt(smallest, selected)
            if (candidate.decision.allowed) partial = candidate
            else selected.removeAt(selected.lastIndex)
        }
        // Keep the fullest authored tier that still fits the chosen facts.
        // An individually oversized entry should not force unrelated excerpts.
        for (tier in tiers.dropLast(1)) {
            val fuller = fitAt(tier, selected)
            if (fuller.decision.allowed) {
                partial = fuller
                break
            }
        }
        return partial.copy(playthroughEntriesOmitted = entries.size - selected.size)
    }
}
