package com.example.domain.repository

import com.example.data.MessageDao
import com.example.data.model.MessageEntity

/**
 * Resolves only archived source messages and every variant in their relevant
 * groups. Preserve missing-source and ambiguous-primary detection without
 * copying an entire roleplay transcript into each generation.
 *
 * Both source and group predicates are scoped by chatId. Empty input performs
 * no query; chunks stay well under SQLite's supported bind-variable limits.
 */
internal class MemoryProvenanceReader(private val messageDao: MessageDao) {
    suspend fun read(
        chatId: String,
        sourceMessageIds: Collection<String>,
        variantGroupIds: Collection<String> = emptyList()
    ): List<MessageEntity> {
        val sources = sourceMessageIds.asSequence().filter(String::isNotBlank).distinct()
            .toList().chunked(400).flatMap { messageDao.getMemorySourceMessages(chatId, it) }
        val groups = (variantGroupIds.asSequence() + sources.asSequence().map { it.variantGroupId })
            .filter(String::isNotBlank).distinct().toList()
        val variants = groups.chunked(400).flatMap {
            messageDao.getMemorySourceVariants(chatId, it)
        }
        return (sources + variants).distinctBy { it.messageId }
    }
}
