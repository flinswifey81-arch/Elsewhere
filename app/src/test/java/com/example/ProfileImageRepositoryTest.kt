package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileImageRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: ProfileImageRepository
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "profile_images").deleteRecursively()
        repository = ProfileImageRepository(context)
        source = File(context.cacheDir, "profile-image-source-${System.nanoTime()}.png")
        Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.rgb(96, 64, 128))
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        source.delete()
        File(context.filesDir, "profile_images").deleteRecursively()
    }

    @Test
    fun importNormalizesIntoPrivateHashedStorageSeparatedByProfileKind() = runBlocking {
        source.appendBytes("PRIVATE_SOURCE_METADATA".toByteArray())

        val persona = repository.replaceImage(ProfileImageKind.PERSONA, "shared/id", Uri.fromFile(source))
        val character = repository.replaceImage(ProfileImageKind.CHARACTER, "shared/id", Uri.fromFile(source))

        assertTrue(persona.isFile)
        assertTrue(character.isFile)
        assertNotEquals(persona.canonicalPath, character.canonicalPath)
        assertFalse(persona.name.contains("shared"))
        assertFalse(persona.name.contains("id"))
        assertFalse(String(persona.readBytes(), Charsets.ISO_8859_1).contains("PRIVATE_SOURCE_METADATA"))
        assertNotNull(BitmapFactoryCompat.decode(persona))
    }

    @Test
    fun invalidReplacementLeavesExistingPictureByteForByte() = runBlocking {
        val existing = repository.replaceImage(ProfileImageKind.CHARACTER, "character-1", Uri.fromFile(source))
        val expected = existing.readBytes()
        val invalid = File(context.cacheDir, "not-an-image-${System.nanoTime()}.png")
        try {
            invalid.writeText("not an image")
            val result = runCatching {
                repository.replaceImage(ProfileImageKind.CHARACTER, "character-1", Uri.fromFile(invalid))
            }

            assertTrue(result.isFailure)
            assertArrayEquals(expected, repository.resolveImage(ProfileImageKind.CHARACTER, "character-1")!!.readBytes())
        } finally {
            invalid.delete()
        }
    }

    @Test
    fun copyAndRemoveUseStableProfileAssociations() = runBlocking {
        repository.replaceImage(ProfileImageKind.PERSONA, "persona-original", Uri.fromFile(source))

        assertTrue(repository.copyImage(ProfileImageKind.PERSONA, "persona-original", "persona-copy"))
        assertArrayEquals(
            repository.resolveImage(ProfileImageKind.PERSONA, "persona-original")!!.readBytes(),
            repository.resolveImage(ProfileImageKind.PERSONA, "persona-copy")!!.readBytes()
        )
        assertTrue(repository.removeImage(ProfileImageKind.PERSONA, "persona-original"))
        assertFalse(repository.resolveImage(ProfileImageKind.PERSONA, "persona-original")?.exists() == true)
        assertTrue(repository.resolveImage(ProfileImageKind.PERSONA, "persona-copy")?.isFile == true)
    }

    @Test
    fun localPictureNeverEntersModelContextExportsOrAndroidBackups() = runBlocking {
        val image = repository.replaceImage(ProfileImageKind.CHARACTER, "character-1", Uri.fromFile(source))
        val personaImage = repository.replaceImage(ProfileImageKind.PERSONA, "persona-1", Uri.fromFile(source))
        val character = CharacterEntity(
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
        val persona = PersonaEntity(
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
        val current = MessageEntity(
            chatId = "chat",
            speakerType = SpeakerType.PERSONA,
            speakerId = persona.personaId,
            speakerDisplayNameSnapshot = persona.displayName,
            content = "Hello",
            orderIndex = 1
        )

        val compiled = ContextCompilerV1().compileSoloContext(
            character = character,
            persona = persona,
            chatHistory = emptyList(),
            currentMessage = current,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null
        ).joinToString("\n") { it.content }

        assertFalse(compiled.contains(image.absolutePath))
        assertFalse(compiled.contains(personaImage.absolutePath))
        assertFalse(compiled.contains("profile_images"))
        assertFalse(compiled.contains(ProfileImageRepository.storageKey(ProfileImageKind.CHARACTER, character.characterId)))

        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.characterDao().insertCharacter(character)
            database.personaDao().insertPersona(persona)
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val characterRepository = CharacterRepository(database.characterDao(), moshi, repository)
            val personaRepository = PersonaRepository(database.personaDao(), moshi, repository)
            val characterExport = characterRepository.exportCharacterJson(character.characterId)
            val personaExport = personaRepository.exportPersonaJson(persona.personaId)

            listOf(characterExport, personaExport).forEach { exported ->
                assertFalse(exported.contains("profile_images"))
                assertFalse(exported.contains(image.name))
                assertFalse(exported.contains(personaImage.name))
                assertFalse(exported.contains(image.absolutePath))
                assertFalse(exported.contains(personaImage.absolutePath))
            }
            assertNotEquals(
                character.characterId,
                characterRepository.importCharacterJson(characterExport, importAsNew = true)
            )
            assertNotEquals(
                persona.personaId,
                personaRepository.importPersonaJson(personaExport, importAsNew = true)
            )
        } finally {
            database.close()
        }

        assertTrue(privateFileRootExclusionCount(R.xml.backup_rules) == 1)
        assertTrue(privateFileRootExclusionCount(R.xml.data_extraction_rules) == 2)
    }

    private fun privateFileRootExclusionCount(resourceId: Int): Int {
        val parser = context.resources.getXml(resourceId)
        var count = 0
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (
                parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                parser.name == "exclude" &&
                parser.getAttributeValue(null, "domain") == "file" &&
                parser.getAttributeValue(null, "path") == "."
            ) {
                count += 1
            }
            parser.next()
        }
        parser.close()
        return count
    }

    private object BitmapFactoryCompat {
        fun decode(file: File): Bitmap? = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    }
}
