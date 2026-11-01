package com.example.domain.world

import com.example.data.model.ScenarioDefinition
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * File contract for an authored World definition. Parsing does not write to Room.
 * This is NOT the encrypted complete-World archive: no playthrough transcripts or
 * attachments are represented here.
 *
 * Import/export persistence and collision planning are intentionally separate
 * operations so a future importer can validate *all* dependencies before a write.
 */
@JsonClass(generateAdapter = true)
data class WorldDefinitionV1(
    val format: String,
    @Json(name = "schema_version") val schemaVersion: Int,
    val world: WorldMetadataV1,
    val resources: WorldResourcesV1,
    val links: WorldLinksV1,
    val continuity: List<WorldContinuityV1> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorldMetadataV1(
    @Json(name = "world_id") val worldId: String,
    val title: String,
    val description: String = "",
    @Json(name = "setting_summary") val settingSummary: String,
    @Json(name = "world_rules") val worldRules: String = "",
    @Json(name = "genre_tags") val genreTags: List<String> = emptyList(),
    @Json(name = "default_lorebook_ids") val defaultLorebookIds: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorldResourcesV1(
    // These embedded artifacts require their own existing Elsewhere validation
    // before persistence. A syntactically valid World JSON is not yet importable.
    val lorebooks: List<Map<String, Any?>> = emptyList(),
    @Json(name = "world_cast") val worldCast: List<WorldCastV1> = emptyList(),
    val characters: List<Map<String, Any?>> = emptyList(),
    val personas: List<Map<String, Any?>> = emptyList(),
    val scenarios: List<WorldScenarioV1> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorldScenarioV1(
    @Json(name = "scenario_id") val scenarioId: String,
    val definition: ScenarioDefinition
)

@JsonClass(generateAdapter = true)
data class WorldCastV1(
    @Json(name = "world_character_id") val worldCharacterId: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val profile: Map<String, String> = emptyMap(),
    val provenance: WorldProvenanceV1? = null,
    @Json(name = "locked_fields") val lockedFields: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorldProvenanceV1(
    @Json(name = "source_kind") val sourceKind: String,
    @Json(name = "source_id") val sourceId: String? = null
)

@JsonClass(generateAdapter = true)
data class WorldLinksV1(
    @Json(name = "cast_roles") val castRoles: List<WorldCastRoleV1> = emptyList(),
    val relationships: List<WorldRelationshipV1> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorldCastRoleV1(
    @Json(name = "world_character_id") val worldCharacterId: String,
    @Json(name = "role_type") val roleType: String,
    @Json(name = "resource_id") val resourceId: String,
    @Json(name = "scenario_id") val scenarioId: String? = null
)

@JsonClass(generateAdapter = true)
data class WorldRelationshipV1(
    @Json(name = "from_world_character_id") val fromWorldCharacterId: String,
    @Json(name = "to_world_character_id") val toWorldCharacterId: String,
    val description: String,
    @Json(name = "knowledge_limit") val knowledgeLimit: String = ""
)

@JsonClass(generateAdapter = true)
data class WorldContinuityV1(
    @Json(name = "event_id") val eventId: String,
    val title: String,
    val facts: String,
    val status: String,
    @Json(name = "source_chat_id") val sourceChatId: String? = null,
    @Json(name = "related_world_character_ids") val relatedWorldCharacterIds: List<String> = emptyList()
)

/** Pure parsing and serialization: no database writes and no "partial import". */
object WorldDefinitionCodec {
    const val FORMAT = "elsewhere_world_definition"
    const val SCHEMA_VERSION = 1
    const val MAX_JSON_CHARS = 2_000_000
    private const val MAX_CAST = 2_000
    private const val MAX_EVENTS = 5_000
    private const val MAX_RESOURCES = 2_000
    private const val MAX_PROSE = 200_000
    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(WorldDefinitionV1::class.java)
        .failOnUnknown()
        .indent("  ")

    fun parse(json: String): WorldDefinitionV1 {
        require(json.length <= MAX_JSON_CHARS) { "World JSON exceeds size limit." }
        val document = try {
            adapter.fromJson(json)
        } catch (failure: Exception) {
            throw IllegalArgumentException("World JSON is malformed or contains unknown fields.", failure)
        } ?: throw IllegalArgumentException("World JSON is empty.")
        validate(document)
        return document
    }

    fun encode(document: WorldDefinitionV1): String {
        validate(document)
        val json = adapter.toJson(document)
        require(json.length <= MAX_JSON_CHARS) { "World JSON exceeds size limit." }
        return json
    }

    fun validate(document: WorldDefinitionV1) {
        require(document.format == FORMAT && document.schemaVersion == SCHEMA_VERSION) {
            "Unsupported World definition format or version."
        }
        requireId(document.world.worldId, "World ID")
        require(document.world.title.isNotBlank() && document.world.title.length <= 300) {
            "World needs a title (up to 300 characters)."
        }
        require(document.world.description.length <= MAX_PROSE &&
            document.world.settingSummary.length <= MAX_PROSE &&
            document.world.worldRules.length <= MAX_PROSE) { "World text exceeds size limit." }
        require(document.world.genreTags.size <= 1000 &&
            document.world.defaultLorebookIds.size <= MAX_RESOURCES) { "Too many World tags or default Lorebooks." }
        require(document.resources.worldCast.size <= MAX_CAST) { "Too many World cast entries." }
        require(document.resources.scenarios.size <= MAX_RESOURCES &&
            document.resources.lorebooks.size <= MAX_RESOURCES &&
            document.resources.characters.size <= MAX_RESOURCES &&
            document.resources.personas.size <= MAX_RESOURCES) { "Too many embedded World resources." }
        require(document.continuity.size <= MAX_EVENTS) { "Too many continuity events." }

        val castIds = document.resources.worldCast.map { character ->
            requireId(character.worldCharacterId, "World character ID")
            require(character.name.isNotBlank() && character.name.length <= 300) { "World character needs a name." }
            require(character.aliases.size <= 1000 &&
                character.aliases.all { it.isNotBlank() && it.length <= 300 }) { "Invalid character aliases." }
            require(character.profile.size <= 40 &&
                character.profile.values.all { it.length <= MAX_PROSE }) { "Character profile exceeds limits." }
            character.worldCharacterId
        }
        require(castIds.toSet().size == castIds.size) { "Duplicate World character ID." }
        val castSet = castIds.toSet()

        val scenarioIds = document.resources.scenarios.map {
            requireId(it.scenarioId, "Scenario ID")
            it.definition.validate()
            it.scenarioId
        }
        require(scenarioIds.toSet().size == scenarioIds.size) { "Duplicate Scenario ID." }
        val scenarioSet = scenarioIds.toSet()
        document.links.relationships.forEach { relation ->
            require(relation.fromWorldCharacterId in castSet && relation.toWorldCharacterId in castSet) {
                "World relationship refers to an unknown character."
            }
            require(relation.description.isNotBlank() && relation.description.length <= MAX_PROSE) {
                "World relationship needs a description."
            }
        }
        document.links.castRoles.forEach { role ->
            require(role.worldCharacterId in castSet) { "World role refers to an unknown character." }
            requireId(role.resourceId, "Linked resource ID")
            require(role.roleType in setOf("CHARACTER", "PERSONA", "SCENARIO_NPC", "NPC_LIBRARY")) {
                "Unknown World role type."
            }
            if (role.roleType == "SCENARIO_NPC") require(role.scenarioId in scenarioSet) {
                "Scenario role refers to a missing Scenario."
            }
        }
        val eventIds = document.continuity.map { event ->
            requireId(event.eventId, "Event ID")
            require(event.title.isNotBlank() && event.title.length <= 300 &&
                event.facts.isNotBlank() && event.facts.length <= MAX_PROSE) {
                "World continuity event needs a title and facts."
            }
            require(event.status == "ACCEPTED") { "World definition may only contain accepted continuity." }
            require(event.relatedWorldCharacterIds.all { it in castSet }) {
                "Continuity event refers to an unknown character."
            }
            event.eventId
        }
        require(eventIds.toSet().size == eventIds.size) { "Duplicate continuity event ID." }
        document.world.defaultLorebookIds.forEach { requireId(it, "Default Lorebook ID") }
        require(document.world.defaultLorebookIds.distinct().size == document.world.defaultLorebookIds.size) {
            "Duplicate default Lorebook ID."
        }
    }

    private fun requireId(value: String, label: String) {
        require(value.length in 1..200 && value.all {
            it.isLetterOrDigit() && it.code < 128 || it in "._:-"
        }) { "$label has an invalid identifier." }
    }
}
