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
import com.example.domain.repository.StoryPromiseOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Actor-linked promise transitions are user-approved, local, and source-preserving. */
@RunWith(RobolectricTestRunner::class)
class StoryPromiseStateTest {
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
        for (id in listOf("rowan", "alia")) {
            db.characterDao().insertCharacter(CharacterEntity(
                characterId = id, displayName = id, characterName = id,
                aliases = emptyList(), importSchemaVersion = 1,
                identityJson = null, appearanceJson = null, personalityJson = null,
                voiceJson = null, behaviorJson = null, backstoryJson = null,
                knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
                examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
            ))
        }
        db.personaDao().insertPersona(PersonaEntity(
            personaId = "mara", displayName = "mara", personaName = "mara",
            aliases = emptyList(), importSchemaVersion = 1,
            identityJson = null, appearanceJson = null, personalityJson = null,
            roleplayProfileJson = null, backgroundJson = null,
            worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
        ))
        for (chatId in listOf("story-a", "story-b")) {
            db.chatDao().insertChat(ChatEntity(
                chatId = chatId, displayName = chatId, chatType = ChatType.GROUP
            ))
            db.chatDao().insertChatParticipant(ChatParticipantEntity(
                chatId = chatId, characterId = "rowan"
            ))
            db.chatDao().insertChatParticipant(ChatParticipantEntity(
                chatId = chatId, characterId = "alia"
            ))
            db.chatDao().insertChatPersonaParticipant(ChatPersonaParticipantEntity(
                chatId = chatId, personaId = "mara"
            ))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun explicitOutcomeSupersedesOnlyItsOwnPromiseAndRetainsLineage() = runBlocking {
        val silverKey = repository.savePromise(
            "story-a", mara, rowan, "Mara promises Rowan the silver key."
        )
        val seaMap = repository.savePromise(
            "story-a", mara, rowan, "Mara promises Rowan a map of the coast."
        )
        val outcome = repository.closePromise(
            "story-a", silverKey.memory.memoryId, StoryPromiseOutcome.KEPT,
            "The silver key was delivered."
        )
        val history = repository.getPromiseHistory("story-a")
        assertEquals(3, history.size)
        assertEquals(silverKey.memory.memoryId, outcome.memory.previousStateMemoryId)
        assertEquals("KEPT", outcome.memory.promiseOutcome)
        assertEquals("RESOLVED", outcome.memory.lifecycle)
        assertEquals("SUPERSEDED", repository.get("story-a", silverKey.memory.memoryId)?.lifecycle)
        assertEquals("ACTIVE", repository.get("story-a", seaMap.memory.memoryId)?.lifecycle)
        assertEquals("Mara promises Rowan the silver key.",
            repository.get("story-a", silverKey.memory.memoryId)?.content)
        assertTrue(outcome.memory.content.contains("The silver key was delivered."))
        // No automatic transfer of private access to the new outcome.
        assertTrue(repository.getCharacterKnowers("story-a", outcome.memory.memoryId).isEmpty())
        assertTrue(repository.getCharacterKnowers(
            "story-a", silverKey.memory.memoryId
        ).isEmpty())
        val narrator = repository.retrieveForGeneration(
            "story-a",
            StoryActorRef(StoryActorKind.NARRATOR, "narrator:story-a"),
            sceneText = "Mara Rowan silver key"
        )
        assertTrue(narrator.selected.any { it.memoryId == outcome.memory.memoryId })
        assertFalse(narrator.selected.any { it.memoryId == silverKey.memory.memoryId })
        val character = repository.retrieveForGeneration("story-a", rowan, "Mara silver key")
        assertTrue(character.selected.isEmpty())
        assertTrue(repository.getPromiseHistory("story-b").isEmpty())
    }

    @Test fun privatePromiseCannotBeCopiedIntoNarratorMemoryByClosing() = runBlocking {
        val old = repository.savePromise(
            "story-a", mara, rowan, "Rowan alone knows the hidden oath."
        )
        repository.setCharacterKnowledge("story-a", old.memory.memoryId, setOf("rowan"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.closePromise(
                    "story-a", old.memory.memoryId, StoryPromiseOutcome.KEPT,
                    "The hidden oath was honored."
                )
            }
        }
        assertEquals("ACTIVE", repository.get("story-a", old.memory.memoryId)?.lifecycle)
        assertEquals(1, repository.getPromiseHistory("story-a").size)
        // Deliberate owner action changes the audience before writing outcome.
        repository.setCharacterKnowledge("story-a", old.memory.memoryId, emptySet())
        val next = repository.closePromise(
            "story-a", old.memory.memoryId, StoryPromiseOutcome.KEPT,
            "The oath was honored."
        )
        assertEquals("STORYTELLER_ONLY", next.memory.visibility)
    }

    @Test fun brokenAndReleasedAreDistinctExplicitFinalStates() = runBlocking {
        val first = repository.savePromise("story-a", rowan, mara, "Rowan will guard the garden.")
        val second = repository.savePromise("story-a", alia, mara, "Alia will deliver the letter.")
        val broken = repository.closePromise(
            "story-a", first.memory.memoryId, StoryPromiseOutcome.BROKEN,
            "Rowan left the gates open."
        )
        val released = repository.closePromise(
            "story-a", second.memory.memoryId, StoryPromiseOutcome.RELEASED,
            "Mara released Alia from the obligation."
        )
        assertEquals("BROKEN", broken.memory.promiseOutcome)
        assertEquals("RELEASED", released.memory.promiseOutcome)
        assertEquals(first.memory.memoryId, broken.memory.previousStateMemoryId)
        assertEquals(second.memory.memoryId, released.memory.previousStateMemoryId)
        assertTrue(broken.memory.sourceOrderIndex > first.memory.sourceOrderIndex)
        assertTrue(released.memory.sourceOrderIndex > broken.memory.sourceOrderIndex)
    }

    @Test fun foreignAndAlreadyClosedPromisesCannotBeModified() = runBlocking {
        val original = repository.savePromise("story-a", mara, rowan, "Mara owes Rowan a book.")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.closePromise(
                "story-b", original.memory.memoryId, StoryPromiseOutcome.KEPT, "Delivered."
            ) }
        }
        val closed = repository.closePromise(
            "story-a", original.memory.memoryId, StoryPromiseOutcome.KEPT, "Delivered."
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.closePromise(
                "story-a", original.memory.memoryId, StoryPromiseOutcome.BROKEN, "No."
            ) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.closePromise(
                "story-a", closed.memory.memoryId, StoryPromiseOutcome.BROKEN, "No."
            ) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.savePromise("story-a", mara,
                StoryActorRef(StoryActorKind.CHARACTER, "not-in-chat"), "Foreign") }
        }
        assertEquals(2, repository.getPromiseHistory("story-a").size)
    }

    @Test fun preWackPromiseRequiresIndividualUserRestoreBeforeOutcome() = runBlocking {
        val old = repository.savePromise(
            "story-a", mara, rowan, "Mara promised Rowan a secret."
        )
        db.chatDao().setContextResetBoundary("story-a", 200L, 300L)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.closePromise(
                "story-a", old.memory.memoryId, StoryPromiseOutcome.KEPT, "Secret revealed."
            ) }
        }
        assertEquals("ACTIVE", repository.get("story-a", old.memory.memoryId)?.lifecycle)
        assertEquals(1, repository.getPromiseHistory("story-a").size)
        repository.restoreAcrossWack("story-a", old.memory.memoryId)
        val closed = repository.closePromise(
            "story-a", old.memory.memoryId, StoryPromiseOutcome.KEPT, "Secret revealed."
        )
        assertTrue(closed.memory.sourceOrderIndex > 200L)
        assertEquals(old.memory.memoryId, closed.memory.previousStateMemoryId)
    }
}
