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

    @Test fun checkpointTracksSelectedVariantEditsAndHardWackBoundary() = runBlocking {
        val world = worlds.createWorld("Memory safety")
        val first = campaigns.createCampaign(world.worldId, "First story")
        val second = campaigns.createCampaign(world.worldId, "Separate story")
        db.chatDao().insertChat(ChatEntity(
            chatId = "checkpoint-chat", displayName = "The chapter", chatType = ChatType.SOLO
        ))
        val chapter = campaigns.addChapter(first.campaignId, "checkpoint-chat", "First chapter")
        val firstTurn = MessageEntity(
            messageId = "checkpoint-primary", chatId = "checkpoint-chat",
            speakerType = SpeakerType.PERSONA, speakerId = "player",
            speakerDisplayNameSnapshot = "Player", content = "A silver key.",
            orderIndex = 13L, variantGroupId = "key-choice"
        )
        db.messageDao().insertMessage(firstTurn)
        val saved = campaigns.saveCheckpoint(first.campaignId, chapter.chapterId,
            "After finding the key", "We are standing in the library.")
        assertEquals("CHECKPOINT", saved.entryType)
        assertEquals(firstTurn.messageId, saved.sourceMessageId)
        assertEquals(firstTurn.variantGroupId, saved.sourceVariantGroupId)
        assertEquals(CampaignRepository.CheckpointSourceStatus.CURRENT,
            campaigns.checkpointStatus(first.campaignId, saved.journalEntryId))
        assertTrue(runCatching {
            campaigns.checkpointStatus(second.campaignId, saved.journalEntryId)
        }.isFailure)
        assertTrue(campaigns.journal(second.campaignId).isEmpty())
        val alternate = firstTurn.copy(
            messageId = "checkpoint-alternative", content = "A copper key.",
            isPrimaryVariant = false
        )
        db.messageDao().insertVariantAndSelect(alternate)
        assertEquals(CampaignRepository.CheckpointSourceStatus.OTHER_VARIANT,
            campaigns.checkpointStatus(first.campaignId, saved.journalEntryId))
        db.messageDao().clearPrimaryVariants("key-choice")
        db.messageDao().setPrimaryVariant(firstTurn.messageId)
        db.messageDao().updateMessage(firstTurn.copy(content = "A changed silver key."))
        assertEquals(CampaignRepository.CheckpointSourceStatus.MESSAGE_EDITED,
            campaigns.checkpointStatus(first.campaignId, saved.journalEntryId))
        db.chatDao().setContextResetBoundary("checkpoint-chat", 14L, 14L)
        assertEquals(CampaignRepository.CheckpointSourceStatus.BEFORE_WACK,
            campaigns.checkpointStatus(first.campaignId, saved.journalEntryId))
        assertEquals("We are standing in the library.",
            campaigns.journal(first.campaignId).single().body)
    }

    @Test fun journalRemainsPrivateAndSourceDeletionDoesNotEraseCheckpoint() = runBlocking {
        val world = worlds.createWorld("Timeline")
        val first = campaigns.createCampaign(world.worldId, "A")
        val second = campaigns.createCampaign(world.worldId, "B")
        db.chatDao().insertChat(ChatEntity(
            chatId = "journal-chat", displayName = "Episode A", chatType = ChatType.SOLO
        ))
        val chapter = campaigns.addChapter(first.campaignId, "journal-chat", "Opening")
        val source = MessageEntity(
            messageId = "journal-source", chatId = "journal-chat",
            speakerType = SpeakerType.CHARACTER, speakerId = "char",
            speakerDisplayNameSnapshot = "Char", content = "An old discovery.",
            orderIndex = 1L
        )
        db.messageDao().insertMessage(source)
        val checkpoint = campaigns.saveCheckpoint(first.campaignId,
            chapter.chapterId, "Checkpoint", "Source notes")
        val note = campaigns.addJournalNote(first.campaignId, "An open thread",
            "Find the observatory.", chapter.chapterId)
        assertEquals(2, campaigns.journal(first.campaignId).size)
        assertTrue(campaigns.journal(second.campaignId).isEmpty())
        assertTrue(runCatching {
            campaigns.updateJournalEntry(second.campaignId, note.journalEntryId, "Stolen", "")
        }.isFailure)
        campaigns.updateJournalEntry(first.campaignId, note.journalEntryId,
            "An open thread", "Find the observatory after dark.")
        assertEquals("Find the observatory after dark.",
            campaigns.journal(first.campaignId).single { it.journalEntryId == note.journalEntryId }.body)
        db.messageDao().deleteMessageById(source.messageId)
        assertEquals(CampaignRepository.CheckpointSourceStatus.SOURCE_REMOVED,
            campaigns.checkpointStatus(first.campaignId, checkpoint.journalEntryId))
        assertNull(campaigns.journal(first.campaignId)
            .single { it.journalEntryId == checkpoint.journalEntryId }.sourceMessageId)
        campaigns.removeChapter(first.campaignId, chapter.chapterId)
        assertEquals(CampaignRepository.CheckpointSourceStatus.CHAPTER_REMOVED,
            campaigns.checkpointStatus(first.campaignId, checkpoint.journalEntryId))
        assertEquals(2, campaigns.journal(first.campaignId).size)
        assertNotNull(db.chatDao().getChatById("journal-chat"))
        campaigns.deleteCampaign(first.campaignId)
        assertTrue(db.campaignDao().getAllJournalEntries().isEmpty())
        assertNotNull(db.chatDao().getChatById("journal-chat"))
    }

    @Test fun savingCheckpointAfterWackNeverSelectsOldVisibleHistory() = runBlocking {
        val world = worlds.createWorld("Reset safety")
        val campaign = campaigns.createCampaign(world.worldId, "Reset RP")
        db.chatDao().insertChat(ChatEntity(
            chatId = "reset-story", displayName = "Reset", chatType = ChatType.SOLO
        ))
        val chapter = campaigns.addChapter(campaign.campaignId, "reset-story", "Restart")
        db.messageDao().insertMessage(MessageEntity(
            messageId = "pre-wack-source", chatId = "reset-story",
            speakerType = SpeakerType.PERSONA, speakerId = "player",
            speakerDisplayNameSnapshot = "Player", content = "The old timeline.",
            orderIndex = 1L
        ))
        db.chatDao().setContextResetBoundary("reset-story", 2L, 2L)
        val blank = campaigns.saveCheckpoint(campaign.campaignId, chapter.chapterId,
            "After Wack")
        assertNull(blank.sourceMessageId)
        assertEquals(CampaignRepository.CheckpointSourceStatus.START_OF_CHAPTER,
            campaigns.checkpointStatus(campaign.campaignId, blank.journalEntryId))
        db.messageDao().insertMessage(MessageEntity(
            messageId = "after-wack-source", chatId = "reset-story",
            speakerType = SpeakerType.PERSONA, speakerId = "player",
            speakerDisplayNameSnapshot = "Player", content = "The new timeline.",
            orderIndex = 3L
        ))
        val next = campaigns.saveCheckpoint(campaign.campaignId, chapter.chapterId,
            "New continuity")
        assertEquals("after-wack-source", next.sourceMessageId)
        assertEquals(CampaignRepository.CheckpointSourceStatus.CURRENT,
            campaigns.checkpointStatus(campaign.campaignId, next.journalEntryId))
    }

    @Test fun checkpointWithoutAnyMessageIsExplicitStartOfChapter() = runBlocking {
        val world = worlds.createWorld("Empty story")
        val campaign = campaigns.createCampaign(world.worldId, "Beginning")
        db.chatDao().insertChat(ChatEntity(
            chatId = "new-story", displayName = "Empty chapter", chatType = ChatType.SOLO
        ))
        val chapter = campaigns.addChapter(campaign.campaignId, "new-story", "Start")
        val entry = campaigns.saveCheckpoint(campaign.campaignId, chapter.chapterId,
            "Before the first scene")
        assertEquals(CampaignRepository.CheckpointSourceStatus.START_OF_CHAPTER,
            campaigns.checkpointStatus(campaign.campaignId, entry.journalEntryId))
        assertNull(entry.sourceMessageId)
    }

    @Test fun journalPromotionCreatesReviewedWorldDraftButNeverAutomaticallyAcceptsIt() = runBlocking {
        val world = worlds.createWorld("Shared World")
        val first = campaigns.createCampaign(world.worldId, "Private Story A")
        val second = campaigns.createCampaign(world.worldId, "Private Story B")
        db.chatDao().insertChat(ChatEntity(
            chatId = "campaign-a-chat", displayName = "Private A", chatType = ChatType.SOLO
        ))
        db.chatDao().insertChat(ChatEntity(
            chatId = "campaign-b-chat", displayName = "Private B", chatType = ChatType.SOLO
        ))
        val chapter = campaigns.addChapter(first.campaignId, "campaign-a-chat", "Opening")
        val privateEntry = campaigns.addJournalNote(
            first.campaignId,
            "Private observation",
            "A confidential speculation; this must stay inside Campaign A.",
            chapter.chapterId
        )
        campaigns.addChapter(second.campaignId, "campaign-b-chat", "Different history")
        val baseline = worlds.draftEvent(world.worldId,
            "Established World Rule", "Old baseline remains accepted.")
        worlds.acceptEvent(world.worldId, baseline.eventId)
        val expectedOriginalEvents = worlds.getEvents(world.worldId)
        assertTrue(runCatching {
            campaigns.proposeJournalEntryForWorldReview(
                second.campaignId, privateEntry.journalEntryId,
                "Stolen title", "Stolen private fact"
            )
        }.isFailure)
        assertEquals(expectedOriginalEvents, worlds.getEvents(world.worldId))

        val proposal = campaigns.proposeJournalEntryForWorldReview(
            first.campaignId, privateEntry.journalEntryId,
            "Reviewed setting fact",
            "This deliberate, player-approved fact can be considered for World Canon."
        )
        assertEquals(world.worldId, proposal.worldId)
        assertEquals("DRAFT", proposal.status)
        assertEquals("campaign-a-chat", proposal.sourceChatId)
        assertEquals("Reviewed setting fact", proposal.title)
        assertTrue(proposal.facts.contains("deliberate, player-approved"))
        assertFalse(proposal.facts.contains("confidential speculation"))
        assertEquals("DRAFT", worlds.getEvents(world.worldId)
            .single { it.eventId == proposal.eventId }.status)
        assertEquals("ACCEPTED", worlds.getEvents(world.worldId)
            .single { it.eventId == baseline.eventId }.status)
        assertEquals(listOf(baseline.eventId), worlds.getEvents(world.worldId)
            .filter { it.status == "ACCEPTED" }.map { it.eventId })
        val worldContextBeforeAcceptance = requireNotNull(
            com.example.domain.repository.ScenarioRepository(db).worldContextForChat("campaign-b-chat")
        )
        assertTrue(worldContextBeforeAcceptance.contains("Old baseline remains accepted."))
        assertFalse(worldContextBeforeAcceptance.contains("Reviewed setting fact"))
        assertFalse(worldContextBeforeAcceptance.contains("player-approved fact"))
        assertFalse(worldContextBeforeAcceptance.contains("confidential speculation"))
        assertTrue(campaigns.journal(second.campaignId).isEmpty())
        assertEquals(privateEntry.body,
            campaigns.journal(first.campaignId).single().body)
        assertNotNull(db.chatDao().getChatById("campaign-a-chat"))
        assertTrue(db.messageDao().getMessagesForChatOnce("campaign-a-chat").isEmpty())

        // The existing explicit World acceptance action is still mandatory.
        worlds.acceptEvent(world.worldId, proposal.eventId)
        assertEquals("ACCEPTED", worlds.getEvents(world.worldId)
            .single { it.eventId == proposal.eventId }.status)
        val worldContextAfterAcceptance = requireNotNull(
            com.example.domain.repository.ScenarioRepository(db).worldContextForChat("campaign-b-chat")
        )
        assertTrue(worldContextAfterAcceptance.contains("Reviewed setting fact"))
        assertTrue(worldContextAfterAcceptance.contains("player-approved fact"))
        assertFalse(worldContextAfterAcceptance.contains("confidential speculation"))
        assertEquals(privateEntry.body,
            campaigns.journal(first.campaignId).single().body)
    }

    @Test fun privateCheckpointsCannotBePromotedWithoutAuthoredJournalReview() = runBlocking {
        val world = worlds.createWorld("No implicit checkpoint sharing")
        val campaign = campaigns.createCampaign(world.worldId, "One story")
        db.chatDao().insertChat(ChatEntity(
            chatId = "checkpoint-promotion", displayName = "Chapter", chatType = ChatType.SOLO
        ))
        val chapter = campaigns.addChapter(campaign.campaignId, "checkpoint-promotion", "Chapter")
        val checkpoint = campaigns.saveCheckpoint(campaign.campaignId,
            chapter.chapterId, "An old bookmark", "Do not share automatically.")
        assertTrue(runCatching {
            campaigns.proposeJournalEntryForWorldReview(
                campaign.campaignId, checkpoint.journalEntryId,
                checkpoint.title, checkpoint.body
            )
        }.isFailure)
        assertTrue(worlds.getEvents(world.worldId).isEmpty())
        assertEquals("CHECKPOINT", campaigns.journal(campaign.campaignId).single().entryType)
    }

    @Test fun arcsAndPromiseThreadsAreIndependentAndOnlyResolveOnPlayerAction() = runBlocking {
        val world = worlds.createWorld("Long-running AU")
        val first = campaigns.createCampaign(world.worldId, "Story A")
        val second = campaigns.createCampaign(world.worldId, "Story B")
        db.chatDao().insertChat(ChatEntity(
            chatId = "arc-story-a", displayName = "Arc A", chatType = ChatType.SOLO
        ))
        db.chatDao().insertChat(ChatEntity(
            chatId = "arc-story-b", displayName = "Arc B", chatType = ChatType.SOLO
        ))
        val opening = campaigns.addChapter(first.campaignId, "arc-story-a", "An arrival")
        val other = campaigns.addChapter(second.campaignId, "arc-story-b", "Different timeline")
        val journal = campaigns.addJournalNote(
            first.campaignId, "Original clue", "A locked observatory.", opening.chapterId
        )
        val arc = campaigns.addContinuityItem(
            first.campaignId, "ARC", "The Observatory Arc",
            "The travelers arrived and found a locked observatory.",
            opening.chapterId, journal.journalEntryId
        )
        val promise = campaigns.addContinuityItem(
            first.campaignId, "PROMISE", "Return the silver compass",
            "The promise is still unfulfilled.", opening.chapterId
        )
        assertEquals("ACTIVE", arc.status)
        assertEquals("OPEN", promise.status)
        // Records created within the same millisecond are deterministically
        // sorted by ID, not insertion order. This test checks ownership and
        // independent existence, not a chronological ordering contract.
        val firstItems = campaigns.continuityItems(first.campaignId)
        assertEquals(2, firstItems.size)
        assertEquals(setOf(arc.itemId, promise.itemId), firstItems.map { it.itemId }.toSet())
        assertTrue(campaigns.continuityItems(second.campaignId).isEmpty())
        assertTrue(runCatching {
            campaigns.addContinuityItem(second.campaignId,
                "DISCOVERY", "Secret from A", "A locked observatory.",
                other.chapterId, journal.journalEntryId)
        }.isFailure)
        assertTrue(runCatching {
            campaigns.updateContinuityItem(second.campaignId, promise.itemId,
                "Hijacked", "This should fail", "RESOLVED", "Stolen")
        }.isFailure)
        assertTrue(runCatching {
            campaigns.updateContinuityItem(first.campaignId, promise.itemId,
                "Return the silver compass", "Still open",
                "RESOLVED", "This ending belongs to B.", other.chapterId)
        }.isFailure)

        val closed = campaigns.updateContinuityItem(
            first.campaignId, promise.itemId, "Return the silver compass",
            "The promise was finally honored.", "RESOLVED",
            "Returned to its owner.", opening.chapterId
        )
        assertEquals("RESOLVED", closed.status)
        assertEquals("Returned to its owner.", closed.resolution)
        assertEquals("ACTIVE", campaigns.continuityItems(first.campaignId)
            .single { it.itemId == arc.itemId }.status)
        val reopened = campaigns.updateContinuityItem(
            first.campaignId, promise.itemId, "Return the silver compass",
            "A new obligation emerged.", "OPEN"
        )
        assertEquals("OPEN", reopened.status)
        assertEquals("", reopened.resolution)
        assertNull(reopened.closedChapterId)
        val finishedArc = campaigns.updateContinuityItem(
            first.campaignId, arc.itemId, "The Observatory Arc",
            "The observatory was explored.", "COMPLETE",
            "The expedition safely returned.", opening.chapterId
        )
        assertEquals("COMPLETE", finishedArc.status)
        assertTrue(worlds.getEvents(world.worldId).isEmpty())
        assertNotNull(db.chatDao().getChatById("arc-story-a"))
        assertNotNull(db.chatDao().getChatById("arc-story-b"))
    }

    @Test fun chapterAndJournalRemovalPreservePlayerAuthoredArcAndOpenThreads() = runBlocking {
        val world = worlds.createWorld("Deleted link")
        val campaign = campaigns.createCampaign(world.worldId, "Private RP")
        db.chatDao().insertChat(ChatEntity(
            chatId = "retained-chat", displayName = "Saved chat", chatType = ChatType.SOLO
        ))
        val chapter = campaigns.addChapter(campaign.campaignId, "retained-chat", "Opening")
        val source = campaigns.addJournalNote(
            campaign.campaignId, "Source event", "A discovery.", chapter.chapterId
        )
        val thread = campaigns.addContinuityItem(
            campaign.campaignId, "DISCOVERY", "Unsolved map",
            "There is a map to find.", chapter.chapterId, source.journalEntryId
        )
        campaigns.deleteJournalEntry(campaign.campaignId, source.journalEntryId)
        val withoutSource = campaigns.continuityItems(campaign.campaignId).single()
        assertNull(withoutSource.sourceJournalEntryId)
        assertEquals(thread.title, withoutSource.title)
        campaigns.removeChapter(campaign.campaignId, chapter.chapterId)
        val orphan = campaigns.continuityItems(campaign.campaignId).single()
        assertNull(orphan.openedChapterId)
        assertEquals("OPEN", orphan.status)
        assertNotNull(db.chatDao().getChatById("retained-chat"))
        campaigns.deleteCampaign(campaign.campaignId)
        assertTrue(db.campaignDao().getAllContinuityItems().isEmpty())
        assertNotNull(db.chatDao().getChatById("retained-chat"))
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
