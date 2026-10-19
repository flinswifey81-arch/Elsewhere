package com.example.domain.coop

import com.squareup.moshi.JsonClass
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@JsonClass(generateAdapter = true)
data class CoopSecureClientHello(
    val protocolVersion: Int = CoopProtocol.VERSION,
    val roomId: String,
    val participantId: String,
    val expectedHostFingerprint: String,
    val clientEphemeralPublicKey: String,
    val clientNonce: String,
    val inviteProof: String,
    val participantPublicKey: String,
    val participantKeyFingerprint: String,
    val participantSignature: String
)

@JsonClass(generateAdapter = true)
data class CoopSecureHostHello(
    val protocolVersion: Int = CoopProtocol.VERSION,
    val roomId: String,
    val sessionId: String,
    val hostPublicKey: String,
    val hostEphemeralPublicKey: String,
    val hostNonce: String,
    val transcriptSignature: String
)

@JsonClass(generateAdapter = true)
data class CoopSecureFrame(
    val sessionId: String,
    val counter: Long,
    val ciphertext: String
)

class CoopClientHandshakeState internal constructor(
    val hello: CoopSecureClientHello,
    internal val ephemeralKeyPair: KeyPair,
    internal val inviteSecret: ByteArray,
    internal val pinnedHostPublicKey: ByteArray
)

data class CoopHostHandshakeResult(
    val hello: CoopSecureHostHello,
    val channel: CoopSecureChannel,
    val authenticatedParticipant: CoopAuthenticatedParticipant
)

data class CoopAuthenticatedParticipant(
    val participantId: String,
    val publicKeyEncoded: ByteArray,
    val keyFingerprint: String
)

/**
 * Authenticated co-op session primitives built only from platform algorithms:
 * P-256 ECDH, ECDSA/SHA-256, RFC 5869 HKDF-SHA256, and AES-256-GCM.
 */
object CoopSessionCrypto {
    private val random = SecureRandom()
    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()

    fun newInviteSecret(): String = base64.encodeToString(randomBytes(24))

    fun generateSigningKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    fun hostFingerprint(publicKeyEncoded: ByteArray): String =
        base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded))

    fun signParticipantEvent(
        identity: KeyPair,
        roomId: String,
        clientEventId: String,
        participantId: String,
        eventType: String,
        originPayloadJson: String
    ): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private, random)
            update(participantEventTranscript(roomId, clientEventId, participantId, eventType, originPayloadJson))
            sign()
        }
        return base64.encodeToString(signature)
    }

    fun verifyParticipantEvent(
        publicKeyEncoded: ByteArray,
        signatureText: String,
        roomId: String,
        clientEventId: String,
        participantId: String,
        eventType: String,
        originPayloadJson: String
    ): Boolean = runCatching {
        val signature = decodeBounded(signatureText, MAX_SIGNATURE_BYTES, "participant event signature")
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(decodeEcPublicKey(publicKeyEncoded))
            update(participantEventTranscript(roomId, clientEventId, participantId, eventType, originPayloadJson))
            verify(signature)
        }
    }.getOrDefault(false)

    fun startClientHandshake(
        roomId: String,
        participantId: String,
        inviteSecretText: String,
        pinnedHostPublicKey: ByteArray,
        participantIdentity: KeyPair
    ): CoopClientHandshakeState {
        validateId(roomId, "room")
        validateId(participantId, "participant")
        val inviteSecret = decodeInviteSecret(inviteSecretText)
        val ephemeral = generateSigningKeyPair()
        val nonce = randomBytes(NONCE_BYTES)
        val fingerprint = hostFingerprint(pinnedHostPublicKey)
        val proofInput = canonical(
            CLIENT_PROOF_LABEL.toByteArray(),
            roomId.toByteArray(),
            participantId.toByteArray(),
            ephemeral.public.encoded,
            nonce,
            fingerprint.toByteArray()
        )
        val participantPublicKey = participantIdentity.public.encoded
        val participantFingerprint = hostFingerprint(participantPublicKey)
        val inviteProof = hmac(inviteSecret, proofInput)
        val participantSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(participantIdentity.private, random)
            update(
                participantProofTranscript(
                    roomId,
                    participantId,
                    fingerprint,
                    ephemeral.public.encoded,
                    nonce,
                    inviteProof,
                    participantPublicKey
                )
            )
            sign()
        }
        val hello = CoopSecureClientHello(
            roomId = roomId,
            participantId = participantId,
            expectedHostFingerprint = fingerprint,
            clientEphemeralPublicKey = base64.encodeToString(ephemeral.public.encoded),
            clientNonce = base64.encodeToString(nonce),
            inviteProof = base64.encodeToString(inviteProof),
            participantPublicKey = base64.encodeToString(participantPublicKey),
            participantKeyFingerprint = participantFingerprint,
            participantSignature = base64.encodeToString(participantSignature)
        )
        return CoopClientHandshakeState(hello, ephemeral, inviteSecret, pinnedHostPublicKey.copyOf())
    }

    fun acceptClientHandshake(
        clientHello: CoopSecureClientHello,
        inviteSecretText: String,
        hostSigningKeyPair: KeyPair
    ): CoopHostHandshakeResult {
        require(clientHello.protocolVersion == CoopProtocol.VERSION) { "Unsupported secure protocol version" }
        validateId(clientHello.roomId, "room")
        validateId(clientHello.participantId, "participant")
        val inviteSecret = decodeInviteSecret(inviteSecretText)
        try {
            val hostPublic = hostSigningKeyPair.public.encoded
            val fingerprint = hostFingerprint(hostPublic)
            require(constantTimeEquals(fingerprint, clientHello.expectedHostFingerprint)) {
                "Pinned host identity does not match"
            }
            val clientPublicBytes = decodeBounded(clientHello.clientEphemeralPublicKey, MAX_KEY_BYTES, "client key")
            val clientNonce = decodeBounded(clientHello.clientNonce, NONCE_BYTES, "client nonce")
            require(clientNonce.size == NONCE_BYTES) { "Invalid client nonce" }
            val expectedProof = hmac(
                inviteSecret,
                canonical(
                    CLIENT_PROOF_LABEL.toByteArray(),
                    clientHello.roomId.toByteArray(),
                    clientHello.participantId.toByteArray(),
                    clientPublicBytes,
                    clientNonce,
                    fingerprint.toByteArray()
                )
            )
            val suppliedProof = decodeBounded(clientHello.inviteProof, 64, "invite proof")
            require(MessageDigest.isEqual(expectedProof, suppliedProof)) { "Invite proof is invalid" }
            val participantPublicBytes = decodeBounded(
                clientHello.participantPublicKey,
                MAX_KEY_BYTES,
                "participant key"
            )
            val participantFingerprint = hostFingerprint(participantPublicBytes)
            require(constantTimeEquals(participantFingerprint, clientHello.participantKeyFingerprint)) {
                "Participant key fingerprint does not match"
            }
            val participantSignature = decodeBounded(
                clientHello.participantSignature,
                MAX_SIGNATURE_BYTES,
                "participant signature"
            )
            val participantSignatureValid = Signature.getInstance("SHA256withECDSA").run {
                initVerify(decodeEcPublicKey(participantPublicBytes))
                update(
                    participantProofTranscript(
                        clientHello.roomId,
                        clientHello.participantId,
                        fingerprint,
                        clientPublicBytes,
                        clientNonce,
                        suppliedProof,
                        participantPublicBytes
                    )
                )
                verify(participantSignature)
            }
            require(participantSignatureValid) {
                "Participant membership signature is invalid"
            }

            val hostEphemeral = generateSigningKeyPair()
            val hostNonce = randomBytes(NONCE_BYTES)
            val sessionId = base64.encodeToString(randomBytes(SESSION_ID_BYTES))
            val transcript = handshakeTranscript(
                clientHello,
                hostPublic,
                hostEphemeral.public.encoded,
                hostNonce,
                sessionId
            )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(hostSigningKeyPair.private, random)
                update(transcript)
                sign()
            }
            val hello = CoopSecureHostHello(
                roomId = clientHello.roomId,
                sessionId = sessionId,
                hostPublicKey = base64.encodeToString(hostPublic),
                hostEphemeralPublicKey = base64.encodeToString(hostEphemeral.public.encoded),
                hostNonce = base64.encodeToString(hostNonce),
                transcriptSignature = base64.encodeToString(signature)
            )
            val material = deriveSessionMaterial(
                sharedSecret(hostEphemeral, decodeEcPublicKey(clientPublicBytes)),
                inviteSecret,
                clientNonce,
                hostNonce,
                clientHello.roomId,
                sessionId,
                fingerprint,
                participantFingerprint
            )
            val channel = CoopSecureChannel.host(clientHello.roomId, sessionId, material)
            material.fill(0)
            return CoopHostHandshakeResult(
                hello,
                channel,
                CoopAuthenticatedParticipant(
                    clientHello.participantId,
                    participantPublicBytes,
                    participantFingerprint
                )
            )
        } finally {
            inviteSecret.fill(0)
        }
    }

    fun finishClientHandshake(
        state: CoopClientHandshakeState,
        hostHello: CoopSecureHostHello
    ): CoopSecureChannel {
        require(hostHello.protocolVersion == CoopProtocol.VERSION) { "Unsupported secure protocol version" }
        require(hostHello.roomId == state.hello.roomId) { "Host hello room mismatch" }
        validateId(hostHello.sessionId, "session")
        try {
            val hostPublicBytes = decodeBounded(hostHello.hostPublicKey, MAX_KEY_BYTES, "host key")
            require(MessageDigest.isEqual(hostPublicBytes, state.pinnedHostPublicKey)) {
                "Host public key does not match the pinned invite"
            }
            require(
                constantTimeEquals(hostFingerprint(hostPublicBytes), state.hello.expectedHostFingerprint)
            ) { "Host fingerprint does not match the pinned invite" }
            val hostEphemeralBytes = decodeBounded(hostHello.hostEphemeralPublicKey, MAX_KEY_BYTES, "host ephemeral key")
            val hostNonce = decodeBounded(hostHello.hostNonce, NONCE_BYTES, "host nonce")
            require(hostNonce.size == NONCE_BYTES) { "Invalid host nonce" }
            val transcript = handshakeTranscript(
                state.hello,
                hostPublicBytes,
                hostEphemeralBytes,
                hostNonce,
                hostHello.sessionId
            )
            val signature = decodeBounded(hostHello.transcriptSignature, MAX_SIGNATURE_BYTES, "host signature")
            val verified = Signature.getInstance("SHA256withECDSA").run {
                initVerify(decodeEcPublicKey(hostPublicBytes))
                update(transcript)
                verify(signature)
            }
            require(verified) { "Host handshake signature is invalid" }
            val clientNonce = base64Decoder.decode(state.hello.clientNonce)
            val material = deriveSessionMaterial(
                sharedSecret(state.ephemeralKeyPair, decodeEcPublicKey(hostEphemeralBytes)),
                state.inviteSecret,
                clientNonce,
                hostNonce,
                state.hello.roomId,
                hostHello.sessionId,
                state.hello.expectedHostFingerprint,
                state.hello.participantKeyFingerprint
            )
            val channel = CoopSecureChannel.client(state.hello.roomId, hostHello.sessionId, material)
            material.fill(0)
            return channel
        } finally {
            state.inviteSecret.fill(0)
        }
    }

    private fun deriveSessionMaterial(
        sharedSecret: ByteArray,
        inviteSecret: ByteArray,
        clientNonce: ByteArray,
        hostNonce: ByteArray,
        roomId: String,
        sessionId: String,
        fingerprint: String,
        participantFingerprint: String
    ): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(
            canonical(inviteSecret, clientNonce, hostNonce)
        )
        val info = canonical(
            SESSION_LABEL.toByteArray(),
            roomId.toByteArray(),
            sessionId.toByteArray(),
            fingerprint.toByteArray(),
            participantFingerprint.toByteArray()
        )
        return try {
            hkdfSha256(sharedSecret, salt, info, SESSION_MATERIAL_BYTES)
        } finally {
            sharedSecret.fill(0)
        }
    }

    private fun handshakeTranscript(
        client: CoopSecureClientHello,
        hostPublicKey: ByteArray,
        hostEphemeralKey: ByteArray,
        hostNonce: ByteArray,
        sessionId: String
    ) = canonical(
        TRANSCRIPT_LABEL.toByteArray(),
        client.protocolVersion.toString().toByteArray(),
        client.roomId.toByteArray(),
        client.participantId.toByteArray(),
        client.expectedHostFingerprint.toByteArray(),
        base64Decoder.decode(client.clientEphemeralPublicKey),
        base64Decoder.decode(client.clientNonce),
        base64Decoder.decode(client.inviteProof),
        base64Decoder.decode(client.participantPublicKey),
        client.participantKeyFingerprint.toByteArray(),
        base64Decoder.decode(client.participantSignature),
        hostPublicKey,
        hostEphemeralKey,
        hostNonce,
        sessionId.toByteArray()
    )

    private fun participantProofTranscript(
        roomId: String,
        participantId: String,
        hostFingerprint: String,
        clientEphemeralPublicKey: ByteArray,
        clientNonce: ByteArray,
        inviteProof: ByteArray,
        participantPublicKey: ByteArray
    ) = canonical(
        PARTICIPANT_PROOF_LABEL.toByteArray(),
        CoopProtocol.VERSION.toString().toByteArray(),
        roomId.toByteArray(),
        participantId.toByteArray(),
        hostFingerprint.toByteArray(),
        clientEphemeralPublicKey,
        clientNonce,
        inviteProof,
        participantPublicKey
    )

    private fun participantEventTranscript(
        roomId: String,
        clientEventId: String,
        participantId: String,
        eventType: String,
        originPayloadJson: String
    ) = canonical(
        PARTICIPANT_EVENT_LABEL.toByteArray(),
        CoopProtocol.VERSION.toString().toByteArray(),
        roomId.toByteArray(Charsets.UTF_8),
        clientEventId.toByteArray(Charsets.UTF_8),
        participantId.toByteArray(Charsets.UTF_8),
        eventType.toByteArray(Charsets.UTF_8),
        originPayloadJson.toByteArray(Charsets.UTF_8)
    )

    private fun sharedSecret(ownEphemeral: KeyPair, peerPublic: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(ownEphemeral.private)
            doPhase(peerPublic, true)
            generateSecret()
        }

    private fun decodeEcPublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    private fun decodeInviteSecret(value: String): ByteArray {
        val decoded = runCatching { base64Decoder.decode(value) }
            .getOrElse { throw IllegalArgumentException("Invite secret is malformed") }
        require(decoded.size >= 16) { "Invite secret must contain at least 128 bits" }
        require(decoded.size <= 64) { "Invite secret is too large" }
        return decoded
    }

    private fun decodeBounded(value: String, maxBytes: Int, label: String): ByteArray {
        require(value.length <= maxBytes * 2) { "$label is too large" }
        val decoded = runCatching { base64Decoder.decode(value) }
            .getOrElse { throw IllegalArgumentException("$label is malformed") }
        require(decoded.size <= maxBytes) { "$label is too large" }
        return decoded
    }

    private fun validateId(value: String, label: String) {
        require(value.isNotBlank() && value.length <= CoopProtocol.MAX_ID_CHARS) { "Invalid $label ID" }
    }

    internal fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF output length" }
        val pseudoRandomKey = hmac(salt, inputKeyMaterial)
        val output = ByteArrayOutputStream(length)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            previous = hmac(pseudoRandomKey, previous + info + byteArrayOf(counter.toByte()))
            output.write(previous)
            counter++
        }
        pseudoRandomKey.fill(0)
        previous.fill(0)
        return output.toByteArray().copyOf(length)
    }

    internal fun canonical(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                parts.forEach { part ->
                    output.writeInt(part.size)
                    output.write(part)
                }
            }
            bytes.toByteArray()
        }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(StandardCharsets.US_ASCII), right.toByteArray(StandardCharsets.US_ASCII))

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private const val NONCE_BYTES = 32
    private const val SESSION_ID_BYTES = 16
    private const val MAX_KEY_BYTES = 256
    private const val MAX_SIGNATURE_BYTES = 256
    private const val SESSION_MATERIAL_BYTES = 72
    private const val CLIENT_PROOF_LABEL = "elsewhere-client-proof-v1"
    private const val PARTICIPANT_PROOF_LABEL = "elsewhere-participant-proof-v2"
    private const val TRANSCRIPT_LABEL = "elsewhere-handshake-transcript-v2"
    private const val SESSION_LABEL = "elsewhere-session-keys-v2"
    private const val PARTICIPANT_EVENT_LABEL = "elsewhere-participant-event-v1"
}

class CoopSecureChannel private constructor(
    private val roomId: String,
    private val sessionId: String,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendNoncePrefix: ByteArray,
    private val receiveNoncePrefix: ByteArray,
    private val sendDirection: String,
    private val receiveDirection: String
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized
    fun encrypt(plaintext: ByteArray): CoopSecureFrame {
        require(plaintext.size <= CoopProtocol.MAX_FRAME_BYTES) { "Secure frame plaintext is too large" }
        check(sendCounter < Long.MAX_VALUE) { "Secure send counter exhausted" }
        val counter = ++sendCounter
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sendKey, "AES"),
            GCMParameterSpec(128, nonce(sendNoncePrefix, counter))
        )
        cipher.updateAAD(aad(sendDirection, counter))
        val encrypted = cipher.doFinal(plaintext)
        return CoopSecureFrame(
            sessionId = sessionId,
            counter = counter,
            ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
        )
    }

    @Synchronized
    fun decrypt(frame: CoopSecureFrame): ByteArray {
        require(frame.sessionId == sessionId) { "Secure frame session mismatch" }
        require(frame.counter == receiveCounter + 1) { "Secure frame counter is replayed or out of order" }
        require(frame.ciphertext.length <= (CoopProtocol.MAX_FRAME_BYTES + 16) * 2) {
            "Secure frame ciphertext is too large"
        }
        val encrypted = runCatching { Base64.getUrlDecoder().decode(frame.ciphertext) }
            .getOrElse { throw IllegalArgumentException("Secure frame ciphertext is malformed") }
        require(encrypted.size <= CoopProtocol.MAX_FRAME_BYTES + 16) { "Secure frame ciphertext is too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(receiveKey, "AES"),
            GCMParameterSpec(128, nonce(receiveNoncePrefix, frame.counter))
        )
        cipher.updateAAD(aad(receiveDirection, frame.counter))
        val plaintext = cipher.doFinal(encrypted)
        receiveCounter = frame.counter
        return plaintext
    }

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        prefix + ByteBuffer.allocate(8).putLong(counter).array()

    private fun aad(direction: String, counter: Long): ByteArray = CoopSessionCrypto.canonical(
        roomId.toByteArray(StandardCharsets.UTF_8),
        sessionId.toByteArray(StandardCharsets.UTF_8),
        direction.toByteArray(StandardCharsets.US_ASCII),
        ByteBuffer.allocate(8).putLong(counter).array()
    )

    companion object {
        internal fun host(roomId: String, sessionId: String, material: ByteArray) = CoopSecureChannel(
            roomId,
            sessionId,
            sendKey = material.copyOfRange(32, 64),
            receiveKey = material.copyOfRange(0, 32),
            sendNoncePrefix = material.copyOfRange(68, 72),
            receiveNoncePrefix = material.copyOfRange(64, 68),
            sendDirection = "H2C",
            receiveDirection = "C2H"
        )

        internal fun client(roomId: String, sessionId: String, material: ByteArray) = CoopSecureChannel(
            roomId,
            sessionId,
            sendKey = material.copyOfRange(0, 32),
            receiveKey = material.copyOfRange(32, 64),
            sendNoncePrefix = material.copyOfRange(64, 68),
            receiveNoncePrefix = material.copyOfRange(68, 72),
            sendDirection = "C2H",
            receiveDirection = "H2C"
        )
    }
}
