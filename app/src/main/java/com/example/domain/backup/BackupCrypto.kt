package com.example.domain.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object BackupCrypto {
    private val magic = byteArrayOf(0x45, 0x4C, 0x53, 0x45, 0x57, 0x48, 0x52, 0x31) // ELSEWHR1
    private const val CONTAINER_VERSION = 1
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    fun requirePassphrase(passphrase: CharArray) {
        require(passphrase.size >= 10) { "Backup passphrases must contain at least 10 characters." }
    }

    fun encrypt(plainInput: InputStream, encryptedOutput: OutputStream, passphrase: CharArray) {
        requirePassphrase(passphrase)
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt)
        try {
            val output = DataOutputStream(encryptedOutput)
            output.write(magic)
            output.writeInt(CONTAINER_VERSION)
            output.writeInt(ITERATIONS)
            output.writeInt(salt.size)
            output.write(salt)
            output.writeInt(iv.size)
            output.write(iv)
            output.flush()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            CipherOutputStream(output, cipher).use { cipherOutput ->
                plainInput.copyTo(cipherOutput)
            }
        } finally {
            key.encoded?.fill(0)
        }
    }

    fun decrypt(encryptedInput: InputStream, plainOutput: OutputStream, passphrase: CharArray) {
        requirePassphrase(passphrase)
        val input = DataInputStream(encryptedInput)
        val actualMagic = ByteArray(magic.size)
        runCatching { input.readFully(actualMagic) }
            .getOrElse { throw BackupValidationException("This is not a complete Elsewhere backup.", it) }
        if (!actualMagic.contentEquals(magic)) {
            throw BackupValidationException("This file is not an Elsewhere encrypted backup.")
        }
        val version = input.readInt()
        if (version != CONTAINER_VERSION) {
            throw BackupValidationException("Unsupported encrypted backup container version: $version")
        }
        val iterations = input.readInt()
        if (iterations !in 100_000..1_000_000) {
            throw BackupValidationException("Invalid backup key-derivation parameters.")
        }
        val saltLength = input.readInt()
        if (saltLength !in 16..64) throw BackupValidationException("Invalid backup salt.")
        val salt = ByteArray(saltLength).also(input::readFully)
        val ivLength = input.readInt()
        if (ivLength !in 12..32) throw BackupValidationException("Invalid backup initialization vector.")
        val iv = ByteArray(ivLength).also(input::readFully)
        val key = deriveKey(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            try {
                CipherInputStream(input, cipher).use { cipherInput -> cipherInput.copyTo(plainOutput) }
            } catch (error: Exception) {
                throw BackupValidationException("The backup passphrase is incorrect or the backup is corrupted.", error)
            }
        } finally {
            key.encoded?.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES").also { encoded.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }
}
