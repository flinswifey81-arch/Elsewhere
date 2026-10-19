package com.example.domain

import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.LorebookEntryEntity
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.lore.LorebookActivationEngine
import com.example.domain.memory.ContextResetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextResetPolicyTest {
    @Test
    fun generationAndInspectorWindowExcludePreResetMessagesEvenAfterEditOrVariantSwitch() {
        val editedPreResetVariant = message(
            id = "old-variant",
            content = "moon gate edited after reset",
            orderIndex = 5L,
            primary = true
        )
        val nonPrimaryPostResetVariant = message(
            id = "post-alternate",
            content = "alternate",
            orderIndex = 11L,
            primary = false
        )
        val postResetCurrent = message(
            id = "post-current",
            content = "A quiet road continues.",
            orderIndex = 12L,
            primary = true
        )

        val eligible = ContextResetPolicy.eligiblePrimaryMessages(
            listOf(editedPreResetVariant, nonPrimaryPostResetVariant, postResetCurrent),
            boundary = 10L
        )

        assertEquals(listOf("post-current"), eligible.map { it.messageId })
        assertFalse(ContextResetPolicy.includes(editedPreResetVariant, 10L))
    }

    @Test
    fun oldKeywordCannotReactivateUntilItAppearsInPostResetText() {
        val keywordEntry = LorebookEntryEntity(
            entryId = "moon-gate",
            lorebookId = "lorebook-1",
            title = "Moon Gate",
            keys = listOf("moon gate")
        )
        val preReset = message("old", "The moon gate opens.", 5L)
        val neutralPostReset = message("new-neutral", "The road is quiet.", 11L)
        val eligibleNeutral = ContextResetPolicy.eligiblePrimaryMessages(
            listOf(preReset, neutralPostReset),
            boundary = 10L
        )
        val neutralScan = LorebookActivationEngine.conversationScanText(
            eligibleNeutral.dropLast(1),
            eligibleNeutral.last()
        )
        assertFalse(LorebookActivationEngine.keywordMatches(keywordEntry, neutralScan))

        val reactivated = neutralPostReset.copy(
            messageId = "new-keyword",
            content = "The moon gate opens again.",
            orderIndex = 12L
        )
        val reactivatedScan = LorebookActivationEngine.conversationScanText(emptyList(), reactivated)
        assertTrue(LorebookActivationEngine.keywordMatches(keywordEntry, reactivatedScan))
    }

    @Test
    fun rollingSummaryMustAdvancePastBoundaryBeforeInspectorOrGenerationCanUseIt() {
        val old = ConversationSummaryEntity("chat-1", "Old summary", 10L)
        val fresh = ConversationSummaryEntity("chat-1", "Fresh summary", 11L)

        assertNull(ContextResetPolicy.eligibleSummary(old, boundary = 10L))
        assertEquals(fresh, ContextResetPolicy.eligibleSummary(fresh, boundary = 10L))
        assertEquals(old, ContextResetPolicy.eligibleSummary(old, boundary = null))
    }

    private fun message(
        id: String,
        content: String,
        orderIndex: Long,
        primary: Boolean = true
    ) = MessageEntity(
        messageId = id,
        chatId = "chat-1",
        speakerType = SpeakerType.PERSONA,
        speakerId = "persona-1",
        speakerDisplayNameSnapshot = "Mara",
        content = content,
        orderIndex = orderIndex,
        isPrimaryVariant = primary
    )
}
