package com.example.domain.lore

import com.example.data.model.LorebookEntryEntity
import com.example.data.model.MessageEntity

data class ActiveLoreEntry(
    val title: String,
    val currentContent: String,
    val priority: Int
)

object LorebookActivationEngine {
    fun conversationScanText(
        chatHistory: List<MessageEntity>,
        currentMessage: MessageEntity,
        recentMessageLimit: Int = 24
    ): String = buildList {
        addAll(chatHistory.filter { it.isPrimaryVariant }.takeLast(recentMessageLimit).map { it.content })
        add(currentMessage.content)
    }.joinToString("\n")

    fun keywordMatches(entry: LorebookEntryEntity, conversationalText: String): Boolean {
        val keys = (entry.keys + entry.secondaryKeys).filter(String::isNotBlank)
        if (keys.isEmpty()) return false
        val matches = keys.map { key -> containsKey(conversationalText, key, entry.caseSensitive, entry.wholeWords) }
        return when (entry.matchMode) {
            "all" -> matches.all { it }
            else -> matches.any { it }
        }
    }

    private fun containsKey(text: String, key: String, caseSensitive: Boolean, wholeWords: Boolean): Boolean {
        if (!wholeWords) return text.contains(key, ignoreCase = !caseSensitive)
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val boundaryPattern = "(?<![\\p{L}\\p{N}_])${Regex.escape(key)}(?![\\p{L}\\p{N}_])"
        return Regex(boundaryPattern, options).containsMatchIn(text)
    }
}
