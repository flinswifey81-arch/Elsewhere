package com.example.domain

import com.example.domain.memory.*
import org.junit.Assert.*
import org.junit.Test

class StoryMemoryTemporalRetrievalTest {
    private val recipient = StoryActorRef(StoryActorKind.CHARACTER, "actor-a")
    private val context = StoryMemoryReadContext(
        chatId = "chat-a",
        branchId = null,
        wackBoundaryOrderIndex = null,
        recipient = recipient,
        archivedMessages = emptyList()
    )

    private fun record(
        id: String,
        text: String,
        order: Long,
        lifecycle: StoryMemoryLifecycle = StoryMemoryLifecycle.ACTIVE,
        claim: StoryMemoryClaim = StoryMemoryClaim.RELATIONSHIP
    ) = StoryMemoryRecord(
        memoryId = id,
        content = text,
        createdAtEpochMillis = order,
        updatedAtEpochMillis = order,
        ownerId = "chat-a",
        sourceChatId = "chat-a",
        scope = StoryMemoryOwnerScope.CHAT,
        branchId = null,
        origin = StoryMemoryOrigin.Manual(order),
        claim = claim,
        visibility = StoryMemoryVisibility.NAMED_ACTORS,
        knownBy = setOf(recipient),
        review = StoryMemoryReview.APPROVED,
        lifecycle = lifecycle
    )

    @Test fun chosenRelationshipEventsArePresentedInChronologicalOrder() {
        val conflict = record("conflict", "Ari and Bea argued about the letter", 1)
        val reconciliation = record("reconciliation", "Ari and Bea reconciled about the letter", 9)
        val result = StoryMemoryLocalRetriever.search(
            listOf(reconciliation, conflict),
            StoryMemorySearchRequest(context, "Ari Bea letter", maxMemories = 2)
        )
        assertEquals(listOf("conflict", "reconciliation"), result.selected.map { it.memoryId })
    }

    @Test fun supersededEventsDoNotReenterEvenWhenRelevant() {
        val obsolete = record("obsolete", "Ari Bea letter", 1, StoryMemoryLifecycle.SUPERSEDED)
        val current = record("current", "Ari Bea letter", 2)
        val result = StoryMemoryLocalRetriever.search(
            listOf(obsolete, current),
            StoryMemorySearchRequest(context, "Ari Bea letter")
        )
        assertEquals(listOf("current"), result.selected.map { it.memoryId })
        assertEquals(1, result.excludedCounts[StoryMemoryExclusion.SUPERSEDED])
    }

    @Test fun resolvedPromisesRemainHistoricalAndAreNotAutomaticallyErased() {
        val resolved = record("fulfilled", "Ari returned the letter", 3,
            StoryMemoryLifecycle.RESOLVED, StoryMemoryClaim.PROMISE)
        val result = StoryMemoryLocalRetriever.search(
            listOf(resolved), StoryMemorySearchRequest(context, "returned letter")
        )
        assertEquals(listOf("fulfilled"), result.selected.map { it.memoryId })
    }

    @Test fun unapprovedSecretsNeverReachRanking() {
        val secret = record("pending", "Ari hid the letter", 4).copy(
            review = StoryMemoryReview.PENDING_REVIEW
        )
        val result = StoryMemoryLocalRetriever.search(
            listOf(secret), StoryMemorySearchRequest(context, "Ari letter")
        )
        assertTrue(result.selected.isEmpty())
        assertEquals(1, result.excludedCounts[StoryMemoryExclusion.NOT_APPROVED])
    }
}
