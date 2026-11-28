package com.example.phone

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A local selector, not another provider connection. No key is stored here. */
class PulseModelSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("world_pulse_model", Context.MODE_PRIVATE)
    suspend fun getModel(): String? = withContext(Dispatchers.IO) { prefs.getString("model", null) }
    suspend fun saveModel(value: String) = withContext(Dispatchers.IO) {
        val id = value.trim()
        require(id.isBlank() || (id.length <= 180 && MODEL.matches(id) && !id.equals("openrouter/auto", true))) {
            "Choose a specific model in provider/model format, not Auto."
        }
        val edit = prefs.edit()
        if (id.isEmpty()) edit.remove("model") else edit.putString("model", id)
        check(edit.commit()) { "Could not save the Pulse model." }
    }
    private companion object { val MODEL = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._:-]+") }
}
