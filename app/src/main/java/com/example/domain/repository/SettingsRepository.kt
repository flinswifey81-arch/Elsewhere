package com.example.domain.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ApiKeyRepository {
    suspend fun saveApiKey(apiKey: String)
    suspend fun getApiKey(): String?
    suspend fun deleteApiKey()
}

class SettingsRepository(private val context: Context) : ApiKeyRepository {
    
    private val keyAlias = "openrouter_api_key_alias"
    private val androidKeyStore = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"
    private val fileName = "encrypted_api_key.dat"

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(androidKeyStore)
        keyStore.load(null)
        
        if (keyStore.containsAlias(keyAlias)) {
            val secretKeyEntry = keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
            return secretKeyEntry.secretKey
        }
        
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, androidKeyStore)
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
            
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    override suspend fun saveApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        
        val file = File(context.filesDir, fileName)
        file.outputStream().use { os ->
            os.write(iv.size)
            os.write(iv)
            os.write(ciphertext.size)
            os.write(ciphertext)
        }
    }

    override suspend fun getApiKey(): String? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return@withContext null
        
        try {
            file.inputStream().use { inputStream ->
                val ivSize = inputStream.read()
                val iv = ByteArray(ivSize)
                inputStream.read(iv)
                
                val ciphertextSize = inputStream.read()
                val ciphertext = ByteArray(ciphertextSize)
                inputStream.read(ciphertext)
                
                val cipher = Cipher.getInstance(transformation)
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
                
                val plaintext = cipher.doFinal(ciphertext)
                return@withContext String(plaintext, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    override suspend fun deleteApiKey() = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            file.delete()
        }
        
        val keyStore = KeyStore.getInstance(androidKeyStore)
        keyStore.load(null)
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }
}
