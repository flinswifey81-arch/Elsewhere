package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A replacement must never turn a multi-year RP into history-only. */
@RunWith(RobolectricTestRunner::class)
class SameIdJsonReplaceTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var chars: CharacterRepository
    private lateinit var personas: PersonaRepository
    private lateinit var chats: ChatRepository
    private lateinit var images: ProfileImageRepository
    private lateinit var worlds: WorldRepository
    private val coordinator = AppDataMaintenanceCoordinator()
    private var photo: File? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "profile_images").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        images = ProfileImageRepository(context, coordinator)
        chars = CharacterRepository(db.characterDao(), moshi, images, coordinator)
        personas = PersonaRepository(db.personaDao(), moshi, images, coordinator)
        chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
        worlds = WorldRepository(db, coordinator)
    }
    @After fun tearDown() {
        db.close()
        File(context.filesDir, "profile_images").deleteRecursively()
        photo?.delete()
    }

    private fun imageFile(): File {
        val file = File(context.cacheDir, "same-id-qa-${System.nanoTime()}.png")
        Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.CYAN)
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        photo = file
        return file
    }
    private fun characterJson(id: String, label: String, voice: String) = """
        {
          "schema_version": 1, "character_id": "$id",
          "display_name": "$label", "character_name": "$label",
          "aliases": [], "voice": {"style": "$voice"},
          "personality": {"summary": "Stay in character."}
        }
    """.trimIndent()
    private fun personaJson(id: String, label: String) = """
        {
          "schema_version": 1, "persona_id": "$id",
          "display_name": "$label", "persona_name": "$label",
          "aliases": [], "roleplay_profile": {"goal": "Stay curious."}
        }
    """.trimIndent()

    @Test fun replacingBotJsonPreservesChatMessagesMemoryLinksWorldAndAvatar() = runBlocking {
        val charId = chars.importCharacterJson(characterJson("long-lived-bot", "Before", "old"))
        val personaId = personas.importPersonaJson(personaJson("long-lived-persona", "Player"))
        val chatId = chats.createSoloChat("Hundreds of hours of history", personaId, charId)
        val first = MessageEntity(
            messageId = "historic-turn-1", chatId = chatId,
            speakerType = SpeakerType.CHARACTER, speakerId = charId,
            speakerDisplayNameSnapshot = "Before",
            content = "Our story continues.", orderIndex = 1L
        )
        db.messageDao().insertMessage(first)
        db.storyMemoryDao().insert(StoryMemoryEntity(
            memoryId = "memory-from-deep-in-chat", chatId = chatId, ownerId = chatId,
            content = "The Character and player made a promise.",
            sourceOrderIndex = 1L
        ))
        db.backupDao().insertRoleplayMemories(listOf(RoleplayMemoryEntity(
            memoryId = "legacy-promise", chatId = chatId, characterId = charId,
            category = MemoryCategory.PROMISE, content = "A long-standing promise."
        )))
        val world = worlds.createWorld("Ongoing World")
        worlds.attach(world.worldId, WorldResourceType.CHARACTER, charId)
        val file = imageFile()
        images.replaceImage(ProfileImageKind.CHARACTER, charId, Uri.fromFile(file))
        val avatar = requireNotNull(images.resolveImage(ProfileImageKind.CHARACTER, charId))
        val original = requireNotNull(chars.getCharacterById(charId))
        val originalMessages = db.messageDao().getMessagesForChat(chatId).first()
        val originalHistory = requireNotNull(chats.getChatSummaryById(chatId))

        assertThrows(DuplicateIdException::class.java) {
            runBlocking { chars.importCharacterJson(characterJson(charId, "After", "new")) }
        }
        assertEquals("Before", chars.getCharacterById(charId)?.displayName)
        assertEquals(originalMessages, db.messageDao().getMessagesForChat(chatId).first())

        assertEquals(charId, chars.importCharacterJson(
            characterJson(charId, "After", "new"), replaceExisting = true
        ))
        val replaced = requireNotNull(chars.getCharacterById(charId))
        assertEquals(charId, replaced.characterId)
        assertEquals("After", replaced.displayName)
        assertTrue(requireNotNull(replaced.voiceJson).contains("new"))
        assertEquals(original.createdAt, replaced.createdAt)
        assertTrue(replaced.updatedAt >= original.updatedAt)
        assertEquals(1, db.backupDao().getCharacters().size)
        assertEquals(listOf(charId),
            requireNotNull(chats.getChatSummaryById(chatId)).characters.map { it.characterId })
        assertEquals(originalHistory.chat.chatId, chats.getChatSummaryById(chatId)?.chat?.chatId)
        assertEquals(originalMessages, db.messageDao().getMessagesForChat(chatId).first())
        assertEquals("Before", db.messageDao().getMessageById(first.messageId)?.speakerDisplayNameSnapshot)
        assertEquals("memory-from-deep-in-chat",
            db.storyMemoryDao().forChat(chatId).single().memoryId)
        assertEquals(listOf("legacy-promise"), db.backupDao().getRoleplayMemories()
            .filter { it.chatId == chatId }.map { it.memoryId })
        assertEquals(charId,
            worlds.getMemberships(world.worldId).single().resourceId)
        assertEquals(avatar.absolutePath,
            images.resolveImage(ProfileImageKind.CHARACTER, charId)?.absolutePath)
        assertTrue(avatar.exists())

        // A second update should STILL leave the chat attached and usable.
        chars.importCharacterJson(characterJson(charId, "Fixed again", "v3"), replaceExisting = true)
        assertEquals(listOf(charId),
            requireNotNull(chats.getChatSummaryById(chatId)).characters.map { it.characterId })
        assertEquals(originalMessages, db.messageDao().getMessagesForChat(chatId).first())
    }

    @Test fun replacingPersonaJsonPreservesOngoingParticipantIdentityAndAvatar() = runBlocking {
        val charId = chars.importCharacterJson(characterJson("steady-char", "Companion", "calm"))
        val personaId = personas.importPersonaJson(personaJson("steady-persona", "Old Player"))
        val chatId = chats.createSoloChat("Ongoing Persona Story", personaId, charId)
        val message = MessageEntity(
            messageId = "persona-turn", chatId = chatId,
            speakerType = SpeakerType.PERSONA, speakerId = personaId,
            speakerDisplayNameSnapshot = "Old Player",
            content = "I stay.", orderIndex = 2L
        )
        db.messageDao().insertMessage(message)
        val file = imageFile()
        images.replaceImage(ProfileImageKind.PERSONA, personaId, Uri.fromFile(file))
        val avatar = requireNotNull(images.resolveImage(ProfileImageKind.PERSONA, personaId))
        val before = requireNotNull(personas.getPersonaById(personaId))

        assertThrows(DuplicateIdException::class.java) {
            runBlocking { personas.importPersonaJson(personaJson(personaId, "New Player")) }
        }
        assertEquals(personaId, personas.importPersonaJson(
            personaJson(personaId, "New Player"), replaceExisting = true
        ))
        val after = requireNotNull(personas.getPersonaById(personaId))
        assertEquals("New Player", after.displayName)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(1, db.backupDao().getPersonas().size)
        assertEquals(listOf(personaId),
            requireNotNull(chats.getChatSummaryById(chatId)).personas.map { it.personaId })
        assertEquals(message, db.messageDao().getMessageById(message.messageId))
        assertEquals(avatar.absolutePath,
            images.resolveImage(ProfileImageKind.PERSONA, personaId)?.absolutePath)
        assertTrue(avatar.exists())
    }

    @Test fun missingOrAmbiguousReplaceIsRefusedWithoutModifyingLibrary() = runBlocking {
        val originalId = chars.importCharacterJson(characterJson("protected", "Original", "old"))
        val old = chars.getCharacterById(originalId)
        val missingId = characterJson("not-installed", "Different", "new")

        assertTrue(runCatching {
            chars.importCharacterJson(missingId, replaceExisting = true)
        }.isFailure)
        assertTrue(runCatching {
            chars.importCharacterJson("""{"display_name":"No ID"}""", replaceExisting = true)
        }.isFailure)
        assertTrue(runCatching {
            chars.importCharacterJson(
                characterJson(originalId, "Oops", "new"),
                replaceExisting = true, importAsNew = true
            )
        }.isFailure)
        assertEquals(old, chars.getCharacterById(originalId))
        assertEquals(1, db.backupDao().getCharacters().size)

        val newCopy = chars.importCharacterJson(
            characterJson(originalId, "Separate Copy", "v2"), importAsNew = true
        )
        assertNotEquals(originalId, newCopy)
        assertEquals("Original", chars.getCharacterById(originalId)?.displayName)
        assertEquals("Separate Copy", chars.getCharacterById(newCopy)?.displayName)

        val personaId = personas.importPersonaJson(personaJson("protected-p", "Original Player"))
        val oldPersona = personas.getPersonaById(personaId)
        assertTrue(runCatching {
            personas.importPersonaJson(
                personaJson("nonexistent-p", "New Player"), replaceExisting = true
            )
        }.isFailure)
        assertTrue(runCatching {
            personas.importPersonaJson(
                personaJson(personaId, "Ambiguous"),
                replaceExisting = true, importAsNew = true
            )
        }.isFailure)
        assertEquals(oldPersona, personas.getPersonaById(personaId))
    }
}
