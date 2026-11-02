package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.WorldResourceType
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.WorldRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorldRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var worlds: WorldRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        worlds = WorldRepository(database, AppDataMaintenanceCoordinator())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun worldsCanLinkAnExistingChatWithoutTakingOwnershipOfItsHistory() = runBlocking {
        val chat = ChatEntity(chatId = "chat-original", displayName = "Piper and her Alphas", chatType = ChatType.SOLO)
        database.chatDao().insertChat(chat)
        val world = worlds.createWorld("Law & Disorder: Mondstadt")
        worlds.attach(world.worldId, WorldResourceType.CHAT, chat.chatId)

        assertEquals("CHAT", worlds.getMemberships(world.worldId).single().resourceType)
        assertEquals(chat.chatId, worlds.getMemberships(world.worldId).single().resourceId)

        // Reject both missing references and accidental duplicate World membership.
        assertTrue(runCatching {
            worlds.attach(world.worldId, WorldResourceType.CHAT, "absent-chat")
        }.isFailure)
        assertTrue(runCatching {
            worlds.attach(world.worldId, WorldResourceType.CHAT, chat.chatId)
        }.isFailure)

        worlds.deleteWorld(world.worldId)
        assertTrue(database.worldDao().getWorlds().isEmpty())
        assertTrue(database.worldDao().getMemberships(world.worldId).isEmpty())
        assertNotNull(database.chatDao().getChatById(chat.chatId))
    }

    @Test
    fun completeCastProfileAndEditableDraftPreserveWorldBoundaries() = runBlocking {
        val world = worlds.createWorld("Mondstadt")
        val alternate = worlds.createWorld("Another AU")
        val record = com.example.data.model.WorldCastEntity(
            worldId = world.worldId, name = "  Sucrose  ", role = "Morgue Technician",
            identity = "Kind forensic specialist", voice = "Gentle"
        )
        val saved = worlds.createCastMember(record)
        assertEquals("Sucrose", saved.name)
        assertEquals("Gentle", worlds.getCast(world.worldId).single().voice)
        assertTrue(worlds.getCast(alternate.worldId).isEmpty())
        assertTrue(runCatching {
            worlds.createCastMember(record.copy(worldId = "missing-world", worldCharacterId = "other"))
        }.isFailure)

        val event = worlds.draftEvent(world.worldId, "Hearing", "Venti attended.")
        worlds.updateDraftEvent(event.copy(facts = "Venti listened to residents."))
        assertEquals("Venti listened to residents.", worlds.getEvents(world.worldId).single().facts)
        assertTrue(runCatching {
            worlds.updateDraftEvent(event.copy(worldId = alternate.worldId))
        }.isFailure)
        worlds.acceptEvent(world.worldId, event.eventId)
        assertTrue(runCatching {
            worlds.updateDraftEvent(event.copy(facts = "A retroactive rewrite."))
        }.isFailure)
        assertEquals("Venti listened to residents.", worlds.getEvents(world.worldId).single().facts)
    }

    @Test
    fun castAndContinuityStayScopedToTheirWorld() = runBlocking {
        val first = worlds.createWorld("Mondstadt")
        val second = worlds.createWorld("Another Teyvat AU")
        val varka = worlds.createCastMember(first.worldId, "Varka", "Police Commissioner")
        val otherVarka = worlds.createCastMember(second.worldId, "Varka", "A different AU")
        assertTrue(varka.worldCharacterId != otherVarka.worldCharacterId)
        assertEquals(1, worlds.getCast(first.worldId).size)
        assertEquals(1, worlds.getCast(second.worldId).size)

        val event = worlds.draftEvent(first.worldId, "First meeting", "Piper meets Flins and Varka.")
        assertEquals("DRAFT", worlds.getEvents(first.worldId).single().status)
        assertTrue(runCatching { worlds.acceptEvent(second.worldId, event.eventId) }.isFailure)
        assertEquals("DRAFT", worlds.getEvents(first.worldId).single().status)
        worlds.acceptEvent(first.worldId, event.eventId)
        assertEquals("ACCEPTED", worlds.getEvents(first.worldId).single().status)
        assertTrue(runCatching { worlds.acceptEvent(first.worldId, event.eventId) }.isFailure)

        worlds.deleteWorld(first.worldId)
        assertTrue(worlds.getCast(first.worldId).isEmpty())
        assertTrue(worlds.getEvents(first.worldId).isEmpty())
        assertEquals(1, worlds.getCast(second.worldId).size)
        assertFalse(database.worldDao().getWorlds().isEmpty())
    }

    @Test
    fun importedDefinitionPreservesRelationshipsAndExportsWithoutMutatingExistingWorlds() = runBlocking {
        val original = worlds.createWorld("Different Teyvat AU")
        val input = com.example.domain.world.WorldDefinitionV1(
            format = com.example.domain.world.WorldDefinitionCodec.FORMAT,
            schemaVersion = 1,
            world = com.example.domain.world.WorldMetadataV1(
                worldId = "law-disorder", title = "Law & Disorder: Mondstadt",
                settingSummary = "Modern Mondstadt", genreTags = listOf("procedural", "omegaverse")
            ),
            resources = com.example.domain.world.WorldResourcesV1(
                worldCast = listOf(
                    com.example.domain.world.WorldCastV1("varka", "Varka", profile = mapOf("role" to "Commissioner")),
                    com.example.domain.world.WorldCastV1("flins", "Flins", profile = mapOf("role" to "District Attorney"))
                )
            ),
            links = com.example.domain.world.WorldLinksV1(
                relationships = listOf(com.example.domain.world.WorldRelationshipV1("varka", "flins", "Mated Alphas"))
            ),
            continuity = listOf(com.example.domain.world.WorldContinuityV1(
                "event", "City Hall", "Venti serves as Mayor.", "ACCEPTED",
                relatedWorldCharacterIds = listOf("varka")
            ))
        )
        val imported = worlds.importDefinition(com.example.domain.world.WorldDefinitionCodec.encode(input))
        assertEquals("Law & Disorder: Mondstadt", imported.title)
        assertEquals(2, worlds.getCast(imported.worldId).size)
        val output = com.example.domain.world.WorldDefinitionCodec.parse(worlds.exportDefinition(imported.worldId))
        assertEquals(1, output.links.relationships.size)
        assertEquals("Mated Alphas", output.links.relationships.single().description)
        assertEquals(output.resources.worldCast.map { it.worldCharacterId }.toSet(),
            setOf(output.links.relationships.single().fromWorldCharacterId,
                output.links.relationships.single().toWorldCharacterId))
        assertEquals(1, output.continuity.size)
        assertEquals("Venti serves as Mayor.", output.continuity.single().facts)
        assertEquals(1, output.continuity.single().relatedWorldCharacterIds.size)
        assertEquals(listOf("procedural", "omegaverse"), output.world.genreTags)

        val reimported = worlds.importDefinition(worlds.exportDefinition(imported.worldId))
        assertTrue(reimported.worldId != imported.worldId)
        assertEquals(2, worlds.getCast(reimported.worldId).size)
        assertEquals(3, database.worldDao().getWorlds().size)
        assertNotNull(worlds.getWorld(original.worldId))
    }

    @Test
    fun importFailsClosedWithoutLeavingPartialWorldWhenEmbeddedDataIsUnsupported() = runBlocking {
        val oldCount = database.worldDao().getWorlds().size
        val example = com.example.domain.world.WorldDefinitionV1(
            format = com.example.domain.world.WorldDefinitionCodec.FORMAT,
            schemaVersion = 1,
            world = com.example.domain.world.WorldMetadataV1("test-world", "Test World", settingSummary = "Town"),
            resources = com.example.domain.world.WorldResourcesV1(
                characters = listOf(mapOf("name" to "Not Yet Supported"))
            ),
            links = com.example.domain.world.WorldLinksV1()
        )
        assertTrue(runCatching {
            worlds.importDefinition(com.example.domain.world.WorldDefinitionCodec.encode(example))
        }.isFailure)
        assertEquals(oldCount, database.worldDao().getWorlds().size)
    }

    @Test
    fun exportRefusesToDropExistingLinkedChats() = runBlocking {
        val existing = worlds.createWorld("Connected")
        val chat = ChatEntity(chatId = "active-story", displayName = "Story", chatType = ChatType.SOLO)
        database.chatDao().insertChat(chat)
        worlds.attach(existing.worldId, WorldResourceType.CHAT, chat.chatId)
        assertTrue(runCatching { worlds.exportDefinition(existing.worldId) }.isFailure)
    }
}
