package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.data.model.StoryMemoryActorEntity
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryActorRef
import com.example.domain.memory.StoryMemoryClaim
import com.example.domain.memory.StoryMemoryVisibility
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.CampaignRepository
import com.example.domain.repository.StoryMemoryRepository
import com.example.domain.repository.WorldRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CampaignMemoryScopeTest {
    private lateinit var db: AppDatabase
    private lateinit var campaigns: CampaignRepository
    private lateinit var memories: StoryMemoryRepository
    private lateinit var worlds: WorldRepository
    private val coordinator = AppDataMaintenanceCoordinator()
    private lateinit var campaignA: String
    private lateinit var campaignB: String

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        campaigns = CampaignRepository(db, coordinator)
        memories = StoryMemoryRepository(db, coordinator)
        worlds = WorldRepository(db, coordinator)
        val world = worlds.createWorld("Same setting, independent stories")
        campaignA = campaigns.createCampaign(world.worldId, "First timeline").campaignId
        campaignB = campaigns.createCampaign(world.worldId, "Second timeline").campaignId
        listOf("a-early", "a-later", "b-early", "b-later", "standalone").forEach {
            db.chatDao().insertChat(ChatEntity(chatId = it, displayName = it, chatType = ChatType.SOLO))
        }
        campaigns.addChapter(campaignA, "a-early", "First opening")
        campaigns.addChapter(campaignA, "a-later", "First continuation")
        campaigns.addChapter(campaignB, "b-early", "Other opening")
        campaigns.addChapter(campaignB, "b-later", "Other continuation")
    }

    @After fun tearDown() = db.close()

    private suspend fun read(chat: String, who: StoryActorRef = StoryActorRef(
        StoryActorKind.NARRATOR, "narrator:$chat"
    ), scene: String = "silver compass") =
        memories.retrieveForGeneration(chat, who, scene)

    @Test fun onlyExplicitlySharedMemoryReachesLaterChaptersOfTheSameCampaign() = runBlocking {
        val note = memories.saveManual("a-early", "The silver compass belongs to Mara.")
        assertTrue(read("a-later").selected.isEmpty())
        val share = campaigns.shareStoryMemoryWithCampaign(campaignA, "a-early", note.memoryId)
        assertEquals(campaignA, share.campaignId)
        assertEquals(note.memoryId, share.sourceMemoryId)
        assertEquals(listOf(note.memoryId), read("a-later").selected.map { it.memoryId })
        assertTrue(read("b-later").selected.isEmpty())
        assertTrue(read("b-early").selected.isEmpty())
        assertTrue(read("standalone").selected.isEmpty())
        assertTrue(read("a-early").selected.map { it.memoryId }.contains(note.memoryId))
        assertEquals("CHAT", db.storyMemoryDao().get(note.memoryId)?.scope)
        assertEquals("a-early", db.storyMemoryDao().get(note.memoryId)?.ownerId)
        assertEquals(1, db.campaignDao().getAllMemoryShares().size)

        assertTrue(runCatching {
            campaigns.shareStoryMemoryWithCampaign(campaignB, "a-early", note.memoryId)
        }.isFailure)
        assertTrue(runCatching {
            campaigns.shareStoryMemoryWithCampaign(campaignA, "b-early", note.memoryId)
        }.isFailure)
        assertTrue(runCatching {
            campaigns.shareStoryMemoryWithCampaign(campaignA, "a-early", note.memoryId)
        }.isFailure)
        campaigns.revokeCampaignMemoryShare(campaignA, "a-early", note.memoryId)
        assertTrue(read("a-later").selected.isEmpty())
        assertNotNull(db.storyMemoryDao().get(note.memoryId))
        assertNotNull(db.chatDao().getChatById("a-early"))
    }

    @Test fun secretRequiresOriginalCharacterKnowledgeNeverBecomesUniversalCampaignKnowledge() = runBlocking {
        val secret = memories.saveManual(
            "a-early", "The silver compass hides a secret letter.", StoryMemoryClaim.SECRET
        )
        db.storyMemoryDao().insertActor(StoryMemoryActorEntity(
            memoryId = secret.memoryId, actorKind = "CHARACTER",
            actorId = "character-knows", relation = "KNOWS"
        ))
        db.storyMemoryDao().update(secret.copy(
            visibility = StoryMemoryVisibility.CHARACTER_ONLY.name
        ))
        campaigns.shareStoryMemoryWithCampaign(campaignA, "a-early", secret.memoryId)
        val knower = StoryActorRef(StoryActorKind.CHARACTER, "character-knows")
        val stranger = StoryActorRef(StoryActorKind.CHARACTER, "character-unaware")
        assertEquals(listOf(secret.memoryId),
            read("a-later", knower).selected.map { it.memoryId })
        assertTrue(read("a-later", stranger).selected.isEmpty())
        assertTrue(read("a-later").selected.isEmpty())
        assertTrue(read("b-later", knower).selected.isEmpty())
        assertTrue(read("standalone", knower).selected.isEmpty())
        db.storyMemoryDao().clearKnowledgeForMemory(secret.memoryId)
        assertTrue(read("a-later", knower).selected.isEmpty())
    }

    @Test fun sourceEditsVariantsAndWackCanNeverSlipIntoAnotherChapter() = runBlocking {
        val primary = MessageEntity(
            messageId = "original", chatId = "a-early",
            speakerType = SpeakerType.CHARACTER, speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "The silver compass points north.", orderIndex = 13L,
            variantGroupId = "choice"
        )
        db.messageDao().insertMessage(primary)
        val memory = memories.saveFromMessage("a-early", primary.messageId)
        campaigns.shareStoryMemoryWithCampaign(campaignA, "a-early", memory.memoryId)
        assertEquals(listOf(memory.memoryId), read("a-later").selected.map { it.memoryId })
        val alternate = primary.copy(
            messageId = "alternate", content = "The silver compass points east.",
            isPrimaryVariant = false
        )
        db.messageDao().insertVariantAndSelect(alternate)
        assertTrue(read("a-later").selected.isEmpty())
        db.messageDao().clearPrimaryVariants("choice")
        db.messageDao().setPrimaryVariant(primary.messageId)
        assertEquals(1, read("a-later").selected.size)
        // Wack in the source chat cannot silently restore the old memory.
        db.chatDao().setContextResetBoundary("a-early", 14L, 14L)
        assertTrue(read("a-later").selected.isEmpty())
        memories.restoreAcrossWack("a-early", memory.memoryId)
        assertEquals(1, read("a-later").selected.size)

        // A Wack in the destination blocks ALL previously shared Campaign
        // memories, even if the source had a valid individual restore.
        db.chatDao().setContextResetBoundary("a-later", 25L, 25L)
        assertTrue(read("a-later").selected.isEmpty())
    }

    @Test fun memoryEditInvalidatesOldApprovalAndRemovalRevokesLinkWithoutDeletingChat() = runBlocking {
        val note = memories.saveManual("a-early", "The silver compass is in the attic.")
        campaigns.shareStoryMemoryWithCampaign(campaignA, "a-early", note.memoryId)
        assertEquals(1, read("a-later").selected.size)
        memories.update("a-early", note.memoryId, text = "The silver compass is in the cellar.")
        assertTrue(read("a-later").selected.isEmpty())
        assertEquals(1, db.campaignDao().getAllMemoryShares().size)
        campaigns.revokeCampaignMemoryShare(campaignA, "a-early", note.memoryId)
        campaigns.shareStoryMemoryWithCampaign(campaignA, "a-early", note.memoryId)
        assertEquals("The silver compass is in the cellar.",
            read("a-later").selected.single().content)
        val firstChapter = campaigns.chapters(campaignA).first()
        campaigns.removeChapter(campaignA, firstChapter.chapterId)
        assertTrue(db.campaignDao().getAllMemoryShares().isEmpty())
        assertNotNull(db.storyMemoryDao().get(note.memoryId))
        assertNotNull(db.chatDao().getChatById("a-early"))
    }

    @Test fun WackAlsoBlocksFreshShareOfUnrestoredOldSourceMemory() = runBlocking {
        val note = memories.saveManual("a-early", "The silver compass is missing.")
        db.chatDao().setContextResetBoundary("a-early", 18L, 18L)
        assertTrue(runCatching {
            campaigns.shareStoryMemoryWithCampaign(campaignA, "a-early", note.memoryId)
        }.isFailure)
        assertTrue(db.campaignDao().getAllMemoryShares().isEmpty())
        memories.restoreAcrossWack("a-early", note.memoryId)
        campaigns.shareStoryMemoryWithCampaign(campaignA, "a-early", note.memoryId)
        assertEquals(1, read("a-later").selected.size)
    }
}
