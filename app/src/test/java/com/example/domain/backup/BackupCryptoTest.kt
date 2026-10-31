package com.example.domain.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun encryptedEnvelopeRoundTripsWithoutContainingPlaintext() {
        val plaintext = "private local archive".repeat(100).toByteArray()
        val encrypted = ByteArrayOutputStream()

        BackupCrypto.encrypt(
            ByteArrayInputStream(plaintext),
            encrypted,
            "a sufficiently long passphrase".toCharArray()
        )

        assertTrue(encrypted.size() > plaintext.size)
        assertTrue(!encrypted.toByteArray().containsSubsequence("private local archive".toByteArray()))
        val restored = ByteArrayOutputStream()
        BackupCrypto.decrypt(
            ByteArrayInputStream(encrypted.toByteArray()),
            restored,
            "a sufficiently long passphrase".toCharArray()
        )
        assertArrayEquals(plaintext, restored.toByteArray())
    }

    @Test
    fun wrongPasswordFailsClearlyAndCorrectRetryCanStillReadSameFile() {
        val plaintext = "recover my chats and characters".toByteArray()
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(plaintext), encrypted, "correct-long-passphrase".toCharArray())
        val bytes = encrypted.toByteArray()
        val failed = runCatching {
            BackupCrypto.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), "incorrect-long-passphrase".toCharArray())
        }.exceptionOrNull()
        assertTrue(failed is BackupValidationException)
        assertTrue(failed?.message.orEmpty().contains("passphrase is incorrect or the backup is corrupted"))
        val retried = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(bytes), retried, "correct-long-passphrase".toCharArray())
        assertArrayEquals(plaintext, retried.toByteArray())
    }

    @Test
    fun shortPassphrasesAreRejected() {
        val failure = runCatching {
            BackupCrypto.encrypt(
                ByteArrayInputStream(byteArrayOf(1)),
                ByteArrayOutputStream(),
                "too-short".toCharArray()
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }
}
