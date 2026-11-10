package com.example.domain.memory

import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.SpeakerType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Stage 1 eligibility CONTRACT, not a Room entity and not yet wired into generation.
 *
 * A source can exist in the local archive without being eligible to appear in a prompt.
 * Gate provenance, selected variant, Wack, branch, ownership and knowledge BEFORE
 * ranking or allocating token budget. No provider calls or global/shared-World writes.
 */
enum class StoryMemoryOwnerScope { CHAT, CAMPAIGN, WORLD }
enum class StoryActorKind { CHARACTER, PERSONA, NPC, SCENARIO_NPC, WORLD_CAST, NARRATOR }
data class StoryActorRef(val kind: StoryActorKind, val stableId: String)

enum class StoryMemoryClaim { FACT, EVENT, PROMISE, DISCOVERY, RELATIONSHIP, BELIEF, RUMOR, SECRET, CONTRADICTION, UNRESOLVED_THREAD }
enum class StoryMemoryVisibility { PUBLIC, STORYTELLER_ONLY, PERSONA_ONLY, CHARACTER_ONLY, NAMED_ACTORS }
enum class StoryMemoryReview { APPROVED, PENDING_REVIEW, REJECTED }
enum class StoryMemoryLifecycle { ACTIVE, RESOLVED, SUPERSEDED }

/** A source refers to the exact message and variant, not just its display name. */
sealed interface StoryMemoryOrigin {
    data class Message(
        val messageId: String,
        val variantGroupId: String,
        val orderIndex: Long,
        val speakerType: SpeakerType,
        val speakerId: String,
        /** Optional content digest to detect source edits without storing source text twice. */
        val sourceSha256: String? = null
    ) : StoryMemoryOrigin

    /** Created through a trusted user action, not inferred from a generated response. */
    data class Manual(val createdAtOrderIndex: Long) : StoryMemoryOrigin
}

/**
 * Future structured story-memory record. Only CHAT records are eligible in this stage.
 * 'actors' is stable-ID metadata, not an implied knowledge grant.
 * 'knownBy' is explicit knowledge ownership, independent of actors mentioned in the fact.
 */
data class StoryMemoryRecord(
    val memoryId: String,
    val ownerId: String,
    val sourceChatId: String,
    val scope: StoryMemoryOwnerScope,
    val branchId: String?,
    val origin: StoryMemoryOrigin,
    val claim: StoryMemoryClaim,
    val visibility: StoryMemoryVisibility,
    val knownBy: Set<StoryActorRef> = emptySet(),
    val believedBy: StoryActorRef? = null,
    val actors: Set<StoryActorRef> = emptySet(),
    val review: StoryMemoryReview = StoryMemoryReview.PENDING_REVIEW,
    val lifecycle: StoryMemoryLifecycle = StoryMemoryLifecycle.ACTIVE,
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val userEditedAt: Long? = null
)

/**
 * A user-requested return of ONE memory for ONE Wack boundary.
 * Merely editing the memory, pinning it, or having an older grant is NOT consent.
 * A future persistence layer must record the initiating user action durably.
 */
data class ExplicitMemoryReturn(
    val memoryId: String,
    val chatId: String,
    val resetBoundaryOrderIndex: Long,
    val approvedAtOrderIndex: Long,
    val branchId: String?,
    val initiatedByUser: Boolean
)

data class StoryMemoryReadContext(
    val chatId: String,
    val branchId: String?,
    val wackBoundaryOrderIndex: Long?,
    val recipient: StoryActorRef,
    /** All local source records, including unselected and pre-Wack messages; never sent wholesale. */
    val archivedMessages: List<MessageEntity>,
    val explicitReturns: Set<ExplicitMemoryReturn> = emptySet()
)

enum class StoryMemoryExclusion {
    ELIGIBLE,
    OTHER_STORY_OR_SCOPE,
    OTHER_BRANCH,
    NOT_APPROVED,
    HIDDEN,
    SUPERSEDED,
    INVALID_KNOWLEDGE_GRANT,
    NOT_KNOWN_TO_RECIPIENT,
    SOURCE_MISSING,
    SOURCE_NOT_SELECTED,
    AMBIGUOUS_SELECTED_VARIANTS,
    SOURCE_CHANGED,
    WACK_BOUNDARY,
    LEGACY_ORIGIN_UNKNOWN,
    LEGACY_BRANCH_UNKNOWN
}

data class StoryMemoryDecision(val reason: StoryMemoryExclusion) {
    val eligible: Boolean get() = reason == StoryMemoryExclusion.ELIGIBLE
}

object StoryMemoryEligibilityPolicy {
    private fun deny(reason: StoryMemoryExclusion) = StoryMemoryDecision(reason)
    private val allow = StoryMemoryDecision(StoryMemoryExclusion.ELIGIBLE)

    /** Decision for future source-linked records; no unreviewed proposals enter context. */
    fun evaluate(record: StoryMemoryRecord, context: StoryMemoryReadContext): StoryMemoryDecision {
        if (record.scope != StoryMemoryOwnerScope.CHAT ||
            record.ownerId != context.chatId || record.sourceChatId != context.chatId
        ) return deny(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE)
        if (record.branchId != context.branchId) return deny(StoryMemoryExclusion.OTHER_BRANCH)
        if (record.review != StoryMemoryReview.APPROVED) return deny(StoryMemoryExclusion.NOT_APPROVED)
        if (record.hidden) return deny(StoryMemoryExclusion.HIDDEN)
        if (record.lifecycle == StoryMemoryLifecycle.SUPERSEDED) {
            return deny(StoryMemoryExclusion.SUPERSEDED)
        }
        val knowledge = knowledgeAllowed(record, context.recipient)
        if (knowledge != StoryMemoryExclusion.ELIGIBLE) return deny(knowledge)
        val orderIndex = when (val origin = record.origin) {
            is StoryMemoryOrigin.Manual -> origin.createdAtOrderIndex
            is StoryMemoryOrigin.Message -> {
                val check = checkSource(
                    context.archivedMessages,
                    context.chatId,
                    origin.messageId,
                    origin.variantGroupId,
                    origin.orderIndex,
                    origin.speakerType,
                    origin.speakerId,
                    origin.sourceSha256
                )
                if (check != StoryMemoryExclusion.ELIGIBLE) return deny(check)
                origin.orderIndex
            }
        }
        return if (passedWack(record.memoryId, orderIndex, context)) allow
            else deny(StoryMemoryExclusion.WACK_BOUNDARY)
    }

    /**
     * Transitional read policy for the EXISTING Room memory shape, which lacks
     * branch/visibility and may lack a source ID. This is NOT yet called from
     * ChatDetailViewModel; doing so changes production recall behavior and needs
     * migration/backup and generation regression approval.
     */
    fun evaluateLegacy(
        memory: RoleplayMemoryEntity,
        context: StoryMemoryReadContext
    ): StoryMemoryDecision {
        if (memory.chatId != context.chatId) return deny(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE)
        // Legacy memory is speaker-scoped (characterId or "narrator:chatId"),
        // never treated as general PUBLIC knowledge.
        val owns = when (context.recipient.kind) {
            StoryActorKind.CHARACTER -> memory.characterId == context.recipient.stableId
            StoryActorKind.NARRATOR -> memory.characterId == "narrator:${context.chatId}" &&
                context.recipient.stableId == "narrator:${context.chatId}"
            else -> false
        }
        if (!owns) return deny(StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT)
        // Existing records have no durable branch ID; do not project them into
        // a named/future campaign branch on the basis of display names.
        if (context.branchId != null) return deny(StoryMemoryExclusion.LEGACY_BRANCH_UNKNOWN)
        val sourceId = memory.sourceMessageId
        if (sourceId == null) {
            val boundary = context.wackBoundaryOrderIndex
            if (boundary == null) return allow
            return if (hasExplicitReturn(memory.memoryId, context)) allow
                else deny(StoryMemoryExclusion.LEGACY_ORIGIN_UNKNOWN)
        }
        val source = context.archivedMessages.firstOrNull { it.messageId == sourceId }
            ?: return deny(StoryMemoryExclusion.SOURCE_MISSING)
        val check = checkSource(
            context.archivedMessages, context.chatId,
            sourceId, source.variantGroupId, source.orderIndex,
            source.speakerType, source.speakerId, sourceSha256 = null
        )
        if (check != StoryMemoryExclusion.ELIGIBLE) return deny(check)
        // A legacy record does not know whether it was captured before an edit.
        if (source.editedAt != null) return deny(StoryMemoryExclusion.SOURCE_CHANGED)
        return if (passedWack(memory.memoryId, source.orderIndex, context)) allow
            else deny(StoryMemoryExclusion.WACK_BOUNDARY)
    }

    private fun knowledgeAllowed(
        record: StoryMemoryRecord,
        recipient: StoryActorRef
    ): StoryMemoryExclusion {
        // Source participants or actors mentioned in text are NOT proof of access.
        val visibilityValid = when (record.visibility) {
            StoryMemoryVisibility.PUBLIC -> true
            StoryMemoryVisibility.STORYTELLER_ONLY -> recipient.kind == StoryActorKind.NARRATOR
            StoryMemoryVisibility.PERSONA_ONLY -> recipient.kind == StoryActorKind.PERSONA &&
                recipient in record.knownBy
            StoryMemoryVisibility.CHARACTER_ONLY -> recipient.kind == StoryActorKind.CHARACTER &&
                recipient in record.knownBy
            StoryMemoryVisibility.NAMED_ACTORS -> recipient in record.knownBy
        }
        // Secret + PUBLIC would leak immediately. A Character's belief is not
        // established truth for other speakers, even in a shared conversation.
        if (record.claim == StoryMemoryClaim.SECRET &&
            record.visibility == StoryMemoryVisibility.PUBLIC
        ) return StoryMemoryExclusion.INVALID_KNOWLEDGE_GRANT
        if (record.claim == StoryMemoryClaim.BELIEF && record.believedBy == null) {
            return StoryMemoryExclusion.INVALID_KNOWLEDGE_GRANT
        }
        if (record.claim == StoryMemoryClaim.CONTRADICTION &&
            record.visibility != StoryMemoryVisibility.STORYTELLER_ONLY
        ) return StoryMemoryExclusion.INVALID_KNOWLEDGE_GRANT
        if (!visibilityValid) return StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT
        if (record.claim == StoryMemoryClaim.BELIEF &&
            recipient.kind != StoryActorKind.NARRATOR &&
            record.believedBy != recipient
        ) return StoryMemoryExclusion.NOT_KNOWN_TO_RECIPIENT
        return StoryMemoryExclusion.ELIGIBLE
    }

    private fun checkSource(
        messages: List<MessageEntity>,
        chatId: String,
        messageId: String,
        variantGroupId: String,
        orderIndex: Long,
        speakerType: SpeakerType,
        speakerId: String,
        sourceSha256: String?
    ): StoryMemoryExclusion {
        val source = messages.firstOrNull { it.messageId == messageId }
            ?: return StoryMemoryExclusion.SOURCE_MISSING
        if (source.chatId != chatId || source.variantGroupId != variantGroupId ||
            source.orderIndex != orderIndex || source.speakerType != speakerType ||
            source.speakerId != speakerId
        ) return StoryMemoryExclusion.SOURCE_CHANGED
        val selected = messages.filter {
            it.chatId == chatId && it.variantGroupId == variantGroupId && it.isPrimaryVariant
        }
        if (selected.size > 1) return StoryMemoryExclusion.AMBIGUOUS_SELECTED_VARIANTS
        if (!source.isPrimaryVariant || selected.singleOrNull()?.messageId != messageId) {
            return StoryMemoryExclusion.SOURCE_NOT_SELECTED
        }
        if (sourceSha256 != null && sourceSha256 != sha256(source.content)) {
            return StoryMemoryExclusion.SOURCE_CHANGED
        }
        if (sourceSha256 == null && source.editedAt != null) {
            return StoryMemoryExclusion.SOURCE_CHANGED
        }
        return StoryMemoryExclusion.ELIGIBLE
    }

    private fun passedWack(
        memoryId: String,
        sourceOrderIndex: Long,
        context: StoryMemoryReadContext
    ): Boolean {
        val boundary = context.wackBoundaryOrderIndex ?: return true
        return sourceOrderIndex > boundary || hasExplicitReturn(memoryId, context)
    }

    private fun hasExplicitReturn(
        memoryId: String,
        context: StoryMemoryReadContext
    ): Boolean {
        val boundary = context.wackBoundaryOrderIndex ?: return false
        return context.explicitReturns.any {
            it.initiatedByUser &&
                it.memoryId == memoryId &&
                it.chatId == context.chatId &&
                it.branchId == context.branchId &&
                it.resetBoundaryOrderIndex == boundary &&
                it.approvedAtOrderIndex > boundary
        }
    }

    /** Hashed locally; digest is provenance metadata, never a substitute for consent. */
    fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
