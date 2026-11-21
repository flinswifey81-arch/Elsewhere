package com.example.domain.compiler

import com.example.data.model.MessageEntity

/**
 * Builds the recent selected-variant window without allocating a copy of the
 * complete transcript. Scans backward until enough primary turns are found,
 * then restores narrative order. No source mutation or filtering bypass.
 */
object RecentPrimaryMessageWindow {
    fun take(history: List<MessageEntity>, limit: Int): List<MessageEntity> {
        require(limit >= 0) { "Recent message limit must be non-negative." }
        if (limit == 0) return emptyList()
        val result = ArrayList<MessageEntity>(minOf(limit, history.size))
        for (index in history.indices.reversed()) {
            val message = history[index]
            if (!message.isPrimaryVariant) continue
            result.add(message)
            if (result.size == limit) break
        }
        result.reverse()
        return result
    }
}
