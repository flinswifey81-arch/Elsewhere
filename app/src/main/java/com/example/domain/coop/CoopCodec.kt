package com.example.domain.coop

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import java.util.Base64

class CoopCodec(private val moshi: Moshi) {
    private fun <T> adapter(type: Class<T>): JsonAdapter<T> = moshi.adapter(type)

    private val wireAdapter = adapter(CoopWireEnvelope::class.java)
    private val helloAdapter = adapter(CoopHelloPayload::class.java)
    private val inviteBundleAdapter = adapter(CoopInviteBundle::class.java)
    private val proposedAdapter = adapter(CoopProposedEventPayload::class.java)
    private val proposalRejectionAdapter = adapter(CoopProposalRejectionPayload::class.java)
    private val eventAdapter = adapter(CoopEventDto::class.java)
    private val snapshotAdapter = adapter(CoopSnapshotPayload::class.java)
    private val resyncAdapter = adapter(CoopResyncRequestPayload::class.java)
    private val messageAdapter = adapter(CoopMessagePayload::class.java)
    private val deleteAdapter = adapter(CoopMessageDeletePayload::class.java)
    private val participantAdapter = adapter(CoopParticipantDto::class.java)
    private val ownershipAdapter = adapter(CoopPersonaOwnershipDto::class.java)
    private val rosterAdapter = adapter(CoopCharacterRosterPayload::class.java)
    private val payerAdapter = adapter(CoopPayerPolicyPayload::class.java)
    private val generationAdapter = adapter(CoopGenerationRequestPayload::class.java)
    private val generationApprovalAdapter = adapter(CoopGenerationApprovalPayload::class.java)
    private val generationExecutionAdapter = adapter(CoopGenerationExecutionPayload::class.java)
    private val completionAdapter = adapter(CoopGenerationCompletionPayload::class.java)
    private val secureClientHelloAdapter = adapter(CoopSecureClientHello::class.java)
    private val secureHostHelloAdapter = adapter(CoopSecureHostHello::class.java)
    private val secureFrameAdapter = adapter(CoopSecureFrame::class.java)

    fun wireToJson(value: CoopWireEnvelope) = wireAdapter.toJson(value)
    fun wireFromJson(json: String) = wireAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty co-op wire envelope")
    fun helloToJson(value: CoopHelloPayload) = helloAdapter.toJson(value)
    fun helloFromJson(json: String) = helloAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty co-op hello")
    fun inviteBundleToText(value: CoopInviteBundle): String {
        validateInviteBundle(value)
        return INVITE_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(inviteBundleAdapter.toJson(value).toByteArray(Charsets.UTF_8))
    }
    fun inviteBundleFromText(text: String): CoopInviteBundle {
        val normalized = text.trim()
        require(normalized.startsWith(INVITE_PREFIX)) { "This is not an Elsewhere co-op invite bundle" }
        val encoded = normalized.removePrefix(INVITE_PREFIX)
        require(encoded.length in 1..MAX_INVITE_TEXT_CHARS) { "Invite bundle size is invalid" }
        val json = runCatching {
            Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
        }.getOrElse { throw CoopProtocolException("Invite bundle encoding is invalid") }
        val bundle = runCatching { inviteBundleAdapter.fromJson(json) }
            .getOrElse { throw CoopProtocolException("Invite bundle data is invalid") }
            ?: throw CoopProtocolException("Invite bundle is empty")
        validateInviteBundle(bundle)
        return bundle
    }
    fun proposedToJson(value: CoopProposedEventPayload) = proposedAdapter.toJson(value)
    fun proposedFromJson(json: String) = proposedAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty proposed event")
    fun proposalRejectionToJson(value: CoopProposalRejectionPayload) = proposalRejectionAdapter.toJson(value)
    fun proposalRejectionFromJson(json: String) = proposalRejectionAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty proposal rejection")
    fun eventToJson(value: CoopEventDto) = eventAdapter.toJson(value)
    fun eventFromJson(json: String) = eventAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty ordered event")
    fun snapshotToJson(value: CoopSnapshotPayload) = snapshotAdapter.toJson(value)
    fun snapshotFromJson(json: String) = snapshotAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty snapshot")
    fun resyncToJson(value: CoopResyncRequestPayload) = resyncAdapter.toJson(value)
    fun resyncFromJson(json: String) = resyncAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty resync request")
    fun messageToJson(value: CoopMessagePayload) = messageAdapter.toJson(value)
    fun messageFromJson(json: String) = messageAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty message payload")
    fun deleteToJson(value: CoopMessageDeletePayload) = deleteAdapter.toJson(value)
    fun deleteFromJson(json: String) = deleteAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty delete payload")
    fun participantToJson(value: CoopParticipantDto) = participantAdapter.toJson(value)
    fun participantFromJson(json: String) = participantAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty participant payload")
    fun ownershipToJson(value: CoopPersonaOwnershipDto) = ownershipAdapter.toJson(value)
    fun ownershipFromJson(json: String) = ownershipAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty Persona ownership payload")
    fun rosterToJson(value: CoopCharacterRosterPayload) = rosterAdapter.toJson(value)
    fun rosterFromJson(json: String) = rosterAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty Character roster payload")
    fun payerToJson(value: CoopPayerPolicyPayload) = payerAdapter.toJson(value)
    fun payerFromJson(json: String) = payerAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty payer policy")
    fun generationToJson(value: CoopGenerationRequestPayload) = generationAdapter.toJson(value)
    fun generationFromJson(json: String) = generationAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty generation request")
    fun approvalToJson(value: CoopGenerationApprovalPayload) = generationApprovalAdapter.toJson(value)
    fun approvalFromJson(json: String) = generationApprovalAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty generation approval")
    fun executionToJson(value: CoopGenerationExecutionPayload) = generationExecutionAdapter.toJson(value)
    fun executionFromJson(json: String) = generationExecutionAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty generation execution")
    fun completionToJson(value: CoopGenerationCompletionPayload) = completionAdapter.toJson(value)
    fun completionFromJson(json: String) = completionAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty generation completion")
    fun secureClientHelloToJson(value: CoopSecureClientHello) = secureClientHelloAdapter.toJson(value)
    fun secureClientHelloFromJson(json: String) = secureClientHelloAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty secure client hello")
    fun secureHostHelloToJson(value: CoopSecureHostHello) = secureHostHelloAdapter.toJson(value)
    fun secureHostHelloFromJson(json: String) = secureHostHelloAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty secure host hello")
    fun secureFrameToJson(value: CoopSecureFrame) = secureFrameAdapter.toJson(value)
    fun secureFrameFromJson(json: String) = secureFrameAdapter.fromJson(json)
        ?: throw CoopProtocolException("Empty secure frame")

    private fun validateInviteBundle(value: CoopInviteBundle) {
        require(value.bundleVersion == INVITE_BUNDLE_VERSION) { "Unsupported invite bundle version" }
        require(value.protocolVersion == CoopProtocol.VERSION) { "Invite uses a different co-op protocol version" }
        CoopLanAddressPolicy.requirePrivateIpv4(value.hostAddress)
        require(value.port in 1024..65535) { "Invite LAN port is invalid" }
        require(value.roomId.isNotBlank() && value.roomId.length <= CoopProtocol.MAX_ID_CHARS) {
            "Invite room ID is invalid"
        }
        require(value.hostParticipantId.isNotBlank() &&
            value.hostParticipantId.length <= CoopProtocol.MAX_ID_CHARS) { "Invite host member is invalid" }
        require(value.inviteSecret.length in 22..128) { "Invite secret is invalid" }
        require(value.hostPublicKey.isNotBlank() &&
            value.hostPublicKey.length <= CoopProtocol.MAX_PUBLIC_KEY_CHARS) { "Invite host key is invalid" }
        require(value.hostKeyFingerprint.isNotBlank() &&
            value.hostKeyFingerprint.length <= CoopProtocol.MAX_DEVICE_ID_CHARS) {
            "Invite host fingerprint is invalid"
        }
        val decoded = runCatching { Base64.getUrlDecoder().decode(value.hostPublicKey) }
            .getOrElse { throw CoopProtocolException("Invite host key is malformed") }
        require(CoopSessionCrypto.hostFingerprint(decoded) == value.hostKeyFingerprint) {
            "Invite host key fingerprint does not match"
        }
    }

    companion object {
        private const val INVITE_BUNDLE_VERSION = 1
        private const val INVITE_PREFIX = "ELSEWHERE-COOP-1:"
        private const val MAX_INVITE_TEXT_CHARS = 4096
    }
}
