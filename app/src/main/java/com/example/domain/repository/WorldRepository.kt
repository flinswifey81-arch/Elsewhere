package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.world.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

/**
 * World records only reference pre-existing app resources. No NPC or LLM generation here.
 * All changes share the app's backup/restore mutation boundary.
 */
class WorldRepository(
    private val database: AppDatabase,
    private val coordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    private val dao get() = database.worldDao()

    private val jsonCodec = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val worldLinksAdapter = jsonCodec.adapter(WorldLinksV1::class.java)
    private val stringsAdapter = jsonCodec.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    /**
     * Portable World **definition** import. Safe first phase: only World metadata,
     * reviewed cast and accepted continuity. Never silently drops linked resources.
     * This does not import playthrough history. Existing resources/worlds are untouched.
     */
    suspend fun importDefinition(json: String): WorldEntity {
        val document = WorldDefinitionCodec.parse(json)
        require(document.resources.lorebooks.isEmpty() &&
            document.resources.characters.isEmpty() &&
            document.resources.personas.isEmpty() &&
            document.resources.scenarios.isEmpty() &&
            document.links.castRoles.isEmpty() &&
            document.world.defaultLorebookIds.isEmpty()
        ) { "Embedded resources and role links require the upcoming full World importer." }
        require(document.continuity.all { it.sourceChatId == null }) {
            "World definition cannot import references to external chats."
        }
        val supportedProfileKeys = setOf("identity", "appearance", "personality", "voice",
            "backstory", "motivations", "knowledge_limits", "role")
        require(document.resources.worldCast.all { it.profile.keys.all(supportedProfileKeys::contains) }) {
            "A World character contains unsupported profile fields."
        }

        return coordinator.withMutation {
            database.withTransaction {
                val castIds = document.resources.worldCast.associate {
                    it.worldCharacterId to UUID.randomUUID().toString()
                }
                val remappedLinks = document.links.copy(
                    relationships = document.links.relationships.map { relation ->
                        relation.copy(
                            fromWorldCharacterId = requireNotNull(castIds[relation.fromWorldCharacterId]),
                            toWorldCharacterId = requireNotNull(castIds[relation.toWorldCharacterId])
                        )
                    }
                )
                val world = WorldEntity(
                    title = document.world.title,
                    description = document.world.description,
                    settingSummary = document.world.settingSummary,
                    worldRules = document.world.worldRules,
                    genreTagsJson = stringsAdapter.toJson(document.world.genreTags),
                    defaultLorebookIdsJson = stringsAdapter.toJson(emptyList()),
                    linksJson = worldLinksAdapter.toJson(remappedLinks)
                )
                dao.insertWorld(world)
                for (person in document.resources.worldCast) {
                    dao.insertCastMember(WorldCastEntity(
                        worldCharacterId = requireNotNull(castIds[person.worldCharacterId]),
                        worldId = world.worldId,
                        name = person.name,
                        aliasesJson = stringsAdapter.toJson(person.aliases),
                        identity = person.profile["identity"].orEmpty(),
                        appearance = person.profile["appearance"].orEmpty(),
                        personality = person.profile["personality"].orEmpty(),
                        voice = person.profile["voice"].orEmpty(),
                        backstory = person.profile["backstory"].orEmpty(),
                        motivations = person.profile["motivations"].orEmpty(),
                        knowledgeLimits = person.profile["knowledge_limits"].orEmpty(),
                        role = person.profile["role"].orEmpty(),
                        provenanceKind = "import",
                        lockedFieldsJson = stringsAdapter.toJson(person.lockedFields)
                    ))
                }
                for (event in document.continuity) {
                    dao.insertEvent(WorldContinuityEventEntity(
                        worldId = world.worldId,
                        title = event.title,
                        facts = event.facts,
                        status = "ACCEPTED",
                        relatedCastIdsJson = stringsAdapter.toJson(
                            event.relatedWorldCharacterIds.map { requireNotNull(castIds[it]) }
                        )
                    ))
                }
                world
            }
        }
    }

    /**
     * World definition export preserves the currently representable setting, cast,
     * relationships and accepted events. Refuse nonempty linked resources rather
     * than falsely claiming to export their data, episodes or transcripts.
     */
    suspend fun exportDefinition(worldId: String): String {
        val document = database.withTransaction {
            val world = requireWorld(worldId)
            require(dao.getMemberships(worldId).isEmpty()) {
                "World has linked resources; use complete World archive when available."
            }
            val links = requireNotNull(worldLinksAdapter.fromJson(world.linksJson)) {
                "World relationship data is invalid."
            }
            require(links.castRoles.isEmpty() &&
                requireNotNull(stringsAdapter.fromJson(world.defaultLorebookIdsJson)).isEmpty()) {
                "World role/default Lorebook links require complete export."
            }
            val cast = dao.getCast(worldId)
            val castIds = cast.map { it.worldCharacterId }.toSet()
            require(links.relationships.all {
                it.fromWorldCharacterId in castIds && it.toWorldCharacterId in castIds
            }) { "World relationship refers to a missing cast member." }

            val events = dao.getEvents(worldId).filter { it.status == "ACCEPTED" }
            require(events.all { it.sourceChatId == null }) {
                "Accepted event references a chat; use complete World archive."
            }
            WorldDefinitionV1(
                format = WorldDefinitionCodec.FORMAT,
                schemaVersion = WorldDefinitionCodec.SCHEMA_VERSION,
                world = WorldMetadataV1(
                    worldId = world.worldId,
                    title = world.title,
                    description = world.description,
                    settingSummary = world.settingSummary,
                    worldRules = world.worldRules,
                    genreTags = requireNotNull(stringsAdapter.fromJson(world.genreTagsJson))
                ),
                resources = WorldResourcesV1(worldCast = cast.map { member ->
                    WorldCastV1(
                        worldCharacterId = member.worldCharacterId,
                        name = member.name,
                        aliases = requireNotNull(stringsAdapter.fromJson(member.aliasesJson)),
                        profile = mapOf(
                            "identity" to member.identity,
                            "appearance" to member.appearance,
                            "personality" to member.personality,
                            "voice" to member.voice,
                            "backstory" to member.backstory,
                            "motivations" to member.motivations,
                            "knowledge_limits" to member.knowledgeLimits,
                            "role" to member.role
                        ),
                        provenance = WorldProvenanceV1(sourceKind = "import"),
                        lockedFields = requireNotNull(stringsAdapter.fromJson(member.lockedFieldsJson))
                    )
                }),
                links = links,
                continuity = events.map { event ->
                    WorldContinuityV1(
                        eventId = event.eventId,
                        title = event.title,
                        facts = event.facts,
                        status = "ACCEPTED",
                        relatedWorldCharacterIds = requireNotNull(
                            stringsAdapter.fromJson(event.relatedCastIdsJson)
                        )
                    )
                }
            )
        }
        return WorldDefinitionCodec.encode(document)
    }

    fun observeWorlds() = dao.observeWorlds()
    suspend fun getWorld(worldId: String) = dao.getWorld(worldId)
    suspend fun getMemberships(worldId: String) = dao.getMemberships(worldId)
    suspend fun getCast(worldId: String) = dao.getCast(worldId)
    suspend fun getEvents(worldId: String) = dao.getEvents(worldId)

    suspend fun createWorld(title: String, settingSummary: String = ""): WorldEntity =
        coordinator.withMutation {
            val name = title.trim()
            require(name.isNotEmpty() && name.length <= 300) { "Give the World a title (up to 300 characters)." }
            val world = WorldEntity(worldId = UUID.randomUUID().toString(), title = name, settingSummary = settingSummary)
            dao.insertWorld(world)
            world
        }

    suspend fun updateWorld(world: WorldEntity) = coordinator.withMutation {
        require(world.title.isNotBlank() && world.title.length <= 300) { "Give the World a valid title." }
        require(dao.updateWorld(world.copy(updatedAt = System.currentTimeMillis())) == 1) {
            "World is no longer available."
        }
    }

    /** Links a resource; does not change the underlying Character, Persona, Scenario, etc. */
    suspend fun attach(worldId: String, type: WorldResourceType, resourceId: String) =
        coordinator.withMutation {
            require(resourceId.isNotBlank()) { "A resource identifier is required." }
            database.withTransaction {
                requireWorld(worldId)
                val exists = when (type) {
                    WorldResourceType.LOREBOOK -> dao.hasLorebook(resourceId)
                    WorldResourceType.CHARACTER -> dao.hasCharacter(resourceId)
                    WorldResourceType.PERSONA -> dao.hasPersona(resourceId)
                    WorldResourceType.SCENARIO -> dao.hasScenario(resourceId)
                    WorldResourceType.CHAT -> dao.hasChat(resourceId)
                    WorldResourceType.NPC_LIBRARY_BANK -> dao.hasNpcLibraryBank(resourceId)
                }
                require(exists) { "Cannot attach a missing ${type.name.lowercase()}." }
                dao.insertMembership(WorldMembershipEntity(worldId, type.name, resourceId))
            }
        }

    suspend fun detach(worldId: String, type: WorldResourceType, resourceId: String) =
        coordinator.withMutation {
            dao.deleteMembership(worldId, type.name, resourceId)
        }

    suspend fun createCastMember(worldId: String, name: String, identity: String = ""): WorldCastEntity =
        coordinator.withMutation {
            require(name.isNotBlank() && name.length <= 300) { "Give the World character a name." }
            database.withTransaction {
                requireWorld(worldId)
                WorldCastEntity(worldId = worldId, name = name.trim(), identity = identity)
                    .also { dao.insertCastMember(it) }
            }
        }

    /** Create a complete reviewed profile in one transaction, not a placeholder followed by an update. */
    suspend fun createCastMember(member: WorldCastEntity): WorldCastEntity = coordinator.withMutation {
        require(member.name.isNotBlank() && member.name.length <= 300) { "Give the World character a name." }
        database.withTransaction {
            requireWorld(member.worldId)
            val created = member.copy(name = member.name.trim())
            dao.insertCastMember(created)
            created
        }
    }

    suspend fun updateCastMember(member: WorldCastEntity) = coordinator.withMutation {
        require(member.name.isNotBlank()) { "Give the World character a name." }
        database.withTransaction {
            val existing = requireNotNull(dao.getCastMember(member.worldCharacterId)) { "Cast member not found." }
            require(existing.worldId == member.worldId) { "A cast member cannot silently move Worlds." }
            require(dao.updateCastMember(member.copy(updatedAt = System.currentTimeMillis())) == 1)
        }
    }

    suspend fun draftEvent(worldId: String, title: String, facts: String): WorldContinuityEventEntity =
        coordinator.withMutation {
            require(title.isNotBlank() && facts.isNotBlank()) { "An event needs a title and facts." }
            database.withTransaction {
                requireWorld(worldId)
                WorldContinuityEventEntity(worldId = worldId, title = title.trim(), facts = facts)
                    .also { dao.insertEvent(it) }
            }
        }

    /** Edits are permitted only while a continuity event is still a draft. */
    suspend fun updateDraftEvent(event: WorldContinuityEventEntity) = coordinator.withMutation {
        require(event.title.isNotBlank() && event.facts.isNotBlank()) { "An event needs a title and facts." }
        database.withTransaction {
            val existing = requireNotNull(dao.getEvent(event.eventId)) { "Continuity event not found." }
            require(existing.worldId == event.worldId) { "An event cannot move Worlds." }
            require(existing.status == "DRAFT" && event.status == "DRAFT") {
                "Accepted continuity cannot be rewritten as a draft."
            }
            require(dao.updateEvent(event.copy(updatedAt = System.currentTimeMillis())) == 1)
        }
    }

    /** Only an explicit user action should invoke this operation. */
    suspend fun acceptEvent(worldId: String, eventId: String) = coordinator.withMutation {
        database.withTransaction {
            val event = requireNotNull(dao.getEvent(eventId)) { "Continuity event not found." }
            require(event.worldId == worldId) { "Event belongs to a different World." }
            require(event.status == "DRAFT") { "Only draft events can be accepted." }
            require(dao.updateEvent(event.copy(status = "ACCEPTED", updatedAt = System.currentTimeMillis())) == 1)
        }
    }

    /** World-only deletion: linked chat transcripts and source records survive. */
    suspend fun deleteWorld(worldId: String) = coordinator.withMutation {
        dao.deleteWorld(worldId)
    }

    private suspend fun requireWorld(worldId: String): WorldEntity =
        requireNotNull(dao.getWorld(worldId)) { "World is no longer available." }
}
