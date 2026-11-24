package com.example.domain.npc

import java.util.Locale

/**
 * Conservative fallback for named supporting NPCs who are explicitly credited
 * with speaking in ordinary prose, but whose model response omitted the optional
 * NPC Bank metadata and did not use line-oriented speaker segments.
 *
 * Do not invent people from descriptions ("the guard"), scene mentions, inferred
 * entrances, or ambiguous unattributed dialogue. Caller must exclude ALL active
 * player and AI-controlled speaker names before proposing a dossier.
 */
object NpcProseDiscovery {
    private const val NAME = "([\\p{Lu}][\\p{L}'’.-]{1,40}(?:[ \\t]+[\\p{Lu}][\\p{L}'’.-]{1,40}){0,3})"
    private const val VERB = "(?:said|asked|replied|answered|whispered|murmured|shouted|called|exclaimed|demanded|responded|warned)"
    private const val QUOTE = "(?:“[^”\\r\\n]{1,240}”|\"[^\"\\r\\n]{1,240}\")"
    private val nameAfterQuote = Regex("$QUOTE[ \\t]*[,!?]?[ \\t]*$NAME[ \\t]+$VERB\\b")
    private val nameBeforeQuote = Regex("(?<![\\p{L}\\p{N}])$NAME[ \\t]+$VERB[ \\t]*[:,]?[ \\t]*$QUOTE")
    private val verbBeforeName = Regex("$QUOTE[ \\t]*[,!?]?[ \\t]*$VERB[ \\t]+$NAME\\b")
    private val excludedFirstWords = setOf(
        "the", "a", "an", "someone", "anyone", "nobody", "chapter", "scene",
        "narrator", "character", "persona", "user", "voice", "dialogue"
    )
    private val genericSingleNames = setOf(
        "guard", "maid", "servant", "soldier", "stranger", "villager",
        "woman", "man", "child", "person", "someone", "narrator"
    )

    fun namedSpeakers(text: String, protectedNames: Collection<String>): List<String> {
        val protected = protectedNames.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val found = linkedSetOf<String>()
        for (pattern in listOf(nameAfterQuote, nameBeforeQuote, verbBeforeName)) {
            for (match in pattern.findAll(text)) {
                val name = match.groupValues[1].trim()
                val words = name.split(Regex("\\s+"))
                val key = name.lowercase(Locale.ROOT)
                if (name.length in 2..120 &&
                    words.first().lowercase(Locale.ROOT) !in excludedFirstWords &&
                    (words.size > 1 || key !in genericSingleNames) &&
                    key !in protected
                ) found += name
                if (found.size >= 12) return found.toList()
            }
        }
        return found.toList()
    }
}
