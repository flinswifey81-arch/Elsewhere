package com.example.domain.memory

import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity

/**
 * Compatibility gate for pre-v16 durable memories. A Wack excludes all old
 * sources unless a future explicit per-record restore is provided.
 *
 * This evaluates the complete local archive (including unselected variants),
 * never only the trimmed prompt transcript. No model or provider calls.
 */
object LegacyMemoryEligibilityFilter {
    fun eligible(
        memories: List<RoleplayMemoryEntity>,
        chatId: String,
        recipient: StoryActorRef,
        boundary: Long?,
        archivedMessages: List<MessageEntity>
    ): List<RoleplayMemoryEntity> {
        val context = StoryMemoryReadContext(
            chatId = chatId,
            branchId = null,
            wackBoundaryOrderIndex = boundary,
            recipient = recipient,
            archivedMessages = archivedMessages
        )
        return memories.filter { StoryMemoryEligibilityPolicy.evaluateLegacy(it, context).eligible }
    }
}
