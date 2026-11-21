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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real local Room graphs, no provider requests. Reusing a Character as an NPC
 * in multiple separate stories must not unify their story-local memories.
 */
@RunWith(RobolectricTestRunner::class)
class CharacterToNpcLinkTest {
    private lateinit var db: AppDatabase
    private lateinit var npc: NpcRepository
    private lateinit var cards: CharacterRepository
    private lateinit var chats: ChatRepository
    private val coordinator = AppDataMaintenanceCoordinator()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        npc = NpcRepository(db, moshi, coordinator)
        cards = CharacterRepository(db.characterDao(), moshi, dataCoordinator = coordinator)
        chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
    }
    @After fun tearDown() { db.close() }

    private suspend fun player() {
        db.personaDao().insertPersona(PersonaEntity(
            personaId = "player", displayName = "Player", personaName = "Player",
            aliases = emptyList(), importSchemaVersion = 1,
            identityJson = null, appearanceJson = null, personalityJson = null,
            roleplayProfileJson = null, backgroundJson = null,
            worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
        ))
    }
    private suspend fun originalCharacter() {
        cards.importCharacterJson(
            """{"schema_version":1,"character_id":"existing","display_name":"Captain",
            "character_name":"Captain","aliases":["Guard"],
            "voice":{"tone":"VOICE_BASELINE_SENTINEL"},
            "personality":{"summary":"PERSONALITY_BASELINE_SENTINEL"},
            "knowledge":{"private":"FORBIDDEN_OTHER_CHAT_SECRET"},
            "author_notes":{"private":"FORBIDDEN_AUTHOR_NOTE"}}"""
        )
    }

    @Test fun sameCharacterLinkedAcrossIndependentChatNpcRolesWithoutMergingState() = runBlocking {
        player(); originalCharacter()
        val firstChat = chats.createSoloChat("Campaign One", "player", "existing")
        val secondChat = chats.createSoloChat("Campaign Two", "player", "existing")
        val firstId = npc.addExistingCharacterAsNpc(firstChat, "existing")
        val secondId = npc.addExistingCharacterAsNpc(secondChat, "existing")
        assertNotEquals(firstId, secondId)
        assertNotEquals("existing", firstId)
        assertEquals(firstId, npc.addExistingCharacterAsNpc(firstChat, "existing"))
        assertEquals(secondId, npc.addExistingCharacterAsNpc(secondChat, "existing"))
        assertEquals(1, npc.getProjectedDossiers(firstChat).size)
        assertEquals(1, npc.getProjectedDossiers(secondChat).size)
        assertEquals("existing", npc.getProjectedDossiers(firstChat).single().linkedCharacterId)
        assertEquals("existing", npc.getProjectedDossiers(secondChat).single().linkedCharacterId)
        assertEquals(1, db.backupDao().getCharacters().size)

        val firstRole = npc.getProjectedDossiers(firstChat).single().copy(
            sceneMode = "HERE", relationshipToPersona = "STORY_ONE_PRIVATE_RELATIONSHIP",
            characterKnowledge = mapOf("observer" to "ONE_KNOWLEDGE_ONLY")
        )
        npc.saveManual(firstRole)
        npc.setSceneMode(firstId, NpcSceneMode.HERE)
        npc.setSceneMode(secondId, NpcSceneMode.HERE)

        val firstPrompt = npc.relevantContext(
            chatId = firstChat, recentMessages = emptyList(),
            observerCharacterId = "observer", activePersonaId = "player"
        ).orEmpty()
        assertTrue(firstPrompt.contains("LINKED CHARACTER ID: existing"))
        assertTrue(firstPrompt.contains("VOICE_BASELINE_SENTINEL"))
        assertTrue(firstPrompt.contains("PERSONALITY_BASELINE_SENTINEL"))
        assertTrue(firstPrompt.contains("STORY_ONE_PRIVATE_RELATIONSHIP"))
        assertTrue(firstPrompt.contains("ONE_KNOWLEDGE_ONLY"))
        assertFalse(firstPrompt.contains("FORBIDDEN_OTHER_CHAT_SECRET"))
        assertFalse(firstPrompt.contains("FORBIDDEN_AUTHOR_NOTE"))

        val secondPrompt = npc.relevantContext(
            chatId = secondChat, recentMessages = emptyList(),
            observerCharacterId = "observer", activePersonaId = "player"
        ).orEmpty()
        assertTrue(secondPrompt.contains("VOICE_BASELINE_SENTINEL"))
        assertFalse(secondPrompt.contains("STORY_ONE_PRIVATE_RELATIONSHIP"))
        assertFalse(secondPrompt.contains("ONE_KNOWLEDGE_ONLY"))
        assertNull(npc.relevantContext(
            chatId = firstChat, recentMessages = emptyList(),
            observerCharacterId = "existing", activePersonaId = "player"
        ))
        assertEquals("existing", cards.getCharacterById("existing")?.characterId)
        assertEquals(listOf("existing"), chats.getParticipantsForChatOnce(firstChat)
            .map { it.characterId })
    }

    @Test fun internalLinkIsNotExportedOrReintroducedByUntrustedNpcJson() = runBlocking {
        player(); originalCharacter()
        val chatId = chats.createSoloChat("Secret Story", "player", "existing")
        val linked = npc.addExistingCharacterAsNpc(chatId, "existing")
        val privateJson = npc.exportJson(chatId, includePrivateFields = true)
        assertFalse(privateJson.contains("_elsewhere_linked_character_id"))
        val libraryId = npc.saveChatToLibrary(chatId, "Shared Library")
        assertTrue(db.npcDao().getLibraryEntries(libraryId).single()
            .extensionFieldsJson?.contains("_elsewhere_linked_character_id") != true)
        val other = chats.createSoloChat("Other Story", "player", "existing")
        val imported = npc.importJson(other, privateJson)
        assertEquals(1, imported)
        assertNull(npc.getProjectedDossiers(other).single().linkedCharacterId)
        assertEquals("existing", npc.getProjectedDossiers(chatId).single().linkedCharacterId)
        assertNotNull(db.npcDao().getDossier(linked))
    }

    @Test fun missingOrArchivedCharacterFailsWithoutCreatingFakeNpc() = runBlocking {
        player(); originalCharacter()
        val chatId = chats.createSoloChat("Story", "player", "existing")
        assertTrue(runCatching { npc.addExistingCharacterAsNpc(chatId, "missing") }.isFailure)
        assertTrue(npc.getProjectedDossiers(chatId).isEmpty())
        cards.archiveCharacter("existing")
        assertTrue(runCatching { npc.addExistingCharacterAsNpc(chatId, "existing") }.isFailure)
        assertTrue(npc.getProjectedDossiers(chatId).isEmpty())
    }
}
