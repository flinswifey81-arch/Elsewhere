package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A real Room graph with an ongoing story, same-ID NPC / Character pair and
 * switchable SOLO spotlight. The old messages and NPC variant state never move.
 */
@RunWith(RobolectricTestRunner::class)
class LivingCastPromotionTest {
    private lateinit var db: AppDatabase
    private lateinit var npc: NpcRepository
    private lateinit var chars: CharacterRepository
    private lateinit var personas: PersonaRepository
    private lateinit var chats: ChatRepository
    private val coordinator = AppDataMaintenanceCoordinator()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        npc = NpcRepository(db, moshi, coordinator)
        chars = CharacterRepository(db.characterDao(), moshi, dataCoordinator = coordinator)
        personas = PersonaRepository(db.personaDao(), moshi, dataCoordinator = coordinator)
        chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
    }
    @After fun close() { db.close() }

    private suspend fun createCharacter(id: String, name: String): String =
        chars.importCharacterJson(
            """{"schema_version":1,"character_id":"$id","display_name":"$name","character_name":"$name","aliases":[]}"""
        )
    private suspend fun createPersona(): String =
        personas.importPersonaJson(
            """{"schema_version":1,"persona_id":"player","display_name":"Player","persona_name":"Player","aliases":[]}"""
        )
    private fun dossier(chatId: String = "story", id: String = "recurring-npc") =
        NpcDossier(
            npcId = id, chatId = chatId,
            displayName = "Mara", aliases = listOf("Archivist"),
            role = "Keeper of the archive",
            appearance = "Copper braid and green coat",
            personality = "Curious and stubborn",
            relationshipToPersona = "PROMOTION_NOT_SHARED_RELATIONSHIP",
            personaRelationships = mapOf("player" to "PROMOTION_PRIVATE_PERSONA_RELATIONSHIP"),
            characterKnowledge = mapOf("main-bot" to "PROMOTION_PRIVATE_KNOWLEDGE"),
            goals = "Find the lost atlas",
            secrets = "PROMOTION_PRIVATE_SECRET",
            authorNotes = "PROMOTION_LOCAL_AUTHOR_NOTES",
            keepInScene = true,
            isManualBase = true,
            createdAt = 1L, updatedAt = 1L,
            sceneMode = "HERE"
        )

    @Test fun sameIdPromotionPreservesEntireSoloChatAndDoesNotGlobalizeSecrets() = runBlocking {
        createCharacter("main-bot", "Primary")
        createPersona()
        val chatId = chats.createSoloChat("Long RP", "player", "main-bot")
        val historical = MessageEntity(
            messageId = "long-established-turn", chatId = chatId,
            speakerType = SpeakerType.CHARACTER, speakerId = "main-bot",
            speakerDisplayNameSnapshot = "Primary",
            content = "We promised to return to the tower.", orderIndex = 5L
        )
        db.messageDao().insertMessage(historical)
        db.storyMemoryDao().insert(StoryMemoryEntity(
            memoryId = "long-term-promise", chatId = chatId, ownerId = chatId,
            content = "Their promise remains open.", sourceOrderIndex = 5L
        ))
        db.backupDao().insertRoleplayMemories(listOf(RoleplayMemoryEntity(
            memoryId = "legacy-promise", chatId = chatId, characterId = "main-bot",
            category = MemoryCategory.PROMISE, content = "Keep the old promise"
        )))
        val original = dossier(chatId)
        npc.saveManual(original)
        val beforeMessages = db.messageDao().getMessagesForChat(chatId).first()
        val beforeDossier = npc.getProjectedDossiers(chatId).single()
        val first = npc.promoteNpcToCharacter(chatId, original.npcId)
        assertTrue(first.created)
        assertTrue(first.attachedToChat)
        assertEquals(original.npcId, first.characterId)

        val card = requireNotNull(chars.getCharacterById(original.npcId))
        assertEquals(original.displayName, card.displayName)
        assertEquals(listOf("Archivist"), card.aliases)
        assertTrue(card.identityJson.orEmpty().contains("Keeper of the archive"))
        assertTrue(card.appearanceJson.orEmpty().contains("Copper braid"))
        assertTrue(card.personalityJson.orEmpty().contains("Find the lost atlas"))
        val scoped = npc.promotedSpeakerRelationshipContext(
            chatId, original.npcId, "player"
        ).orEmpty()
        assertTrue(scoped.contains("PROMOTION_PRIVATE_PERSONA_RELATIONSHIP"))
        assertFalse(scoped.contains("PROMOTION_PRIVATE_SECRET"))
        assertFalse(scoped.contains("PROMOTION_LOCAL_AUTHOR_NOTES"))
        assertNull(npc.promotedSpeakerRelationshipContext(
            chatId, original.npcId, "another-persona"
        ))
        assertNull(npc.promotedSpeakerRelationshipContext(
            chatId, original.npcId, "player", afterOrderIndex = 100L
        ))
        val exported = chars.exportCharacterJson(original.npcId)
        listOf("PROMOTION_PRIVATE_SECRET", "PROMOTION_LOCAL_AUTHOR_NOTES",
            "PROMOTION_PRIVATE_RELATIONSHIP", "PROMOTION_PRIVATE_PERSONA_RELATIONSHIP",
            "PROMOTION_PRIVATE_KNOWLEDGE"
        ).forEach { assertFalse("Do not globalize private NPC context: $it", exported.contains(it)) }
        assertEquals(2, chats.getParticipantsForChatOnce(chatId).size)
        assertEquals("main-bot",
            chats.getParticipantsForChatOnce(chatId).first().characterId)
        assertEquals(beforeMessages, db.messageDao().getMessagesForChat(chatId).first())
        assertEquals(beforeDossier, npc.getProjectedDossiers(chatId).single())
        assertEquals(listOf("long-term-promise"),
            db.storyMemoryDao().forChat(chatId).map { it.memoryId })
        assertEquals(listOf("legacy-promise"), db.backupDao().getRoleplayMemories()
            .filter { it.chatId == chatId }.map { it.memoryId })

        val second = npc.promoteNpcToCharacter(chatId, original.npcId)
        assertFalse(second.created)
        assertFalse(second.attachedToChat)
        assertEquals(2, chats.getParticipantsForChatOnce(chatId).size)
        assertEquals(2, db.backupDao().getCharacters().size)
        assertTrue(npc.observePromotedCharacterIds().first().contains(original.npcId))

        // Fixing the Character card by same-ID JSON import must not break its
        // original NPC identity or split the live chat into two copies.
        chars.importCharacterJson(
            """{"schema_version":1,"character_id":"${original.npcId}","display_name":"Mara Revised","character_name":"Mara","aliases":[]}""",
            replaceExisting = true
        )
        assertEquals("Mara Revised", chars.getCharacterById(original.npcId)?.displayName)
        assertTrue(npc.observePromotedCharacterIds().first().contains(original.npcId))
        assertFalse(npc.promoteNpcToCharacter(chatId, original.npcId).created)
        assertEquals(beforeMessages, db.messageDao().getMessagesForChat(chatId).first())
    }

    @Test fun soloSpotlightSwitchesBothWaysWithoutChangingHistoryOrScope() = runBlocking {
        createCharacter("main-bot", "Primary")
        createPersona()
        val chatId = chats.createSoloChat("Solo", "player", "main-bot")
        npc.saveManual(dossier(chatId))
        npc.promoteNpcToCharacter(chatId, "recurring-npc")
        val before = db.backupDao().getMessages()
        chats.selectSoloSpotlight(chatId, "recurring-npc")
        assertEquals("recurring-npc",
            chats.getParticipantsForChatOnce(chatId).first().characterId)
        assertEquals(setOf("main-bot", "recurring-npc"),
            requireNotNull(chats.getChatSummaryById(chatId)).characters
                .map { it.characterId }.toSet())
        chats.selectSoloSpotlight(chatId, "main-bot")
        assertEquals("main-bot",
            chats.getParticipantsForChatOnce(chatId).first().characterId)
        assertEquals(before, db.backupDao().getMessages())
        assertEquals("recurring-npc", npc.getProjectedDossiers(chatId).single().npcId)

        assertTrue(runCatching {
            chats.selectSoloSpotlight(chatId, "not-in-this-chat")
        }.isFailure)
        assertEquals("main-bot", chats.getParticipantsForChatOnce(chatId).first().characterId)
    }

    @Test fun groupPromotesIntoSameRosterWhileScenarioKeepsNarratorOnly() = runBlocking {
        createCharacter("main-bot", "Primary")
        createPersona()
        val groupId = chats.createGroupChat("Group", listOf("player"), listOf("main-bot"))
        npc.saveManual(dossier(groupId, "group-npc"))
        npc.promoteNpcToCharacter(groupId, "group-npc")
        assertEquals(listOf("main-bot", "group-npc"),
            chats.getParticipantsForChatOnce(groupId).map { it.characterId })
        assertTrue(runCatching {
            chats.selectSoloSpotlight(groupId, "group-npc")
        }.isFailure)

        val scenarioId = "scenario-story"
        db.chatDao().insertChat(ChatEntity(
            chatId = scenarioId, displayName = "Narrator-only Scenario",
            chatType = ChatType.SCENARIO
        ))
        npc.saveManual(dossier(scenarioId, "scenario-npc"))
        val result = npc.promoteNpcToCharacter(scenarioId, "scenario-npc")
        assertTrue(result.created)
        assertFalse(result.attachedToChat)
        assertTrue(chats.getParticipantsForChatOnce(scenarioId).isEmpty())
        assertNotNull(chars.getCharacterById("scenario-npc"))
        assertEquals("scenario-npc", npc.getProjectedDossiers(scenarioId).single().npcId)
    }

    @Test fun promotionCopiesNpcPortraitWithoutDeletingTheNpcOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val photos = ProfileImageRepository(context, coordinator)
        val withPhotos = NpcRepository(db, moshi, coordinator, photos)
        createCharacter("main-bot", "Primary")
        createPersona()
        val chatId = chats.createSoloChat("Portrait Story", "player", "main-bot")
        val id = "portrait-npc-${System.nanoTime()}"
        withPhotos.saveManual(dossier(chatId, id))
        val bitmap = android.graphics.Bitmap.createBitmap(
            4, 4, android.graphics.Bitmap.Config.ARGB_8888
        )
        val source = java.io.File(context.cacheDir, "portrait-source-$id.png")
        try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            source.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            photos.replaceImage(ProfileImageKind.NPC, id, android.net.Uri.fromFile(source))
            val original = requireNotNull(photos.resolveImage(ProfileImageKind.NPC, id))
            val before = original.readBytes()
            withPhotos.promoteNpcToCharacter(chatId, id)
            val copy = requireNotNull(photos.resolveImage(ProfileImageKind.CHARACTER, id))
            assertTrue(copy.exists())
            assertTrue(original.exists())
            assertArrayEquals(before, copy.readBytes())
            assertArrayEquals(before, original.readBytes())
            photos.removeImage(ProfileImageKind.CHARACTER, id)
            assertTrue(photos.resolveImage(ProfileImageKind.NPC, id)?.exists() == true)
        } finally {
            bitmap.recycle()
            source.delete()
            photos.removeImage(ProfileImageKind.NPC, id)
            photos.removeImage(ProfileImageKind.CHARACTER, id)
        }
    }

    @Test fun crossChatNpcPromotionAndUnrelatedIdentityCollisionFailClosed() = runBlocking {
        createCharacter("main-bot", "Primary")
        createPersona()
        val chatId = chats.createSoloChat("Home", "player", "main-bot")
        val other = chats.createSoloChat("Other", "player", "main-bot")
        npc.saveManual(dossier(chatId))
        assertTrue(runCatching {
            npc.promoteNpcToCharacter(other, "recurring-npc")
        }.isFailure)
        assertNull(chars.getCharacterById("recurring-npc"))

        createCharacter("recurring-npc", "Unrelated imported Character")
        assertTrue(runCatching {
            npc.promoteNpcToCharacter(chatId, "recurring-npc")
        }.isFailure)
        assertEquals("Unrelated imported Character",
            chars.getCharacterById("recurring-npc")?.displayName)
        assertEquals(1, chats.getParticipantsForChatOnce(chatId).size)
        assertEquals("recurring-npc", npc.getProjectedDossiers(chatId).single().npcId)
    }
}
