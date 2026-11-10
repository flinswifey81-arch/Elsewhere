package com.example.domain.memory

import java.util.Locale

/**
 * Stage 2A: deterministic, offline selection of approved chat-local memories.
 *
 * IMPORTANT: This is a read-only selection component, NOT yet wired to model
 * prompt construction. It must never receive an unfiltered list from callers
 * who plan to send the returned text to a provider without rechecking the
 * recipient and the generation's active branch/Wack state.
 *
 * Eligibility is always evaluated before scoring. A pinned memory does not
 * override knowledge, selected-variant, provenance, or Wack boundaries.
 */
data class StoryMemorySearchRequest(
    val readContext: StoryMemoryReadContext,
    val sceneText: String,
    val activeActors: Set<StoryActorRef> = emptySet(),
    val maxMemories: Int = 8,
    /** A local text-size cap, not a tokenizer or model context budget. */
    val maxSelectedTextCharacters: Int = 4_000
)

data class StoryMemorySearchResult(
    val selected: List<StoryMemoryRecord>,
    val eligibleCount: Int,
    /** Only reasons/counts: diagnostics must not expose excluded secret text. */
    val excludedCounts: Map<StoryMemoryExclusion, Int>,
    val skippedForTextLimit: Int
)

object StoryMemoryLocalRetriever {
    private val wordPattern = Regex("[\\p{L}\\p{N}]+")
    private val uninformativeWords = setOf(
        "the", "and", "for", "but", "with", "from", "this", "that",
        "they", "them", "their", "then", "there", "were", "have",
        "has", "had", "into", "about", "what", "when", "where",
        "will", "would", "could", "should", "your", "you", "our",
        "are", "was", "who", "his", "her", "its", "not", "she",
        "him", "all", "can", "did", "does", "how", "why", "out"
    )

    /**
     * Caller supplies local archive records and the exact recipient context.
     * No network calls, model calls, database writes, or global caches.
     * No text from rejected records enters relevance scoring.
     */
    fun search(
        candidates: List<StoryMemoryRecord>,
        request: StoryMemorySearchRequest
    ): StoryMemorySearchResult {
        require(request.maxMemories in 0..64) { "maxMemories must be between 0 and 64." }
        require(request.maxSelectedTextCharacters in 0..64_000) {
            "maxSelectedTextCharacters must be between 0 and 64000."
        }
        val excluded = mutableMapOf<StoryMemoryExclusion, Int>()
        val eligible = candidates.filter { record ->
            val decision = StoryMemoryEligibilityPolicy.evaluate(record, request.readContext)
            if (!decision.eligible) {
                excluded[decision.reason] = (excluded[decision.reason] ?: 0) + 1
            }
            decision.eligible
        }
        val queryWords = terms(request.sceneText.takeLast(4_000))
        val queryPhrase = request.sceneText.takeLast(4_000).trim()
            .lowercase(Locale.ROOT).takeIf { it.length in 4..120 }

        data class Scored(val record: StoryMemoryRecord, val score: Int)
        val scored = eligible.mapNotNull { record ->
            val memoryWords = terms(record.content)
            val overlap = queryWords.count { it in memoryWords }
            val actorMatches = request.activeActors.count { it in record.actors || it in record.knownBy }
            val phraseMatch = queryPhrase != null &&
                record.content.lowercase(Locale.ROOT).contains(queryPhrase)
            val isOpenThread = record.lifecycle == StoryMemoryLifecycle.ACTIVE &&
                (record.claim == StoryMemoryClaim.PROMISE ||
                    record.claim == StoryMemoryClaim.UNRESOLVED_THREAD)
            val score = overlap * 10 + actorMatches * 8 +
                (if (phraseMatch) 12 else 0) +
                (if (record.pinned) 24 else 0) +
                (if (isOpenThread) 5 else 0)

            // Avoid filling a prompt with irrelevant past facts just because
            // they happened to be eligible. Pinned/open threads are explicit
            // continuity signals even without a literal keyword match.
            if (score == 0) null else Scored(record, score)
        }.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { sourceOrder(it.record) }
                .thenByDescending { it.record.updatedAtEpochMillis }
                .thenBy { it.record.memoryId }
        )

        val selected = mutableListOf<StoryMemoryRecord>()
        var usedCharacters = 0
        var skipped = 0
        for (candidate in scored) {
            if (selected.size >= request.maxMemories) break
            val length = candidate.record.content.length
            if (length > request.maxSelectedTextCharacters - usedCharacters) {
                skipped++
                continue
            }
            selected += candidate.record
            usedCharacters += length
        }
        return StoryMemorySearchResult(
            selected = selected,
            eligibleCount = eligible.size,
            excludedCounts = excluded.toSortedMap(compareBy { it.name }),
            skippedForTextLimit = skipped
        )
    }

    private fun sourceOrder(record: StoryMemoryRecord): Long = when (val origin = record.origin) {
        is StoryMemoryOrigin.Manual -> origin.createdAtOrderIndex
        is StoryMemoryOrigin.Message -> origin.orderIndex
    }

    private fun terms(text: String): Set<String> =
        wordPattern.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 2 && it !in uninformativeWords }
            .toSet()
}
