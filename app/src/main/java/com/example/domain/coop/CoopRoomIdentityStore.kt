package com.example.domain.coop

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

data class CoopRoomIdentity(
    val keyPair: KeyPair,
    val publicKeyEncoded: ByteArray,
    val fingerprint: String
)

interface CoopRoomIdentityStore {
    fun getOrCreate(roomId: String): CoopRoomIdentity
    fun get(roomId: String): CoopRoomIdentity?
    fun delete(roomId: String)
}

interface CoopParticipantIdentityStore {
    fun getOrCreate(roomId: String, participantId: String): CoopRoomIdentity
    fun get(roomId: String, participantId: String): CoopRoomIdentity?
    fun delete(roomId: String, participantId: String)
}

/** Keeps each room host signing key non-exportable in AndroidKeyStore. */
class AndroidCoopRoomIdentityStore : CoopRoomIdentityStore {
    override fun getOrCreate(roomId: String): CoopRoomIdentity = get(roomId) ?: run {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias(roomId),
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()
        get(roomId) ?: throw CoopProtocolException("Unable to create room host identity")
    }

    override fun get(roomId: String): CoopRoomIdentity? {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val entry = store.getEntry(alias(roomId), null) as? KeyStore.PrivateKeyEntry ?: return null
        val pair = KeyPair(entry.certificate.publicKey, entry.privateKey)
        return CoopRoomIdentity(
            keyPair = pair,
            publicKeyEncoded = pair.public.encoded,
            fingerprint = CoopSessionCrypto.hostFingerprint(pair.public.encoded)
        )
    }

    override fun delete(roomId: String) {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (store.containsAlias(alias(roomId))) store.deleteEntry(alias(roomId))
    }

    private fun alias(roomId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(roomId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "elsewhere_coop_room_$digest"
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}

/**
 * Keeps a distinct non-exportable signing identity for each participant membership. The public
 * key fingerprint is the room-scoped device pseudonym stored in the roster; reconnecting requires
 * proving possession of the matching private key, not merely knowing the shared invite secret.
 */
class AndroidCoopParticipantIdentityStore : CoopParticipantIdentityStore {
    override fun getOrCreate(roomId: String, participantId: String): CoopRoomIdentity =
        get(roomId, participantId) ?: run {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias(roomId, participantId),
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
            get(roomId, participantId)
                ?: throw CoopProtocolException("Unable to create participant membership identity")
        }

    override fun get(roomId: String, participantId: String): CoopRoomIdentity? {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val entry = store.getEntry(alias(roomId, participantId), null) as? KeyStore.PrivateKeyEntry
            ?: return null
        val pair = KeyPair(entry.certificate.publicKey, entry.privateKey)
        return CoopRoomIdentity(
            keyPair = pair,
            publicKeyEncoded = pair.public.encoded,
            fingerprint = CoopSessionCrypto.hostFingerprint(pair.public.encoded)
        )
    }

    override fun delete(roomId: String, participantId: String) {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val alias = alias(roomId, participantId)
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun alias(roomId: String, participantId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$roomId\u0000$participantId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "elsewhere_coop_participant_$digest"
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
