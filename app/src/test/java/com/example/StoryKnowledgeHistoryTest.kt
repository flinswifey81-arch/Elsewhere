package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.ChatParticipantEntity
import com.example.data.model.CharacterEntity
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryActorRef
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.StoryMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Knowledge audit is manual, append-only and never a substitute for
 * current recipient authorization in StoryMemoryEligibilityPolicy.
 */
@RunWith(RobolectricTestRunner::class)
class StoryKnowledgeHistoryTest {
    private lateinit var db: AppDatabase
    private lateinit var story: StoryMemoryRepository

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        story = StoryMemoryRepository(db, AppDataMaintenanceCoordinator())
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
        for (chatId in listOf("story-a", "story-b")) {
            db.chatDao().insertChat(ChatEntity(
                chatId = chatId, displayName = chatId, chatType = ChatType.GROUP
            ))
            for (id in listOf("rowan", "alia")) {
                db.chatDao().insertChatParticipant(ChatParticipantEntity(
                    chatId = chatId, characterId = id
                ))
            }
        }
    }

    @After fun tearDown() = db.close()

    @Test fun playerKnowledgeRevisionsAreAppendOnlyAndCurrentAccessStaysStrict() = runBlocking {
        val record = story.saveManual("story-a", "The silver key is in the cupboard.")
        story.setCharacterKnowledge("story-a", record.memoryId, setOf("rowan", "alia"))
        val initial = story.getCharacterKnowledgeHistory("story-a", record.memoryId)
        assertEquals(2, initial.size)
        assertEquals(setOf("rowan", "alia"), initial.map { it.characterId }.toSet())
        assertEquals(setOf("GRANTED"), initial.map { it.change }.toSet())
        assertTrue(initial.all { it.revision == 1L })

        story.setCharacterKnowledge("story-a", record.memoryId, setOf("rowan"))
        story.setCharacterKnowledge("story-a", record.memoryId, setOf("rowan"))
        val history = story.getCharacterKnowledgeHistory("story-a", record.memoryId)
        assertEquals(3, history.size) // No entry for an identical/no-op assignment.
        assertEquals(2L, history.last().revision)
        assertEquals("alia", history.last().characterId)
        assertEquals("REVOKED", history.last().change)
        assertEquals(setOf("rowan"), story.getCharacterKnowers("story-a", record.memoryId))

        val rowan = story.retrieveForGeneration(
            chatId = "story-a",
            recipient = StoryActorRef(StoryActorKind.CHARACTER, "rowan"),
            sceneText = "silver key cupboard"
        )
        val alia = story.retrieveForGeneration(
            chatId = "story-a",
            recipient = StoryActorRef(StoryActorKind.CHARACTER, "alia"),
            sceneText = "silver key cupboard"
        )
        assertEquals(listOf(record.memoryId), rowan.selected.map { it.memoryId })
        assertTrue(alia.selected.isEmpty())
        val narrator = story.retrieveForGeneration(
            chatId = "story-a",
            recipient = StoryActorRef(StoryActorKind.NARRATOR, "narrator:story-a"),
            sceneText = "silver key cupboard"
        )
        assertTrue(narrator.selected.isEmpty())
        assertTrue(db.storyMemoryDao().getAllKnowledgeChanges().size == 3)
    }

    @Test fun historyNeverTransfersToOtherStoriesAndDeletionCascadesOnlyOwnEvents() = runBlocking {
        val a = story.saveManual("story-a", "Rowan knows the secret map.")
        val b = story.saveManual("story-b", "Rowan knows a different map.")
        story.setCharacterKnowledge("story-a", a.memoryId, setOf("rowan"))
        story.setCharacterKnowledge("story-b", b.memoryId, setOf("alia"))
        assertEquals("rowan", story.getCharacterKnowledgeHistory("story-a", a.memoryId).single().characterId)
        assertEquals("alia", story.getCharacterKnowledgeHistory("story-b", b.memoryId).single().characterId)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { story.getCharacterKnowledgeHistory("story-b", a.memoryId) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { story.setCharacterKnowledge("story-b", a.memoryId, setOf("alia")) }
        }
        story.delete("story-a", a.memoryId)
        assertEquals(listOf(b.memoryId), db.storyMemoryDao().getAllKnowledgeChanges().map { it.memoryId })
        assertEquals(setOf("alia"), story.getCharacterKnowers("story-b", b.memoryId))
    }

    @Test fun wackBoundaryIsRecordedButNeverOverridesEligibility() = runBlocking {
        val old = story.saveManual("story-a", "A blue stone lies under the gate.")
        db.chatDao().setContextResetBoundary("story-a", 100L, 200L)
        story.setCharacterKnowledge("story-a", old.memoryId, setOf("rowan"))
        val event = story.getCharacterKnowledgeHistory("story-a", old.memoryId).single()
        assertEquals(100L, event.wackBoundaryOrderIndex)
        assertTrue(story.retrieveForGeneration(
            chatId = "story-a",
            recipient = StoryActorRef(StoryActorKind.CHARACTER, "rowan"),
            sceneText = "blue stone"
        ).selected.isEmpty())
    }
}
