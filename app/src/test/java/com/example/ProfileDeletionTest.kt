package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ManualCharacterFields
import com.example.data.model.ManualPersonaFields
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.ProfileDeletionResult
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileDeletionTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var characterRepository: CharacterRepository
    private lateinit var personaRepository: PersonaRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var profileImageRepository: ProfileImageRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        java.io.File(context.filesDir, "profile_images").deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        profileImageRepository = ProfileImageRepository(context)
        characterRepository = CharacterRepository(database.characterDao(), moshi, profileImageRepository)
        personaRepository = PersonaRepository(database.personaDao(), moshi, profileImageRepository)
        chatRepository = ChatRepository(database.chatDao(), database.messageDao())
    }

    @After
    fun tearDown() {
        database.close()
        java.io.File(context.filesDir, "profile_images").deleteRecursively()
    }

    @Test
    fun unusedCharacterIsPermanentlyDeleted() = runBlocking {
        val characterId = characterRepository.createManualCharacter(ManualCharacterFields(name = "Unused"))

        val result = characterRepository.deleteCharacterPermanently(characterId)

        assertEquals(ProfileDeletionResult.Deleted(0, 0), result)
        assertNull(characterRepository.getCharacterById(characterId))
        assertEquals(emptyList<Any>(), characterRepository.getAllCharacters().first())
    }

    @Test
    fun deletingProfilesAlsoRemovesTheirDeviceLocalPictures() = runBlocking {
        val characterId = characterRepository.createManualCharacter(ManualCharacterFields(name = "Portrait Character"))
        val personaId = personaRepository.createManualPersona(ManualPersonaFields(name = "Portrait Persona"))
        val source = java.io.File(context.cacheDir, "profile-delete-picture-${System.nanoTime()}.png")
        try {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(Color.MAGENTA)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            profileImageRepository.replaceImage(ProfileImageKind.CHARACTER, characterId, Uri.fromFile(source))
            profileImageRepository.replaceImage(ProfileImageKind.PERSONA, personaId, Uri.fromFile(source))

            characterRepository.deleteCharacterPermanently(characterId)
            personaRepository.deletePersonaPermanently(personaId)

            assertFalse(profileImageRepository.resolveImage(ProfileImageKind.CHARACTER, characterId)?.exists() == true)
            assertFalse(profileImageRepository.resolveImage(ProfileImageKind.PERSONA, personaId)?.exists() == true)
        } finally {
            source.delete()
        }
    }

    @Test
    fun deletingSoloCharacterKeepsChatHistoryAndMakesTheChatHistoryOnly() = runBlocking {
        val characterId = characterRepository.createManualCharacter(ManualCharacterFields(name = "In Use"))
        val personaId = personaRepository.createManualPersona(ManualPersonaFields(name = "Persona"))
        val chatId = chatRepository.createSoloChat("Existing Story", personaId, characterId)
        database.messageDao().insertMessage(
            MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.CHARACTER,
                speakerId = characterId,
                speakerDisplayNameSnapshot = "In Use",
                content = "Preserve this history.",
                orderIndex = 1L
            )
        )

        val result = characterRepository.deleteCharacterPermanently(characterId)

        assertEquals(ProfileDeletionResult.Deleted(affectedChatCount = 1, historyOnlyChatCount = 1), result)
        assertNull(characterRepository.getCharacterById(characterId))
        assertEquals(emptyList<Any>(), chatRepository.getChatSummaryById(chatId)!!.characters)
        assertEquals("Preserve this history.", database.messageDao().getMessagesForChat(chatId).first().single().content)
    }

    @Test
    fun unusedPersonaIsPermanentlyDeleted() = runBlocking {
        val personaId = personaRepository.createManualPersona(ManualPersonaFields(name = "Unused"))

        val result = personaRepository.deletePersonaPermanently(personaId)

        assertEquals(ProfileDeletionResult.Deleted(0, 0), result)
        assertNull(personaRepository.getPersonaById(personaId))
        assertEquals(emptyList<Any>(), personaRepository.getAllPersonas().first())
    }

    @Test
    fun deletingSoloPersonaKeepsChatHistoryAndMakesTheChatHistoryOnly() = runBlocking {
        val characterId = characterRepository.createManualCharacter(ManualCharacterFields(name = "Character"))
        val personaId = personaRepository.createManualPersona(ManualPersonaFields(name = "In Use"))
        val chatId = chatRepository.createSoloChat("Existing Story", personaId, characterId)
        database.messageDao().insertMessage(
            MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = personaId,
                speakerDisplayNameSnapshot = "In Use",
                content = "Keep my message.",
                orderIndex = 1L
            )
        )

        val result = personaRepository.deletePersonaPermanently(personaId)

        assertEquals(ProfileDeletionResult.Deleted(affectedChatCount = 1, historyOnlyChatCount = 1), result)
        assertNull(personaRepository.getPersonaById(personaId))
        assertEquals(emptyList<Any>(), chatRepository.getChatSummaryById(chatId)!!.personas)
        assertEquals("Keep my message.", database.messageDao().getMessagesForChat(chatId).first().single().content)
    }

    @Test
    fun deletingOneGroupCharacterRetainsItsMessagesAndLeavesOtherCharacterActive() = runBlocking {
        val firstCharacterId = characterRepository.createManualCharacter(ManualCharacterFields(name = "First"))
        val secondCharacterId = characterRepository.createManualCharacter(ManualCharacterFields(name = "Second"))
        val personaId = personaRepository.createManualPersona(ManualPersonaFields(name = "Persona"))
        val chatId = chatRepository.createGroupChat(
            displayName = "Group",
            personaIds = listOf(personaId),
            characterIds = listOf(firstCharacterId, secondCharacterId)
        )
        database.messageDao().insertMessage(
            MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.CHARACTER,
                speakerId = firstCharacterId,
                speakerDisplayNameSnapshot = "First",
                content = "A historical reply.",
                orderIndex = 1L
            )
        )

        val result = characterRepository.deleteCharacterPermanently(firstCharacterId)

        assertEquals(ProfileDeletionResult.Deleted(affectedChatCount = 1, historyOnlyChatCount = 0), result)
        assertNull(characterRepository.getCharacterById(firstCharacterId))
        assertEquals(
            listOf(secondCharacterId),
            chatRepository.getChatSummaryById(chatId)!!.characters.map { it.characterId }
        )
        val preserved = database.messageDao().getMessagesForChat(chatId).first().single()
        assertEquals(firstCharacterId, preserved.speakerId)
        assertEquals("First", preserved.speakerDisplayNameSnapshot)
        assertEquals("A historical reply.", preserved.content)
    }

    @Test
    fun deletingOneGroupPersonaRetainsMessagesAndLeavesOtherPersonaActive() = runBlocking {
        val characterId = characterRepository.createManualCharacter(ManualCharacterFields(name = "Character"))
        val firstPersonaId = personaRepository.createManualPersona(ManualPersonaFields(name = "First Persona"))
        val secondPersonaId = personaRepository.createManualPersona(ManualPersonaFields(name = "Second Persona"))
        val chatId = chatRepository.createGroupChat(
            displayName = "Legacy Multi-Persona Group",
            personaIds = listOf(firstPersonaId, secondPersonaId),
            characterIds = listOf(characterId)
        )
        database.messageDao().insertMessage(
            MessageEntity(
                chatId = chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = firstPersonaId,
                speakerDisplayNameSnapshot = "First Persona",
                content = "A historical Persona message.",
                orderIndex = 1L
            )
        )

        val result = personaRepository.deletePersonaPermanently(firstPersonaId)

        assertEquals(ProfileDeletionResult.Deleted(affectedChatCount = 1, historyOnlyChatCount = 0), result)
        assertNull(personaRepository.getPersonaById(firstPersonaId))
        assertEquals(
            listOf(secondPersonaId),
            chatRepository.getChatSummaryById(chatId)!!.personas.map { it.personaId }
        )
        val preserved = database.messageDao().getMessagesForChat(chatId).first().single()
        assertEquals(firstPersonaId, preserved.speakerId)
        assertEquals("First Persona", preserved.speakerDisplayNameSnapshot)
        assertEquals("A historical Persona message.", preserved.content)
    }
}
