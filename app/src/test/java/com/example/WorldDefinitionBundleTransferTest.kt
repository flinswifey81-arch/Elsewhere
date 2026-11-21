package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.LorebookRepository
import com.example.domain.repository.WorldRepository
import com.example.domain.world.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real, synthetic Room -> World Definition JSON -> Room tests; no network,
 * model request, existing user data or source-ID overwrite.
 */
@RunWith(RobolectricTestRunner::class)
class WorldDefinitionBundleTransferTest {
    private lateinit var db: AppDatabase
    private lateinit var worlds: WorldRepository
    private lateinit var lore: LorebookRepository
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val linksAdapter = moshi.adapter(WorldLinksV1::class.java)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val coordinator = AppDataMaintenanceCoordinator()
        worlds = WorldRepository(db, coordinator)
        lore = LorebookRepository(db.lorebookDao(), moshi, coordinator)
    }
    @After fun tearDown() { db.close() }

    private suspend fun completeBundle(): WorldDefinitionV1 {
        val book = lore.newLorebook("The Crossroads Lore")
        val entry = lore.newEntry(book.lorebook.lorebookId).copy(
            title = "Moonlit crossings", canonContent = "The silver bridge is real.",
            notes = "Author's private notes should not be silently discarded."
        )
        lore.saveDefinition(book.lorebook, listOf(entry))
        val loreJson = mapAdapter.fromJson(
            lore.exportJson(book.lorebook.lorebookId)
        )!!
        val oldBookId = book.lorebook.lorebookId
        return WorldDefinitionV1(
            format = WorldDefinitionCodec.FORMAT,
            schemaVersion = WorldDefinitionCodec.SCHEMA_VERSION,
            world = WorldMetadataV1(
                worldId = "source-world", title = "Moonlit Crossroads",
                settingSummary = "A mythic city and a dangerous wood.",
                genreTags = listOf("mythic"),
                defaultLorebookIds = listOf(oldBookId)
            ),
            resources = WorldResourcesV1(
                characters = listOf(
                    mapOf(
                        "schema_version" to 1, "character_id" to "source-character",
                        "display_name" to "Varka", "character_name" to "Varka",
                        "aliases" to listOf("Captain"),
                        "voice" to mapOf("tone" to "Calm determination"),
                        "author_notes" to mapOf("private" to "Varka's creator secret")
                    )
                ),
                personas = listOf(
                    mapOf(
                        "schema_version" to 1, "persona_id" to "source-persona",
                        "display_name" to "Piper", "persona_name" to "Piper",
                        "aliases" to emptyList<String>(),
                        "private_notes" to mapOf("private" to "Piper's creator secret")
                    )
                ),
                scenarios = listOf(
                    WorldScenarioV1(
                        "source-scenario",
                        ScenarioDefinition(
                            title = "Moonlit Meeting",
                            premise = "The traveler reaches a crossing.",
                            npcs = listOf(
                                ScenarioNpc(
                                    npcId = "source-npc", name = "The Captain",
                                    personality = "Loyal to his friends.",
                                    sourceCharacterId = "source-character"
                                )
                            )
                        )
                    )
                ),
                lorebooks = listOf(loreJson),
                worldCast = listOf(
                    WorldCastV1(
                        worldCharacterId = "source-cast", name = "Varka",
                        profile = mapOf("role" to "Captain of the Guard"),
                        provenance = WorldProvenanceV1("CHARACTER", "source-character")
                    ),
                    WorldCastV1(
                        worldCharacterId = "source-piper", name = "Piper",
                        provenance = WorldProvenanceV1("PERSONA", "source-persona")
                    )
                )
            ),
            links = WorldLinksV1(
                castRoles = listOf(
                    WorldCastRoleV1("source-cast", "CHARACTER", "source-character"),
                    WorldCastRoleV1("source-cast", "SCENARIO_NPC", "source-npc",
                        "source-scenario"),
                    WorldCastRoleV1("source-piper", "PERSONA", "source-persona")
                ),
                relationships = listOf(
                    WorldRelationshipV1("source-cast", "source-piper", "Trusted allies")
                )
            ),
            continuity = listOf(
                WorldContinuityV1(
                    eventId = "source-event",
                    title = "The Bridge Was Repaired", facts = "The guild rebuilt the crossing.",
                    status = "ACCEPTED",
                    relatedWorldCharacterIds = listOf("source-cast")
                )
            )
        )
    }

    @Test fun fullBundleImportsWithFreshIdsAndExportsSameLinkedResourceGraph() = runBlocking {
        val original = completeBundle()
        val oldBookId = original.world.defaultLorebookIds.single()
        val first = worlds.importDefinition(WorldDefinitionCodec.encode(original))
        assertNotEquals(original.world.worldId, first.worldId)
        assertEquals(2, worlds.getCast(first.worldId).size)
        val members = worlds.getMemberships(first.worldId)
        assertEquals(4, members.size)
        assertEquals(setOf("CHARACTER", "PERSONA", "SCENARIO", "LOREBOOK"),
            members.map { it.resourceType }.toSet())
        val embeddedChar = members.single { it.resourceType == "CHARACTER" }.resourceId
        val embeddedPersona = members.single { it.resourceType == "PERSONA" }.resourceId
        val embeddedScenario = members.single { it.resourceType == "SCENARIO" }.resourceId
        val embeddedLore = members.single { it.resourceType == "LOREBOOK" }.resourceId
        assertNotEquals("source-character", embeddedChar)
        assertNotEquals("source-persona", embeddedPersona)
        assertNotEquals("source-scenario", embeddedScenario)
        assertNotEquals(oldBookId, embeddedLore)
        assertEquals("Varka", db.characterDao().getCharacterById(embeddedChar)?.displayName)
        assertTrue(requireNotNull(
            db.characterDao().getCharacterById(embeddedChar)?.authorNotesJson
        ).contains("Varka's creator secret"))
        assertTrue(requireNotNull(
            db.personaDao().getPersonaById(embeddedPersona)?.privateNotesJson
        ).contains("Piper's creator secret"))
        val importedScenario = ScenarioCodec.decode(
            requireNotNull(db.scenarioDao().get(embeddedScenario)).definitionJson
        )
        val linkedNpc = importedScenario.npcs.single()
        assertNotEquals("source-npc", linkedNpc.npcId)
        assertEquals(embeddedChar, linkedNpc.sourceCharacterId)
        assertEquals(oldBookId, db.lorebookDao().getLorebook(oldBookId)?.lorebookId)
        val copiedEntries = db.lorebookDao().getEntries(embeddedLore)
        assertEquals(1, copiedEntries.size)
        assertEquals("The silver bridge is real.", copiedEntries.single().canonContent)
        assertEquals("Author's private notes should not be silently discarded.",
            copiedEntries.single().notes)
        val links = requireNotNull(linksAdapter.fromJson(
            requireNotNull(worlds.getWorld(first.worldId)).linksJson
        ))
        assertEquals(3, links.castRoles.size)
        assertTrue(links.castRoles.any {
            it.roleType == "SCENARIO_NPC" &&
                it.resourceId == linkedNpc.npcId &&
                it.scenarioId == embeddedScenario
        })
        assertEquals(1, links.relationships.size)
        assertEquals(1, worlds.getEvents(first.worldId).size)
        assertEquals("ACCEPTED", worlds.getEvents(first.worldId).single().status)

        val exported = worlds.exportDefinition(first.worldId)
        val decoded = WorldDefinitionCodec.parse(exported)
        assertEquals(1, decoded.resources.characters.size)
        assertEquals(1, decoded.resources.personas.size)
        assertEquals(1, decoded.resources.scenarios.size)
        assertEquals(1, decoded.resources.lorebooks.size)
        assertEquals(2, decoded.resources.worldCast.size)
        assertEquals(listOf(embeddedLore), decoded.world.defaultLorebookIds)
        assertEquals(1, decoded.continuity.size)
        val second = worlds.importDefinition(exported)
        assertNotEquals(first.worldId, second.worldId)
        assertEquals(2, worlds.getCast(second.worldId).size)
        assertEquals(4, worlds.getMemberships(second.worldId).size)
        assertEquals(2, db.worldDao().getWorlds().size)
        assertEquals("The silver bridge is real.",
            db.lorebookDao().getEntries(
                worlds.getMemberships(second.worldId)
                    .single { it.resourceType == "LOREBOOK" }.resourceId
            ).single().canonContent)
        assertEquals("Moonlit Meeting", ScenarioCodec.decode(
            requireNotNull(db.scenarioDao().get(embeddedScenario)).definitionJson
        ).title)
    }

    @Test fun malformedOrDanglingResourceNeverCreatesPartialWorldOrReplacesCards() = runBlocking {
        val complete = completeBundle()
        val broken = complete.copy(
            resources = complete.resources.copy(
                scenarios = complete.resources.scenarios.map { scenario ->
                    scenario.copy(definition = scenario.definition.copy(
                        npcs = scenario.definition.npcs.map {
                            it.copy(sourceCharacterId = "missing-character")
                        }
                    ))
                }
            )
        )
        val sourceLorebookCount = db.lorebookDao().getLorebooksOnce().size
        val sourceCharacterCount = db.backupDao().getCharacters().size
        assertTrue(runCatching {
            worlds.importDefinition(WorldDefinitionCodec.encode(broken))
        }.isFailure)
        assertTrue(db.worldDao().getWorlds().isEmpty())
        assertEquals(sourceLorebookCount, db.lorebookDao().getLorebooksOnce().size)
        assertEquals(sourceCharacterCount, db.backupDao().getCharacters().size)
        assertTrue(db.scenarioDao().getAll().isEmpty())

        val brokenRole = complete.copy(
            links = complete.links.copy(castRoles = listOf(
                WorldCastRoleV1("source-cast", "CHARACTER", "not-embedded")
            ))
        )
        assertTrue(runCatching {
            worlds.importDefinition(WorldDefinitionCodec.encode(brokenRole))
        }.isFailure)
        assertTrue(db.worldDao().getWorlds().isEmpty())
        assertTrue(db.scenarioDao().getAll().isEmpty())
    }

    @Test fun shareableWorldJsonRefusesChatHistoryRatherThanSilentlyDroppingIt() = runBlocking {
        val saved = worlds.importDefinition(WorldDefinitionCodec.encode(completeBundle()))
        val chat = ChatEntity(
            chatId = "linked-chat", displayName = "Do Not Export My RP",
            chatType = ChatType.SOLO
        )
        db.chatDao().insertChat(chat)
        worlds.attach(saved.worldId, WorldResourceType.CHAT, chat.chatId)
        assertTrue(runCatching { worlds.exportDefinition(saved.worldId) }.isFailure)
        assertNotNull(db.chatDao().getChatById(chat.chatId))
    }
}
