package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ManualCharacterFields
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManualCharacterTest {
    private lateinit var database: AppDatabase
    private lateinit var characterRepository: CharacterRepository

    private val fields = ManualCharacterFields(
        name = "  Mira of Rain  ",
        shortBackstory = "First line\n\n  indented backstory  ",
        initialMessage = "*Rain taps the glass.*\n\nHello...  ",
        systemInstructions = "Stay in character.\n\n  Preserve this indentation.  ",
        personality = "Patient, but not passive.\nKeeps exact spacing.",
        tone = "Warm; restrained.",
        age = "29 years",
        birthday = "Winter's final day",
        story = "Act I\nAct II\nAct III",
        likes = "Tea\nOld maps",
        dislikes = "Cruelty\n  Loud rooms",
        conversationalGoals = "Ask before assuming.\nKeep Mira's secrets.",
        conversationalExamples = "User: Stay?\nMira: Always.",
        appearance = "Silver braid\nWeathered blue coat",
        knowledgeRelationships = "Ren — trusted sibling\nAri — unknown",
        knowledgeGeneral = "Knows the northern roads.\nDoes not know modern slang."
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        characterRepository = CharacterRepository(database.characterDao(), moshi)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `manual character creation persists and reloads exact text`() = runBlocking {
        val id = characterRepository.createManualCharacter(fields)
        val saved = characterRepository.getCharacterById(id)

        assertNotNull(saved)
        assertTrue(id.isNotBlank())
        assertEquals(id, saved!!.characterId)
        assertEquals(fields, characterRepository.getManualFields(saved))
        assertTrue(characterRepository.getAllCharacters().first().any { it.characterId == id })
    }

    @Test
    fun `editing updates the same stable character id`() = runBlocking {
        val id = characterRepository.createManualCharacter(fields)
        val edited = fields.copy(
            name = "Mira Updated",
            story = "  replacement story\nwith trailing space  ",
            conversationalGoals = "New goal\n\nSecond goal"
        )

        characterRepository.updateManualCharacter(id, edited)
        val reloaded = characterRepository.getCharacterById(id)!!

        assertEquals(id, reloaded.characterId)
        assertEquals("Mira Updated", reloaded.displayName)
        assertEquals(edited, characterRepository.getManualFields(reloaded))
    }

    @Test
    fun `editing imported character preserves imported and custom data`() = runBlocking {
        val importedJson = """
            {
              "schema_version": 7,
              "character_id": "imported-id",
              "display_name": "Imported Display",
              "character_name": "Imported Name",
              "aliases": ["Unchanged Alias"],
              "identity": {"age": "ancient", "pronouns": "they/them"},
              "personality": {"summary": "Original summary", "custom_trait": {"score": 9}},
              "author_notes": {"private": "preserve me"},
              "future_custom_data": {"nested": [1, 2, 3]}
            }
        """.trimIndent()
        val id = characterRepository.importCharacterJson(importedJson)
        val before = characterRepository.getCharacterById(id)!!

        assertEquals("ancient", characterRepository.getManualFields(before).age)
        characterRepository.updateManualCharacter(id, fields.copy(name = "Edited Imported Name"))
        val after = characterRepository.getCharacterById(id)!!

        assertEquals("imported-id", after.characterId)
        assertEquals(7, after.importSchemaVersion)
        assertEquals(listOf("Unchanged Alias"), after.aliases)
        assertEquals(before.identityJson, after.identityJson)
        assertEquals(before.personalityJson, after.personalityJson)
        assertEquals(before.authorNotesJson, after.authorNotesJson)
        val exported = characterRepository.exportCharacterJson(id)
        assertTrue(exported.contains("future_custom_data"))
        assertTrue(exported.contains("\"nested\""))
        assertEquals(fields.copy(name = "Edited Imported Name"), characterRepository.getManualFields(after))
    }

    @Test
    fun `manual character fields are included in compiled context`() = runBlocking {
        val id = characterRepository.createManualCharacter(fields)
        val character = characterRepository.getCharacterById(id)!!
        val context = ContextCompilerV1().compileSoloContext(
            character = character,
            persona = persona(),
            chatHistory = emptyList(),
            currentMessage = MessageEntity(
                chatId = "chat",
                speakerType = SpeakerType.PERSONA,
                speakerId = "persona",
                speakerDisplayNameSnapshot = "Persona",
                content = "Hello",
                orderIndex = 1L
            ),
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null
        ).first().content

        listOf(
            fields.shortBackstory,
            fields.personality,
            fields.tone,
            fields.story,
            fields.likes,
            fields.dislikes,
            fields.conversationalGoals,
            fields.conversationalExamples,
            fields.appearance,
            fields.knowledgeRelationships,
            fields.knowledgeGeneral
        ).forEach { expected -> assertTrue("Missing compiled value: $expected", context.contains(expected)) }
        assertTrue(context.contains("Conversational Goals:"))
    }

    @Test
    fun `initial message becomes first message in a new solo chat`() = runBlocking {
        val characterId = characterRepository.createManualCharacter(fields)
        val character = characterRepository.getCharacterById(characterId)!!
        val persona = persona()
        database.personaDao().insertPersona(persona)
        val chatRepository = ChatRepository(database.chatDao(), database.messageDao())

        val chatId = chatRepository.createSoloChat(
            displayName = "Mira Chat",
            personaId = persona.personaId,
            characterId = characterId,
            characterDisplayName = character.displayName,
            initialCharacterMessage = characterRepository.getInitialMessage(character)
        )
        val messages = database.messageDao().getMessagesForChat(chatId).first()

        assertEquals(1, messages.size)
        assertEquals(SpeakerType.CHARACTER, messages.single().speakerType)
        assertEquals(characterId, messages.single().speakerId)
        assertEquals(fields.initialMessage, messages.single().content)
        assertNotEquals("", messages.single().messageId)
    }

    @Test
    fun `system instructions save and reload`() = runBlocking {
        val id = characterRepository.createManualCharacter(fields)

        val reloaded = characterRepository.getCharacterById(id)!!

        assertEquals(fields.systemInstructions, characterRepository.getManualFields(reloaded).systemInstructions)
    }

    @Test
    fun `system instructions persist after editing`() = runBlocking {
        val id = characterRepository.createManualCharacter(fields)
        val editedInstructions = "Use the revised rules.\nNever narrate for the user."

        characterRepository.updateManualCharacter(
            id,
            characterRepository.getManualFields(characterRepository.getCharacterById(id)!!)
                .copy(systemInstructions = editedInstructions)
        )

        val reloaded = characterRepository.getCharacterById(id)!!
        assertEquals(editedInstructions, characterRepository.getManualFields(reloaded).systemInstructions)
    }

    @Test
    fun `system instructions preserve exact text`() = runBlocking {
        val exact = "  leading spaces\n\nline with trailing spaces  \n\tTabbed line\n"
        val id = characterRepository.createManualCharacter(fields.copy(systemInstructions = exact))

        val reloaded = characterRepository.getCharacterById(id)!!

        assertEquals(exact, characterRepository.getManualFields(reloaded).systemInstructions)
    }

    @Test
    fun `system instructions are included in compiled system context`() = runBlocking {
        val exact = "Remain in first person.\nDo not reveal the hidden oath."
        val id = characterRepository.createManualCharacter(fields.copy(systemInstructions = exact))
        val character = characterRepository.getCharacterById(id)!!

        val compiled = ContextCompilerV1().compileSoloContext(
            character = character,
            persona = persona(),
            chatHistory = emptyList(),
            currentMessage = MessageEntity(
                chatId = "chat",
                speakerType = SpeakerType.PERSONA,
                speakerId = "persona",
                speakerDisplayNameSnapshot = "Persona",
                content = "Hello",
                orderIndex = 1L
            ),
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null
        )

        assertEquals(com.example.domain.provider.Role.SYSTEM, compiled.first().role)
        assertTrue(compiled.first().content.contains("--- CHARACTER SYSTEM INSTRUCTIONS ---\n$exact"))
    }

    private fun persona() = PersonaEntity(
        personaId = "persona",
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
}
