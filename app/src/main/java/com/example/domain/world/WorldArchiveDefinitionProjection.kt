package com.example.domain.world

import com.example.data.model.ScenarioCodec
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Builds the authored-definition component from the SAME validated World history snapshot.
 * No ad-hoc JSON or second database read; the encrypted archive's definition and history
 * cannot accidentally come from different versions of a World.
 *
 * All playthrough records, lore contents, private notes and binaries stay in the encrypted
 * state/binary members, not the compact authored World definition.
 */
object WorldArchiveDefinitionProjection {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val linksAdapter = moshi.adapter(WorldLinksV1::class.java).failOnUnknown()
    private val stringsAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    private fun strings(json: String, label: String): List<String> =
        try {
            requireNotNull(stringsAdapter.fromJson(json)) { "Missing $label" }
        } catch (cause: Exception) {
            throw IllegalArgumentException("World archive cannot decode $label", cause)
        }

    fun fromState(state: WorldArchiveStateV1): WorldDefinitionV1 {
        WorldArchiveStateCodec.validate(state)
        val world = state.world
        val links = requireNotNull(linksAdapter.fromJson(world.linksJson)) {
            "World links are missing"
        }
        val definition = WorldDefinitionV1(
            format = WorldDefinitionCodec.FORMAT,
            schemaVersion = WorldDefinitionCodec.SCHEMA_VERSION,
            world = WorldMetadataV1(
                worldId = state.worldId,
                title = world.title,
                description = world.description,
                settingSummary = world.settingSummary,
                worldRules = world.worldRules,
                genreTags = strings(world.genreTagsJson, "World genre tags"),
                defaultLorebookIds = strings(world.defaultLorebookIdsJson, "World default Lorebooks")
            ),
            resources = WorldResourcesV1(
                worldCast = state.cast.map { cast ->
                    WorldCastV1(
                        worldCharacterId = cast.worldCharacterId,
                        name = cast.name,
                        aliases = strings(cast.aliasesJson, "World cast aliases"),
                        profile = mapOf(
                            "identity" to cast.identity,
                            "appearance" to cast.appearance,
                            "personality" to cast.personality,
                            "voice" to cast.voice,
                            "backstory" to cast.backstory,
                            "motivations" to cast.motivations,
                            "knowledge_limits" to cast.knowledgeLimits,
                            "role" to cast.role
                        ),
                        provenance = WorldProvenanceV1(cast.provenanceKind, cast.provenanceId),
                        lockedFields = strings(cast.lockedFieldsJson, "World cast locked fields")
                    )
                },
                scenarios = state.scenarios.map { scenario ->
                    WorldScenarioV1(scenario.scenarioId, ScenarioCodec.decode(scenario.definitionJson))
                }
            ),
            links = links,
            continuity = state.continuity.filter { it.status == "ACCEPTED" }.map { event ->
                WorldContinuityV1(
                    eventId = event.eventId, title = event.title,
                    facts = event.facts, status = "ACCEPTED",
                    sourceChatId = event.sourceChatId,
                    relatedWorldCharacterIds = strings(event.relatedCastIdsJson,
                        "World continuity cast")
                )
            }
        )
        WorldDefinitionCodec.validate(definition)
        return definition
    }
}
