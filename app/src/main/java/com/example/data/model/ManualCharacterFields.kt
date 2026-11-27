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
        val old = if ((stored["mapping_version"] as? Number)?.toInt() == 2) null else importedFields(character)
        fun value(key: String, fallback: String?) = stored.string(key).ifEmpty { fallback.orEmpty() }
        return ManualCharacterFields(
            name = value("name", old?.name),
            shortBackstory = value("short_backstory", old?.shortBackstory),
            initialMessage = stored.string("initial_message"),
            systemInstructions = value("system_instructions", old?.systemInstructions),
            personality = value("personality", old?.personality),
            tone = value("tone", old?.tone),
            age = value("age", old?.age),
            birthday = value("birthday", old?.birthday),
            story = value("story", old?.story),
            likes = value("likes", old?.likes),
            dislikes = value("dislikes", old?.dislikes),
            conversationalGoals = value("conversational_goals", old?.conversationalGoals),
            conversationalExamples = value("conversational_examples", old?.conversationalExamples),
            appearance = value("appearance", old?.appearance),
            knowledgeRelationships = value("knowledge_relationships", old?.knowledgeRelationships),
            knowledgeGeneral = value("knowledge_general", old?.knowledgeGeneral)
        )
    }

    fun toEditableFields(character: CharacterEntity): ManualCharacterFields =
        readStored(character) ?: importedFields(character)

    fun importedFields(character: CharacterEntity): ManualCharacterFields {
        val layers = ProfileEditorJson.rootLayers(character.extensionFieldsJson)
        fun section(json: String?, key: String) =
            ProfileEditorJson.decode(json) ?: ProfileEditorJson.nestedSection(layers, key)
        val backstory=section(character.backstoryJson,"backstory")
        val identity=section(character.identityJson,"identity")
        val personality=section(character.personalityJson,"personality")
        val appearance=section(character.appearanceJson,"appearance")
        val voice=section(character.voiceJson,"voice")
        val behavior=section(character.behaviorJson,"behavior")
        val writing=section(character.writingRulesJson,"writing_rules")
        val knowledge=section(character.knowledgeJson,"knowledge")
        val examples=section(character.examplesJson,"examples")
        fun root(vararg keys: String) = ProfileEditorJson.rootText(layers, *keys)
        return ManualCharacterFields(
            name = character.characterName,
            shortBackstory = ProfileEditorJson.field(backstory,"short_backstory","summary","description",plain=true)
                .ifEmpty { root("short_backstory","description") },
            // Greeting precedence comes from CharacterRepository, including alternate greeting metadata.
            systemInstructions = ProfileEditorJson.field(writing,"system_instructions","system_prompt","instructions")
                .ifEmpty { ProfileEditorJson.field(behavior,"system_instructions","system_prompt","instructions") }
                .ifEmpty { root("system_instructions","system_prompt") },
            personality = ProfileEditorJson.field(personality,"summary","description","personality","traits",plain=true)
                .ifEmpty { root("personality") },
            tone = ProfileEditorJson.field(voice,"tone","summary","style",plain=true).ifEmpty { root("tone") },
            age = ProfileEditorJson.field(identity,"age").ifEmpty { root("age") },
            birthday = ProfileEditorJson.field(identity,"birthday","date_of_birth").ifEmpty { root("birthday","date_of_birth") },
            story = ProfileEditorJson.field(backstory,"story","history").ifEmpty { root("story","scenario") },
            likes = ProfileEditorJson.field(personality,"likes").ifEmpty { root("likes") },
            dislikes = ProfileEditorJson.field(personality,"dislikes").ifEmpty { root("dislikes") },
            conversationalGoals = ProfileEditorJson.field(writing,"conversational_goals","goals")
                .ifEmpty { root("conversational_goals") },
            conversationalExamples = ProfileEditorJson.field(examples,"conversational_examples","examples","mes_example","dialogue_examples")
                .ifEmpty { root("conversational_examples","mes_example") },
            appearance = ProfileEditorJson.field(appearance,"summary","description",plain=true,structured=true),
            knowledgeRelationships = ProfileEditorJson.field(knowledge,"relationships").ifEmpty { root("knowledge_relationships") },
            knowledgeGeneral = ProfileEditorJson.field(knowledge,"general",plain=true).ifEmpty { root("knowledge_general") }
        )
    }

    /**
     * Remove mapped copies only from the prompt view. The editor overlay is now the
     * author source for those slots; structured siblings and the saved JSON remain intact.
     */
    fun importedRemainderForPrompt(character: CharacterEntity): CharacterEntity {
        val hasEdits=readStored(character)!=null
        fun remaining(json: String?, vararg keys: String, plain: Boolean=false, structured: Boolean=false) =
            if (hasEdits) ProfileEditorJson.withoutMapped(json,keys.toSet(),plain,structured) else json
        // Opening choices are metadata, never a hidden greeting in the model context.
        val effectiveExamples=ProfileEditorJson.examplesWithoutOpeningChoices(character.examplesJson, hasEdits)
        return character.copy(
            identityJson=remaining(character.identityJson,"age","birthday","date_of_birth"),
            appearanceJson=remaining(character.appearanceJson,"summary","description",plain=true,structured=true),
            personalityJson=remaining(character.personalityJson,"summary","description","personality","traits","likes","dislikes",plain=true),
            voiceJson=remaining(character.voiceJson,"tone","summary","style",plain=true),
            behaviorJson=remaining(character.behaviorJson,"system_instructions","system_prompt","instructions"),
            backstoryJson=remaining(character.backstoryJson,"short_backstory","summary","description","story","history",plain=true),
            knowledgeJson=remaining(character.knowledgeJson,"relationships","general",plain=true),
            writingRulesJson=remaining(character.writingRulesJson,"system_instructions","system_prompt","instructions","conversational_goals","goals"),
            examplesJson=effectiveExamples
        )
    }

    fun hasPrimaryGreetingEdit(character: CharacterEntity): Boolean =
        (parseObject(character.extensionFieldsJson)?.get(EXTENSION_KEY) as? Map<*, *>)
            ?.get("initial_message_edited") == true

    fun write(existingExtensionFieldsJson: String?, fields: ManualCharacterFields,
              primaryGreetingEdited: Boolean = false): String {
        val extensions = parseObject(existingExtensionFieldsJson)?.toMutableMap() ?: mutableMapOf()
        extensions[EXTENSION_KEY] = linkedMapOf(
            "mapping_version" to 2,
            "name" to fields.name,
            "short_backstory" to fields.shortBackstory,
            "initial_message" to fields.initialMessage,
            "initial_message_edited" to primaryGreetingEdited,
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

    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: ""

}
