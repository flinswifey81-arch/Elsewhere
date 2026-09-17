package com.example.data.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class ManualCharacterFields(
    val name: String = "",
    val shortBackstory: String = "",
    val initialMessage: String = "",
    val systemInstructions: String = "",
    val personality: String = "",
    val tone: String = "",
    val age: String = "",
    val birthday: String = "",
    val story: String = "",
    val likes: String = "",
    val dislikes: String = "",
    val conversationalGoals: String = "",
    val conversationalExamples: String = "",
    val appearance: String = "",
    val knowledgeRelationships: String = "",
    val knowledgeGeneral: String = ""
)

/**
 * Stores the manual editor as a reserved extension object. Imported sections remain untouched,
 * while the manual overlay can be round-tripped exactly and exported with the character.
 */
object ManualCharacterFieldsCodec {
    const val EXTENSION_KEY = "elsewhere_manual_fields"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun readStored(character: CharacterEntity): ManualCharacterFields? {
        val extensions = parseObject(character.extensionFieldsJson) ?: return null
        val stored = extensions[EXTENSION_KEY] as? Map<*, *> ?: return null
        return ManualCharacterFields(
            name = stored.string("name"),
            shortBackstory = stored.string("short_backstory"),
            initialMessage = stored.string("initial_message"),
            systemInstructions = stored.string("system_instructions"),
            personality = stored.string("personality"),
            tone = stored.string("tone"),
            age = stored.string("age"),
            birthday = stored.string("birthday"),
            story = stored.string("story"),
            likes = stored.string("likes"),
            dislikes = stored.string("dislikes"),
            conversationalGoals = stored.string("conversational_goals"),
            conversationalExamples = stored.string("conversational_examples"),
            appearance = stored.string("appearance"),
            knowledgeRelationships = stored.string("knowledge_relationships"),
            knowledgeGeneral = stored.string("knowledge_general")
        )
    }

    fun toEditableFields(character: CharacterEntity): ManualCharacterFields =
        readStored(character) ?: ManualCharacterFields(
            name = character.characterName,
            shortBackstory = firstText(character.backstoryJson, "short_backstory", "summary", "description"),
            initialMessage = firstText(character.examplesJson, "initial_message", "first_message", "first_mes", "greeting"),
            personality = firstText(character.personalityJson, "summary", "description"),
            tone = firstText(character.voiceJson, "tone", "summary", "style"),
            age = firstText(character.identityJson, "age"),
            birthday = firstText(character.identityJson, "birthday", "date_of_birth"),
            story = firstText(character.backstoryJson, "story", "history"),
            likes = firstText(character.personalityJson, "likes"),
            dislikes = firstText(character.personalityJson, "dislikes"),
            conversationalGoals = firstText(character.writingRulesJson, "conversational_goals", "goals"),
            conversationalExamples = firstText(character.examplesJson, "conversational_examples", "examples"),
            appearance = firstText(character.appearanceJson, "summary", "description"),
            knowledgeRelationships = firstText(character.knowledgeJson, "relationships"),
            knowledgeGeneral = firstText(character.knowledgeJson, "general")
        )

    fun write(existingExtensionFieldsJson: String?, fields: ManualCharacterFields): String {
        val extensions = parseObject(existingExtensionFieldsJson)?.toMutableMap() ?: mutableMapOf()
        extensions[EXTENSION_KEY] = linkedMapOf(
            "name" to fields.name,
            "short_backstory" to fields.shortBackstory,
            "initial_message" to fields.initialMessage,
            "system_instructions" to fields.systemInstructions,
            "personality" to fields.personality,
            "tone" to fields.tone,
            "age" to fields.age,
            "birthday" to fields.birthday,
            "story" to fields.story,
            "likes" to fields.likes,
            "dislikes" to fields.dislikes,
            "conversational_goals" to fields.conversationalGoals,
            "conversational_examples" to fields.conversationalExamples,
            "appearance" to fields.appearance,
            "knowledge_relationships" to fields.knowledgeRelationships,
            "knowledge_general" to fields.knowledgeGeneral
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

    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: ""

    private fun Any?.asEditableText(): String? = when (this) {
        is String -> this
        is Number, is Boolean -> toString()
        else -> null
    }
}
