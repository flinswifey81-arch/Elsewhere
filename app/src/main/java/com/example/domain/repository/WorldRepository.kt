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
    private val resourcesMapAdapter = jsonCodec.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val stringsAdapter = jsonCodec.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    /**
     * Complete, authored World Definition bundle import. All embedded resources
     * are validated and re-ID'd before a SINGLE atomic Room transaction.
     * Existing app Characters, Personas, Scenarios and Lorebooks never change.
     * Chats, attachments and actual playthrough history belong to the separately
     * encrypted World archive, NOT this shareable plaintext definition format.
     */
    suspend fun importDefinition(json: String): WorldEntity {
        val document = WorldDefinitionCodec.parse(json)
        val plan = WorldDefinitionBundleResources(database, jsonCodec).prepare(document)
        return coordinator.withMutation {
            database.withTransaction {
                dao.insertWorld(plan.world)
                plan.characters.forEach { database.characterDao().insertCharacter(it) }
                plan.personas.forEach { database.personaDao().insertPersona(it) }
                plan.scenarios.forEach { database.scenarioDao().save(it) }
                plan.lorebooks.forEach { book ->
                    database.lorebookDao().saveDefinition(book.lorebook, book.entries)
                }
                if (plan.cast.isNotEmpty()) dao.insertCast(plan.cast)
                if (plan.continuity.isNotEmpty()) dao.insertEvents(plan.continuity)
                if (plan.memberships.isNotEmpty()) dao.insertMemberships(plan.memberships)
                plan.world
            }
        }
    }

    /**
     * Shareable authored World Definition JSON. Include all supported linked
     * Character, Persona, Scenario and Lorebook definitions, never just IDs.
     * No chats, attached images, unsent drafts or roleplay memory are exported:
     * use the encrypted World archive for complete playthrough transfer.
     */
    suspend fun exportDefinition(worldId: String): String {
        val document = database.withTransaction {
            val world = requireWorld(worldId)
            val memberships = dao.getMemberships(worldId)
            require(memberships.none {
                it.resourceType in setOf(
                    WorldResourceType.CHAT.name, WorldResourceType.NPC_LIBRARY_BANK.name
                )
            }) { "Chat and NPC Bank links require an encrypted World archive." }
            val links = requireNotNull(worldLinksAdapter.fromJson(world.linksJson)) {
                "World role and relationship data is invalid."
            }
            require(links.castRoles.none { it.roleType == "NPC_LIBRARY" }) {
                "NPC Library role links require an encrypted World archive."
            }
            val cast = dao.getCast(worldId)
            val castIds = cast.map { it.worldCharacterId }.toSet()
            require(links.relationships.all {
                it.fromWorldCharacterId in castIds && it.toWorldCharacterId in castIds
            }) { "World relationship refers to a missing cast member." }
            val events = dao.getEvents(worldId).filter { it.status == "ACCEPTED" }
            require(events.all { it.sourceChatId == null }) {
                "Accepted event cites a chat; use encrypted World archive."
            }

            val scenarioIds = (
                memberships.filter { it.resourceType == WorldResourceType.SCENARIO.name }
                    .map { it.resourceId } +
                links.castRoles.filter { it.roleType == "SCENARIO_NPC" }
                    .mapNotNull { it.scenarioId } +
                cast.filter { it.provenanceKind.uppercase() == "SCENARIO" }
                    .mapNotNull { it.provenanceId }
            ).toSortedSet()
            val scenarios = scenarioIds.map { id ->
                val saved = requireNotNull(database.scenarioDao().get(id)) {
                    "Linked Scenario was deleted; remove the link before export."
                }
                WorldScenarioV1(id, ScenarioCodec.decode(saved.definitionJson))
            }
            val characterIds = (
                memberships.filter { it.resourceType == WorldResourceType.CHARACTER.name }
                    .map { it.resourceId } +
                links.castRoles.filter { it.roleType == "CHARACTER" }
                    .map { it.resourceId } +
                cast.filter { it.provenanceKind.uppercase() == "CHARACTER" }
                    .mapNotNull { it.provenanceId } +
                scenarios.flatMap { item ->
                    item.definition.npcs.mapNotNull { it.sourceCharacterId }
                }
            ).toSortedSet()
            val personaIds = (
                memberships.filter { it.resourceType == WorldResourceType.PERSONA.name }
                    .map { it.resourceId } +
                links.castRoles.filter { it.roleType == "PERSONA" }
                    .map { it.resourceId } +
                cast.filter { it.provenanceKind.uppercase() == "PERSONA" }
                    .mapNotNull { it.provenanceId }
            ).toSortedSet()
            val defaultLorebooks = requireNotNull(
                stringsAdapter.fromJson(world.defaultLorebookIdsJson)
            )
            val lorebookIds = (
                memberships.filter { it.resourceType == WorldResourceType.LOREBOOK.name }
                    .map { it.resourceId } + defaultLorebooks
            ).toSortedSet()
            val charRepo = CharacterRepository(database.characterDao(), jsonCodec)
            val personaRepo = PersonaRepository(database.personaDao(), jsonCodec)
            val loreRepo = LorebookRepository(database.lorebookDao(), jsonCodec)
            fun parsedObject(raw: String, label: String): Map<String, Any?> =
                requireNotNull(resourcesMapAdapter.fromJson(raw)) {
                    "Linked $label returned an empty definition."
                }
            val characterCards = characterIds.map { id ->
                parsedObject(charRepo.exportCharacterJson(id), "Character")
            }
            val personaCards = personaIds.map { id ->
                parsedObject(personaRepo.exportPersonaJson(id), "Persona")
            }
            val lorebookCards = lorebookIds.map { id ->
                parsedObject(loreRepo.exportJson(id), "Lorebook")
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
                    genreTags = requireNotNull(stringsAdapter.fromJson(world.genreTagsJson)),
                    defaultLorebookIds = defaultLorebooks
                ),
                resources = WorldResourcesV1(
                    characters = characterCards,
                    personas = personaCards,
                    scenarios = scenarios,
                    lorebooks = lorebookCards,
                    worldCast = cast.map { member ->
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
                            provenance = WorldProvenanceV1(
                                sourceKind = member.provenanceKind,
                                sourceId = member.provenanceId
                            ),
                            lockedFields = requireNotNull(
                                stringsAdapter.fromJson(member.lockedFieldsJson)
                            )
                        )
                    }
                ),
                links = links,
                continuity = events.map { event ->
                    WorldContinuityV1(
                        eventId = event.eventId, title = event.title,
                        facts = event.facts, status = "ACCEPTED",
                        relatedWorldCharacterIds = requireNotNull(
                            stringsAdapter.fromJson(event.relatedCastIdsJson)
                        )
                    )
                }
            )
        }
        // The same dependency validator used by import runs BEFORE returning
        // anything, so an export never claims to be portable when links are missing.
        WorldDefinitionBundleResources(database, jsonCodec).prepare(document)
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
        require(database.campaignDao().forWorld(worldId).isEmpty()) {
            "This World contains Campaigns. Delete or move their chapters explicitly first; chats will not be deleted."
        }
        dao.deleteWorld(worldId)
    }

    private suspend fun requireWorld(worldId: String): WorldEntity =
        requireNotNull(dao.getWorld(worldId)) { "World is no longer available." }
}
