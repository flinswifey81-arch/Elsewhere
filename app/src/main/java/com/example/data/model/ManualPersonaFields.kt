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
        val old = if ((stored["mapping_version"] as? Number)?.toInt() == 2) null else importedFields(persona)
        return ManualPersonaFields(
            name = (stored["name"] as? String).orEmpty().ifEmpty { old?.name.orEmpty() },
            backstoryInstructions = (stored["backstory_instructions"] as? String).orEmpty()
                .ifEmpty { old?.backstoryInstructions.orEmpty() }
        )
    }

    private val instructionKeys = arrayOf("persona_instructions", "backstory_instructions", "instructions", "summary")
    private val backstoryKeys = arrayOf("backstory", "summary", "description")

    fun toEditableFields(persona: PersonaEntity): ManualPersonaFields = readStored(persona) ?: importedFields(persona)

    private fun importedFields(persona: PersonaEntity): ManualPersonaFields {
        val layers = ProfileEditorJson.rootLayers(persona.extensionFieldsJson)
        val roleplayInstructions = importedRoleplayInstructions(persona)
        val background = ProfileEditorJson.decode(persona.backgroundJson)
            ?: ProfileEditorJson.nestedSection(layers,"background")
        return ManualPersonaFields(
            name = persona.personaName,
            backstoryInstructions = roleplayInstructions
                .ifEmpty { ProfileEditorJson.field(background,*backstoryKeys,plain=true) }
                .ifEmpty { ProfileEditorJson.rootText(layers,"backstory_instructions","persona_instructions","instructions","description") }
        )
    }

    fun importedRoleplayInstructions(persona: PersonaEntity): String = ProfileEditorJson.field(
        ProfileEditorJson.decode(persona.roleplayProfileJson)
            ?: ProfileEditorJson.nestedSection(ProfileEditorJson.rootLayers(persona.extensionFieldsJson),"roleplay_profile"),
        *instructionKeys, plain = true
    )

    fun importedRemainderForPrompt(persona: PersonaEntity): PersonaEntity {
        if (readStored(persona) == null) return persona
        return if (importedRoleplayInstructions(persona).isNotEmpty()) {
            persona.copy(roleplayProfileJson = ProfileEditorJson.withoutMapped(persona.roleplayProfileJson,instructionKeys.toSet(),true))
        } else {
            persona.copy(backgroundJson = ProfileEditorJson.withoutMapped(persona.backgroundJson,backstoryKeys.toSet(),true))
        }
    }

    fun write(existingExtensionFieldsJson: String?, fields: ManualPersonaFields): String {
        val extensions = parseObject(existingExtensionFieldsJson)?.toMutableMap() ?: mutableMapOf()
        extensions[EXTENSION_KEY] = linkedMapOf(
            "mapping_version" to 2,
            "name" to fields.name,
            "backstory_instructions" to fields.backstoryInstructions
        )
        return mapAdapter.toJson(extensions)
    }

    private fun parseObject(json: String?): Map<String, Any?>? =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { mapAdapter.fromJson(it) }.getOrNull() }

}
