package com.example.domain.memory

import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryMemoryPromptBudgetTest {
    private fun record(id: String, length: Int) = StoryMemoryRecord(
        memoryId = id, content = "compass " + "x".repeat(length),
        createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
        ownerId = "chat-a", sourceChatId = "chat-a",
        scope = StoryMemoryOwnerScope.CHAT, branchId = null,
        origin = StoryMemoryOrigin.Manual(1L), claim = StoryMemoryClaim.PROMISE,
        visibility = StoryMemoryVisibility.CHARACTER_ONLY,
        review = StoryMemoryReview.APPROVED
    )

    @Test fun smallBaseCanAddRelevantMemoryWithoutGrowingPastTheAdditiveAllowance() {
        val base = listOf(RoleplayMessage(Role.SYSTEM, "Roleplay carefully."))
        val selected = StoryMemoryPromptBudget.select(base, listOf(record("one", 80)), 8_192)
        assertEquals(listOf("one"), selected.selected.map { it.memoryId })
        assertTrue(selected.approximateInputCeilingTokens <= 8_192 - 2_048)
        assertTrue(selected.windowKnown)
    }

    @Test fun longBaselineSkipsOptionalMemoryRatherThanTruncatingAuthoredContext() {
        val base = listOf(RoleplayMessage(Role.SYSTEM, "Rules " + "a".repeat(30_000)))
        val result = StoryMemoryPromptBudget.select(base, listOf(record("secret", 100)), 8_192)
        assertTrue(result.selected.isEmpty())
        assertEquals(1, result.omittedForBudget)
        assertTrue(result.approximateBaselineTokens > result.approximateInputCeilingTokens)
    }

    @Test fun unknownModelUsesFallbackAndCharCapWithoutNewCatalogCalls() {
        val base = listOf(RoleplayMessage(Role.USER, "Where is the compass?"))
        val many = (1..10).map { record("m-" + it, 600) }
        val result = StoryMemoryPromptBudget.select(base, many, null)
        assertFalse(result.windowKnown)
        assertTrue(result.selected.sumOf { it.content.length } <= 4_000)
        assertEquals(many.size, result.selected.size + result.omittedForBudget)
    }

    @Test fun explicitCeilingAndImageReserveReduceMemoryAllowance() {
        val base = listOf(RoleplayMessage(Role.SYSTEM, "World story " + "a".repeat(6_000)))
        val memory = record("one", 200)
        val roomy = StoryMemoryPromptBudget.select(base, listOf(memory), 128_000)
        val tight = StoryMemoryPromptBudget.select(base, listOf(memory), 128_000,
            configuredInputCeilingTokens = 1_024)
        assertTrue(roomy.selected.isNotEmpty())
        assertTrue(tight.selected.isEmpty())
    }
}
