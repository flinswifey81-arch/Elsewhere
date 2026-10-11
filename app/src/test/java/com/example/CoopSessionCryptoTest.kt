package com.example

import com.example.domain.coop.CoopSecureFrame
import com.example.domain.coop.CoopSessionCrypto
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopSessionCryptoTest {
    @Test
    fun participantEventSignatureBindsRoomIdentityTypeAndPayload() {
        val member = CoopSessionCrypto.generateSigningKeyPair()
        val attacker = CoopSessionCrypto.generateSigningKeyPair()
        val signature = CoopSessionCrypto.signParticipantEvent(
            member,
            "room",
            "client-event",
            "participant",
            "GENERATION_APPROVED",
            "{\"requestId\":\"request\"}"
        )

        assertTrue(
            CoopSessionCrypto.verifyParticipantEvent(
                member.public.encoded,
                signature,
                "room",
                "client-event",
                "participant",
                "GENERATION_APPROVED",
                "{\"requestId\":\"request\"}"
            )
        )
        assertTrue(!CoopSessionCrypto.verifyParticipantEvent(
            attacker.public.encoded,
            signature,
            "room",
            "client-event",
            "participant",
            "GENERATION_APPROVED",
            "{\"requestId\":\"request\"}"
        ))
        assertTrue(!CoopSessionCrypto.verifyParticipantEvent(
            member.public.encoded,
            signature,
            "room",
            "client-event",
            "participant",
            "GENERATION_APPROVED",
            "{\"requestId\":\"other\"}"
        ))
    }

    @Test
    fun authenticatedHandshakeCreatesBidirectionalEncryptedChannels() {
        val hostIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val participantIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val invite = CoopSessionCrypto.newInviteSecret()
        val clientStart = CoopSessionCrypto.startClientHandshake(
            roomId = "room",
            participantId = "participant",
            inviteSecretText = invite,
            pinnedHostPublicKey = hostIdentity.public.encoded,
            participantIdentity = participantIdentity
        )
        val host = CoopSessionCrypto.acceptClientHandshake(clientStart.hello, invite, hostIdentity)
        val client = CoopSessionCrypto.finishClientHandshake(clientStart, host.hello)

        val clientPayload = "client-to-host".toByteArray()
        assertArrayEquals(clientPayload, host.channel.decrypt(client.encrypt(clientPayload)))
        val hostPayload = "host-to-client".toByteArray()
        assertArrayEquals(hostPayload, client.decrypt(host.channel.encrypt(hostPayload)))
    }

    @Test
    fun wrongInviteSecretCannotAuthenticateClient() {
        val hostIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val participantIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val client = CoopSessionCrypto.startClientHandshake(
            "room", "participant", CoopSessionCrypto.newInviteSecret(), hostIdentity.public.encoded,
            participantIdentity
        )

        val error = runCatching {
            CoopSessionCrypto.acceptClientHandshake(
                client.hello,
                CoopSessionCrypto.newInviteSecret(),
                hostIdentity
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("Invite proof") == true)
    }

    @Test
    fun clientRejectsSubstitutedHostIdentityAndSignature() {
        val expectedHost = CoopSessionCrypto.generateSigningKeyPair()
        val attacker = CoopSessionCrypto.generateSigningKeyPair()
        val participantIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val invite = CoopSessionCrypto.newInviteSecret()
        val pinnedClient = CoopSessionCrypto.startClientHandshake(
            "room", "participant", invite, expectedHost.public.encoded, participantIdentity
        )
        assertTrue(runCatching {
            CoopSessionCrypto.acceptClientHandshake(pinnedClient.hello, invite, attacker)
        }.isFailure)

        val validClient = CoopSessionCrypto.startClientHandshake(
            "room", "participant", invite, expectedHost.public.encoded, participantIdentity
        )
        val host = CoopSessionCrypto.acceptClientHandshake(validClient.hello, invite, expectedHost)
        val signatureBytes = Base64.getUrlDecoder().decode(host.hello.transcriptSignature)
        signatureBytes[signatureBytes.lastIndex] = (signatureBytes.last().toInt() xor 1).toByte()
        val tampered = host.hello.copy(
            transcriptSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes)
        )
        assertTrue(runCatching { CoopSessionCrypto.finishClientHandshake(validClient, tampered) }.isFailure)
    }

    @Test
    fun participantMembershipSignatureCannotBeReplacedByAnotherInviteHolder() {
        val hostIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val memberIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val invite = CoopSessionCrypto.newInviteSecret()
        val member = CoopSessionCrypto.startClientHandshake(
            "room", "member", invite, hostIdentity.public.encoded, memberIdentity
        )
        val attackerIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val forged = member.hello.copy(
            participantPublicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(attackerIdentity.public.encoded),
            participantKeyFingerprint = CoopSessionCrypto.hostFingerprint(attackerIdentity.public.encoded)
        )

        assertTrue(
            runCatching {
                CoopSessionCrypto.acceptClientHandshake(forged, invite, hostIdentity)
            }.exceptionOrNull()?.message?.contains("membership signature") == true
        )
    }

    @Test
    fun encryptedFramesRejectTamperingReplayAndOutOfOrderCounters() {
        val (client, host) = channels()
        val first = client.encrypt("first".toByteArray())
        val bytes = Base64.getUrlDecoder().decode(first.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = first.copy(
            ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        )
        assertTrue(runCatching { host.decrypt(tampered) }.isFailure)
        assertArrayEquals("first".toByteArray(), host.decrypt(first))
        assertTrue(runCatching { host.decrypt(first) }.isFailure)

        val second = client.encrypt("second".toByteArray())
        val fourthCounter = CoopSecureFrame(second.sessionId, second.counter + 2, second.ciphertext)
        assertTrue(runCatching { host.decrypt(fourthCounter) }.isFailure)
        assertArrayEquals("second".toByteArray(), host.decrypt(second))
    }

    @Test
    fun inviteAndHostFingerprintAreHighEntropyAndStableForThePinnedKey() {
        val identity = CoopSessionCrypto.generateSigningKeyPair()
        val invite = CoopSessionCrypto.newInviteSecret()
        assertTrue(Base64.getUrlDecoder().decode(invite).size >= 16)
        val fingerprint = CoopSessionCrypto.hostFingerprint(identity.public.encoded)
        assertNotEquals(invite, fingerprint)
        assertTrue(fingerprint.length >= 40)
        assertTrue(
            fingerprint == CoopSessionCrypto.hostFingerprint(identity.public.encoded)
        )
    }

    private fun channels(): Pair<com.example.domain.coop.CoopSecureChannel, com.example.domain.coop.CoopSecureChannel> {
        val identity = CoopSessionCrypto.generateSigningKeyPair()
        val participantIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val invite = CoopSessionCrypto.newInviteSecret()
        val clientStart = CoopSessionCrypto.startClientHandshake(
            "room", "participant", invite, identity.public.encoded, participantIdentity
        )
        val host = CoopSessionCrypto.acceptClientHandshake(clientStart.hello, invite, identity)
        val client = CoopSessionCrypto.finishClientHandshake(clientStart, host.hello)
        return client to host.channel
    }
}
