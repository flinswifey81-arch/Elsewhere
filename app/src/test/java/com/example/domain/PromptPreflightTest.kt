package com.example.domain

import com.example.domain.compiler.PromptPreflight
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import org.junit.Assert.*
import org.junit.Test

class PromptPreflightTest {
    @Test fun shortPromptFitsKnownWindow() {
        val result = PromptPreflight.inspect(listOf(RoleplayMessage(Role.USER, "Hello")), 8192)
        assertTrue(result.allowed)
        assertTrue(result.modelWindowKnown)
        assertEquals(6144, result.inputCeilingTokens)
    }

    @Test fun baselineTooLargeFailsWithoutProvider() {
        val result = PromptPreflight.inspect(listOf(RoleplayMessage(Role.SYSTEM, "x".repeat(30_000))), 8192)
        assertFalse(result.allowed)
        assertTrue(result.estimatedInputTokens > result.inputCeilingTokens)
    }

    @Test fun unknownWindowFailsClosedToFallbackBudget() {
        val result = PromptPreflight.inspect(listOf(RoleplayMessage(Role.SYSTEM, "x".repeat(40_000))), null)
        assertFalse(result.allowed)
        assertFalse(result.modelWindowKnown)
        assertEquals(12_000, result.inputCeilingTokens)
    }

    @Test fun imagesAreIncludedInEstimate() {
        val textOnly = PromptPreflight.inspect(listOf(RoleplayMessage(Role.USER, "Hi")), 8192)
        assertTrue(textOnly.estimatedInputTokens > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCeilingRejected() {
        PromptPreflight.inspect(emptyList(), 8192, configuredInputCeilingTokens = 0)
    }
}
