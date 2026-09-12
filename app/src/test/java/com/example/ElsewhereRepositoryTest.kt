package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.DuplicateIdException
import com.example.domain.repository.PersonaRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ElsewhereRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var characterRepo: CharacterRepository
    private lateinit var personaRepo: PersonaRepository
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        characterRepo = CharacterRepository(database.characterDao(), moshi)
        personaRepo = PersonaRepository(database.personaDao(), moshi)
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun getResource(name: String): String {
        return javaClass.classLoader?.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() } ?: ""
    }

    @Test
    fun testMinimalCharacterImport() = runBlocking {
        val json = getResource("minimal_character.json")
        val id = characterRepo.importCharacterJson(json)
        val char = characterRepo.getCharacterById(id)
        assertNotNull(char)
        assertEquals("Minimal Char", char?.displayName)
        assertEquals(1, char?.importSchemaVersion)
        assertNull(char?.extensionFieldsJson)
    }

    @Test
    fun testRichCharacterImport() = runBlocking {
        val json = getResource("rich_character.json")
        val id = characterRepo.importCharacterJson(json)
        val char = characterRepo.getCharacterById(id)
        assertNotNull(char)
        assertEquals("Lyra", char?.characterName)
        assertNotNull(char?.identityJson)
        assertNotNull(char?.personalityJson)
    }

    @Test
    fun testPersonaImport() = runBlocking {
        val json = getResource("minimal_persona.json")
        val id = personaRepo.importPersonaJson(json)
        val persona = personaRepo.getPersonaById(id)
        assertNotNull(persona)
        assertEquals("Minimal Persona", persona?.displayName)
    }

    @Test(expected = Exception::class)
    fun testMalformedJsonHandling() = runBlocking {
        val json = getResource("malformed.json")
        characterRepo.importCharacterJson(json)
        Unit
    }

    @Test(expected = Exception::class)
    fun testMissingRequiredFieldHandling() = runBlocking {
        val json = getResource("missing_fields_character.json")
        characterRepo.importCharacterJson(json)
        Unit
    }

    @Test
    fun testDuplicateIdBehavior() = runBlocking {
        val json = getResource("minimal_character.json")
        characterRepo.importCharacterJson(json)
        
        // Should throw DuplicateIdException if neither replace nor asNew is true
        var threw = false
        try {
            characterRepo.importCharacterJson(json)
        } catch (e: DuplicateIdException) {
            threw = true
        }
        assertTrue(threw)

        // Should succeed with importAsNew
        val newId = characterRepo.importCharacterJson(json, importAsNew = true)
        assertNotEquals("min-char-1", newId)

        // Should succeed with replaceExisting
        val originalJsonMod = json.replace("Minimal Char", "Replaced Char")
        characterRepo.importCharacterJson(originalJsonMod, replaceExisting = true)
        val char = characterRepo.getCharacterById("min-char-1")
        assertEquals("Replaced Char", char?.displayName)
    }

    @Test
    fun testExtensionPreservation() = runBlocking {
        val json = getResource("extension_character.json")
        val id = characterRepo.importCharacterJson(json)
        val char = characterRepo.getCharacterById(id)
        assertNotNull(char?.extensionFieldsJson)
        
        val exportJson = characterRepo.exportCharacterJson(id)
        assertTrue(exportJson.contains("custom_future_section"))
        assertTrue(exportJson.contains("magic_power"))
        assertTrue(exportJson.contains("some_unknown_string"))
    }

    @Test
    fun testCharacterExportReimport() = runBlocking {
        val json = getResource("rich_character.json")
        val id = characterRepo.importCharacterJson(json)
        
        val exportedJson = characterRepo.exportCharacterJson(id)
        val newId = characterRepo.importCharacterJson(exportedJson, importAsNew = true)
        
        val reimported = characterRepo.getCharacterById(newId)
        assertNotNull(reimported)
        assertEquals("Lyra", reimported?.characterName)
        assertNotNull(reimported?.identityJson)
    }
    
    @Test
    fun testDuplicateCharacterGetsNewId() = runBlocking {
        val json = getResource("minimal_character.json")
        val originalId = characterRepo.importCharacterJson(json)
        
        val duplicateId = characterRepo.duplicateCharacter(originalId, "Duplicate Minimal Char")
        assertNotEquals(originalId, duplicateId)
        
        val duplicate = characterRepo.getCharacterById(duplicateId)
        assertEquals("Duplicate Minimal Char", duplicate?.displayName)
        assertEquals("Minimal", duplicate?.characterName) // Same characterName works
    }
}
