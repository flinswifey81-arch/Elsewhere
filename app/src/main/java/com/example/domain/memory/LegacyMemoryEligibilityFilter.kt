package com.example.domain.memory

import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity

/**
 * Compatibility gate for pre-v16 durable memories. A Wack excludes all old
 * sources unless a future explicit per-record restore is provided.
 *
 * Callers must supply every source-linked memory's source and all variants
 * in each referenced group, including unselected variants. This can be a
 * targeted indexed database read, not the full archive; the trimmed provider
 * transcript alone is never sufficient. No model or provider calls.
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
