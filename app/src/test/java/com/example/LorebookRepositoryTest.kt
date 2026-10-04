package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.lore.LorebookActivationEngine
import com.example.domain.provider.Role
import com.example.domain.repository.LorebookImportMode
import com.example.domain.repository.LorebookRepository
import com.example.domain.repository.LorebookValidationException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class LorebookRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: LorebookRepository
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LorebookRepository(database.lorebookDao(), moshi)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun jsonRoundTripPreservesCanonCurrentMetadataAndArbitraryPriority() = runBlocking {
        repository.importJson(completeJson(), LorebookImportMode.REJECT_DUPLICATE)
        val exported = repository.exportJson("book-1")
        val parsed = repository.parseLorebookJson(exported)

        assertEquals("Moon Archive", parsed.lorebook.name)
        assertEquals("story", parsed.lorebook.scopeType)
        assertNull(parsed.lorebook.storyId)
        assertEquals(2, parsed.entries.size)
        val gate = parsed.entries.single { it.entryId == "entry-gate" }
        assertEquals(73, gate.priority)
        assertEquals("CANON_BASELINE_ONLY", gate.canonContent)
        assertEquals("CURRENT_WORLD_TRUTH", gate.currentContent)
        assertEquals(listOf("moon gate"), gate.keys)
        assertEquals(listOf("silver threshold"), gate.secondaryKeys)
        assertEquals(listOf("location", "secret"), gate.tags)
        assertEquals("PRIVATE_NOTES_NEVER_SEND", gate.notes)
        assertEquals(111L, gate.createdAt)
        assertEquals(222L, gate.updatedAt)
        assertTrue(gate.canonLocked)
        assertEquals("", parsed.entries.single { it.entryId == "entry-manual" }.currentContent)
        assertTrue(exported.contains("\"created_at\": \"1970-01-01T00:00:00.111Z\""))
    }

    @Test
    fun iso8601TimestampsFromPublishedShapeImportAndExportCanonically() = runBlocking {
        val isoJson = completeJson()
            .replace("\"created_at\": 111", "\"created_at\": \"2026-10-04T00:00:00Z\"")
            .replace("\"updated_at\": 222", "\"updated_at\": \"2026-10-04T12:34:56.789Z\"")
            .replace("\"created_at\": 333", "\"created_at\": \"2026-10-04T00:00:00Z\"")
            .replace("\"updated_at\": 444", "\"updated_at\": \"2026-10-04T12:34:56.789Z\"")

        repository.importJson(isoJson, LorebookImportMode.REJECT_DUPLICATE)
        val gate = repository.getLorebook("book-1")!!.entries.single { it.entryId == "entry-gate" }
        assertEquals(Instant.parse("2026-10-04T00:00:00Z").toEpochMilli(), gate.createdAt)
        assertEquals(Instant.parse("2026-10-04T12:34:56.789Z").toEpochMilli(), gate.updatedAt)

        val exported = repository.exportJson("book-1")
        assertTrue(exported.contains("\"created_at\": \"2026-10-04T00:00:00Z\""))
        assertTrue(exported.contains("\"updated_at\": \"2026-10-04T12:34:56.789Z\""))
        repository.parseLorebookJson(exported)
        Unit
    }

    @Test
    fun publishedGuideExampleParsesThroughProductionImporter() {
        val json = javaClass.classLoader?.getResourceAsStream("valid_lorebook_v1.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Guide example resource missing")
        runBlocking {
            val id = repository.importJson(json, LorebookImportMode.REJECT_DUPLICATE)
            val exported = repository.exportJson(id)
            val reparsed = repository.parseLorebookJson(exported)
            assertEquals("Evermere Primer", reparsed.lorebook.name)
            assertEquals(listOf("The Moonwell", "Evermere Guest Right"), reparsed.entries.map { it.title })
        }
    }

    @Test
    fun missingScopeImportsAsExplicitlyAttachedUnboundStoryScope() = runBlocking {
        val withoutScope = completeJson().replace(
            "\"scope\": {\"type\": \"story\", \"story_id\": null},",
            ""
        )
        repository.importJson(withoutScope, LorebookImportMode.REJECT_DUPLICATE)
        val book = repository.getLorebook("book-1")!!.lorebook
        assertEquals("story", book.scopeType)
        assertNull(book.storyId)

        val personaMessage = message("chat-unattached", "The moon gate opens.")
        assertTrue(repository.resolveActiveLore("chat-unattached", emptyList(), personaMessage).isEmpty())
    }

    @Test
    fun unsupportedFutureScopePositionAndUnknownFieldsAreRejected() {
        assertValidationFailure(completeJson().replace("\"story\"", "\"global\""), "scope")
        assertValidationFailure(completeJson().replace("\"position\": \"lore\"", "\"position\": \"after_history\""), "position")
        assertValidationFailure(completeJson().replace("\"name\": \"Moon Archive\"", "\"name\": \"Moon Archive\", \"future_field\": true"), "future_field")
        assertValidationFailure(
            completeJson().replace("\"scope\": {\"type\": \"story\", \"story_id\": null}", "\"scope\": null"),
            "scope"
        )
    }

    @Test
    fun duplicateImportRequiresCopyOrExplicitTransactionalReplacement() = runBlocking {
        repository.importJson(completeJson(), LorebookImportMode.REJECT_DUPLICATE)
        val preview = repository.previewImport(completeJson())
        assertTrue(preview.duplicateLorebookId)
        assertTrue(preview.conflictingEntryIds.isEmpty())

        val copyId = repository.importJson(completeJson(), LorebookImportMode.IMPORT_AS_NEW_COPY)
        assertNotEquals("book-1", copyId)
        val copy = repository.getLorebook(copyId)!!
        assertEquals("Moon Archive (Copy)", copy.lorebook.name)
        assertTrue(copy.entries.none { it.entryId == "entry-gate" || it.entryId == "entry-manual" })

        repository.attachLorebook("chat-replace", "book-1")
        repository.setChatCurrentOverride("chat-replace", "entry-gate", "CHAT_STATE_PRESERVED")
        val replacement = completeJson().replace("CURRENT_WORLD_TRUTH", "REPLACED_CURRENT")
        repository.importJson(replacement, LorebookImportMode.REPLACE_EXISTING)
        assertEquals("REPLACED_CURRENT", repository.getLorebook("book-1")!!.entries.single { it.entryId == "entry-gate" }.currentContent)
        val replacedState = repository.getChatLorebookStates("chat-replace").single { it.lorebook.lorebookId == "book-1" }
        assertTrue(replacedState.attachment != null)
        assertEquals("CHAT_STATE_PRESERVED", replacedState.overlays.getValue("entry-gate").currentContent)
    }

    @Test
    fun maximumLengthCopyNameRemainsValidAndRoundTrips() = runBlocking {
        val maximumName = "A".repeat(200)
        val json = completeJson().replace("Moon Archive", maximumName)
        repository.importJson(json, LorebookImportMode.REJECT_DUPLICATE)

        val copyId = repository.importJson(json, LorebookImportMode.IMPORT_AS_NEW_COPY)
        val copy = repository.getLorebook(copyId)!!.lorebook
        assertEquals(200, copy.name.length)
        assertTrue(copy.name.endsWith(" (Copy)"))

        val reparsed = repository.parseLorebookJson(repository.exportJson(copyId))
        assertEquals(copy.name, reparsed.lorebook.name)
    }

    @Test
    fun manualStoryIdUsesImporterBoundsAndValidBoundaryRoundTrips() = runBlocking {
        val validStoryId = "S".repeat(200)
        val book = repository.newLorebook("Manual Story").lorebook.copy(storyId = validStoryId)
        repository.saveDefinition(book, emptyList())

        val reparsed = repository.parseLorebookJson(repository.exportJson(book.lorebookId))
        assertEquals(validStoryId, reparsed.lorebook.storyId)

        listOf("", " ", "S".repeat(201)).forEach { invalidStoryId ->
            val error = runCatching {
                repository.saveDefinition(book.copy(lorebookId = "invalid-${invalidStoryId.length}", storyId = invalidStoryId), emptyList())
            }.exceptionOrNull()
            assertTrue(error is LorebookValidationException)
            assertTrue(error?.message.orEmpty().contains("Story ID"))
        }
    }

    @Test
    fun previewDetectsEntryIdsOwnedByAnotherBookAndOnlyNewCopyCanImportThem() = runBlocking {
        repository.importJson(completeJson(), LorebookImportMode.REJECT_DUPLICATE)
        val collidingJson = completeJson()
            .replaceFirst("\"id\": \"book-1\"", "\"id\": \"book-2\"")
            .replace("\"name\": \"Moon Archive\"", "\"name\": \"Colliding Archive\"")

        val preview = repository.previewImport(collidingJson)
        assertFalse(preview.duplicateLorebookId)
        assertEquals(listOf("entry-gate", "entry-manual"), preview.conflictingEntryIds)

        val rejected = runCatching {
            repository.importJson(collidingJson, LorebookImportMode.REJECT_DUPLICATE)
        }.exceptionOrNull()
        assertTrue(rejected is LorebookValidationException)
        assertTrue(rejected?.message.orEmpty().contains("another Lorebook"))

        val copiedId = repository.importJson(collidingJson, LorebookImportMode.IMPORT_AS_NEW_COPY)
        assertNotEquals("book-2", copiedId)
        assertTrue(repository.getLorebook(copiedId)!!.entries.none {
            it.entryId == "entry-gate" || it.entryId == "entry-manual"
        })
    }

    @Test
    fun attachmentsManualActivationAndBlankOverridesRemainChatLocalAcrossRepositoryRestart() = runBlocking {
        repository.importJson(completeJson(), LorebookImportMode.REJECT_DUPLICATE)
        repository.attachLorebook("chat-a", "book-1")
        repository.attachLorebook("chat-b", "book-1")
        repository.setManualActive("chat-a", "entry-manual", true)
        repository.setChatCurrentOverride("chat-a", "entry-manual", "")

        repository = LorebookRepository(database.lorebookDao(), moshi)
        val message = message("chat-a", "Nothing about a keyword.")
        val activeA = repository.resolveActiveLore("chat-a", emptyList(), message)
        val activeB = repository.resolveActiveLore("chat-b", emptyList(), message.copy(chatId = "chat-b"))
        assertEquals(listOf("Manual Empty"), activeA.map { it.title })
        assertEquals("", activeA.single().currentContent)
        assertTrue(activeB.isEmpty())

        repository.detachLorebook("chat-a", "book-1")
        assertTrue(repository.resolveActiveLore("chat-a", emptyList(), message).isEmpty())
        repository.attachLorebook("chat-a", "book-1")
        assertEquals("", repository.resolveActiveLore("chat-a", emptyList(), message)
            .single { it.title == "Manual Empty" }.currentContent)

        val exported = repository.exportJson("book-1")
        assertFalse(exported.contains("manualActive"))
        assertFalse(exported.contains("hasCurrentOverride"))
    }

    @Test
    fun combinedPrimaryAndSecondaryKeysHonorAnyAllCaseAndWholeWordRules() {
        val base = LorebookEntryEntity(
            entryId = "entry",
            lorebookId = "book",
            title = "Key Test",
            keys = listOf("Moon"),
            secondaryKeys = listOf("Gate"),
            matchMode = "all",
            caseSensitive = false,
            wholeWords = true
        )
        assertTrue(LorebookActivationEngine.keywordMatches(base, "The moon gate opens."))
        assertFalse(LorebookActivationEngine.keywordMatches(base, "The moon gateway opens."))
        assertFalse(LorebookActivationEngine.keywordMatches(base.copy(caseSensitive = true), "The moon Gate opens."))
        assertTrue(LorebookActivationEngine.keywordMatches(base.copy(matchMode = "any"), "Only the Moon is visible."))
    }

    @Test
    fun compilerReceivesOnlyTitleAndSelectedCurrentFromLorebook() = runBlocking {
        repository.importJson(completeJson(), LorebookImportMode.REJECT_DUPLICATE)
        repository.attachLorebook("chat-1", "book-1")
        val current = message("chat-1", "We cross the moon gate.")
        val lore = repository.resolveActiveLore("chat-1", emptyList(), current)
        val compiled = ContextCompilerV1().compileSoloContext(
            character = character(),
            persona = persona(),
            chatHistory = emptyList(),
            currentMessage = current,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null,
            durableMemories = listOf(
                RoleplayMemoryEntity(
                    chatId = "chat-1",
                    characterId = "character-1",
                    category = MemoryCategory.DETAIL,
                    content = "NORMAL_MEMORY_MARKER"
                )
            ),
            rollingSummary = "NORMAL_SUMMARY_MARKER",
            activeLore = lore
        )
        val system = compiled.single { it.role == Role.SYSTEM }.content
        assertTrue(system.contains("Moon Gate"))
        assertTrue(system.contains("CURRENT_WORLD_TRUTH"))
        assertTrue(system.contains("NORMAL_MEMORY_MARKER"))
        assertTrue(system.contains("NORMAL_SUMMARY_MARKER"))
        listOf(
            "CANON_BASELINE_ONLY",
            "PRIVATE_NOTES_NEVER_SEND",
            "silver threshold",
            "entry-gate",
            "book-1",
            "location",
            "111",
            "222",
            "schema_version"
        ).forEach { forbidden -> assertFalse("Leaked Lorebook metadata: $forbidden", system.contains(forbidden)) }
    }

    @Test
    fun emptyCurrentNeverFallsBackToCanon() = runBlocking {
        repository.importJson(completeJson(), LorebookImportMode.REJECT_DUPLICATE)
        repository.attachLorebook("chat-1", "book-1")
        repository.setManualActive("chat-1", "entry-manual", true)
        val current = message("chat-1", "No keywords here.")
        val lore = repository.resolveActiveLore("chat-1", emptyList(), current)
        val manual = lore.single { it.title == "Manual Empty" }
        assertEquals("", manual.currentContent)
        assertFalse(manual.currentContent.contains("MANUAL_CANON_SHOULD_NOT_FALLBACK"))
    }

    private fun assertValidationFailure(json: String, expectedText: String) {
        val error = runCatching { repository.parseLorebookJson(json) }.exceptionOrNull()
        assertTrue(error is LorebookValidationException)
        assertTrue(error?.message.orEmpty().contains(expectedText, ignoreCase = true))
    }

    private fun message(chatId: String, content: String) = MessageEntity(
        messageId = "message-${content.hashCode()}",
        chatId = chatId,
        speakerType = SpeakerType.PERSONA,
        speakerId = "persona-1",
        speakerDisplayNameSnapshot = "Persona",
        content = content,
        orderIndex = 1L
    )

    private fun character() = CharacterEntity(
        characterId = "character-1",
        displayName = "Character",
        characterName = "Character",
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        voiceJson = null,
        behaviorJson = null,
        backstoryJson = null,
        knowledgeJson = null,
        worldContextJson = null,
        writingRulesJson = null,
        examplesJson = null,
        authorNotesJson = null,
        extensionFieldsJson = null
    )

    private fun persona() = PersonaEntity(
        personaId = "persona-1",
        displayName = "Persona",
        personaName = "Persona",
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        roleplayProfileJson = null,
        backgroundJson = null,
        worldContextJson = null,
        privateNotesJson = null,
        extensionFieldsJson = null
    )

    private fun completeJson(): String =
        """
        {
          "schema_version": 1,
          "type": "elsewhere_lorebook",
          "id": "book-1",
          "name": "Moon Archive",
          "description": "A reusable story book",
          "enabled": true,
          "scope": {"type": "story", "story_id": null},
          "entries": [
            {
              "id": "entry-gate",
              "title": "Moon Gate",
              "enabled": true,
              "activation": {
                "mode": "keyword",
                "keys": ["moon gate"],
                "secondary_keys": ["silver threshold"],
                "match": "any",
                "case_sensitive": false,
                "whole_words": true
              },
              "canon": {"content": "CANON_BASELINE_ONLY", "locked": true},
              "current": {"content": "CURRENT_WORLD_TRUTH"},
              "injection": {"priority": 73, "position": "lore"},
              "tags": ["location", "secret"],
              "notes": "PRIVATE_NOTES_NEVER_SEND",
              "created_at": 111,
              "updated_at": 222
            },
            {
              "id": "entry-manual",
              "title": "Manual Empty",
              "enabled": true,
              "activation": {
                "mode": "manual",
                "keys": [],
                "secondary_keys": [],
                "match": "all",
                "case_sensitive": true,
                "whole_words": false
              },
              "canon": {"content": "MANUAL_CANON_SHOULD_NOT_FALLBACK", "locked": false},
              "current": {"content": ""},
              "injection": {"priority": -17, "position": "lore"},
              "tags": [],
              "notes": "",
              "created_at": 333,
              "updated_at": 444
            }
          ]
        }
        """.trimIndent()
}
