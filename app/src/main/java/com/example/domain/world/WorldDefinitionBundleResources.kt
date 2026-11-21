package com.example.domain.world

import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.LorebookRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.UUID

/** Fully validated plan; NO Room writes until all World dependencies resolve. */
internal data class WorldDefinitionBundlePlan(
    val world: WorldEntity,
    val characters: List<CharacterEntity>,
    val personas: List<PersonaEntity>,
    val scenarios: List<ScenarioEntity>,
    val lorebooks: List<LorebookWithEntries>,
    val cast: List<WorldCastEntity>,
    val continuity: List<WorldContinuityEventEntity>,
    val memberships: List<WorldMembershipEntity>
)

internal class WorldDefinitionBundleResources(
    database: AppDatabase,
    moshi: Moshi
) {
    private val any = moshi.adapter(Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val linksAdapter = moshi.adapter(WorldLinksV1::class.java)
    private val stringListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )
    private val lorebookCodec = LorebookRepository(database.lorebookDao(), moshi)
    private fun newId() = UUID.randomUUID().toString()
    private fun encoded(value: Any?): String? = value?.let(any::toJson)
    private fun required(m: Map<String, Any?>, key: String): String =
        (m[key] as? String)?.takeIf { it.isNotBlank() && it.length <= 500 }
            ?: throw IllegalArgumentException("Embedded resource requires $key.")
    private fun aliases(m: Map<String, Any?>): List<String> =
        (m["aliases"] as? List<*>)?.map { value ->
            require(value is String && value.isNotBlank() && value.length <= 300) {
                "Invalid resource alias."
            }
            value
        } ?: emptyList()
    private fun <T> unique(values: List<T>, label: String, id: (T) -> String) {
        require(values.map(id).distinct().size == values.size) {
            "World bundle repeats an embedded $label ID."
        }
    }
    private fun sourceId(value: String, label: String): String {
        require(value.length in 1..200 && value.all {
            (it.code < 128 && it.isLetterOrDigit()) || it in "._:-"
        }) { "$label ID is invalid." }
        return value
    }

    private fun parseCharacter(m: Map<String, Any?>): CharacterEntity {
        val nested = m["data"] as? Map<*, *>
        val id = (m["character_id"] as? String)
            ?: (nested?.get("character_id") as? String)
            ?: throw IllegalArgumentException("Embedded Character needs character_id.")
        sourceId(id, "Character")
        val name = (m["display_name"] as? String) ?: (m["name"] as? String)
            ?: (nested?.get("display_name") as? String)
            ?: (nested?.get("name") as? String)
            ?: throw IllegalArgumentException("Embedded Character needs a name.")
        require(name.isNotBlank() && name.length <= 300)
        val schema = (m["schema_version"] as? Number)?.toInt() ?: 1
        require(schema in 1..1000) { "Unsupported Character schema version." }
        val known = setOf("schema_version", "character_id", "display_name",
            "character_name", "name", "aliases", "identity", "appearance", "personality",
            "voice", "behavior", "backstory", "knowledge", "world_context",
            "writing_rules", "examples", "author_notes")
        return CharacterEntity(
            characterId = id, displayName = name,
            characterName = (m["character_name"] as? String) ?: name,
            aliases = aliases(m), importSchemaVersion = schema,
            identityJson = encoded(m["identity"]),
            appearanceJson = encoded(m["appearance"]),
            personalityJson = encoded(m["personality"]),
            voiceJson = encoded(m["voice"]),
            behaviorJson = encoded(m["behavior"]),
            backstoryJson = encoded(m["backstory"]),
            knowledgeJson = encoded(m["knowledge"]),
            worldContextJson = encoded(m["world_context"]),
            writingRulesJson = encoded(m["writing_rules"]),
            examplesJson = encoded(m["examples"]),
            authorNotesJson = encoded(m["author_notes"]),
            extensionFieldsJson = m.filterKeys { it !in known }
                .takeIf { it.isNotEmpty() }?.let(mapAdapter::toJson)
        )
    }

    private fun parsePersona(m: Map<String, Any?>): PersonaEntity {
        val id = sourceId(required(m, "persona_id"), "Persona")
        val schema = (m["schema_version"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Embedded Persona needs schema_version.")
        require(schema in 1..1000) { "Unsupported Persona schema version." }
        val known = setOf("schema_version", "persona_id", "display_name",
            "persona_name", "aliases", "identity", "appearance", "personality",
            "roleplay_profile", "background", "world_context", "private_notes")
        return PersonaEntity(
            personaId = id, displayName = required(m, "display_name"),
            personaName = required(m, "persona_name"),
            aliases = aliases(m), importSchemaVersion = schema,
            identityJson = encoded(m["identity"]),
            appearanceJson = encoded(m["appearance"]),
            personalityJson = encoded(m["personality"]),
            roleplayProfileJson = encoded(m["roleplay_profile"]),
            backgroundJson = encoded(m["background"]),
            worldContextJson = encoded(m["world_context"]),
            privateNotesJson = encoded(m["private_notes"]),
            extensionFieldsJson = m.filterKeys { it !in known }
                .takeIf { it.isNotEmpty() }?.let(mapAdapter::toJson)
        )
    }

    fun prepare(doc: WorldDefinitionV1): WorldDefinitionBundlePlan {
        WorldDefinitionCodec.validate(doc)
        require(doc.continuity.none { it.sourceChatId != null }) {
            "Chat-linked continuity requires an encrypted World archive."
        }
        val chars = doc.resources.characters.map(::parseCharacter)
        val personas = doc.resources.personas.map(::parsePersona)
        val scenarios = doc.resources.scenarios
        val books = doc.resources.lorebooks.map {
            lorebookCodec.parseLorebookJson(mapAdapter.toJson(it))
        }
        unique(chars, "Character", CharacterEntity::characterId)
        unique(personas, "Persona", PersonaEntity::personaId)
        unique(scenarios, "Scenario", WorldScenarioV1::scenarioId)
        unique(books, "Lorebook") { it.lorebook.lorebookId }
        unique(books.flatMap { it.entries }, "Lorebook Entry", LorebookEntryEntity::entryId)

        val charIds = chars.map { it.characterId }.toSet()
        val personaIds = personas.map { it.personaId }.toSet()
        val scenarioIds = scenarios.map { it.scenarioId }.toSet()
        val bookIds = books.map { it.lorebook.lorebookId }.toSet()
        val castIds = doc.resources.worldCast.map { it.worldCharacterId }.toSet()
        require(doc.world.defaultLorebookIds.all { it in bookIds }) {
            "Default Lorebooks must be embedded in the World bundle."
        }
        require(books.all {
            it.lorebook.storyId == null || it.lorebook.storyId == doc.world.worldId
        }) { "Chat-scoped Lorebooks require an encrypted World archive." }
        scenarios.forEach { scenario ->
            require(scenario.definition.npcs.all {
                it.sourceCharacterId == null || it.sourceCharacterId in charIds
            }) { "Scenario NPC linked Character must be embedded in the World bundle." }
        }
        doc.links.castRoles.forEach { role ->
            require(when (role.roleType) {
                "CHARACTER" -> role.resourceId in charIds
                "PERSONA" -> role.resourceId in personaIds
                "SCENARIO_NPC" -> scenarios.find { it.scenarioId == role.scenarioId }
                    ?.definition?.npcs?.any { it.npcId == role.resourceId } == true
                else -> false
            }) {
                "World role type " + role.roleType +
                    " requires an embedded resource; use an encrypted World archive for NPC banks."
            }
        }
        doc.resources.worldCast.forEach { member ->
            val provenance = member.provenance
            val id = provenance?.sourceId
            if (id != null) {
                require(when (provenance.sourceKind.uppercase()) {
                    "CHARACTER" -> id in charIds
                    "PERSONA" -> id in personaIds
                    "SCENARIO" -> id in scenarioIds
                    else -> false
                }) { "World Cast provenance refers to an unembedded resource." }
            }
        }

        fun idMap(ids: Set<String>) = ids.associateWith { newId() }
        val newWorldId = newId()
        val characterIds = idMap(charIds)
        val personaIdsNew = idMap(personaIds)
        val scenarioIdsNew = idMap(scenarioIds)
        val bookIdsNew = idMap(bookIds)
        val castIdsNew = idMap(castIds)
        val eventIds = idMap(doc.continuity.map { it.eventId }.toSet())
        val npcIds = scenarios.associate { scenario ->
            scenario.scenarioId to idMap(scenario.definition.npcs.map { it.npcId }.toSet())
        }
        fun lookup(m: Map<String, String>, source: String): String =
            requireNotNull(m[source]) { "Unresolved World resource ID: $source" }
        val newLinks = doc.links.copy(
            castRoles = doc.links.castRoles.map { role ->
                role.copy(
                    worldCharacterId = lookup(castIdsNew, role.worldCharacterId),
                    resourceId = when (role.roleType) {
                        "CHARACTER" -> lookup(characterIds, role.resourceId)
                        "PERSONA" -> lookup(personaIdsNew, role.resourceId)
                        "SCENARIO_NPC" -> lookup(
                            requireNotNull(npcIds[role.scenarioId]), role.resourceId
                        )
                        else -> error("Unportable World role")
                    },
                    scenarioId = role.scenarioId?.let { lookup(scenarioIdsNew, it) }
                )
            },
            relationships = doc.links.relationships.map {
                it.copy(
                    fromWorldCharacterId = lookup(castIdsNew, it.fromWorldCharacterId),
                    toWorldCharacterId = lookup(castIdsNew, it.toWorldCharacterId)
                )
            }
        )
        val world = WorldEntity(
            worldId = newWorldId,
            title = doc.world.title,
            description = doc.world.description,
            settingSummary = doc.world.settingSummary,
            worldRules = doc.world.worldRules,
            genreTagsJson = stringListAdapter.toJson(doc.world.genreTags),
            linksJson = linksAdapter.toJson(newLinks),
            defaultLorebookIdsJson = stringListAdapter.toJson(
                doc.world.defaultLorebookIds.map { lookup(bookIdsNew, it) }
            )
        )
        val worldCast = doc.resources.worldCast.map { member ->
            WorldCastEntity(
                worldCharacterId = lookup(castIdsNew, member.worldCharacterId),
                worldId = newWorldId, name = member.name,
                aliasesJson = stringListAdapter.toJson(member.aliases),
                identity = member.profile["identity"].orEmpty(),
                appearance = member.profile["appearance"].orEmpty(),
                personality = member.profile["personality"].orEmpty(),
                voice = member.profile["voice"].orEmpty(),
                backstory = member.profile["backstory"].orEmpty(),
                motivations = member.profile["motivations"].orEmpty(),
                knowledgeLimits = member.profile["knowledge_limits"].orEmpty(),
                role = member.profile["role"].orEmpty(),
                provenanceKind = member.provenance?.sourceKind ?: "manual",
                provenanceId = member.provenance?.sourceId?.let { id ->
                    when (member.provenance?.sourceKind?.uppercase()) {
                        "CHARACTER" -> lookup(characterIds, id)
                        "PERSONA" -> lookup(personaIdsNew, id)
                        "SCENARIO" -> lookup(scenarioIdsNew, id)
                        else -> error("Unportable provenance")
                    }
                },
                lockedFieldsJson = stringListAdapter.toJson(member.lockedFields)
            )
        }
        val events = doc.continuity.map { item ->
            WorldContinuityEventEntity(
                eventId = lookup(eventIds, item.eventId),
                worldId = newWorldId,
                title = item.title, facts = item.facts, status = item.status,
                sourceChatId = null,
                relatedCastIdsJson = stringListAdapter.toJson(
                    item.relatedWorldCharacterIds.map { lookup(castIdsNew, it) }
                )
            )
        }
        val remappedScenarios = scenarios.map { item ->
            val npcMapping = requireNotNull(npcIds[item.scenarioId])
            ScenarioEntity(
                scenarioId = lookup(scenarioIdsNew, item.scenarioId),
                definitionJson = ScenarioCodec.encode(item.definition.copy(
                    npcs = item.definition.npcs.map { npc ->
                        npc.copy(
                            npcId = lookup(npcMapping, npc.npcId),
                            sourceCharacterId = npc.sourceCharacterId?.let {
                                lookup(characterIds, it)
                            }
                        )
                    }
                ))
            )
        }
        val remappedLorebooks = books.map { book ->
            val id = lookup(bookIdsNew, book.lorebook.lorebookId)
            LorebookWithEntries(
                lorebook = book.lorebook.copy(
                    lorebookId = id,
                    storyId = book.lorebook.storyId?.let { newWorldId }
                ),
                entries = book.entries.map {
                    it.copy(entryId = newId(), lorebookId = id)
                }
            )
        }
        val members =
            characterIds.values.map {
                WorldMembershipEntity(newWorldId, WorldResourceType.CHARACTER.name, it)
            } + personaIdsNew.values.map {
                WorldMembershipEntity(newWorldId, WorldResourceType.PERSONA.name, it)
            } + scenarioIdsNew.values.map {
                WorldMembershipEntity(newWorldId, WorldResourceType.SCENARIO.name, it)
            } + bookIdsNew.values.map {
                WorldMembershipEntity(newWorldId, WorldResourceType.LOREBOOK.name, it)
            }
        return WorldDefinitionBundlePlan(
            world = world,
            characters = chars.map {
                it.copy(characterId = lookup(characterIds, it.characterId))
            },
            personas = personas.map {
                it.copy(personaId = lookup(personaIdsNew, it.personaId))
            },
            scenarios = remappedScenarios,
            lorebooks = remappedLorebooks,
            cast = worldCast,
            continuity = events,
            memberships = members
        )
    }
}
