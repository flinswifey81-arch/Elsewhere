package com.example.domain.coop

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CoopDeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("coop_device_identity", Context.MODE_PRIVATE)

    fun roomScopedId(roomId: String): String = synchronized(preferences) {
        require(roomId.isNotBlank()) { "Room ID is required" }
        val secret = preferences.getString(KEY_SECRET, null)?.let(Base64.getDecoder()::decode)
            ?: ByteArray(32).also(SecureRandom()::nextBytes).also { generated ->
                preferences.edit().putString(KEY_SECRET, Base64.getEncoder().encodeToString(generated)).apply()
            }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(roomId.toByteArray(Charsets.UTF_8)))
            .take(32)
        }

    companion object {
        private const val KEY_SECRET = "room_pseudonym_secret"
    }
}
