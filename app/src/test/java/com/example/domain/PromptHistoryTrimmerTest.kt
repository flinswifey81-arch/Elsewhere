package com.example.domain

import com.example.domain.compiler.PromptHistoryTrimmer
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import org.junit.Assert.*
import org.junit.Test

class PromptHistoryTrimmerTest {
    private fun history(n: Int) = RoleplayMessage(Role.ASSISTANT, "scene-".repeat(400), sourceMessageId = "m$n")

    @Test fun removesOldestHistoryButKeepsCurrentAndSystem() {
        val system = RoleplayMessage(Role.SYSTEM, "Never control the player.")
        val current = RoleplayMessage(Role.USER, "Current choice", sourceMessageId = "current")
        val messages = listOf(system) + (1..12).map(::history) + current
        val result = PromptHistoryTrimmer.fit(messages, 8192, protectedRecentTurns = 3)
        assertTrue(result.decision.allowed)
        assertTrue(result.removedHistoricalTurns > 0)
        assertEquals(system, result.messages.first())
        assertEquals(current, result.messages.last())
        assertTrue(result.messages.any { it.sourceMessageId == "m12" })
        assertFalse(result.messages.any { it.sourceMessageId == "m1" })
    }

    @Test fun refusesWhenProtectedSystemAloneExceedsBudget() {
        val system = RoleplayMessage(Role.SYSTEM, "canon".repeat(12_000))
        val result = PromptHistoryTrimmer.fit(listOf(system, history(1)), 8192)
        assertFalse(result.decision.allowed)
        assertEquals(system, result.messages.first())
    }

    @Test fun neverRemovesDirectorTurnsOrTheOnlyRecentTurn() {
        val director = RoleplayMessage(Role.USER, "[DIRECTOR TURN CONTROL — continue]")
        val recent = RoleplayMessage(Role.USER, "Player speaks", sourceMessageId = "recent")
        val result = PromptHistoryTrimmer.fit(listOf(history(1), recent, director), 2048, protectedRecentTurns = 1)
        assertTrue(result.messages.contains(director))
        assertTrue(result.messages.contains(recent))
    }

    @Test fun noChangeWhenWithinBudget() {
        val messages = listOf(RoleplayMessage(Role.SYSTEM, "A"), history(1))
        val result = PromptHistoryTrimmer.fit(messages, 8192)
        assertEquals(messages, result.messages)
        assertEquals(0, result.removedHistoricalTurns)
    }
}
