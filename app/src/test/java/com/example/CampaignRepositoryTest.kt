package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.data.model.StoryMemoryEntity
import com.example.data.model.WorldResourceType
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.CampaignRepository
import com.example.domain.repository.WorldRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CampaignRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var campaigns: CampaignRepository
    private lateinit var worlds: WorldRepository
    private val coordinator = AppDataMaintenanceCoordinator()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        campaigns = CampaignRepository(db, coordinator)
        worlds = WorldRepository(db, coordinator)
    }

    @After fun tearDown() = db.close()

    @Test fun siblingCampaignsInSameWorldStayIsolatedAndDoNotCopyChatHistory() = runBlocking {
        val world = worlds.createWorld("Amphoreus AU")
        val first = campaigns.createCampaign(world.worldId, "Kremnos Palace")
        val second = campaigns.createCampaign(world.worldId, "Different RP")
        db.chatDao().insertChat(ChatEntity(chatId = "story-a", displayName = "The lion", chatType = ChatType.SOLO))
        db.chatDao().insertChat(ChatEntity(chatId = "story-b", displayName = "Another story", chatType = ChatType.GROUP))
        val message = MessageEntity(
            messageId = "first-turn", chatId = "story-a",
            speakerType = SpeakerType.CHARACTER, speakerId = "mydei",
            speakerDisplayNameSnapshot = "Mydei", content = "An old promise.",
            orderIndex = 7L
        )
        db.messageDao().insertMessage(message)
        db.storyMemoryDao().insert(StoryMemoryEntity(
            memoryId = "first-promise", chatId = "story-a", ownerId = "story-a",
            content = "A private chapter promise.", sourceOrderIndex = 7L
        ))
        val chapterA = campaigns.addChapter(first.campaignId, "story-a", "The Lion")
        val chapterB = campaigns.addChapter(second.campaignId, "story-b", "Elsewhere")
        assertEquals(1L, chapterA.ordinal)
        assertEquals(1L, chapterB.ordinal)
        assertEquals(listOf("story-a"), campaigns.chapters(first.campaignId).map { it.chatId })
        assertEquals(listOf("story-b"), campaigns.chapters(second.campaignId).map { it.chatId })
        assertEquals(setOf(first.campaignId, second.campaignId), campaigns.forWorld(world.worldId).map { it.campaignId }.toSet())
        assertEquals(message, db.messageDao().getMessageById("first-turn"))
        assertEquals(listOf("first-promise"), db.storyMemoryDao().forChat("story-a").map { it.memoryId })
        assertTrue(db.storyMemoryDao().forChat("story-b").isEmpty())
        assertEquals(listOf(world.worldId), db.worldDao().getMembershipsForResource("CHAT", "story-a").map { it.worldId })
    }

    @Test fun sameChatCannotJoinIndependentCampaignsOrBeStolenFromAnotherWorld() = runBlocking {
        val world = worlds.createWorld("World A")
        val otherWorld = worlds.createWorld("World B")
        val first = campaigns.createCampaign(world.worldId, "First")
        val second = campaigns.createCampaign(world.worldId, "Second")
        val foreign = campaigns.createCampaign(otherWorld.worldId, "Foreign")
        db.chatDao().insertChat(ChatEntity(chatId = "my-story", displayName = "RP", chatType = ChatType.SOLO))
        campaigns.addChapter(first.campaignId, "my-story", "Chapter 1")
        assertTrue(runCatching { campaigns.addChapter(second.campaignId, "my-story", "Duplicate") }.isFailure)
        assertTrue(runCatching { campaigns.addChapter(foreign.campaignId, "my-story", "Foreign") }.isFailure)
        assertEquals(1, campaigns.chapters(first.campaignId).size)
        assertTrue(campaigns.chapters(second.campaignId).isEmpty())
        db.chatDao().insertChat(ChatEntity(chatId = "foreign-story", displayName = "Other RP", chatType = ChatType.SOLO))
        worlds.attach(otherWorld.worldId, WorldResourceType.CHAT, "foreign-story")
        assertTrue(runCatching { campaigns.addChapter(first.campaignId, "foreign-story", "Stolen") }.isFailure)
        assertTrue(campaigns.chapters(foreign.campaignId).isEmpty())
    }

    @Test fun editingAndRemovingChapterNeverChangesChatOrApprovedWorldCanon() = runBlocking {
        val world = worlds.createWorld("Teyvat")
        val campaign = campaigns.createCampaign(world.worldId, "Piper's journey")
        db.chatDao().insertChat(ChatEntity(chatId = "episode", displayName = "Chapter RP", chatType = ChatType.SOLO))
        val chapter = campaigns.addChapter(campaign.campaignId, "episode", "Arrival")
        val accepted = worlds.draftEvent(world.worldId, "Baseline", "Established world rule")
        worlds.acceptEvent(world.worldId, accepted.eventId)
        campaigns.updateChapter(campaign.campaignId, chapter.chapterId, "Arrival Revised",
            "A private development that does not become shared World Canon.", true)
        val edited = campaigns.chapters(campaign.campaignId).single()
        assertEquals("COMPLETE", edited.state)
        assertTrue(edited.recap.contains("private development"))
        assertEquals("ACCEPTED", worlds.getEvents(world.worldId).single().status)
        assertEquals("Established world rule", worlds.getEvents(world.worldId).single().facts)
        campaigns.removeChapter(campaign.campaignId, chapter.chapterId)
        assertTrue(campaigns.chapters(campaign.campaignId).isEmpty())
        assertNotNull(db.chatDao().getChatById("episode"))
        campaigns.deleteCampaign(campaign.campaignId)
        assertTrue(campaigns.forWorld(world.worldId).isEmpty())
        assertNotNull(db.chatDao().getChatById("episode"))
        assertNotNull(worlds.getWorld(world.worldId))
    }

    @Test fun invalidCrossCampaignEditsDoNotMutateOtherCampaignOrChapter() = runBlocking {
        val world = worlds.createWorld("Two timelines")
        val first = campaigns.createCampaign(world.worldId, "Timeline A")
        val second = campaigns.createCampaign(world.worldId, "Timeline B")
        db.chatDao().insertChat(ChatEntity(chatId = "episode-a", displayName = "A", chatType = ChatType.SOLO))
        val chapter = campaigns.addChapter(first.campaignId, "episode-a", "Original")
        assertTrue(runCatching {
            campaigns.updateChapter(second.campaignId, chapter.chapterId, "Hijacked", "Leak", true)
        }.isFailure)
        assertTrue(runCatching { campaigns.removeChapter(second.campaignId, chapter.chapterId) }.isFailure)
        assertEquals("Original", campaigns.chapters(first.campaignId).single().title)
        assertEquals("", campaigns.chapters(first.campaignId).single().recap)
        assertTrue(campaigns.chapters(second.campaignId).isEmpty())
    }
}
