package com.example.domain.memory

import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.MessageEntity

object ContextResetPolicy {
    fun includes(message: MessageEntity, boundary: Long?): Boolean =
        boundary == null || message.orderIndex > boundary

    fun eligiblePrimaryMessages(
        messages: List<MessageEntity>,
        boundary: Long?
    ): List<MessageEntity> = messages.filter { message ->
        message.isPrimaryVariant && includes(message, boundary)
    }

    fun eligibleSummary(
        summary: ConversationSummaryEntity?,
        boundary: Long?
    ): ConversationSummaryEntity? = summary?.takeIf { persisted ->
        boundary == null || persisted.summarizedThroughOrderIndex > boundary
    }
}
