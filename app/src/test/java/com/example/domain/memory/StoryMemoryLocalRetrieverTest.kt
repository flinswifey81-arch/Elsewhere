package com.example.domain.memory

import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline Stage 2A contracts: no Room mutation, provider traffic, or prompt injection. */
class StoryMemoryLocalRetrieverTest {
    private val rowan = StoryActorRef(StoryActorKind.CHARACTER, "rowan")
    private val mara = StoryActorRef(StoryActorKind.PERSONA, "mara")
    private val narrator = StoryActorRef(StoryActorKind.NARRATOR, "narrator:chat-a")

    private fun context(
        recipient: StoryActorRef = rowan,
        boundary: Long? = null,
        branch: String? = null,
        messages: List<MessageEntity> = emptyList(),
        returns: Set<ExplicitMemoryReturn> = emptySet()
    ) = StoryMemoryReadContext(
        chatId = "chat-a", branchId = branch,
        wackBoundaryOrderIndex = boundary, recipient = recipient,
        archivedMessages = messages, explicitReturns = returns
    )

    private fun manual(
        id: String,
        text: String,
        order: Long = 10L,
        claim: StoryMemoryClaim = StoryMemoryClaim.FACT,
        visibility: StoryMemoryVisibility = StoryMemoryVisibility.CHARACTER_ONLY,
        knownBy: Set<StoryActorRef> = setOf(rowan),
        pinned: Boolean = false
    ) = StoryMemoryRecord(
        memoryId = id, content = text,
        createdAtEpochMillis = order, updatedAtEpochMillis = order,
        ownerId = "chat-a", sourceChatId = "chat-a",
        scope = StoryMemoryOwnerScope.CHAT, branchId = null,
        origin = StoryMemoryOrigin.Manual(order),
        claim = claim, visibility = visibility, knownBy = knownBy,
        review = StoryMemoryReview.APPROVED, pinned = pinned
    )

    private fun search(
        memories: List<StoryMemoryRecord>,
        text: String = "Where is the compass?",
        ctx: StoryMemoryReadContext = context(),
        active: Set<StoryActorRef> = emptySet(),
        limit: Int = 8,
        chars: Int = 4_000
    ) = StoryMemoryLocalRetriever.search(
        memories, StoryMemorySearchRequest(ctx, text, active, limit, chars)
    )

    @Test fun selectedRelevantFactsAreDeterministicAndCaseInsensitive() {
        val compass = manual("a", "The BRASS Compass is in the library.", order = 10)
        val other = manual("b", "The lighthouse is empty.", order = 20)
        val later = manual("c", "Rowan repaired the compass.", order = 30)
        val input = listOf(compass, other, later)
        assertEquals(listOf("c", "a"), search(input, "COMPASS").selected.map { it.memoryId })
        assertEquals(listOf("c", "a"), search(input.reversed(), "compass").selected.map { it.memoryId })
        assertEquals(3, search(input, "compass").eligibleCount)
    }

    @Test fun ineligibleMemoriesAreRejectedBeforeRankingEvenWhenPinned() {
        val approved = manual("approved", "The compass is safe.")
        val pending = manual("pending", "The compass is stolen.", pinned = true)
            .copy(review = StoryMemoryReview.PENDING_REVIEW)
        val hidden = manual("hidden", "The compass is cursed.", pinned = true)
            .copy(hidden = true)
        val wrongStory = manual("other", "The compass belongs to another chat.", pinned = true)
            .copy(ownerId = "chat-b")
        val wrongBranch = manual("branch", "The compass is a mirage.", pinned = true)
            .copy(branchId = "branch-2")
        val superseded = manual("old", "The compass was broken.", pinned = true)
            .copy(lifecycle = StoryMemoryLifecycle.SUPERSEDED)
        val result = search(listOf(approved, pending, hidden, wrongStory, wrongBranch, superseded))
        assertEquals(listOf("approved"), result.selected.map { it.memoryId })
        assertEquals(1, result.eligibleCount)
        assertEquals(1, result.excludedCounts[StoryMemoryExclusion.NOT_APPROVED])
        assertEquals(1, result.excludedCounts[StoryMemoryExclusion.OTHER_STORY_OR_SCOPE])
        assertEquals(1, result.excludedCounts[StoryMemoryExclusion.OTHER_BRANCH])
        assertEquals(1, result.excludedCounts[StoryMemoryExclusion.HIDDEN])
        assertEquals(1, result.excludedCounts[StoryMemoryExclusion.SUPERSEDED])
    }

    @Test fun storytellerOnlySecretNeverBecomesCharacterKnowledge() {
        val secret = manual("secret", "The compass is secretly under the throne.",
            claim = StoryMemoryClaim.SECRET,
            visibility = StoryMemoryVisibility.STORYTELLER_ONLY,
            knownBy = emptySet(), pinned = true)
        assertTrue(search(listOf(secret), "compass", context(rowan)).selected.isEmpty())
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT,
            search(listOf(secret), "compass", context(rowan)).excludedCounts.keys.single())
        assertEquals(listOf("secret"),
            search(listOf(secret), "compass", context(narrator)).selected.map { it.memoryId })
        assertTrue(search(listOf(secret), "compass", context(mara)).selected.isEmpty())
    }

    @Test fun activeActorsImproveRankingWithoutGrantingKnowledge() {
        val relevant = manual("a", "The old tower bell rang.", order = 10)
            .copy(actors = setOf(rowan))
        val mentionedButSecret = manual("b", "The tower key is hidden.", order = 11,
            visibility = StoryMemoryVisibility.STORYTELLER_ONLY, knownBy = emptySet())
            .copy(actors = setOf(rowan), pinned = true)
        val result = search(listOf(relevant, mentionedButSecret),
            text = "tower", active = setOf(rowan))
        assertEquals(listOf("a"), result.selected.map { it.memoryId })
    }

    @Test fun wackRequiresExactUserGrantAndNewBoundaryInvalidatesIt() {
        val memory = manual("old", "The compass was buried.", order = 20, pinned = true)
        val grant = ExplicitMemoryReturn(
            "old", "chat-a", 100L, 101L, null, initiatedByUser = true
        )
        assertTrue(search(listOf(memory), ctx = context(boundary = 100L)).selected.isEmpty())
        assertEquals(listOf("old"), search(listOf(memory),
            ctx = context(boundary = 100L, returns = setOf(grant))).selected.map { it.memoryId })
        assertTrue(search(listOf(memory),
            ctx = context(boundary = 101L, returns = setOf(grant))).selected.isEmpty())
        assertTrue(search(listOf(memory),
            ctx = context(boundary = 100L, returns = setOf(grant.copy(initiatedByUser = false)))).selected.isEmpty())
    }

    @Test fun editedOrDeselectedMessageCannotBeRetrievedEvenWhenPinned() {
        val selected = MessageEntity(
            messageId = "m1", chatId = "chat-a",
            speakerType = SpeakerType.CHARACTER, speakerId = "rowan",
            speakerDisplayNameSnapshot = "Rowan", content = "The compass is beneath the altar.",
            orderIndex = 35L, variantGroupId = "fork", isPrimaryVariant = true
        )
        val unselected = selected.copy(messageId = "m2", content = "The compass was stolen.",
            isPrimaryVariant = false)
        val memory = manual("source", selected.content, pinned = true).copy(
            origin = StoryMemoryOrigin.Message(
                selected.messageId, selected.variantGroupId,
                selected.orderIndex, selected.speakerType,
                selected.speakerId, selected.createdAt,
                StoryMemoryEligibilityPolicy.sha256(selected.content)
            )
        )
        val archive = listOf(selected, unselected)
        assertEquals(listOf("source"),
            search(listOf(memory), ctx = context(messages = archive)).selected.map { it.memoryId })
        val edited = selected.copy(content = "The compass is gone.", editedAt = 2L)
        assertTrue(search(listOf(memory), ctx = context(messages = listOf(edited, unselected))).selected.isEmpty())
        val changedSelection = listOf(selected.copy(isPrimaryVariant = false),
            unselected.copy(isPrimaryVariant = true))
        assertTrue(search(listOf(memory), ctx = context(messages = changedSelection)).selected.isEmpty())
        assertTrue(search(listOf(memory), ctx = context(messages = emptyList())).selected.isEmpty())
    }

    @Test fun smallTextBudgetSkipsOversizedMemoryWithoutTruncation() {
        val big = manual("long", "compass " + "x".repeat(120), pinned = true)
        val small = manual("short", "compass safe", order = 30)
        val result = search(listOf(big, small), text = "compass", chars = 20)
        assertEquals(listOf("short"), result.selected.map { it.memoryId })
        assertEquals(1, result.skippedForTextLimit)
        assertEquals("compass safe", result.selected.single().content)
    }

    @Test fun unresolvedPromiseAndPinnedFactStayAvailableWithoutLiteralSearchMatch() {
        val promise = manual("promise", "Rowan promised to find Mara.", claim = StoryMemoryClaim.PROMISE)
        val pinned = manual("pin", "The family oath must be remembered.", pinned = true)
        val irrelevant = manual("idle", "A tree has orange leaves.")
        val result = search(listOf(promise, pinned, irrelevant), text = "campfire")
        assertEquals(listOf("pin", "promise"), result.selected.map { it.memoryId })
        assertFalse(result.selected.any { it.memoryId == "idle" })
    }

    @Test fun invalidLimitsAreRejectedInsteadOfSilentlyExpandingContext() {
        val memory = manual("a", "compass")
        assertTrue(runCatching { search(listOf(memory), limit = -1) }.isFailure)
        assertTrue(runCatching { search(listOf(memory), chars = -1) }.isFailure)
        assertTrue(search(listOf(memory), limit = 0).selected.isEmpty())
        assertTrue(search(listOf(memory), chars = 0).selected.isEmpty())
    }
}
