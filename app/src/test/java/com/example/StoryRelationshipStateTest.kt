package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.ChatParticipantEntity
import com.example.data.model.ChatPersonaParticipantEntity
import com.example.data.model.CharacterEntity
import com.example.data.model.PersonaEntity
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryActorRef
import com.example.domain.memory.StoryMemoryLifecycle
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.StoryMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exact actor IDs, scope, provenance and explicit lifecycle; no provider. */
@RunWith(RobolectricTestRunner::class)
class StoryRelationshipStateTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: StoryMemoryRepository
    private val mara = StoryActorRef(StoryActorKind.PERSONA, "mara")
    private val rowan = StoryActorRef(StoryActorKind.CHARACTER, "rowan")
    private val alia = StoryActorRef(StoryActorKind.CHARACTER, "alia")

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = StoryMemoryRepository(db, AppDataMaintenanceCoordinator())
        db.characterDao().insertCharacter(character("rowan"))
        db.characterDao().insertCharacter(character("alia"))
        db.personaDao().insertPersona(persona("mara"))
        for (chatId in listOf("story-a", "story-b")) {
            db.chatDao().insertChat(
                ChatEntity(chatId = chatId, displayName = chatId, chatType = ChatType.GROUP)
            )
            db.chatDao().insertChatParticipant(
                ChatParticipantEntity(chatId = chatId, characterId = "rowan")
            )
            db.chatDao().insertChatParticipant(
                ChatParticipantEntity(chatId = chatId, characterId = "alia")
            )
            db.chatDao().insertChatPersonaParticipant(
                ChatPersonaParticipantEntity(chatId = chatId, personaId = "mara")
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun character(id: String) = CharacterEntity(
        characterId = id, displayName = id, characterName = id,
        aliases = emptyList(), importSchemaVersion = 1,
        identityJson = null, appearanceJson = null, personalityJson = null,
        voiceJson = null, behaviorJson = null, backstoryJson = null,
        knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
        examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
    )

    private fun persona(id: String) = PersonaEntity(
        personaId = id, displayName = id, personaName = id,
        aliases = emptyList(), importSchemaVersion = 1,
        identityJson = null, appearanceJson = null, personalityJson = null,
        roleplayProfileJson = null, backgroundJson = null, worldContextJson = null,
        privateNotesJson = null, extensionFieldsJson = null
    )

    @Test fun changeIsManualChatLocalTypedAndNeverAutomaticallyGrantsKnowledge() = runBlocking {
        val first = repository.saveRelationshipChange(
            "story-a", mara, rowan, "Mara trusts Rowan with the silver key."
        )
        assertEquals("RELATIONSHIP", first.memory.claim)
        assertEquals("MANUAL", first.memory.originKind)
        assertEquals("APPROVED", first.memory.review)
        assertEquals("STORYTELLER_ONLY", first.memory.visibility)
        assertEquals("ACTIVE", first.memory.lifecycle)
        assertEquals(setOf("RELATIONSHIP_FROM", "RELATIONSHIP_TO"),
            db.storyMemoryDao().actors(first.memory.memoryId).map { it.relation }.toSet())
        assertEquals(emptySet<String>(),
            repository.getCharacterKnowers("story-a", first.memory.memoryId))
        assertEquals(listOf(first), repository.getRelationshipHistory("story-a"))
        assertTrue(repository.getRelationshipHistory("story-b").isEmpty())
        assertEquals(1, db.storyMemoryDao().forChat("story-a").size)
    }

    @Test fun explicitReconciliationSupersedesButKeepsHistoryAndOldKnowledgeDoesNotTransfer() = runBlocking {
        val first = repository.saveRelationshipChange(
            "story-a", mara, rowan, "Mara is furious with Rowan over the stolen compass."
        )
        repository.setCharacterKnowledge("story-a", first.memory.memoryId, setOf("rowan"))
        val next = repository.saveRelationshipChange(
            "story-a", mara, rowan, "Rowan apologized; Mara has forgiven him.",
            supersedeMemoryId = first.memory.memoryId
        )
        val history = repository.getRelationshipHistory("story-a")
        assertEquals(2, history.size)
        assertEquals(listOf(first.memory.memoryId, next.memory.memoryId),
            history.map { it.memory.memoryId })
        assertEquals("SUPERSEDED", history.first().memory.lifecycle)
        assertEquals("ACTIVE", history.last().memory.lifecycle)
        assertTrue(history.first().memory.sourceOrderIndex < history.last().memory.sourceOrderIndex)
        assertEquals("Mara is furious with Rowan over the stolen compass.",
            history.first().memory.content)
        assertEquals("Rowan apologized; Mara has forgiven him.",
            history.last().memory.content)
        assertEquals("STORYTELLER_ONLY", history.last().memory.visibility)
        assertTrue(repository.getCharacterKnowers("story-a", next.memory.memoryId).isEmpty())
        assertEquals(setOf("rowan"),
            repository.getCharacterKnowers("story-a", first.memory.memoryId))
        assertTrue(repository.getRelationshipHistory("story-b").isEmpty())
    }

    @Test fun crossStoryForeignAndWrongDirectionCannotChangePreviousState() = runBlocking {
        val previous = repository.saveRelationshipChange(
            "story-a", mara, rowan, "Mara fears Rowan."
        )
        for (block in listOf<suspend () -> Unit>(
            { repository.saveRelationshipChange("story-b", mara, rowan, "Other", previous.memory.memoryId) },
            { repository.saveRelationshipChange("story-a", rowan, mara, "Reversed", previous.memory.memoryId) },
            { repository.saveRelationshipChange("story-a", mara,
                StoryActorRef(StoryActorKind.CHARACTER, "not-in-chat"), "Foreign") },
            { repository.saveRelationshipChange("story-a", mara, mara, "Same person") },
            { repository.saveRelationshipChange("story-a", mara, rowan, " ") }
        )) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { block() } }
        }
        assertEquals("ACTIVE", repository.getRelationshipHistory("story-a").single().memory.lifecycle)
        assertTrue(repository.getRelationshipHistory("story-b").isEmpty())
        assertEquals(1, db.storyMemoryDao().forChat("story-a").size)
    }

    @Test fun narratorRetrievesOnlyActiveChangeAndCharactersRequireKnowledge() = runBlocking {
        val first = repository.saveRelationshipChange(
            "story-a", mara, rowan, "Mara distrusts Rowan over the silver compass."
        )
        repository.setCharacterKnowledge("story-a", first.memory.memoryId, setOf("rowan"))
        val next = repository.saveRelationshipChange(
            "story-a", mara, rowan, "Mara forgives Rowan for the silver compass.",
            supersedeMemoryId = first.memory.memoryId
        )
        val narrator = repository.retrieveForGeneration(
            chatId = "story-a",
            recipient = StoryActorRef(StoryActorKind.NARRATOR, "narrator:story-a"),
            sceneText = "Mara speaks to Rowan again"
        )
        assertEquals(listOf(next.memory.memoryId),
            narrator.selected.map { it.memoryId })
        assertFalse(narrator.selected.any { it.memoryId == first.memory.memoryId })

        // Rowan knew the earlier relationship state but the user did not
        // grant knowledge of the newer one. Neither an actor tag nor an
        // earlier grant may silently promote the newer fact into his mind.
        val character = repository.retrieveForGeneration(
            chatId = "story-a", recipient = rowan,
            sceneText = "Mara Rowan silver compass"
        )
        assertTrue(character.selected.isEmpty())
        assertTrue(repository.retrieveForGeneration(
            chatId = "story-b",
            recipient = StoryActorRef(StoryActorKind.NARRATOR, "narrator:story-b"),
            sceneText = "Mara Rowan silver compass"
        ).selected.isEmpty())
    }

    @Test fun wackBlocksPreResetStateAndNewExplicitStateIsAfterBoundary() = runBlocking {
        val old = repository.saveRelationshipChange(
            "story-a", mara, rowan, "Mara distrusts Rowan."
        )
        db.chatDao().setContextResetBoundary("story-a", 200L, 300L)
        val current = repository.saveRelationshipChange(
            "story-a", mara, rowan, "Mara and Rowan begin again.",
            supersedeMemoryId = old.memory.memoryId
        )
        assertTrue(current.memory.sourceOrderIndex > 200L)
        assertEquals("SUPERSEDED", repository.getRelationshipHistory("story-a").first().memory.lifecycle)
        assertEquals("ACTIVE", repository.getRelationshipHistory("story-a").last().memory.lifecycle)
        assertTrue(db.storyMemoryDao().getAllReturns().isEmpty())
        assertTrue(repository.getCharacterKnowers("story-a", current.memory.memoryId).isEmpty())
    }
}
