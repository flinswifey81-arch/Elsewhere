package com.example.domain.memory

import com.example.data.model.MemoryCategory
import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.SpeakerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for a FUTURE reader. They deliberately never call OpenRouter,
 * write Room, change the active message selection, or modify any old memories.
 */
class StoryMemoryEligibilityPolicyTest {
    private val rowan = StoryActorRef(StoryActorKind.CHARACTER, "char-rowan")
    private val twin = StoryActorRef(StoryActorKind.CHARACTER, "char-rowan-twin")
    private val mara = StoryActorRef(StoryActorKind.PERSONA, "persona-mara")
    private val narrator = StoryActorRef(StoryActorKind.NARRATOR, "narrator:chat-a")
    private val worldNpc = StoryActorRef(StoryActorKind.NPC, "npc-mara")

    private fun message(
        id: String = "selected",
        group: String = "fork-100",
        order: Long = 100L,
        primary: Boolean = true,
        chatId: String = "chat-a",
        content: String = "Rowan promised to return the compass.",
        speakerId: String = "char-rowan",
        speakerType: SpeakerType = SpeakerType.CHARACTER
    ) = MessageEntity(
        messageId = id, chatId = chatId, speakerType = speakerType,
        speakerId = speakerId, speakerDisplayNameSnapshot = "Rowan",
        content = content, orderIndex = order, variantGroupId = group,
        isPrimaryVariant = primary
    )

    private val selected = message()
    private val unselected = message(id = "discarded", primary = false, content = "Rowan broke the compass.")
    private fun origin(msg: MessageEntity = selected, digest: String? = StoryMemoryEligibilityPolicy.sha256(msg.content)) =
        StoryMemoryOrigin.Message(
            msg.messageId, msg.variantGroupId, msg.orderIndex,
            msg.speakerType, msg.speakerId, msg.createdAt, digest
        )

    private fun record(
        id: String = "memory-a",
        source: StoryMemoryOrigin = origin(),
        ownerId: String = "chat-a",
        sourceChatId: String = "chat-a",
        scope: StoryMemoryOwnerScope = StoryMemoryOwnerScope.CHAT,
        branchId: String? = null,
        claim: StoryMemoryClaim = StoryMemoryClaim.PROMISE,
        visibility: StoryMemoryVisibility = StoryMemoryVisibility.CHARACTER_ONLY,
        knownBy: Set<StoryActorRef> = setOf(rowan),
        believedBy: StoryActorRef? = null,
        actors: Set<StoryActorRef> = setOf(rowan, mara),
        review: StoryMemoryReview = StoryMemoryReview.APPROVED,
        lifecycle: StoryMemoryLifecycle = StoryMemoryLifecycle.ACTIVE,
        hidden: Boolean = false,
        pinned: Boolean = false
    ) = StoryMemoryRecord(
        memoryId = id,
        content = "Rowan promised to return the compass.",
        createdAtEpochMillis = 1_000L, updatedAtEpochMillis = 1_000L,
        ownerId = ownerId, sourceChatId = sourceChatId,
        scope = scope, branchId = branchId, origin = source,
        claim = claim, visibility = visibility, knownBy = knownBy,
        believedBy = believedBy, actors = actors,
        review = review, lifecycle = lifecycle, hidden = hidden, pinned = pinned
    )

    private fun context(
        chatId: String = "chat-a",
        branchId: String? = null,
        boundary: Long? = null,
        recipient: StoryActorRef = rowan,
        archive: List<MessageEntity> = listOf(selected, unselected),
        returns: Set<ExplicitMemoryReturn> = emptySet()
    ) = StoryMemoryReadContext(
        chatId, branchId, boundary, recipient, archive, returns
    )

    private fun reason(memory: StoryMemoryRecord, request: StoryMemoryReadContext = context()) =
        StoryMemoryEligibilityPolicy.evaluate(memory, request).reason

    private fun reasonLegacy(
        sourceMessageId: String? = "selected",
        recipient: StoryActorRef = rowan,
        chatId: String = "chat-a",
        branchId: String? = null,
        boundary: Long? = null,
        archive: List<MessageEntity> = listOf(selected, unselected),
        returns: Set<ExplicitMemoryReturn> = emptySet(),
        ownerCharacter: String = "char-rowan"
    ): StoryMemoryExclusion = StoryMemoryEligibilityPolicy.evaluateLegacy(
        RoleplayMemoryEntity(
            memoryId = "legacy-1", chatId = chatId,
            characterId = ownerCharacter, category = MemoryCategory.DETAIL,
            content = "The brass key belongs to Rowan.", sourceMessageId = sourceMessageId
        ), context(branchId = branchId, boundary = boundary,
            recipient = recipient, archive = archive, returns = returns)
    ).reason

    private fun grant(
        id: String = "memory-a", boundary: Long = 110L,
        approved: Long = 130L, byUser: Boolean = true,
        branch: String? = null, chatId: String = "chat-a"
    ) = ExplicitMemoryReturn(id, chatId, boundary, approved, branch, byUser)

    @Test fun selectedSourceAndApprovedScopedRecipientAreEligible() {
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(record()))
    }

    @Test fun emptyRecordContentIsRejectedBeforeAnyRetrieval() {
        assertEquals(StoryMemoryExclusion.EMPTY_MEMORY,
            reason(record().copy(content = "")))
    }

    @Test fun sourceCreationTimestampMustMatchCapturedProvenance() {
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED,
            reason(record(source = origin().copy(sourceCreatedAtEpochMillis = 0L))))
    }

    @Test fun pinnedMemoryDoesNotBypassPendingReviewOrHiddenStatus() {
        assertEquals(StoryMemoryExclusion.NOT_APPROVED, reason(record(
            pinned = true, review = StoryMemoryReview.PENDING_REVIEW)))
        assertEquals(StoryMemoryExclusion.NOT_APPROVED, reason(record(
            pinned = true, review = StoryMemoryReview.REJECTED)))
        assertEquals(StoryMemoryExclusion.HIDDEN, reason(record(pinned = true, hidden = true)))
    }

    @Test fun resolvedPromiseCanBeReadButSupersededStateIsNotCurrentTruth() {
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(record(lifecycle = StoryMemoryLifecycle.RESOLVED)))
        assertEquals(StoryMemoryExclusion.SUPERSEDED, reason(record(lifecycle = StoryMemoryLifecycle.SUPERSEDED)))
    }

    @Test fun otherChatAndUnimplementedCampaignOrWorldScopeAreDenied() {
        assertEquals(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE, reason(record(ownerId = "chat-b")))
        assertEquals(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE, reason(record(sourceChatId = "chat-b")))
        assertEquals(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE, reason(record(scope = StoryMemoryOwnerScope.CAMPAIGN)))
        assertEquals(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE, reason(record(scope = StoryMemoryOwnerScope.WORLD)))
    }

    @Test fun branchIsAnExactStoryIdentityNotAnInheritedName() {
        assertEquals(StoryMemoryExclusion.OTHER_BRANCH, reason(record(branchId = "fork-north")))
        assertEquals(StoryMemoryExclusion.OTHER_BRANCH, reason(record(), context(branchId = "fork-north")))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(
            record(branchId = "fork-north"), context(branchId = "fork-north")))
    }

    @Test fun wrongMessageVariantCannotEnterEvenWhenTheMemoryIsPinned() {
        assertEquals(StoryMemoryExclusion.SOURCE_NOT_SELECTED,
            reason(record(source = origin(unselected), pinned = true)))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(
            record(source = origin(unselected)),
            context(archive = listOf(selected.copy(isPrimaryVariant = false), unselected.copy(isPrimaryVariant = true)))
        ))
    }

    @Test fun ambiguousSelectedVariantsAreDeniedRatherThanLeakingBothBranches() {
        assertEquals(StoryMemoryExclusion.AMBIGUOUS_SELECTED_VARIANTS,
            reason(record(), context(archive = listOf(selected, unselected.copy(isPrimaryVariant = true)))))
    }

    @Test fun missingSourceCannotBeSilentlyRecreatedFromMemoryText() {
        assertEquals(StoryMemoryExclusion.SOURCE_MISSING,
            reason(record(), context(archive = listOf(unselected))))
    }

    @Test fun mismatchedSourceChronologySpeakerOrChatIsRejected() {
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED,
            reason(record(source = origin().copy(orderIndex = 99L))))
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED,
            reason(record(source = origin().copy(speakerId = "somebody-else"))))
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED,
            reason(record(), context(archive = listOf(selected.copy(chatId = "chat-b")))))
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED,
            reason(record(source = origin().copy(variantGroupId = "other-group"))))
    }

    @Test fun editingSourceContentInvalidatesTheCapturedDigest() {
        val edited = selected.copy(content = "Different promise", editedAt = 150L)
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED,
            reason(record(), context(archive = listOf(edited))))
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED, reason(
            record(source = origin(digest = null)),
            context(archive = listOf(edited))
        ))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(record(
            source = origin(edited, StoryMemoryEligibilityPolicy.sha256(edited.content))),
            context(archive = listOf(edited))
        ))
    }

    @Test fun wackExcludesPreBoundarySourceEvenWhenPinned() {
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            reason(record(pinned = true), context(boundary = 110L)))
        assertEquals(StoryMemoryExclusion.ELIGIBLE,
            reason(record(), context(boundary = 90L)))
    }

    @Test fun manualMemoryCreatedAfterWackIsEligibleButOldManualMemoryIsNot() {
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(
            record(source = StoryMemoryOrigin.Manual(120L)), context(boundary = 110L)))
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY, reason(
            record(source = StoryMemoryOrigin.Manual(30L)), context(boundary = 110L)))
    }

    @Test fun explicitUserReturnIsBoundToExactMemoryAndCurrentWackBoundary() {
        val returns = setOf(grant())
        assertEquals(StoryMemoryExclusion.ELIGIBLE,
            reason(record(), context(boundary = 110L, returns = returns)))
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            reason(record(id = "other"), context(boundary = 110L, returns = returns)))
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            reason(record(), context(boundary = 120L, returns = returns)))
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            reason(record(), context(boundary = 110L, returns = setOf(grant(approved = 110L)))))
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            reason(record(), context(boundary = 110L, returns = setOf(grant(byUser = false)))))
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            reason(record(), context(boundary = 110L, returns = setOf(grant(chatId = "chat-b")))))
    }

    @Test fun reintroductionDoesNotOverrideSourceVariantOrVisibility() {
        assertEquals(StoryMemoryExclusion.SOURCE_NOT_SELECTED,
            reason(record(source = origin(unselected)), context(boundary = 110L, returns = setOf(grant()))))
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT,
            reason(record(), context(boundary = 110L, recipient = twin, returns = setOf(grant()))))
    }

    @Test fun charactersWithSameDisplayNameDoNotSharePrivateKnowledge() {
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT,
            reason(record(actors = setOf(rowan, twin)), context(recipient = twin)))
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT,
            reason(record(actors = setOf(worldNpc, rowan)), context(recipient = worldNpc)))
    }

    @Test fun aSecretCannotBePublicEvenIfTheTextMentionsTheRecipient() {
        assertEquals(StoryMemoryExclusion.INVALID_KNOWLEDGE_GRANT, reason(record(
            claim = StoryMemoryClaim.SECRET, visibility = StoryMemoryVisibility.PUBLIC
        )))
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT, reason(record(
            claim = StoryMemoryClaim.SECRET, visibility = StoryMemoryVisibility.STORYTELLER_ONLY,
            knownBy = emptySet()
        )))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(record(
            claim = StoryMemoryClaim.SECRET, visibility = StoryMemoryVisibility.STORYTELLER_ONLY,
            knownBy = emptySet()
        ), context(recipient = narrator)))
    }

    @Test fun narratorIsNotAutomaticallyACharacterOrPersonaKnowledgeRecipient() {
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT,
            reason(record(), context(recipient = narrator)))
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT,
            reason(record(), context(recipient = mara)))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(record(
            visibility = StoryMemoryVisibility.PERSONA_ONLY, knownBy = setOf(mara)
        ), context(recipient = mara)))
    }

    @Test fun beliefRequiresNamedBelieverAndDoesNotBecomeOtherCharactersTruth() {
        assertEquals(StoryMemoryExclusion.INVALID_KNOWLEDGE_GRANT,
            reason(record(claim = StoryMemoryClaim.BELIEF)))
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT, reason(record(
            claim = StoryMemoryClaim.BELIEF, believedBy = twin, knownBy = setOf(rowan, twin)
        )))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(record(
            claim = StoryMemoryClaim.BELIEF, believedBy = twin, knownBy = setOf(rowan, twin)
        ), context(recipient = twin)))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(record(
            claim = StoryMemoryClaim.BELIEF, believedBy = twin,
            visibility = StoryMemoryVisibility.STORYTELLER_ONLY, knownBy = emptySet()
        ), context(recipient = narrator)))
    }

    @Test fun contradictionCannotBeSharedAsAnUnqualifiedCharacterFact() {
        assertEquals(StoryMemoryExclusion.INVALID_KNOWLEDGE_GRANT, reason(record(
            claim = StoryMemoryClaim.CONTRADICTION
        )))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reason(record(
            claim = StoryMemoryClaim.CONTRADICTION,
            visibility = StoryMemoryVisibility.STORYTELLER_ONLY,
            knownBy = emptySet()
        ), context(recipient = narrator)))
    }

    @Test fun legacySpeakerScopedMemoryWithoutWackRemainsReadable() {
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reasonLegacy())
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT, reasonLegacy(recipient = twin))
        assertEquals(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE, reasonLegacy(chatId = "chat-b"))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reasonLegacy(sourceMessageId = null))
    }

    @Test fun legacyNullSourceIsNotSilentlyBroughtAcrossWack() {
        assertEquals(StoryMemoryExclusion.LEGACY_ORIGIN_UNKNOWN, reasonLegacy(
            sourceMessageId = null, boundary = 110L
        ))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reasonLegacy(
            sourceMessageId = null, boundary = 110L, returns = setOf(grant(id = "legacy-1"))
        ))
    }

    @Test fun legacySourcefulMemoryObeysSelectedVariantsAndWack() {
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY, reasonLegacy(boundary = 110L))
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reasonLegacy(
            boundary = 110L, returns = setOf(grant(id = "legacy-1"))
        ))
        assertEquals(StoryMemoryExclusion.SOURCE_NOT_SELECTED, reasonLegacy(
            sourceMessageId = "discarded"
        ))
        assertEquals(StoryMemoryExclusion.SOURCE_MISSING, reasonLegacy(
            sourceMessageId = "deleted"
        ))
    }

    @Test fun legacyMemoriesWithNoBranchProvenanceCannotEnterNamedBranch() {
        assertEquals(StoryMemoryExclusion.LEGACY_BRANCH_UNKNOWN, reasonLegacy(
            branchId = "campaign-branch-a"
        ))
    }

    @Test fun legacyEditedSourcesAreExcludedUntilReconciled() {
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED, reasonLegacy(
            archive = listOf(selected.copy(editedAt = 105L))
        ))
    }

    @Test fun storytellerLegacyMemoryDoesNotGiveCharactersGlobalSecrets() {
        assertEquals(StoryMemoryExclusion.ELIGIBLE, reasonLegacy(
            recipient = narrator, ownerCharacter = "narrator:chat-a"
        ))
        assertEquals(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT, reasonLegacy(
            recipient = rowan, ownerCharacter = "narrator:chat-a"
        ))
    }
}
