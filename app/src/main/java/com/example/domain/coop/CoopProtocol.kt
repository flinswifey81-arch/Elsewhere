package com.example.domain.coop

import com.example.data.model.CoopEventEntity
import com.example.data.model.CoopParticipantEntity
import com.example.data.model.CoopPersonaOwnershipEntity
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import com.squareup.moshi.JsonClass
import java.security.MessageDigest

object CoopProtocol {
    const val VERSION = 4
    const val DEFAULT_PORT = 42424
    const val MAX_FRAME_BYTES = 1_000_000
    const val MAX_EVENT_PAGE = 8
    const val MAX_EVENT_JSON_BYTES = 180_000
    const val MAX_SNAPSHOT_JSON_BYTES = 430_000
    const val MAX_RETAINED_EVENTS = 10_000L
    const val MAX_PARTICIPANTS = 16
    const val MAX_PERSONAS = 32
    const val MAX_CHARACTERS = 32
    const val MAX_MESSAGE_CHARS = 64_000
    const val MAX_ID_CHARS = 128
    const val MAX_PARTICIPANT_NAME_CHARS = 100
    const val MAX_DISPLAY_NAME_CHARS = 200
    const val MAX_DEVICE_ID_CHARS = 128
    const val MAX_PUBLIC_KEY_CHARS = 512
    const val MAX_SIGNATURE_CHARS = 512
    const val GENERATION_APPROVAL_TIMEOUT_MS = 120_000L
    const val GENERATION_APPROVED_START_TIMEOUT_MS = 120_000L
    const val GENERATION_EXECUTION_TIMEOUT_MS = 15 * 60_000L

    fun generationResultClientEventId(executionId: String): String =
        "generation-result:${stableDigest(executionId)}"

    fun admissionClientEventId(
        kind: String,
        subjectId: String,
        joinClientMessageId: String
    ): String = "join-$kind:${stableDigest("$kind:$subjectId:$joinClientMessageId")}"

    private fun stableDigest(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return digest
    }
}

object CoopWireKind {
    const val HELLO = "HELLO"
    const val JOIN_ACCEPTED = "JOIN_ACCEPTED"
    const val JOIN_REJECTED = "JOIN_REJECTED"
    const val PROPOSE_EVENT = "PROPOSE_EVENT"
    const val PROPOSAL_REJECTED = "PROPOSAL_REJECTED"
    const val ORDERED_EVENT = "ORDERED_EVENT"
    const val RESYNC_REQUEST = "RESYNC_REQUEST"
    const val SNAPSHOT = "SNAPSHOT"
    const val PING = "PING"
    const val PONG = "PONG"
}

object CoopGenerationResultCode {
    const val PAYER_DECLINED = "PAYER_DECLINED"
    const val APPROVAL_TIMEOUT = "PAYER_APPROVAL_TIMEOUT"
    const val EXECUTION_NOT_STARTED = "PAYER_EXECUTION_NOT_STARTED"
    const val EXECUTION_TIMEOUT = "PAYER_EXECUTION_TIMEOUT"
    const val PAYER_DISCONNECTED = "PAYER_DISCONNECTED"
    const val CONTEXT_MISMATCH = "CONTEXT_MISMATCH"
    const val GENERATION_FAILED = "GENERATION_FAILED"
    val all = setOf(
        PAYER_DECLINED,
        APPROVAL_TIMEOUT,
        EXECUTION_NOT_STARTED,
        EXECUTION_TIMEOUT,
        PAYER_DISCONNECTED,
        CONTEXT_MISMATCH,
        GENERATION_FAILED
    )
}

@JsonClass(generateAdapter = true)
data class CoopWireEnvelope(
    val protocolVersion: Int = CoopProtocol.VERSION,
    val kind: String,
    val roomId: String,
    val senderParticipantId: String,
    val clientMessageId: String,
    val targetParticipantId: String? = null,
    val payloadJson: String = ""
)

@JsonClass(generateAdapter = true)
data class CoopHelloPayload(
    val participantId: String,
    val deviceId: String,
    val participantDisplayName: String,
    val personaId: String,
    val personaDisplayName: String,
    val lastAppliedSequence: Long
)

@JsonClass(generateAdapter = true)
data class CoopInviteBundle(
    val bundleVersion: Int = 1,
    val protocolVersion: Int = CoopProtocol.VERSION,
    val hostAddress: String,
    val port: Int,
    val roomId: String,
    val hostParticipantId: String,
    val inviteSecret: String,
    val hostPublicKey: String,
    val hostKeyFingerprint: String
)

@JsonClass(generateAdapter = true)
data class CoopProposedEventPayload(
    val eventType: String,
    val payloadJson: String,
    val originSignature: String
)

@JsonClass(generateAdapter = true)
data class CoopProposalRejectionPayload(
    val clientEventId: String,
    val code: String
)

@JsonClass(generateAdapter = true)
data class CoopEventDto(
    val eventId: String,
    val roomId: String,
    val sequence: Long,
    val clientEventId: String,
    val originParticipantId: String,
    val eventType: String,
    val payloadJson: String,
    val originPayloadJson: String,
    val originSignature: String,
    val createdAt: Long
) {
    fun toEntity() = CoopEventEntity(
        eventId = eventId,
        roomId = roomId,
        sequence = sequence,
        clientEventId = clientEventId,
        originParticipantId = originParticipantId,
        eventType = eventType,
        payloadJson = payloadJson,
        originPayloadJson = originPayloadJson,
        originSignature = originSignature,
        createdAt = createdAt
    )

    companion object {
        fun from(entity: CoopEventEntity) = CoopEventDto(
            eventId = entity.eventId,
            roomId = entity.roomId,
            sequence = entity.sequence,
            clientEventId = entity.clientEventId,
            originParticipantId = entity.originParticipantId,
            eventType = entity.eventType,
            payloadJson = entity.payloadJson,
            originPayloadJson = entity.originPayloadJson,
            originSignature = entity.originSignature,
            createdAt = entity.createdAt
        )
    }
}

@JsonClass(generateAdapter = true)
data class CoopParticipantDto(
    val participantId: String,
    val roomId: String,
    val deviceId: String,
    val identityPublicKey: String,
    val displayName: String,
    val isHost: Boolean,
    val isConnected: Boolean,
    val joinedAt: Long,
    val lastSeenAt: Long,
    val sortPosition: Int
) {
    fun toEntity() = CoopParticipantEntity(
        participantId, roomId, deviceId, identityPublicKey, displayName, isHost, isConnected,
        joinedAt, lastSeenAt, sortPosition
    )

    companion object {
        fun from(entity: CoopParticipantEntity) = CoopParticipantDto(
            entity.participantId, entity.roomId, entity.deviceId, entity.identityPublicKey, entity.displayName,
            entity.isHost, entity.isConnected, entity.joinedAt, entity.lastSeenAt,
            entity.sortPosition
        )
    }
}

@JsonClass(generateAdapter = true)
data class CoopPersonaOwnershipDto(
    val roomId: String,
    val personaId: String,
    val participantId: String,
    val personaDisplayNameSnapshot: String,
    val isActive: Boolean,
    val sortPosition: Int
) {
    fun toEntity() = CoopPersonaOwnershipEntity(
        roomId, personaId, participantId, personaDisplayNameSnapshot, isActive, sortPosition
    )

    companion object {
        fun from(entity: CoopPersonaOwnershipEntity) = CoopPersonaOwnershipDto(
            entity.roomId, entity.personaId, entity.participantId,
            entity.personaDisplayNameSnapshot, entity.isActive, entity.sortPosition
        )
    }
}

@JsonClass(generateAdapter = true)
data class CoopSnapshotPayload(
    val roomId: String,
    val hostParticipantId: String,
    val payerMode: String,
    val selectedPayerParticipantId: String?,
    val lastSequence: Long,
    val participants: List<CoopParticipantDto>,
    val personaOwnerships: List<CoopPersonaOwnershipDto>,
    val events: List<CoopEventDto>,
    val hasMoreEvents: Boolean
)

@JsonClass(generateAdapter = true)
data class CoopResyncRequestPayload(val afterSequence: Long)

@JsonClass(generateAdapter = true)
data class CoopMessagePayload(
    val messageId: String,
    val chatId: String,
    val speakerType: String,
    val speakerId: String,
    val speakerDisplayNameSnapshot: String,
    val content: String,
    val createdAt: Long,
    val editedAt: Long?,
    val originalOrderIndex: Long,
    val variantGroupId: String,
    val isPrimaryVariant: Boolean,
    val responsibleParticipantId: String?,
    val responsibleDeviceId: String?,
    val generationExecutionId: String? = null
) {
    companion object {
        fun from(message: MessageEntity, generationExecutionId: String? = null) = CoopMessagePayload(
            message.messageId,
            message.chatId,
            message.speakerType.name,
            message.speakerId,
            message.speakerDisplayNameSnapshot,
            message.content,
            message.createdAt,
            message.editedAt,
            message.orderIndex,
            message.variantGroupId,
            message.isPrimaryVariant,
            message.responsibleParticipantId,
            message.responsibleDeviceId,
            generationExecutionId
        )
    }
}

@JsonClass(generateAdapter = true)
data class CoopMessageDeletePayload(val messageId: String)

@JsonClass(generateAdapter = true)
data class CoopCharacterRosterItem(
    val characterId: String,
    val displayNameSnapshot: String,
    val isActive: Boolean,
    val sortPosition: Int
)

@JsonClass(generateAdapter = true)
data class CoopCharacterRosterPayload(val characters: List<CoopCharacterRosterItem>)

@JsonClass(generateAdapter = true)
data class CoopPayerPolicyPayload(
    val payerMode: String,
    val selectedPayerParticipantId: String?
)

@JsonClass(generateAdapter = true)
data class CoopGenerationRequestPayload(
    val requestId: String,
    val initiatorParticipantId: String,
    val payerParticipantId: String,
    val characterId: String,
    val operation: String,
    val targetMessageId: String?,
    val requestedAt: Long,
    val modelId: String? = null,
    val providerRoutingMode: String? = "AUTO",
    val providerEndpoint: String? = null,
    /**
     * SHA-256 of the exact provider-bound messages and model options compiled by the host.
     * The prompt itself never crosses the LAN. A remote payer must independently compile
     * identical local context before its credential path may be used.
     */
    val contextFingerprint: String? = null
)

object CoopGenerationStage {
    const val PENDING_APPROVAL = "PENDING_APPROVAL"
    const val APPROVED = "APPROVED"
    const val EXECUTING = "EXECUTING"
}

@JsonClass(generateAdapter = true)
data class CoopGenerationApprovalPayload(
    val requestId: String
)

@JsonClass(generateAdapter = true)
data class CoopGenerationExecutionPayload(
    val requestId: String,
    val executionId: String
)

data class CoopGenerationAuthorization(
    val contextFingerprint: String,
    val modelId: String,
    val providerRoutingMode: String = "AUTO",
    val providerEndpoint: String? = null
)

@JsonClass(generateAdapter = true)
data class CoopGenerationMetadataPayload(
    val messageId: String,
    val requestedModelId: String? = null,
    val resolvedModelId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val reportedCost: Double? = null,
    val finishReason: String? = null,
    val provider: String? = null,
    val generationTimeMs: Long? = null
) {
    fun toEntity() = GenerationMetadataEntity(
        messageId,
        requestedModelId,
        resolvedModelId,
        promptTokens,
        completionTokens,
        totalTokens,
        reasoningTokens,
        cachedTokens,
        reportedCost,
        finishReason,
        provider,
        generationTimeMs
    )

    companion object {
        fun from(value: GenerationMetadataEntity) = CoopGenerationMetadataPayload(
            value.messageId,
            value.requestedModelId,
            value.resolvedModelId,
            value.promptTokens,
            value.completionTokens,
            value.totalTokens,
            value.reasoningTokens,
            value.cachedTokens,
            value.reportedCost,
            value.finishReason,
            value.provider,
            value.generationTimeMs
        )
    }
}

@JsonClass(generateAdapter = true)
data class CoopGenerationCompletionPayload(
    val requestId: String,
    val executionId: String? = null,
    val messageId: String? = null,
    val errorMessage: String? = null,
    val metadata: CoopGenerationMetadataPayload? = null
)

data class CoopGenerationCommand(
    val chatId: String,
    val roomId: String,
    val request: CoopGenerationRequestPayload,
    val executionId: String
)

data class CoopGenerationLifecycle(
    val request: CoopGenerationRequestPayload,
    val stage: String,
    val stateChangedAt: Long,
    val executionId: String? = null
)

class CoopProtocolException(message: String) : IllegalStateException(message)
class CoopSequenceGapException(val expected: Long, val received: Long) :
    IllegalStateException("Co-op event gap: expected $expected but received $received")
