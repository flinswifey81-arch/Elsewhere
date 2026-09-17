package com.example.data.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class ManualPersonaFields(
    val name: String = "",
    val backstoryInstructions: String = ""
)

/** Keeps the two-field manual editor separate from imported Persona sections. */
object ManualPersonaFieldsCodec {
    const val EXTENSION_KEY = "elsewhere_manual_persona_fields"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun readStored(persona: PersonaEntity): ManualPersonaFields? {
        val extensions = parseObject(persona.extensionFieldsJson) ?: return null
        val stored = extensions[EXTENSION_KEY] as? Map<*, *> ?: return null
        return ManualPersonaFields(
            name = stored["name"] as? String ?: "",
            backstoryInstructions = stored["backstory_instructions"] as? String ?: ""
        )
    }

    fun toEditableFields(persona: PersonaEntity): ManualPersonaFields =
        readStored(persona) ?: ManualPersonaFields(
            name = persona.personaName,
            backstoryInstructions = firstText(
                persona.roleplayProfileJson,
                "persona_instructions",
                "instructions",
                "summary"
            ).ifEmpty {
                firstText(persona.backgroundJson, "backstory", "summary", "description")
            }
        )

    fun write(existingExtensionFieldsJson: String?, fields: ManualPersonaFields): String {
        val extensions = parseObject(existingExtensionFieldsJson)?.toMutableMap() ?: mutableMapOf()
        extensions[EXTENSION_KEY] = linkedMapOf(
            "name" to fields.name,
            "backstory_instructions" to fields.backstoryInstructions
        )
        return mapAdapter.toJson(extensions)
    }

    private fun parseObject(json: String?): Map<String, Any?>? =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { mapAdapter.fromJson(it) }.getOrNull() }

    private fun firstText(json: String?, vararg keys: String): String {
        if (json.isNullOrBlank()) return ""
        val parsed = runCatching { moshi.adapter(Any::class.java).fromJson(json) }.getOrNull()
        if (parsed is String) return parsed
        val objectValue = parsed as? Map<*, *> ?: return ""
        return keys.firstNotNullOfOrNull { key -> objectValue[key].asEditableText() } ?: ""
    }

    private fun Any?.asEditableText(): String? = when (this) {
        is String -> this
        is Number, is Boolean -> toString()
        else -> null
    }
}
