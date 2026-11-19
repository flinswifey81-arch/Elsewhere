package com.example.ui.screens

import com.example.data.model.MessageEntity

/** Display selection only: never substitute this for an archival timeline. */
internal data class VisibleChatHistoryWindow(
    val messages: List<MessageEntity>,
    val groupedMessages: List<MessageGroup>,
    val hasEarlierGroups: Boolean
)

/**
 * The query includes exactly one extra, older logical group as a pagination
 * sentinel. Keep complete variant sets together, even when one group has
 * many regenerated alternatives. Rows and groups retain their story order.
 */
internal fun selectVisibleChatHistory(
    rows: List<MessageEntity>,
    visibleGroupLimit: Int
): VisibleChatHistoryWindow {
    require(visibleGroupLimit > 0) { "Visible group limit must be positive." }

    val grouped = rows.groupBy { it.variantGroupId ?: it.messageId }.mapNotNull { (_, variants) ->
        val selected = variants.find { it.isPrimaryVariant } ?: variants.firstOrNull()
        selected?.let { MessageGroup(it, variants.sortedBy { row -> row.orderIndex }) }
    }.sortedBy { it.primaryMessage.orderIndex }
    val selectedGroups = grouped.takeLast(visibleGroupLimit)
    val visibleIds = selectedGroups.flatMap { it.variants }.mapTo(HashSet()) { it.messageId }

    return VisibleChatHistoryWindow(
        messages = rows.filter { it.messageId in visibleIds },
        groupedMessages = selectedGroups,
        hasEarlierGroups = grouped.size > visibleGroupLimit
    )
}
