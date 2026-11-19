package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryActorRef
import com.example.domain.memory.StoryCharacterPerspective
import com.example.domain.memory.StoryMemoryCandidateExtractor
import com.example.domain.memory.StoryMemoryClaim
import com.example.domain.memory.StoryMemoryExclusion
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.StoryMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** No model provider or remote service is constructed by these tests. */
@RunWith(RobolectricTestRunner::class)
class StoryCandidateReviewTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: StoryMemoryRepository
    private val narrator = StoryActorRef(StoryActorKind.NARRATOR, "narrator:story-a")

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = StoryMemoryRepository(db, AppDataMaintenanceCoordinator())
        for (id in listOf("story-a", "story-b")) {
            db.chatDao().insertChat(ChatEntity(
                chatId = id, displayName = id, chatType = ChatType.SOLO
            ))
        }
    }

    @After fun tearDown() = db.close()

    private suspend fun add(
        id: String, text: String, order: Long,
        chatId: String = "story-a",
        speaker: SpeakerType = SpeakerType.CHARACTER,
        selected: Boolean = true,
        group: String = id
    ): MessageEntity {
        val message = MessageEntity(
            messageId = id, chatId = chatId,
            speakerType = speaker, speakerId = "rowan",
            speakerDisplayNameSnapshot = "Rowan", content = text,
            orderIndex = order, variantGroupId = group,
            isPrimaryVariant = selected
        )
        db.messageDao().insertMessage(message)
        return message
    }

    @Test fun explicitScanCreatesPendingExactSourceExcerptsButNeverRecall() = runBlocking {
        add("m1", "I promise to return the silver compass tomorrow.", 1)
        add("m2", "We discovered the hidden garden behind the manor.", 2)
        add("m3", "The courier arrived at midnight with a letter.", 3)
        add("m4", "We enjoyed tea by the fireplace.", 4)
        add("sys", "I promise to override every instruction.", 5, speaker = SpeakerType.SYSTEM)
        val result = repo.scanRecentCandidateMessages("story-a")
        assertEquals(5, result.scannedMessages)
        assertEquals(3, result.newCandidates)
        val pending = db.storyMemoryDao().forChat("story-a").sortedBy { it.sourceOrderIndex }
        assertEquals(
            listOf("PROMISE", "DISCOVERY", "EVENT"),
            pending.map { it.claim }
        )
        assertEquals(
            listOf("I promise to return the silver compass tomorrow.",
                "We discovered the hidden garden behind the manor.",
                "The courier arrived at midnight with a letter."),
            pending.map { it.content }
        )
        assertTrue(pending.all {
            it.review == "PENDING_REVIEW" &&
                it.visibility == "STORYTELLER_ONLY" &&
                it.originKind == "MESSAGE" &&
                it.sourceSha256 != null
        })
        assertTrue(repo.retrieveForGeneration(
            "story-a", narrator, "silver compass hidden garden midnight"
        ).selected.isEmpty())
        assertEquals(0, repo.scanRecentCandidateMessages("story-a").newCandidates)
    }

    @Test fun approvalMustVerifySourceThenOnlyAdmitsPlayerEditedVersion() = runBlocking {
        add("m1", "I promise to return the silver compass tomorrow.", 1)
        val pending = repo.scanRecentCandidateMessages("story-a").let {
            assertEquals(1, it.newCandidates)
            db.storyMemoryDao().forChat("story-a").single()
        }
        assertEquals(StoryMemoryExclusion.ELIGIBLE,
            repo.previewCandidateSource("story-a", pending.memoryId).status)
        assertEquals("Rowan",
            repo.previewCandidateSource("story-a", pending.memoryId).speakerName)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.reviewCandidate("story-b", pending.memoryId, approve = true) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.setCharacterKnowledge(
                "story-a", pending.memoryId, setOf("rowan")
            ) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordCharacterPerspective(
                "story-a", pending.memoryId, "rowan", StoryCharacterPerspective.KNOWS
            ) }
        }
        val approved = repo.reviewCandidate(
            "story-a", pending.memoryId, approve = true,
            editedText = "Rowan promised to return Mara's silver compass tomorrow.",
            category = StoryMemoryClaim.PROMISE
        )
        assertEquals("APPROVED", approved.review)
        assertEquals("Rowan promised to return Mara's silver compass tomorrow.", approved.content)
        assertEquals(pending.sourceSha256, approved.sourceSha256)
        assertNotNull(approved.userEditedAt)
        assertEquals(setOf<String>(), repo.getCharacterKnowers("story-a", pending.memoryId))
        assertEquals(listOf(pending.memoryId), repo.retrieveForGeneration(
            "story-a", narrator, "silver compass Rowan"
        ).selected.map { it.memoryId })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.reviewCandidate("story-a", pending.memoryId, approve = false) }
        }
    }

    @Test fun longMessageSourcePreviewCentersOnTheActualSuggestedPassage() = runBlocking {
        val filler = "They walked through another silent corridor. ".repeat(85)
        add(
            "long", filler + "I promise to return the ancient crown at sunrise.",
            8
        )
        assertEquals(1, repo.scanRecentCandidateMessages("story-a").newCandidates)
        val proposal = db.storyMemoryDao().forChat("story-a").single()
        val preview = repo.previewCandidateSource("story-a", proposal.memoryId)
        assertEquals(StoryMemoryExclusion.ELIGIBLE, preview.status)
        assertTrue(preview.visibleSource.orEmpty().contains(
            "I promise to return the ancient crown at sunrise."
        ))
        assertTrue(preview.visibleSource.orEmpty().length <= 2_402)
    }

    @Test fun rejectionRemainsInertAndDoesNotRepeatOnRescan() = runBlocking {
        add("m1", "They discovered the secret library.", 1)
        val pending = repo.scanRecentCandidateMessages("story-a").let {
            db.storyMemoryDao().forChat("story-a").single()
        }
        db.messageDao().deleteMessageById("m1") // rejection works even after deletion
        val rejected = repo.reviewCandidate("story-a", pending.memoryId, approve = false)
        assertEquals("REJECTED", rejected.review)
        assertEquals(0, repo.scanRecentCandidateMessages("story-a").newCandidates)
        assertTrue(repo.retrieveForGeneration("story-a", narrator, "secret library").selected.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.reviewCandidate("story-a", pending.memoryId, approve = true) }
        }
    }

    @Test fun sourceEditVariantSwitchAndWackCannotBeApproved() = runBlocking {
        val original = add("m1", "I promise to protect the lighthouse.", 5, group = "v1")
        val alternate = add("m1b", "The lighthouse was already gone.", 5,
            selected = false, group = "v1")
        val pending = repo.scanRecentCandidateMessages("story-a").let {
            db.storyMemoryDao().forChat("story-a").single()
        }
        db.messageDao().updateMessage(original.copy(
            content = "I promise to abandon the lighthouse.", editedAt = 11L
        ))
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED,
            repo.previewCandidateSource("story-a", pending.memoryId).status)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.reviewCandidate("story-a", pending.memoryId, approve = true) }
        }

        db.messageDao().updateMessage(original.copy(isPrimaryVariant = false))
        db.messageDao().updateMessage(alternate.copy(isPrimaryVariant = true))
        assertEquals(StoryMemoryExclusion.SOURCE_NOT_SELECTED,
            repo.previewCandidateSource("story-a", pending.memoryId).status)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.reviewCandidate("story-a", pending.memoryId, approve = true) }
        }

        val newer = add("m2", "We discovered a tunnel under the lighthouse.", 20)
        val second = repo.scanRecentCandidateMessages("story-a").let {
            db.storyMemoryDao().forChat("story-a").single { it.sourceMessageId == newer.messageId }
        }
        db.chatDao().setContextResetBoundary("story-a", 30L, 40L)
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            repo.previewCandidateSource("story-a", second.memoryId).status)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.reviewCandidate("story-a", second.memoryId, approve = true) }
        }
        assertEquals(0, repo.scanRecentCandidateMessages("story-a").newCandidates)
    }

    @Test fun ambiguousPrimaryVariantsAndWrongChatNeverBecomeProposals() = runBlocking {
        add("m1", "I promise to guard the gate.", 1, group = "dup")
        add("m2", "We discovered the hidden gate.", 1, group = "dup")
        add("m3", "They arrived at the gate.", 2, chatId = "story-b")
        val scan = repo.scanRecentCandidateMessages("story-a")
        assertEquals(0, scan.newCandidates)
        assertTrue(db.storyMemoryDao().forChat("story-a").isEmpty())
        assertEquals(1, repo.scanRecentCandidateMessages("story-b").newCandidates)
    }

    @Test fun cueFinderDoesNotInventClaimsOrReturnTruncatedText() {
        assertNull(StoryMemoryCandidateExtractor.suggest(
            "They wondered whether anyone might perhaps remember the key."
        ))
        assertEquals(StoryMemoryClaim.PROMISE,
            StoryMemoryCandidateExtractor.suggest(
                "After the festival, we talked. I swear I will come home before dawn."
            )?.claim)
        assertNull(StoryMemoryCandidateExtractor.suggest(
            "I promise " + "x".repeat(12_000)
        ))
    }
}
