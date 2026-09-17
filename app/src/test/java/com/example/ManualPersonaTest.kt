package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ManualPersonaFields
import com.example.data.model.MessageEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.provider.Role
import com.example.domain.repository.PersonaRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManualPersonaTest {
    private lateinit var database: AppDatabase
    private lateinit var personaRepository: PersonaRepository

    private val fields = ManualPersonaFields(
        name = "  Rowan Vale  ",
        backstoryInstructions = "Raised beside the old harbor.\n\n  Respond as Rowan's lived perspective.  "
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        personaRepository = PersonaRepository(database.personaDao(), moshi)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `manual persona creates saves reloads and is immediately available`() = runBlocking {
        val id = personaRepository.createManualPersona(fields)
        val saved = personaRepository.getPersonaById(id)

        assertNotNull(saved)
        assertTrue(id.isNotBlank())
        assertEquals(id, saved!!.personaId)
        assertEquals(fields, personaRepository.getManualFields(saved))
        assertTrue(personaRepository.getAllPersonas().first().any { it.personaId == id })
    }

    @Test
    fun `editing imported persona persists on the same id without losing imported data`() = runBlocking {
        val importedJson = """
            {
              "schema_version": 4,
              "persona_id": "imported-persona-id",
              "display_name": "Imported Display",
              "persona_name": "Imported Persona",
              "aliases": ["Existing Alias"],
              "identity": {"pronouns": "they/them"},
              "background": {"summary": "Existing background", "custom": {"keep": true}},
              "private_notes": {"secret": "keep private"},
              "future_persona_data": {"values": [1, 2, 3]}
            }
        """.trimIndent()
        val id = personaRepository.importPersonaJson(importedJson)
        val before = personaRepository.getPersonaById(id)!!
        val edited = fields.copy(
            name = "Edited Persona",
            backstoryInstructions = "Edited instruction text"
        )

        personaRepository.updateManualPersona(id, edited)
        val after = personaRepository.getPersonaById(id)!!

        assertEquals("imported-persona-id", after.personaId)
        assertEquals(4, after.importSchemaVersion)
        assertEquals(listOf("Existing Alias"), after.aliases)
        assertEquals(before.identityJson, after.identityJson)
        assertEquals(before.backgroundJson, after.backgroundJson)
        assertEquals(before.privateNotesJson, after.privateNotesJson)
        assertEquals(edited, personaRepository.getManualFields(after))
        assertTrue(personaRepository.exportPersonaJson(id).contains("future_persona_data"))
    }

    @Test
    fun `manual persona preserves exact entered text`() = runBlocking {
        val exact = "  leading spaces\n\nline with trailing spaces  \n\tTabbed line\n"
        val exactFields = ManualPersonaFields(
            name = " Name with surrounding spaces ",
            backstoryInstructions = exact
        )
        val id = personaRepository.createManualPersona(exactFields)

        val reloaded = personaRepository.getPersonaById(id)!!

        assertEquals(exactFields, personaRepository.getManualFields(reloaded))
    }

    @Test
    fun `persona instructions are included in compiled system context`() = runBlocking {
        val instructions = "The Character should know Rowan fears deep water.\nTreat that history as established."
        val id = personaRepository.createManualPersona(
            ManualPersonaFields(name = "Rowan", backstoryInstructions = instructions)
        )
        val persona = personaRepository.getPersonaById(id)!!

        val compiled = ContextCompilerV1().compileSoloContext(
            character = character(),
            persona = persona,
            chatHistory = emptyList(),
            currentMessage = MessageEntity(
                chatId = "chat",
                speakerType = SpeakerType.PERSONA,
                speakerId = persona.personaId,
                speakerDisplayNameSnapshot = persona.displayName,
                content = "Hello",
                orderIndex = 1L
            ),
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null
        )

        assertEquals(Role.SYSTEM, compiled.first().role)
        assertTrue(compiled.first().content.contains("--- USER PERSONA: Rowan ---"))
        assertTrue(compiled.first().content.contains("Backstory / Persona Instructions:\n$instructions"))
    }

    private fun character() = CharacterEntity(
        characterId = "character",
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
}
