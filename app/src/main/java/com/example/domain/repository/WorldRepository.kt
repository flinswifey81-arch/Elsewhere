package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.*
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
