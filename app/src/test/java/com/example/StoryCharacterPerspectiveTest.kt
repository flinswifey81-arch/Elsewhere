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
import com.example.domain.memory.StoryCharacterPerspective
import com.example.domain.memory.promptClaimLabel
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.StoryMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoryCharacterPerspectiveTest {
    private lateinit var db: AppDatabase
    private lateinit var story: StoryMemoryRepository
    private val rowan = StoryActorRef(StoryActorKind.CHARACTER, "rowan")
    private val alia = StoryActorRef(StoryActorKind.CHARACTER, "alia")

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
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
        for (id in listOf("story-a", "story-b")) {
            db.chatDao().insertChat(ChatEntity(
                chatId = id, displayName = id, chatType = ChatType.GROUP
            ))
            db.chatDao().insertChatParticipant(ChatParticipantEntity(
                chatId = id, characterId = "rowan"
            ))
            db.chatDao().insertChatParticipant(ChatParticipantEntity(
                chatId = id, characterId = "alia"
            ))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun knowsBelievesSuspectsAndUnawareAreDistinctAndRecipientScoped() = runBlocking {
        val memory = story.saveManual("story-a", "The silver key rests under the tower.")
        story.setCharacterKnowledge("story-a", memory.memoryId, setOf("rowan", "alia"))

        story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan", StoryCharacterPerspective.SUSPECTS,
            "Only the player sees this personal note."
        )
        val suspect = story.retrieveForGeneration("story-a", rowan, "silver key tower")
        assertEquals(1, suspect.selected.size)
        assertTrue(suspect.selected.single().content.contains("Character suspicion"))
        assertEquals("CHARACTER_SUSPICION_UNCONFIRMED",
            suspect.selected.single().promptClaimLabel())
        assertTrue(suspect.selected.single().content.contains("NOT established fact"))
        assertFalse(suspect.selected.single().content.contains("Only the player sees"))

        // Alia has access, but is NOT given Rowan's subjective state.
        val other = story.retrieveForGeneration("story-a", alia, "silver key tower")
        assertEquals(listOf(memory.content), other.selected.map { it.content })
        val narrator = story.retrieveForGeneration(
            "story-a", StoryActorRef(StoryActorKind.NARRATOR, "narrator:story-a"),
            "silver key tower"
        )
        assertTrue(narrator.selected.isEmpty())

        story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan", StoryCharacterPerspective.BELIEVES
        )
        val believer = story.retrieveForGeneration("story-a", rowan, "silver key tower")
        assertTrue(believer.selected.single().content.contains("Character belief"))
        assertEquals("CHARACTER_BELIEF_UNCONFIRMED",
            believer.selected.single().promptClaimLabel())
        assertTrue(believer.selected.single().content.contains("NOT established fact"))

        story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan", StoryCharacterPerspective.KNOWS
        )
        val confirmed = story.retrieveForGeneration("story-a", rowan, "silver key tower")
        assertEquals(listOf(memory.content), confirmed.selected.map { it.content })
        assertEquals("FACT", confirmed.selected.single().promptClaimLabel())

        story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan", StoryCharacterPerspective.UNAWARE
        )
        assertTrue(story.retrieveForGeneration("story-a", rowan, "silver key tower")
            .selected.isEmpty())
        assertEquals(setOf("rowan", "alia"),
            story.getCharacterKnowers("story-a", memory.memoryId))
        assertEquals(listOf(memory.content),
            story.retrieveForGeneration("story-a", alia, "silver key tower")
                .selected.map { it.content })
    }

    @Test fun perspectiveDoesNotGrantPrivateKnowledgeOrCrossWack() = runBlocking {
        val memory = story.saveManual("story-a", "The hidden letter is in the blue vase.")
        story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan", StoryCharacterPerspective.BELIEVES
        )
        assertTrue(story.retrieveForGeneration("story-a", rowan, "hidden letter blue vase")
            .selected.isEmpty())
        assertTrue(story.getCharacterKnowers("story-a", memory.memoryId).isEmpty())
        story.setCharacterKnowledge("story-a", memory.memoryId, setOf("rowan"))
        db.chatDao().setContextResetBoundary("story-a", 100L, 200L)
        assertTrue(story.retrieveForGeneration("story-a", rowan, "hidden letter blue vase")
            .selected.isEmpty())
    }

    @Test fun historyIsAppendOnlyIdempotentAndCannotCrossStories() = runBlocking {
        val memory = story.saveManual("story-a", "Rowan suspects the golden gate is locked.")
        val other = story.saveManual("story-b", "Other story secret.")
        val first = story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan", StoryCharacterPerspective.SUSPECTS,
            "A rumor from the guard."
        )
        val duplicate = story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan", StoryCharacterPerspective.SUSPECTS,
            "A rumor from the guard."
        )
        assertEquals(first, duplicate)
        assertEquals(1L, first.revision)
        val second = story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan", StoryCharacterPerspective.KNOWS,
            "Verified by opening the gate."
        )
        assertEquals(2L, second.revision)
        assertEquals(
            listOf("SUSPECTS", "KNOWS"),
            story.getCharacterPerspectiveHistory("story-a", memory.memoryId, "rowan")
                .map { it.stance }
        )
        assertEquals(listOf(second),
            story.getCurrentCharacterPerspectives("story-a", memory.memoryId))
        assertTrue(story.getCurrentCharacterPerspectives("story-b", other.memoryId).isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { story.recordCharacterPerspective(
                "story-b", memory.memoryId, "rowan", StoryCharacterPerspective.KNOWS
            ) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { story.getCurrentCharacterPerspectives("story-b", memory.memoryId) }
        }
        story.delete("story-a", memory.memoryId)
        assertTrue(db.storyMemoryDao().getAllPerspectives().isEmpty())
    }

    @Test fun editedMemoryMustBeReapprovedBeforeOldPerspectiveCanApply() = runBlocking {
        val memory = story.saveManual("story-a", "The old gate is unlocked.")
        story.setCharacterKnowledge("story-a", memory.memoryId, setOf("rowan"))
        val prior = story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan",
            StoryCharacterPerspective.SUSPECTS, "The guard could be mistaken."
        )
        assertEquals(1, story.retrieveForGeneration(
            "story-a", rowan, "old gate unlocked").selected.size)
        story.update("story-a", memory.memoryId, text = "The old gate has been sealed.")
        // An old subjective state may not attach itself to a new claim.
        assertTrue(story.retrieveForGeneration(
            "story-a", rowan, "old gate sealed").selected.isEmpty())
        val revision = story.recordCharacterPerspective(
            "story-a", memory.memoryId, "rowan",
            StoryCharacterPerspective.SUSPECTS, "The guard could be mistaken."
        )
        assertEquals(2L, revision.revision)
        assertNotEquals(prior.contentSha256, revision.contentSha256)
        val resumed = story.retrieveForGeneration("story-a", rowan, "old gate sealed")
        assertEquals(1, resumed.selected.size)
        assertEquals("CHARACTER_SUSPICION_UNCONFIRMED",
            resumed.selected.single().promptClaimLabel())
        assertEquals(2, story.getCharacterPerspectiveHistory(
            "story-a", memory.memoryId, "rowan").size)
    }

    @Test fun noteLimitAndMissingCharacterFailWithoutSaving() = runBlocking {
        val memory = story.saveManual("story-a", "A map was destroyed.")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { story.recordCharacterPerspective(
                "story-a", memory.memoryId, "rowan",
                StoryCharacterPerspective.SUSPECTS, "x".repeat(2_001)
            ) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { story.recordCharacterPerspective(
                "story-a", memory.memoryId, "not-a-participant",
                StoryCharacterPerspective.SUSPECTS
            ) }
        }
        assertTrue(db.storyMemoryDao().getAllPerspectives().isEmpty())
    }
}
