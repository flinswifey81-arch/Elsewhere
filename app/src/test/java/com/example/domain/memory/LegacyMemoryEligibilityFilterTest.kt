package com.example.domain.memory

import com.example.data.model.MemoryCategory
import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.SpeakerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fail-closed historical compatibility tests: no database or provider needed. */
class LegacyMemoryEligibilityFilterTest {
    private val character = StoryActorRef(StoryActorKind.CHARACTER, "rowan")
    private fun message(id: String, index: Long, text: String, primary: Boolean = true) =
        MessageEntity(
            messageId = id, chatId = "chat-a",
            speakerType = SpeakerType.PERSONA, speakerId = "mara",
            speakerDisplayNameSnapshot = "Mara", content = text,
            orderIndex = index, variantGroupId = "variant-$index",
            isPrimaryVariant = primary
        )
    private fun memory(id: String?, chat: String = "chat-a", owner: String = "rowan") =
        RoleplayMemoryEntity(
            chatId = chat, characterId = owner, category = MemoryCategory.PROMISE,
            content = "Remember the silver compass", sourceMessageId = id
        )
    private fun select(
        memories: List<RoleplayMemoryEntity>,
        history: List<MessageEntity>,
        boundary: Long? = null,
        recipient: StoryActorRef = character
    ) = LegacyMemoryEligibilityFilter.eligible(
        memories = memories, chatId = "chat-a", recipient = recipient,
        boundary = boundary, archivedMessages = history
    )

    @Test fun wackRejectsOldLegacyMemoriesIncludingUnknownOrigin() {
        val old = message("before", 17, "Promise made")
        val new = message("after", 117, "Promise renewed")
        val before = memory("before")
        val after = memory("after")
        val unknown = memory(null)
        assertEquals(listOf(after.memoryId),
            select(listOf(before, after, unknown), listOf(old, new), 100).map { it.memoryId })
        assertEquals(3, select(listOf(before, after, unknown), listOf(old, new)).size)
    }

    @Test fun rejectUnselectedVariantEditedSourceAndDeletedSource() {
        val original = message("old", 17, "The key is safe", primary = false)
        val selected = message("new", 17, "The key was destroyed")
        val selectedMemory = memory("new")
        assertTrue(select(listOf(memory("old")), listOf(original, selected)).isEmpty())
        assertEquals(1, select(listOf(selectedMemory), listOf(original, selected)).size)
        assertTrue(select(listOf(selectedMemory), listOf(original, selected.copy(editedAt = 2L))).isEmpty())
        assertTrue(select(listOf(selectedMemory), listOf(original)).isEmpty())
    }

    @Test fun neverCrossCharacterOrChatForLegacyDurableMemory() {
        val history = listOf(message("m1", 1, "Hello"))
        assertTrue(select(listOf(memory("m1", owner = "other")), history).isEmpty())
        assertTrue(select(listOf(memory("m1", chat = "chat-b")), history).isEmpty())
        assertTrue(select(listOf(memory("m1")), history,
            recipient = StoryActorRef(StoryActorKind.NARRATOR, "narrator:chat-a")).isEmpty())
    }
}
