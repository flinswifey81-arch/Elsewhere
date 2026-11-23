package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.world.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Full production-path, synthetic-only transfer: Room -> reviewed encrypted archive
 * -> authenticated inspection -> separate World -> Room. Never sends model requests.
 */
@RunWith(RobolectricTestRunner::class)
class WorldArchiveMemoryV2TransferTest {
    private lateinit var db: AppDatabase
    private lateinit var transfer: WorldArchiveTransferCoordinator
    private val nonce = UUID.randomUUID().toString()
    private val worldId = "world-$nonce"
    private val chatId = "chat-$nonce"
    private val characterId = "character-$nonce"
    private val messageId = "message-$nonce"
    private val firstMemoryId = "memory-first-$nonce"
    private val secondMemoryId = "memory-next-$nonce"

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val lease = AppDataMaintenanceCoordinator()
        transfer = WorldArchiveTransferCoordinator(
            context, db,
            MessageAttachmentRepository(context, db, lease),
            ProfileImageRepository(context, lease),
            WorldArchiveAdditiveRestorer(db, context.filesDir, lease),
            lease
        )
    }

    @After fun tearDown() { db.close() }

    private suspend fun seedStoryWorld() {
        val character = CharacterEntity(
            characterId = characterId, displayName = "Varka", characterName = "Varka",
            aliases = emptyList(), importSchemaVersion = 1, identityJson = null,
            appearanceJson = null, personalityJson = null, voiceJson = null,
            behaviorJson = null, backstoryJson = null, knowledgeJson = null,
            worldContextJson = null, writingRulesJson = null, examplesJson = null,
            authorNotesJson = null, extensionFieldsJson = null
        )
        val line = "I promised I would come back."
        db.backupDao().insertCharacters(listOf(character))
        db.worldDao().insertWorld(WorldEntity(worldId = worldId, title = "Memory Realm"))
        db.backupDao().insertChats(listOf(
            ChatEntity(chatId = chatId, chatType = ChatType.SOLO, displayName = "Varka's Promise")
        ))
        db.backupDao().insertChatParticipants(listOf(
            ChatParticipantEntity(chatId, characterId)
        ))
        db.backupDao().insertChatSettings(listOf(ChatSettingsEntity(chatId)))
        db.backupDao().insertMessages(listOf(
            MessageEntity(messageId = messageId, chatId = chatId,
                speakerType = SpeakerType.CHARACTER, speakerId = characterId,
                speakerDisplayNameSnapshot = "Varka", content = line, orderIndex = 1L)
        ))
        db.worldDao().insertMembership(
            WorldMembershipEntity(worldId, WorldResourceType.CHAT.name, chatId)
        )
        val source = db.messageDao().getMessageById(messageId)!!
        val memory = StoryMemoryEntity(
            memoryId = firstMemoryId, chatId = chatId, ownerId = chatId,
            content = "Varka promised to return, but has not yet fulfilled the promise.",
            claim = "PROMISE", visibility = "CHARACTER_ONLY",
            originKind = "MESSAGE", sourceMessageId = messageId,
            sourceVariantGroupId = source.variantGroupId,
            sourceOrderIndex = source.orderIndex,
            sourceSpeakerType = source.speakerType.name,
            sourceSpeakerId = source.speakerId,
            sourceCreatedAtEpochMillis = source.createdAt,
            sourceSha256 = digest(source.content)
        )
        db.storyMemoryDao().insertAll(listOf(
            memory,
            StoryMemoryEntity(
                memoryId = secondMemoryId, chatId = chatId, ownerId = chatId,
                content = "He returned and kept the promise.",
                claim = "PROMISE", lifecycle = "RESOLVED",
                originKind = "MANUAL", sourceOrderIndex = 2L,
                previousStateMemoryId = firstMemoryId,
                promiseOutcome = "The promise was kept."
            )
        ))
        db.storyMemoryDao().insertActors(listOf(
            StoryMemoryActorEntity(firstMemoryId, "CHARACTER", characterId, "KNOWS"),
            StoryMemoryActorEntity(firstMemoryId, "CHARACTER", characterId, "MENTIONED")
        ))
        db.storyMemoryDao().insertReturns(listOf(
            StoryMemoryReturnEntity(
                firstMemoryId, resetBoundaryOrderIndex = 10L, chatId = chatId,
                approvedAtOrderIndex = 15L, initiatedByUser = true
            )
        ))
        db.storyMemoryDao().insertKnowledgeChanges(listOf(
            StoryMemoryKnowledgeChangeEntity(firstMemoryId, 1L, characterId, "GRANTED")
        ))
        db.storyMemoryDao().insertPerspectives(listOf(
            StoryMemoryPerspectiveEntity(firstMemoryId, characterId, 1L,
                "BELIEVES", digest(memory.content), note = "He doubts himself."),
            StoryMemoryPerspectiveEntity(firstMemoryId, characterId, 2L,
                "SUSPECTS", digest(memory.content), note = "A revised perspective.")
        ))
    }

    @Test fun encryptedWorldRoundTripPreservesEveryV2RecordAndReference() = runBlocking {
        seedStoryWorld()
        val preview = transfer.previewExport(worldId)
        assertEquals(2, preview.storyMemories)
        assertTrue("Explicit review required for private Story Memory history",
            preview.containsPrivateMaterial)
        val failedStream = ByteArrayOutputStream()
        assertTrue(runCatching {
            transfer.exportReviewed(preview, failedStream,
                "private archive passphrase".toCharArray(), false)
        }.isFailure)
        assertEquals(0, failedStream.size())

        val exported = ByteArrayOutputStream()
        transfer.exportReviewed(preview, exported,
            "private archive passphrase".toCharArray(), true)
        transfer.inspectForImport(
            ByteArrayInputStream(exported.toByteArray()),
            "private archive passphrase".toCharArray()
        ).use { checked ->
            assertEquals(2, checked.preview.storyMemories)
            val added = transfer.importReviewed(
                checked, "private archive passphrase".toCharArray()
            )
            assertNotEquals(worldId, added.worldId)
            assertEquals(2, db.worldDao().getWorlds().size)
        }

        val imported = db.storyMemoryDao().getAll().filter { it.chatId != chatId }
        assertEquals(2, imported.size)
        val original = db.storyMemoryDao().getAll().filter { it.chatId == chatId }
        assertEquals(2, original.size)
        val first = imported.single { it.content.startsWith("Varka promised") }
        val second = imported.single { it.content.startsWith("He returned") }
        assertNotEquals(firstMemoryId, first.memoryId)
        assertNotEquals(chatId, first.chatId)
        assertEquals(first.chatId, first.ownerId)
        assertEquals(first.memoryId, second.previousStateMemoryId)
        assertEquals("The promise was kept.", second.promiseOutcome)
        val newMessage = db.backupDao().getMessages().single { it.chatId == first.chatId }
        assertEquals(newMessage.messageId, first.sourceMessageId)
        assertEquals(newMessage.variantGroupId, first.sourceVariantGroupId)
        assertEquals(newMessage.speakerId, first.sourceSpeakerId)
        assertEquals(digest(newMessage.content), first.sourceSha256)
        val newCharacter = db.backupDao().getChatParticipants()
            .single { it.chatId == first.chatId }.characterId
        val actors = db.storyMemoryDao().getAllActors().filter { it.memoryId == first.memoryId }
        assertEquals(2, actors.size)
        assertTrue(actors.all { it.actorId == newCharacter })
        assertTrue(actors.any { it.relation == "KNOWS" })
        val grant = db.storyMemoryDao().getAllReturns().single { it.memoryId == first.memoryId }
        assertEquals(first.chatId, grant.chatId)
        assertEquals(10L, grant.resetBoundaryOrderIndex)
        assertTrue(grant.initiatedByUser)
        val knowledge = db.storyMemoryDao().getAllKnowledgeChanges()
            .single { it.memoryId == first.memoryId }
        assertEquals(newCharacter, knowledge.characterId)
        assertEquals("GRANTED", knowledge.change)
        val perspectives = db.storyMemoryDao().getAllPerspectives()
            .filter { it.memoryId == first.memoryId }.sortedBy { it.revision }
        assertEquals(2, perspectives.size)
        assertEquals(listOf("BELIEVES", "SUSPECTS"), perspectives.map { it.stance })
        assertTrue(perspectives.all { it.characterId == newCharacter })
        assertEquals("A revised perspective.", perspectives.last().note)
        // Source records are untouched: additive import must never rewrite live history.
        assertEquals(messageId, db.backupDao().getMessages()
            .single { it.chatId == chatId }.messageId)
        assertEquals(firstMemoryId, original.single { it.memoryId == firstMemoryId }.memoryId)
    }

    @Test
    fun encryptedWorldRoundTripPreservesCampaignChaptersJournalAndMemoryShares() = runBlocking {
        seedStoryWorld()
        val secondChat = "second-chapter-$nonce"
        db.backupDao().insertChats(listOf(ChatEntity(
            chatId = secondChat, chatType = ChatType.SOLO, displayName = "The sequel"
        )))
        db.backupDao().insertChatSettings(listOf(ChatSettingsEntity(secondChat)))
        db.backupDao().insertChatParticipants(listOf(
            ChatParticipantEntity(secondChat, characterId)
        ))
        db.worldDao().insertMembership(
            WorldMembershipEntity(worldId, WorldResourceType.CHAT.name, secondChat)
        )
        val campaignId = "campaign-$nonce"
        val firstChapterId = "first-chapter-$nonce"
        val secondChapterId = "second-chapter-$nonce"
        val journalId = "checkpoint-$nonce"
        val threadId = "thread-$nonce"
        val memory = db.storyMemoryDao().get(firstMemoryId)!!
        val campaigns = db.campaignDao()
        campaigns.insertCampaigns(listOf(
            CampaignEntity(campaignId = campaignId, worldId = worldId,
                title = "Varka's Journey")
        ))
        campaigns.insertChapters(listOf(
            CampaignChapterEntity(chapterId = firstChapterId, campaignId = campaignId,
                chatId = chatId, ordinal = 1L, title = "The promise"),
            CampaignChapterEntity(chapterId = secondChapterId, campaignId = campaignId,
                chatId = secondChat, ordinal = 2L, title = "The return")
        ))
        campaigns.insertJournalEntries(listOf(CampaignJournalEntryEntity(
            journalEntryId = journalId, campaignId = campaignId,
            chapterId = firstChapterId, sourceChatId = chatId,
            sourceMessageId = messageId,
            sourceVariantGroupId = db.messageDao().getMessageById(messageId)!!.variantGroupId,
            sourceOrderIndex = 1L, sourceContentSha256 = digest("I promised I would come back."),
            entryType = "CHECKPOINT", title = "Keep the promise",
            body = "Our private milestone"
        )))
        campaigns.insertContinuityItems(listOf(CampaignContinuityItemEntity(
            itemId = threadId, campaignId = campaignId, itemType = "THREAD",
            title = "Varka returns", openedChapterId = firstChapterId,
            sourceJournalEntryId = journalId
        )))
        campaigns.insertMemoryShares(listOf(CampaignMemoryShareEntity(
            campaignId = campaignId, sourceChapterId = firstChapterId,
            sourceMemoryId = firstMemoryId, sourceContentSha256 = digest(memory.content)
        )))

        val preview = transfer.previewExport(worldId)
        assertEquals(1, preview.campaigns)
        assertEquals(2, preview.campaignChapters)
        assertEquals(1, preview.journalEntries)
        assertEquals(1, preview.campaignContinuityItems)
        assertEquals(1, preview.sharedMemories)
        assertTrue(preview.containsPrivateMaterial)
        assertTrue(runCatching {
            transfer.exportReviewed(preview, ByteArrayOutputStream(),
                "private archive passphrase".toCharArray(), false)
        }.isFailure)
        val exported = ByteArrayOutputStream()
        transfer.exportReviewed(preview, exported,
            "private archive passphrase".toCharArray(), true)
        val importedWorldId = transfer.inspectForImport(
            ByteArrayInputStream(exported.toByteArray()),
            "private archive passphrase".toCharArray()
        ).use { opened ->
            assertEquals(1, opened.preview.campaigns)
            assertEquals(2, opened.preview.campaignChapters)
            assertEquals(1, opened.preview.journalEntries)
            assertEquals(1, opened.preview.sharedMemories)
            transfer.importReviewed(opened, "private archive passphrase".toCharArray()).worldId
        }
        assertNotEquals(worldId, importedWorldId)
        val copy = campaigns.forWorld(importedWorldId).single()
        assertNotEquals(campaignId, copy.campaignId)
        val chapters = campaigns.chapters(copy.campaignId)
        assertEquals(listOf(1L, 2L), chapters.map { it.ordinal })
        assertEquals(2, chapters.map { it.chatId }.distinct().size)
        assertTrue(chapters.all { it.chatId !in setOf(chatId, secondChat) })
        val importedJournal = campaigns.journal(copy.campaignId).single()
        assertNotEquals(journalId, importedJournal.journalEntryId)
        assertEquals(chapters[0].chapterId, importedJournal.chapterId)
        assertEquals(chapters[0].chatId, importedJournal.sourceChatId)
        val copiedMessage = db.messageDao().getMessageById(
            importedJournal.sourceMessageId!!
        )!!
        assertEquals(chapters[0].chatId, copiedMessage.chatId)
        assertEquals(digest(copiedMessage.content), importedJournal.sourceContentSha256)
        val importedThread = campaigns.continuityItems(copy.campaignId).single()
        assertEquals(importedJournal.journalEntryId, importedThread.sourceJournalEntryId)
        assertEquals(chapters[0].chapterId, importedThread.openedChapterId)
        val link = campaigns.memorySharesForCampaign(copy.campaignId).single()
        assertEquals(chapters[0].chapterId, link.sourceChapterId)
        val importedMemory = db.storyMemoryDao().get(link.sourceMemoryId)!!
        assertEquals(chapters[0].chatId, importedMemory.chatId)
        assertEquals(digest(importedMemory.content), link.sourceContentSha256)
        // Real retrieval in a *later* imported chapter must respect the
        // transferred approval and the newly remapped Character identity.
        val importedCharacterId = db.backupDao().getChatParticipants().single {
            it.chatId == chapters[1].chatId
        }.characterId
        val retrieved = com.example.domain.repository.StoryMemoryRepository(db)
            .retrieveForGeneration(
                chatId = chapters[1].chatId,
                recipient = com.example.domain.memory.StoryActorRef(
                    com.example.domain.memory.StoryActorKind.CHARACTER, importedCharacterId
                ),
                sceneText = "Varka promised to return"
            )
        assertTrue(retrieved.selected.any { it.memoryId == importedMemory.memoryId })
        // Copying a World never edits the source Campaign or its approval.
        assertEquals(1, campaigns.memorySharesForCampaign(campaignId).size)
        assertEquals(2, campaigns.chapters(campaignId).size)
        assertEquals(messageId, db.messageDao().getMessageById(messageId)?.messageId)
    }

    @Test fun worldExportDoesNotLeakMemoriesFromUnlinkedChats() = runBlocking {
        seedStoryWorld()
        val unrelated = "unlinked-$nonce"
        db.backupDao().insertChats(listOf(
            ChatEntity(chatId = unrelated, chatType = ChatType.SOLO, displayName = "Private Other Story")
        ))
        db.storyMemoryDao().insert(StoryMemoryEntity(
            memoryId = "private-$nonce", chatId = unrelated, ownerId = unrelated,
            content = "Private secret in another World", originKind = "MANUAL",
            sourceOrderIndex = 1L
        ))
        val preview = transfer.previewExport(worldId)
        assertEquals(2, preview.storyMemories)
        val output = ByteArrayOutputStream()
        transfer.exportReviewed(preview, output,
            "private archive passphrase".toCharArray(), true)
        transfer.inspectForImport(ByteArrayInputStream(output.toByteArray()),
            "private archive passphrase".toCharArray()).use {
            assertEquals(2, it.preview.storyMemories)
        }
    }
}
