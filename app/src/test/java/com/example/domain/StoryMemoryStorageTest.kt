package com.example.domain

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
import com.example.domain.memory.StoryMemoryClaim
import com.example.domain.memory.StoryMemoryExclusion
import com.example.domain.repository.StoryMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Room v16 persistence and local-only manual memory actions. */
@RunWith(RobolectricTestRunner::class)
class StoryMemoryStorageTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: StoryMemoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = StoryMemoryRepository(database)
    }

    @After
    fun tearDown() = database.close()

    private suspend fun chat(id: String = "chat-a", boundary: Long? = null) {
        database.chatDao().insertChat(
            ChatEntity(chatId = id, displayName = "Synthetic Story", chatType = ChatType.SOLO,
                contextResetOrderIndex = boundary)
        )
    }

    private val narrator = StoryActorRef(StoryActorKind.NARRATOR, "narrator:chat-a")

    @Test
    fun manualSaveEditPinHideResolveAndDeleteAreChatLocal() = runBlocking {
        chat()
        chat("chat-b")
        val saved = repository.saveManual("chat-a", "  Promise to return the compass  ", StoryMemoryClaim.PROMISE)
        assertEquals("Promise to return the compass", saved.content)
        assertEquals("MANUAL", saved.originKind)
        assertEquals("STORYTELLER_ONLY", saved.visibility)
        assertEquals("APPROVED", saved.review)
        assertEquals("chat-a", saved.ownerId)
        assertEquals(1, database.storyMemoryDao().forChat("chat-a").size)
        assertTrue(database.storyMemoryDao().forChat("chat-b").isEmpty())
        val changed = repository.update("chat-a", saved.memoryId,
            text = "  Promise fulfilled ", pinned = true, hidden = true)
        assertEquals("Promise fulfilled", changed.content)
        assertTrue(changed.pinned)
        assertTrue(changed.hidden)
        assertNotNull(changed.userEditedAt)
        val resolved = repository.update("chat-a", saved.memoryId,
            lifecycle = com.example.domain.memory.StoryMemoryLifecycle.RESOLVED)
        assertEquals("RESOLVED", resolved.lifecycle)
        assertEquals(StoryMemoryExclusion.HIDDEN,
            repository.eligibility(resolved, "chat-a", null, narrator).reason)
        val shown = repository.update("chat-a", saved.memoryId, hidden = false)
        assertEquals(StoryMemoryExclusion.ELIGIBLE,
            repository.eligibility(shown, "chat-a", null, narrator).reason)
        repository.delete("chat-b", saved.memoryId)
        assertNotNull(repository.get("chat-a", saved.memoryId))
        repository.delete("chat-a", saved.memoryId)
        assertNull(repository.get("chat-a", saved.memoryId))
    }

    @Test
    fun sourceLinkedMemoryHonorsSelectedVariantAndRequiresExplicitCurrentWackRestoration() = runBlocking {
        chat(boundary = 100L)
        val original = MessageEntity(
            messageId = "m17", chatId = "chat-a",
            speakerType = SpeakerType.CHARACTER, speakerId = "rowan",
            speakerDisplayNameSnapshot = "Rowan",
            content = "An ancient promise.", orderIndex = 17L, variantGroupId = "branch17",
            isPrimaryVariant = true
        )
        val discarded = original.copy(messageId = "m18", isPrimaryVariant = false, content = "Promise broken")
        database.messageDao().insertMessage(original)
        database.messageDao().insertMessage(discarded)
        val saved = repository.saveFromMessage("chat-a", original.messageId)
        assertEquals("MESSAGE", saved.originKind)
        assertEquals(original.messageId, saved.sourceMessageId)
        assertEquals("branch17", saved.sourceVariantGroupId)
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            repository.eligibility(saved, "chat-a", 100L, narrator).reason)
        val grant = repository.restoreAcrossWack("chat-a", saved.memoryId)
        assertTrue(grant.initiatedByUser)
        assertEquals("chat-a", grant.chatId)
        assertEquals(100L, grant.resetBoundaryOrderIndex)
        assertEquals(StoryMemoryExclusion.ELIGIBLE,
            repository.eligibility(saved, "chat-a", 100L, narrator).reason)
        assertEquals(StoryMemoryExclusion.WACK_BOUNDARY,
            repository.eligibility(saved, "chat-a", 101L, narrator).reason)
        database.messageDao().updateMessage(original.copy(isPrimaryVariant = false))
        database.messageDao().updateMessage(discarded.copy(isPrimaryVariant = true))
        assertEquals(StoryMemoryExclusion.SOURCE_NOT_SELECTED,
            repository.eligibility(saved, "chat-a", 100L, narrator).reason)
    }

    @Test
    fun editedOrDeletedSourceCannotSilentlyRetainItsProvenance() = runBlocking {
        chat()
        val source = MessageEntity(
            messageId = "m5", chatId = "chat-a", speakerType = SpeakerType.PERSONA,
            speakerId = "mara", speakerDisplayNameSnapshot = "Mara",
            content = "Compass is beneath the altar", orderIndex = 5L
        )
        database.messageDao().insertMessage(source)
        val saved = repository.saveFromMessage("chat-a", source.messageId)
        assertEquals(StoryMemoryExclusion.ELIGIBLE,
            repository.eligibility(saved, "chat-a", null, narrator).reason)
        database.messageDao().updateMessage(source.copy(content = "Compass was destroyed", editedAt = 5L))
        assertEquals(StoryMemoryExclusion.SOURCE_CHANGED,
            repository.eligibility(saved, "chat-a", null, narrator).reason)
        database.messageDao().deleteMessage(source)
        assertNotNull(repository.get("chat-a", saved.memoryId))
        assertEquals(StoryMemoryExclusion.SOURCE_MISSING,
            repository.eligibility(saved, "chat-a", null, narrator).reason)
    }

    @Test
    fun deleteChatCascadesOnlyItsNewMemoriesAndGrants() = runBlocking {
        chat(boundary = 100L)
        chat("chat-b")
        val old = MessageEntity(
            messageId = "m17", chatId = "chat-a",
            speakerType = SpeakerType.CHARACTER, speakerId = "rowan",
            speakerDisplayNameSnapshot = "Rowan", content = "A secret",
            orderIndex = 17L
        )
        database.messageDao().insertMessage(old)
        val memory = repository.saveFromMessage("chat-a", old.messageId)
        repository.restoreAcrossWack("chat-a", memory.memoryId)
        val other = repository.saveManual("chat-b", "Other campaign remains")
        database.chatDao().deleteChatById("chat-a")
        assertNull(database.storyMemoryDao().get(memory.memoryId))
        assertTrue(database.storyMemoryDao().returns(memory.memoryId).isEmpty())
        assertNotNull(database.storyMemoryDao().get(other.memoryId))
    }

    @Test
    fun manualPostWackEntryIsNewAndNotAnAccidentalReturnOfOldMessages() = runBlocking {
        chat(boundary = 120L)
        val manual = repository.saveManual("chat-a", "Our new camp is the lighthouse")
        assertTrue(manual.sourceOrderIndex > 120L)
        assertEquals(StoryMemoryExclusion.ELIGIBLE,
            repository.eligibility(manual, "chat-a", 120L, narrator).reason)
        assertTrue(database.storyMemoryDao().returns(manual.memoryId).isEmpty())
    }

    @Test
    fun invalidMissingChatOrBlankInputCannotCreateOrMutateMemory() = runBlocking {
        assertTrue(runCatching { repository.saveManual("missing", "Text") }.isFailure)
        chat()
        assertTrue(runCatching { repository.saveManual("chat-a", "   ") }.isFailure)
        assertTrue(database.storyMemoryDao().forChat("chat-a").isEmpty())
        val saved = repository.saveManual("chat-a", "Keep me")
        assertTrue(runCatching { repository.update("chat-a", saved.memoryId, text = "") }.isFailure)
        assertEquals("Keep me", repository.get("chat-a", saved.memoryId)?.content)
    }
}
