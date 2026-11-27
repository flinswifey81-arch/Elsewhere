package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.ScenarioRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Production repository and Room assertions. A running Scenario changes only
 * when the user explicitly edits that chat's snapshot, never via template edits.
 * No model calls, network, real-device files or user story data.
 */
@RunWith(RobolectricTestRunner::class)
class ScenarioActiveSessionEditTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ScenarioRepository
    private val scenarioId = "tea-party-template"
    private val personaId = "persona-maise"
    private val initial = ScenarioDefinition(
        title = "Tea at the Crossroads",
        premise = "A strange invitation",
        openingMessage = "The clock strikes noon.",
        setting = "A peaceful village",
        instructions = "Keep the player in control.",
        npcs = listOf(ScenarioNpc(npcId = "hatter", name = "The Hatter"))
    )

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = ScenarioRepository(database, AppDataMaintenanceCoordinator())
    }

    @After fun teardown() { database.close() }

    private suspend fun prepare(): Pair<String, String> {
        database.backupDao().insertPersonas(listOf(
            PersonaEntity(
                personaId = personaId,
                displayName = "Maisie", personaName = "Maisie",
                aliases = emptyList(), importSchemaVersion = 1,
                identityJson = null, appearanceJson = null, personalityJson = null,
                roleplayProfileJson = null, backgroundJson = null,
                worldContextJson = null, privateNotesJson = null,
                extensionFieldsJson = null
            )
        ))
        repository.save(scenarioId, initial)
        return repository.start(scenarioId, personaId) to repository.start(scenarioId, personaId)
    }

    @Test fun editingActiveStoryPreservesTemplateSiblingHistoryAndWorldMembership() = runBlocking {
        val (first, sibling) = prepare()
        val messagesBefore = database.backupDao().getMessages().filter { it.chatId == first }
        assertEquals(1, messagesBefore.size)
        assertEquals(initial.openingMessage, messagesBefore.single().content)
        val revised = initial.copy(
            premise = "The player has decided to travel north.",
            setting = "The icebound northern road",
            openingMessage = "This is a future-only edit, not the old introduction.",
            instructions = "Never rewrite a player's choices.",
            npcs = initial.npcs + ScenarioNpc(npcId = "new-traveler", name = "A traveler")
        )
        repository.updateActiveSession(first, revised)

        assertEquals(revised, ScenarioCodec.decode(
            requireNotNull(repository.session(first)).definitionJson
        ))
        assertEquals(initial, ScenarioCodec.decode(
            requireNotNull(repository.session(sibling)).definitionJson
        ))
        assertEquals(initial, ScenarioCodec.decode(
            requireNotNull(repository.get(scenarioId)).definitionJson
        ))
        assertEquals(messagesBefore,
            database.backupDao().getMessages().filter { it.chatId == first })
        assertEquals(2, database.backupDao().getChats().size)
        assertEquals(listOf(scenarioId, scenarioId),
            database.scenarioDao().sessions().map { it.scenarioId }.sorted())
        // New playthroughs still start from the reusable template, not a prior chat edit.
        val third = repository.start(scenarioId, personaId)
        assertEquals(initial, ScenarioCodec.decode(
            requireNotNull(repository.session(third)).definitionJson
        ))
    }

    @Test fun invalidChangesAndNonScenarioChatsCannotOverwriteAPlaythrough() = runBlocking {
        val (first, _) = prepare()
        val before = requireNotNull(repository.session(first))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.updateActiveSession(first, initial.copy(title = "")) }
        }
        assertEquals(before, repository.session(first))

        database.backupDao().insertChats(listOf(
            ChatEntity(chatId = "ordinary-solo", chatType = ChatType.SOLO, displayName = "Solo")
        ))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.updateActiveSession("ordinary-solo", initial) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.updateActiveSession("does-not-exist", initial) }
        }
        assertEquals(before, repository.session(first))
        assertEquals(initial, ScenarioCodec.decode(
            requireNotNull(repository.get(scenarioId)).definitionJson
        ))
    }

    @Test fun editingLiveSnapshotDoesNotRetroactivelyChangeArchivedScenarioSession() = runBlocking {
        val (first, sibling) = prepare()
        val oldSnapshot = requireNotNull(repository.session(first)).copy()
        val edited = initial.copy(worldRules = "The established world rule has changed.")
        repository.updateActiveSession(first, edited)
        assertEquals(oldSnapshot.definitionJson,
            requireNotNull(repository.session(sibling)).definitionJson)
        assertNotEquals(oldSnapshot.definitionJson,
            requireNotNull(repository.session(first)).definitionJson)
        // Editing a Scenario session does not itself require a schema migration.
        // The current FTS-only database bump is version 26.
        assertEquals(27, com.example.data.APP_DATABASE_VERSION)
    }
}
