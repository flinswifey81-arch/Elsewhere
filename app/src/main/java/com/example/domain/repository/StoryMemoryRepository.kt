package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.StoryMemoryActorEntity
import com.example.data.model.StoryMemoryEntity
import com.example.data.model.StoryMemoryReturnEntity
import com.example.domain.memory.*
import com.example.domain.story.StoryResponseCodec
import com.example.domain.npc.NpcStructuredOutputParser
import kotlinx.coroutines.flow.Flow

/**
 * A directed, chat-local relationship statement between two verified actors.
 * Participants are stable typed IDs, not inferred from display names.
 * Mentioning someone does not imply that they know the relationship fact.
 */
data class StoryRelationshipEntry(
    val memory: StoryMemoryEntity,
    val from: StoryActorRef,
    val to: StoryActorRef
)

/**
 * Local user-controlled story memory. Kept out of generation until a separate
 * retrieval/assembler gate is reviewed. All edits take the shared maintenance
 * lock used by backup/restore; Room changes are atomic.
 */
class StoryMemoryRepository(
    private val database: AppDatabase,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    private val dao get() = database.storyMemoryDao()

    fun observe(chatId: String): Flow<List<StoryMemoryEntity>> = dao.observeForChat(chatId)

    suspend fun get(chatId: String, memoryId: String): StoryMemoryEntity? =
        dao.get(memoryId)?.takeIf { it.chatId == chatId }

    suspend fun saveManual(
        chatId: String,
        text: String,
        claim: StoryMemoryClaim = StoryMemoryClaim.FACT
    ): StoryMemoryEntity = dataCoordinator.withMutation {
        require(text.trim().isNotEmpty()) { "Write something to remember." }
        require(text.length <= 12_000) { "A story memory must fit within 12,000 characters." }
        database.withTransaction {
            val chat = requireNotNull(database.chatDao().getChatById(chatId)) { "Chat no longer exists." }
            val latest = database.chatDao().getLatestMessageOrderIndex(chatId) ?: -1L
            val ordinal = maxOf(latest, chat.contextResetOrderIndex ?: -1L) + 1L
            // STORYTELLER_ONLY never grants a Character omniscient knowledge.
            StoryMemoryEntity(
                chatId = chatId, ownerId = chatId,
                content = text.trim(), claim = claim.name,
                visibility = StoryMemoryVisibility.STORYTELLER_ONLY.name,
                review = StoryMemoryReview.APPROVED.name,
                originKind = "MANUAL", sourceOrderIndex = ordinal
            ).also { dao.insert(it) }
        }
    }

    /**
     * Player-authored relationship change. Existing relationship facts stay
     * in the same story-memory archive; superseding an earlier active entry
     * is an explicit action and never discards its old text or provenance.
     *
     * No inference, paid extraction, shared-World write or automatic knowledge
     * grant is performed. Two typed actor links distinguish directed dynamics
     * from ordinary memories that happen to mention a Character's name.
     */
    suspend fun saveRelationshipChange(
        chatId: String,
        from: StoryActorRef,
        to: StoryActorRef,
        text: String,
        supersedeMemoryId: String? = null
    ): StoryRelationshipEntry = dataCoordinator.withMutation {
        val cleaned = text.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 12_000) {
            "Relationship state must contain 1–12,000 characters."
        }
        require(from != to) { "A relationship needs two different people." }
        require(from.kind in setOf(StoryActorKind.CHARACTER, StoryActorKind.PERSONA) &&
            to.kind in setOf(StoryActorKind.CHARACTER, StoryActorKind.PERSONA)) {
            "Use a Character or Persona participant from this chat."
        }
        database.withTransaction {
            val summary = requireNotNull(database.chatDao().getChatSummaryById(chatId)) {
                "Chat no longer exists."
            }
            val validCharacters = summary.characters.map { it.characterId }.toSet()
            val validPersonas = summary.personas.map { it.personaId }.toSet()
            fun validateParticipant(actor: StoryActorRef) {
                val exists = when (actor.kind) {
                    StoryActorKind.CHARACTER -> actor.stableId in validCharacters
                    StoryActorKind.PERSONA -> actor.stableId in validPersonas
                    else -> false
                }
                require(exists) { "A relationship participant does not belong to this chat." }
            }
            validateParticipant(from)
            validateParticipant(to)

            val prior = supersedeMemoryId?.let { id ->
                val old = requireNotNull(get(chatId, id)) {
                    "The earlier relationship state no longer exists in this chat."
                }
                require(old.claim == StoryMemoryClaim.RELATIONSHIP.name &&
                    old.lifecycle == StoryMemoryLifecycle.ACTIVE.name) {
                    "Only an active relationship state can be superseded."
                }
                val previousPair = relationshipPair(dao.actors(id))
                require(previousPair == Pair(from, to)) {
                    "The earlier relationship has different participants or direction."
                }
                old
            }

            val latestMessage = database.chatDao().getLatestMessageOrderIndex(chatId) ?: -1L
            val latestMemory = dao.maxSourceOrderForChat(chatId) ?: -1L
            val ordinal = maxOf(
                latestMessage, latestMemory,
                summary.chat.contextResetOrderIndex ?: -1L
            )
            require(ordinal < Long.MAX_VALUE) { "Relationship chronology is exhausted." }
            val now = System.currentTimeMillis()
            val entry = StoryMemoryEntity(
                chatId = chatId,
                ownerId = chatId,
                content = cleaned,
                claim = StoryMemoryClaim.RELATIONSHIP.name,
                review = StoryMemoryReview.APPROVED.name,
                visibility = StoryMemoryVisibility.STORYTELLER_ONLY.name,
                originKind = "MANUAL",
                sourceOrderIndex = ordinal + 1L,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
            dao.insert(entry)
            dao.insertActor(StoryMemoryActorEntity(
                memoryId = entry.memoryId,
                actorKind = from.kind.name,
                actorId = from.stableId,
                relation = "RELATIONSHIP_FROM"
            ))
            dao.insertActor(StoryMemoryActorEntity(
                memoryId = entry.memoryId,
                actorKind = to.kind.name,
                actorId = to.stableId,
                relation = "RELATIONSHIP_TO"
            ))
            // An old memory's narrative history remains available, but its
            // superseded content must not compete with the new active state
            // for retrieval—even if it used to be pinned or Character-known.
            prior?.let {
                dao.update(it.copy(
                    lifecycle = StoryMemoryLifecycle.SUPERSEDED.name,
                    updatedAtEpochMillis = now
                ))
            }
            StoryRelationshipEntry(entry, from, to)
        }
    }

    /** Inspect chronological relationship history in this chat only. */
    suspend fun getRelationshipHistory(chatId: String): List<StoryRelationshipEntry> =
        database.withTransaction {
            requireNotNull(database.chatDao().getChatById(chatId)) {
                "Chat no longer exists."
            }
            val entries = dao.forChat(chatId)
                .filter { it.claim == StoryMemoryClaim.RELATIONSHIP.name }
            val actors = dao.actorsForChat(chatId).groupBy { it.memoryId }
            entries.mapNotNull { memory ->
                val pair = relationshipPair(actors[memory.memoryId].orEmpty())
                pair?.let { StoryRelationshipEntry(memory, it.first, it.second) }
            }.sortedWith(
                compareBy<StoryRelationshipEntry> { it.memory.sourceOrderIndex }
                    .thenBy { it.memory.createdAtEpochMillis }
                    .thenBy { it.memory.memoryId }
            )
        }

    private fun relationshipPair(
        actors: List<StoryMemoryActorEntity>
    ): Pair<StoryActorRef, StoryActorRef>? {
        fun unique(relation: String): StoryActorRef? {
            val matches = actors.filter { it.relation == relation }
            if (matches.size != 1) return null
            val row = matches.single()
            val kind = runCatching { StoryActorKind.valueOf(row.actorKind) }.getOrNull()
                ?: return null
            if (kind !in setOf(StoryActorKind.CHARACTER, StoryActorKind.PERSONA)) return null
            return StoryActorRef(kind, row.actorId)
        }
        val from = unique("RELATIONSHIP_FROM") ?: return null
        val to = unique("RELATIONSHIP_TO") ?: return null
        return if (from == to) null else Pair(from, to)
    }

    /** A saved message keeps exact variant/speaker fingerprint and order. */
    suspend fun saveFromMessage(chatId: String, messageId: String): StoryMemoryEntity =
        dataCoordinator.withMutation {
            database.withTransaction {
                requireNotNull(database.chatDao().getChatById(chatId)) { "Chat no longer exists." }
                val message = requireNotNull(database.messageDao().getMessageById(messageId))
                    { "Source message was deleted." }
                require(message.chatId == chatId) { "Message belongs to another story." }
                val variants = database.messageDao().getVariantsOnce(message.variantGroupId)
                require(variants.count { it.isPrimaryVariant } == 1 && message.isPrimaryVariant) {
                    "Only the selected message variant may be saved."
                }
                // Ensemble messages are stored as a structured envelope; save a
                // readable story excerpt while fingerprinting the actual source
                // bytes so future edits/branch changes remain detectable.
                val visibleSource = NpcStructuredOutputParser.finalVisibleStoryText(message.content)
                val content = (StoryResponseCodec.decode(visibleSource)
                    ?.let(StoryResponseCodec::readableTranscript)
                    ?: visibleSource).trim()
                require(content.isNotBlank() && content.length <= 12_000) {
                    "Message is empty or too long to save as one memory."
                }
                StoryMemoryEntity(
                    chatId = chatId, ownerId = chatId,
                    content = content, claim = StoryMemoryClaim.EVENT.name,
                    visibility = StoryMemoryVisibility.STORYTELLER_ONLY.name,
                    review = StoryMemoryReview.APPROVED.name,
                    originKind = "MESSAGE", sourceMessageId = message.messageId,
                    sourceVariantGroupId = message.variantGroupId,
                    sourceOrderIndex = message.orderIndex,
                    sourceSpeakerType = message.speakerType.name,
                    sourceSpeakerId = message.speakerId,
                    sourceCreatedAtEpochMillis = message.createdAt,
                    sourceSha256 = StoryMemoryEligibilityPolicy.sha256(message.content)
                ).also { dao.insert(it) }
            }
        }

    /** Update only user-managed fields. Provenance stays immutable. */
    suspend fun update(
        chatId: String, memoryId: String,
        text: String? = null, pinned: Boolean? = null, hidden: Boolean? = null,
        lifecycle: StoryMemoryLifecycle? = null
    ): StoryMemoryEntity = dataCoordinator.withMutation {
        database.withTransaction {
            val old = requireNotNull(get(chatId, memoryId)) { "Memory no longer exists in this chat." }
            val cleaned = text?.trim()
            if (cleaned != null) require(cleaned.isNotBlank() && cleaned.length <= 12_000) {
                "A memory must contain 1–12,000 characters."
            }
            val now = System.currentTimeMillis()
            old.copy(
                content = cleaned ?: old.content,
                pinned = pinned ?: old.pinned,
                hidden = hidden ?: old.hidden,
                lifecycle = lifecycle?.name ?: old.lifecycle,
                userEditedAt = if (cleaned != null) now else old.userEditedAt,
                updatedAtEpochMillis = now
            ).also { dao.update(it) }
        }
    }

    /** Explicit knowledge assignments are restricted to Character participants. */
    suspend fun setCharacterKnowledge(
        chatId: String, memoryId: String, characterIds: Set<String>
    ): StoryMemoryEntity = dataCoordinator.withMutation {
        database.withTransaction {
            val old = requireNotNull(get(chatId, memoryId)) { "Memory does not belong to this chat." }
            val participants = database.chatDao().getParticipantsForChatOnce(chatId)
                .map { it.characterId }.toSet()
            require(characterIds.all { it in participants }) {
                "A knowledge recipient must be a Character participating in this chat."
            }
            require(characterIds.isEmpty() || old.claim != StoryMemoryClaim.CONTRADICTION.name) {
                "Contradictions remain storyteller-only until resolved."
            }
            if (old.claim == StoryMemoryClaim.BELIEF.name) {
                require(characterIds.size <= 1) { "A belief may belong to at most one Character." }
            }
            dao.clearKnowledgeForMemory(memoryId)
            characterIds.sorted().forEach { characterId ->
                dao.insertActor(StoryMemoryActorEntity(
                    memoryId = memoryId,
                    actorKind = StoryActorKind.CHARACTER.name,
                    actorId = characterId, relation = "KNOWS"
                ))
            }
            val believer = characterIds.singleOrNull()
                ?.takeIf { old.claim == StoryMemoryClaim.BELIEF.name }
            old.copy(
                visibility = if (characterIds.isEmpty()) StoryMemoryVisibility.STORYTELLER_ONLY.name
                    else StoryMemoryVisibility.CHARACTER_ONLY.name,
                // Revoking the Character's access must not erase historically
                // attributed belief ownership from storyteller continuity.
                believedByKind = if (believer == null) old.believedByKind else StoryActorKind.CHARACTER.name,
                believedById = believer ?: old.believedById,
                updatedAtEpochMillis = System.currentTimeMillis()
            ).also { dao.update(it) }
        }
    }

    suspend fun getCharacterKnowers(chatId: String, memoryId: String): Set<String> {
        requireNotNull(get(chatId, memoryId)) { "Memory does not belong to this chat." }
        return dao.actors(memoryId).filter {
            it.relation == "KNOWS" && it.actorKind == StoryActorKind.CHARACTER.name
        }.map { it.actorId }.toSet()
    }

    suspend fun delete(chatId: String, memoryId: String) = dataCoordinator.withMutation {
        database.withTransaction {
            get(chatId, memoryId)?.let { dao.delete(it) }
        }
    }

    suspend fun getReturns(chatId: String, memoryId: String): List<StoryMemoryReturnEntity> {
        requireNotNull(get(chatId, memoryId)) { "Memory does not belong to this chat." }
        return dao.returns(memoryId)
    }

    /**
     * The user explicitly restores this exact record for the current Wack.
     * No source-variant/identity/secret-policy bypass. The grant is stored
     * atomically and is invalid after a new Wack boundary.
     */
    suspend fun restoreAcrossWack(chatId: String, memoryId: String): StoryMemoryReturnEntity =
        dataCoordinator.withMutation {
            database.withTransaction {
                val chat = requireNotNull(database.chatDao().getChatById(chatId)) {
                    "Chat no longer exists."
                }
                val boundary = requireNotNull(chat.contextResetOrderIndex) {
                    "There is no active Wack boundary."
                }
                val record = requireNotNull(get(chatId, memoryId)) {
                    "Memory does not belong to this chat."
                }
                require(record.sourceOrderIndex <= boundary) {
                    "This memory was created after the current Wack."
                }
                // Character-only memories can be restored without leaking that
                // private knowledge to the narrator or to another Character.
                val recipients = listOf(StoryActorRef(StoryActorKind.NARRATOR, "narrator:$chatId")) +
                    dao.actors(memoryId).filter { it.relation == "KNOWS" }.mapNotNull { row ->
                        runCatching {
                            StoryActorRef(StoryActorKind.valueOf(row.actorKind), row.actorId)
                        }.getOrNull()
                    }
                val decisions = recipients.map { recipient ->
                    eligibility(record, chatId, boundary = null, recipient = recipient)
                }
                require(decisions.any { it.eligible }) {
                    "This memory is not currently restorable: ${decisions.firstOrNull()?.reason}"
                }
                StoryMemoryReturnEntity(
                    memoryId = record.memoryId, resetBoundaryOrderIndex = boundary,
                    chatId = chatId, branchId = null,
                    approvedAtOrderIndex = maxOf(
                        (database.chatDao().getLatestMessageOrderIndex(chatId) ?: boundary) + 1L,
                        boundary + 1L
                    ),
                    initiatedByUser = true
                ).also { dao.insertReturn(it) }
            }
        }

    /** One local Room snapshot, one recipient, and no paid provider calls. */
    suspend fun retrieveForGeneration(
        chatId: String,
        recipient: StoryActorRef,
        sceneText: String,
        activeActors: Set<StoryActorRef> = emptySet(),
        maxMemories: Int = 8,
        maxSelectedTextCharacters: Int = 4_000
    ): StoryMemorySearchResult = database.withTransaction {
        val chat = requireNotNull(database.chatDao().getChatById(chatId)) {
            "Chat no longer exists."
        }
        val returns = dao.returnsForChat(chatId).map(::toExplicitReturn).toSet()
        // Corrupt or unknown historical values fail closed; do not project
        // an unrecognizable audience or source into a provider prompt.
        val actorsByMemory = dao.actorsForChat(chatId).groupBy { it.memoryId }
        val candidates = dao.forChat(chatId).mapNotNull { memory ->
            try {
                recordFor(memory, actorsByMemory[memory.memoryId].orEmpty())
            } catch (_: IllegalArgumentException) { null }
        }
        val archived = MemoryProvenanceReader(database.messageDao()).read(
            chatId = chatId,
            sourceMessageIds = candidates.mapNotNull {
                (it.origin as? StoryMemoryOrigin.Message)?.messageId
            },
            variantGroupIds = candidates.mapNotNull {
                (it.origin as? StoryMemoryOrigin.Message)?.variantGroupId
            }
        )
        StoryMemoryLocalRetriever.search(
            candidates = candidates,
            request = StoryMemorySearchRequest(
                readContext = StoryMemoryReadContext(
                    chatId = chatId, branchId = null,
                    wackBoundaryOrderIndex = chat.contextResetOrderIndex,
                    recipient = recipient, archivedMessages = archived,
                    explicitReturns = returns
                ),
                sceneText = sceneText, activeActors = activeActors,
                maxMemories = maxMemories,
                maxSelectedTextCharacters = maxSelectedTextCharacters
            )
        )
    }

    suspend fun eligibility(
        memory: StoryMemoryEntity,
        chatId: String,
        boundary: Long?,
        recipient: StoryActorRef
    ): StoryMemoryDecision {
        if (memory.chatId != chatId) return StoryMemoryDecision(StoryMemoryExclusion.OTHER_STORY_OR_SCOPE)
        val record = recordFor(memory, dao.actors(memory.memoryId))
        return StoryMemoryEligibilityPolicy.evaluate(record, StoryMemoryReadContext(
            chatId = chatId, branchId = null, wackBoundaryOrderIndex = boundary,
            recipient = recipient,
            archivedMessages = MemoryProvenanceReader(database.messageDao()).read(
                chatId,
                listOfNotNull(memory.sourceMessageId),
                listOfNotNull(memory.sourceVariantGroupId)
            ),
            explicitReturns = dao.returns(memory.memoryId).map(::toExplicitReturn).toSet()
        ))
    }

    private fun toExplicitReturn(row: StoryMemoryReturnEntity): ExplicitMemoryReturn =
        ExplicitMemoryReturn(
            row.memoryId, row.chatId, row.resetBoundaryOrderIndex,
            row.approvedAtOrderIndex, row.branchId, row.initiatedByUser
        )

    private fun recordFor(
        memory: StoryMemoryEntity, actors: List<StoryMemoryActorEntity>
    ): StoryMemoryRecord {
        fun actor(kind: String, id: String): StoryActorRef? = runCatching {
            StoryActorRef(StoryActorKind.valueOf(kind), id)
        }.getOrNull()
        val knownBy = actors.filter { it.relation == "KNOWS" }
            .mapNotNull { actor(it.actorKind, it.actorId) }.toSet()
        val mentioned = actors.filter {
            it.relation in setOf("MENTIONED", "RELATIONSHIP_FROM", "RELATIONSHIP_TO")
        }.mapNotNull { actor(it.actorKind, it.actorId) }.toSet()
        return StoryMemoryRecord(
            memoryId = memory.memoryId,
            content = memory.content,
            createdAtEpochMillis = memory.createdAtEpochMillis,
            updatedAtEpochMillis = memory.updatedAtEpochMillis,
            ownerId = memory.ownerId, sourceChatId = memory.chatId,
            scope = StoryMemoryOwnerScope.valueOf(memory.scope), branchId = memory.branchId,
            origin = if (memory.originKind == "MESSAGE") {
                StoryMemoryOrigin.Message(
                    messageId = requireNotNull(memory.sourceMessageId),
                    variantGroupId = requireNotNull(memory.sourceVariantGroupId),
                    orderIndex = memory.sourceOrderIndex,
                    speakerType = com.example.data.model.SpeakerType.valueOf(requireNotNull(memory.sourceSpeakerType)),
                    speakerId = requireNotNull(memory.sourceSpeakerId),
                    sourceCreatedAtEpochMillis = requireNotNull(memory.sourceCreatedAtEpochMillis),
                    sourceSha256 = memory.sourceSha256
                )
            } else {
                require(memory.originKind == "MANUAL") { "Unsupported memory provenance" }
                StoryMemoryOrigin.Manual(memory.sourceOrderIndex)
            },
            claim = StoryMemoryClaim.valueOf(memory.claim),
            visibility = StoryMemoryVisibility.valueOf(memory.visibility),
            knownBy = knownBy,
            believedBy = if (memory.believedByKind != null && memory.believedById != null)
                actor(memory.believedByKind, memory.believedById) else null,
            actors = mentioned,
            review = StoryMemoryReview.valueOf(memory.review),
            lifecycle = StoryMemoryLifecycle.valueOf(memory.lifecycle),
            hidden = memory.hidden, pinned = memory.pinned,
            userEditedAt = memory.userEditedAt
        )
    }
}
