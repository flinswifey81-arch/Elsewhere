package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.MessageEntity
import com.example.data.model.NpcDossier
import com.example.data.model.SpeakerType
import com.example.domain.npc.NpcStructuredUpdate
import com.example.domain.repository.NpcRepository
import com.example.domain.repository.NpcImportCollisionMode
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NpcRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: NpcRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NpcRepository(
            database,
            Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun selectingAndDeletingVariantsReprojectsNpcChanges() = runBlocking {
        val first = MessageEntity(
            messageId = "reply-a",
            chatId = "chat",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "First",
            orderIndex = 2,
            variantGroupId = "variants",
            isPrimaryVariant = true
        )
        val second = first.copy(messageId = "reply-b", content = "Second", isPrimaryVariant = false)
        database.messageDao().insertMessage(first)
        database.messageDao().insertMessage(second)
        repository.applyStructuredUpdates(
            chatId = "chat",
            sourceMessageId = first.messageId,
            variantGroupId = first.variantGroupId,
            sourceRevision = 0,
            isApplied = true,
            updates = listOf(NpcStructuredUpdate(null, "Mara", null, "Archivist", null, null, null, "Guard the archive", null))
        )
        val npcId = repository.getProjectedDossiers("chat").single().npcId
        repository.applyStructuredUpdates(
            chatId = "chat",
            sourceMessageId = second.messageId,
            variantGroupId = second.variantGroupId,
            sourceRevision = 0,
            isApplied = false,
            updates = listOf(NpcStructuredUpdate(npcId, null, null, null, null, null, null, "Burn the archive", null))
        )

        repository.selectVariant("variants", "reply-b")
        assertEquals("Burn the archive", repository.getProjectedDossiers("chat").single().goals)

        repository.deleteMessageAndRepair(second.copy(isPrimaryVariant = true))
        assertEquals("Guard the archive", repository.getProjectedDossiers("chat").single().goals)
        assertTrue(database.messageDao().getMessageById("reply-a")?.isPrimaryVariant == true)
    }

    @Test
    fun contextIncludesStorytellerSecretsButExcludesPrivateAuthorNotes() = runBlocking {
        repository.saveManual(
            dossier(
                secrets = "NEVER_SEND_SECRET",
                authorNotes = "NEVER_SEND_AUTHOR_NOTE",
                relationship = "Trusted guide"
            )
        )

        val context = repository.relevantContext("chat", emptyList()).orEmpty()

        assertTrue(context.contains("Trusted guide"))
        assertTrue(context.contains("NEVER_SEND_SECRET"))
        assertFalse(context.contains("NEVER_SEND_AUTHOR_NOTE"))
    }

    @Test
    fun expandedSoloEnsembleContextIncludesAllFiveNpcProfiles() = runBlocking {
        (1..5).forEach { index ->
            repository.saveManual(
                dossier().copy(
                    npcId = "ensemble-npc-$index",
                    displayName = "Partner $index",
                    personality = "Distinct personality $index"
                )
            )
        }

        val ordinaryContext = repository.relevantContext("chat", emptyList()).orEmpty()
        val ensembleContext = repository.relevantContext(
            chatId = "chat",
            recentMessages = emptyList(),
            limit = NpcRepository.SOLO_ENSEMBLE_NPC_CONTEXT_LIMIT
        ).orEmpty()

        val npcMarker = Regex("\\[NPC id=")
        assertEquals(NpcRepository.DEFAULT_NPC_CONTEXT_LIMIT, npcMarker.findAll(ordinaryContext).count())
        assertEquals(5, npcMarker.findAll(ensembleContext).count())
        (1..5).forEach { index ->
            assertTrue("Partner $index should have a dossier in the ensemble prompt", ensembleContext.contains("Name: Partner $index"))
            assertTrue(ensembleContext.contains("Distinct personality $index"))
        }
    }

    @Test
    fun manualEditBecomesNewBaseAndClearsAutomaticOverlay() = runBlocking {
        database.messageDao().insertMessage(
            MessageEntity(
                messageId = "reply",
                chatId = "chat",
                speakerType = SpeakerType.CHARACTER,
                speakerId = "character",
                speakerDisplayNameSnapshot = "Character",
                content = "Reply",
                orderIndex = 1
            )
        )
        repository.applyStructuredUpdates(
            chatId = "chat",
            sourceMessageId = "reply",
            variantGroupId = "reply",
            sourceRevision = 0,
            isApplied = true,
            updates = listOf(NpcStructuredUpdate(null, "Mara", null, null, null, null, null, "Model goal", null))
        )
        val discovered = repository.getProjectedDossiers("chat").single()

        repository.saveManual(discovered.copy(goals = "User goal"))

        assertEquals("User goal", repository.getProjectedDossiers("chat").single().goals)
        assertTrue(database.npcDao().getUpdates("chat").isEmpty())
    }

    @Test
    fun automaticDiscoveryIsDisabledByDefaultPerChat() = runBlocking {
        assertFalse(repository.getSettings("chat").isEnabled)
        repository.setEnabled("chat", true)
        assertTrue(repository.getSettings("chat").isEnabled)
    }

    @Test
    fun groupContextUsesOnlyActivePersonaRelationshipAndRespondingCharacterKnowledge() = runBlocking {
        repository.saveManual(
            dossier(secrets = "STORYTELLER_SECRET").copy(
                personaRelationships = mapOf("persona-a" to "Trusts A", "persona-b" to "Distrusts B"),
                characterKnowledge = mapOf("character-a" to "Knows the brass key", "character-b" to "Knows the river route")
            )
        )

        val context = repository.relevantContext(
            chatId = "chat",
            recentMessages = emptyList(),
            observerCharacterId = "character-a",
            activePersonaId = "persona-a",
            isGroup = true
        ).orEmpty()

        assertTrue(context.contains("Trusts A"))
        assertTrue(context.contains("Knows the brass key"))
        assertTrue(context.contains("STORYTELLER CONTINUITY — NOT AUTOMATIC SPEAKER KNOWLEDGE"))
        assertTrue(context.contains("SPEAKER-AUTHORIZED KNOWLEDGE FOR RESPONDING CHARACTER"))
        assertFalse(context.contains("Distrusts B"))
        assertFalse(context.contains("river route"))
        assertTrue(context.contains("STORYTELLER_SECRET"))
    }

    @Test
    fun responseAndNpcUpdatesRollbackTogetherOnUpdateFailure() = runBlocking {
        val old = MessageEntity(
            messageId = "old",
            chatId = "chat",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "Old",
            orderIndex = 1,
            variantGroupId = "variants",
            isPrimaryVariant = true
        )
        database.messageDao().insertMessage(old)
        val replacement = old.copy(messageId = "replacement", content = "New")

        val result = runCatching {
            repository.persistGeneratedMessageAtomically(
                message = replacement,
                metadata = null,
                isNewVariant = true,
                updates = listOf(
                    NpcStructuredUpdate(null, "Mara", null, "First", null, null, null, null, null),
                    NpcStructuredUpdate(null, "Mara", null, "Second", null, null, null, null, null)
                ),
                activePersonaId = "persona"
            )
        }

        assertTrue(result.isFailure)
        assertTrue(database.messageDao().getMessageById("replacement") == null)
        assertTrue(database.messageDao().getMessageById("old")?.isPrimaryVariant == true)
        assertTrue(database.npcDao().getDossiers("chat").isEmpty())
    }

    @Test
    fun shareSafeExportOmitsPrivateFieldsAndPrivateExportIncludesThem() = runBlocking {
        database.npcDao().insertDossier(
            com.example.data.model.NpcDossierEntity(
                npcId = "npc",
                chatId = "chat",
                displayName = "Mara",
                secrets = "SECRET",
                authorNotes = "PRIVATE_NOTE",
                extensionFieldsJson = "{\"private_extension\":\"EXTENSION_SECRET\"}"
            )
        )

        val safe = repository.exportJson("chat", includePrivateFields = false)
        val private = repository.exportJson("chat", includePrivateFields = true)

        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains("PRIVATE_NOTE"))
        assertFalse(safe.contains("EXTENSION_SECRET"))
        assertTrue(private.contains("SECRET"))
        assertTrue(private.contains("PRIVATE_NOTE"))
        assertTrue(private.contains("EXTENSION_SECRET"))
    }

    @Test
    fun regeneratingOlderResponseDoesNotOverrideLaterNpcState() = runBlocking {
        val old = MessageEntity(
            messageId = "old",
            chatId = "chat",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "Old",
            orderIndex = 10,
            variantGroupId = "old-variants",
            isPrimaryVariant = true
        )
        val later = old.copy(
            messageId = "later",
            content = "Later",
            orderIndex = 20,
            variantGroupId = "later"
        )
        database.messageDao().insertMessage(old)
        database.messageDao().insertMessage(later)
        repository.applyStructuredUpdates(
            "chat", old.messageId, old.variantGroupId, 0, true,
            listOf(NpcStructuredUpdate(null, "Mara", null, null, null, null, null, "Old goal", null))
        )
        val npcId = repository.getProjectedDossiers("chat").single().npcId
        repository.applyStructuredUpdates(
            "chat", later.messageId, later.variantGroupId, 0, true,
            listOf(NpcStructuredUpdate(npcId, null, null, null, null, null, null, "Later goal", null))
        )
        repository.persistGeneratedMessageAtomically(
            old.copy(messageId = "old-regenerated", content = "Old regenerated"),
            metadata = null,
            isNewVariant = true,
            updates = listOf(NpcStructuredUpdate(npcId, null, null, null, null, null, null, "Regenerated old goal", null)),
            activePersonaId = "persona"
        )

        assertEquals("Later goal", repository.getProjectedDossiers("chat").single().goals)
    }

    @Test
    fun importAsNewResolvesIdCollisionFromAnotherChat() = runBlocking {
        repository.saveManual(dossier().copy(chatId = "other-chat"))
        val json = repository.exportJson("other-chat", includePrivateFields = true)

        repository.importJson("target-chat", json, NpcImportCollisionMode.IMPORT_AS_NEW)

        val imported = repository.getProjectedDossiers("target-chat").single()
        assertTrue(imported.npcId != "npc")
        assertEquals("Mara", imported.displayName)
    }

    @Test
    fun importIgnoresUnknownRootAndPreservesUnknownNpcFieldsPrivately() = runBlocking {
        val json = """
            {
              "schema_version": 1,
              "future_root": {"ignored_safely": true},
              "npcs": [{
                "npc_id": "future-npc",
                "display_name": "Future Mara",
                "future_nested": {"private_future_value": "FUTURE_SECRET"}
              }]
            }
        """.trimIndent()

        repository.importJson("chat", json)

        assertEquals("Future Mara", repository.getProjectedDossiers("chat").single().displayName)
        val safe = repository.exportJson("chat", includePrivateFields = false)
        val private = repository.exportJson("chat", includePrivateFields = true)
        assertFalse(safe.contains("FUTURE_SECRET"))
        assertTrue(private.contains("future_nested"))
        assertTrue(private.contains("FUTURE_SECRET"))
        assertFalse(repository.relevantContext("chat", emptyList()).orEmpty().contains("FUTURE_SECRET"))
    }

    @Test
    fun repositoryRejectsAutomaticCoreProfileMutationForExistingNpc() = runBlocking {
        repository.saveManual(dossier())
        val source = MessageEntity(
            messageId = "mutation-source",
            chatId = "chat",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "Reply",
            orderIndex = 1
        )
        database.messageDao().insertMessage(source)

        val result = runCatching {
            repository.applyStructuredUpdates(
                "chat", source.messageId, source.variantGroupId, 0, true,
                listOf(NpcStructuredUpdate("npc", "Renamed", listOf("New alias"), null, "Changed", "Changed", null, null, null))
            )
        }

        assertTrue(result.isFailure)
        val dossier = repository.getProjectedDossiers("chat").single()
        assertEquals("Mara", dossier.displayName)
        assertEquals("Ink-stained gloves", dossier.appearance)
    }

    @Test
    fun continuationBatchesUseSeparateRevisionsAndDeleteTogether() = runBlocking {
        val message = MessageEntity(
            messageId = "reply",
            chatId = "chat",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "First",
            orderIndex = 1,
            variantGroupId = "reply",
            isPrimaryVariant = true
        )
        database.messageDao().insertMessage(message)
        repository.persistContinuationAtomically(
            message.copy(content = "First second"),
            metadata = null,
            updates = listOf(NpcStructuredUpdate(null, "Mara", null, "Guide", null, null, null, null, null)),
            activePersonaId = "persona-a"
        )
        val npcId = repository.getProjectedDossiers("chat").single().npcId
        repository.persistContinuationAtomically(
            message.copy(content = "First second third"),
            metadata = null,
            updates = listOf(NpcStructuredUpdate(npcId, null, null, null, null, null, "Trusted", "Find the gate", null)),
            activePersonaId = "persona-a"
        )

        val revisions = database.npcDao().getUpdatesForMessage("reply").map { it.sourceRevision }.distinct()
        assertEquals(listOf(0, 1), revisions)
        val projected = repository.getProjectedDossiers("chat").single()
        assertEquals("Trusted", projected.personaRelationships["persona-a"])
        assertEquals("Find the gate", projected.goals)

        repository.deleteMessageAndRepair(message)
        assertTrue(repository.getProjectedDossiers("chat").isEmpty())
        assertTrue(database.npcDao().getUpdatesForMessage("reply").isEmpty())
    }

    private fun dossier(
        secrets: String = "",
        authorNotes: String = "",
        relationship: String = ""
    ) = NpcDossier(
        npcId = "npc",
        chatId = "chat",
        displayName = "Mara",
        aliases = listOf("Archivist Voss"),
        role = "Archivist",
        appearance = "Ink-stained gloves",
        personality = "Measured",
        relationshipToPersona = relationship,
        personaRelationships = emptyMap(),
        characterKnowledge = emptyMap(),
        goals = "Protect the ledger",
        secrets = secrets,
        authorNotes = authorNotes,
        keepInScene = false,
        isManualBase = true,
        createdAt = 1,
        updatedAt = 1
    )
}
