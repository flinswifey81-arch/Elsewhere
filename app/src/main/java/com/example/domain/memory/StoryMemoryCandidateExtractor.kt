package com.example.domain.memory

/**
 * Conservative, zero-cost proposal finder. It does not assert truth:
 * proposed excerpts always require the player's explicit review.
 *
 * Keep the exact visible source sentence rather than rewriting or inventing
 * motivations, character thoughts, consequences, or relationship transitions.
 */
object StoryMemoryCandidateExtractor {
    data class Suggestion(val excerpt: String, val claim: StoryMemoryClaim)

    private val promise = Regex(
        """\b(?:i|we)\s+(?:promise|swear|vow)\b|\b(?:promised|swore|vowed)\b""",
        RegexOption.IGNORE_CASE
    )
    private val discovery = Regex(
        """\b(?:discovered|revealed|uncovered|learned|learnt|realized|realised)\b""",
        RegexOption.IGNORE_CASE
    )
    private val event = Regex(
        """\b(?:arrived|departed|escaped|betrayed|forgave|died|destroyed|recovered|reconciled|confessed)\b""",
        RegexOption.IGNORE_CASE
    )
    private val fragments = Regex("""(?<=[.!?])\s+|\n+""")

    fun suggest(visibleText: String): Suggestion? {
        // Scan only bounded input, and never return a cut-off excerpt.
        // Full original source bytes remain hashed in the stored provenance.
        if (visibleText.length > 12_000) return null
        for (fragment in visibleText.split(fragments)) {
            val excerpt = fragment.trim()
            if (excerpt.length !in 12..1_200) continue
            val claim = when {
                promise.containsMatchIn(excerpt) -> StoryMemoryClaim.PROMISE
                discovery.containsMatchIn(excerpt) -> StoryMemoryClaim.DISCOVERY
                event.containsMatchIn(excerpt) -> StoryMemoryClaim.EVENT
                else -> null
            }
            if (claim != null) return Suggestion(excerpt, claim)
        }
        return null
    }
}
