package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MemoryCategory
import com.example.data.model.MessageEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.SpeakerType
import com.example.domain.memory.LegacyMemoryEligibilityFilter
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryActorRef
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.StoryMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Legacy memories must validate source identity against all selected variants
 * but must never acquire another chat's source from an ID lookup.
 * No API/network calls; these use the real production Room DAOs.
 */
@RunWith(RobolectricTestRunner::class)
class BoundedLegacyMemoryProvenanceTest {
    private lateinit var database: AppDatabase
    private lateinit var messages: MessageRepository

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        messages = MessageRepository(database.messageDao())
    }

    @After fun teardown() = database.close()

    private suspend fun chat(id: String) {
        database.chatDao().insertChat(
            ChatEntity(chatId = id, displayName = "Synthetic", chatType = ChatType.SOLO)
        )
    }

    private fun source(id: String, chat: String, selected: Boolean = true, group: String = "branch-1") =
        MessageEntity(
            messageId = id, chatId = chat, speakerType = SpeakerType.CHARACTER,
            speakerId = "actor", speakerDisplayNameSnapshot = "Actor",
            content = "Synthetic private continuity", orderIndex = 4L,
            variantGroupId = group, isPrimaryVariant = selected
        )

    private fun legacy(sourceId: String?) = RoleplayMemoryEntity(
        memoryId = "old-memory", chatId = "chat-A",
        characterId = "actor", category = MemoryCategory.PROMISE,
        content = "Promise from an earlier scene", sourceMessageId = sourceId
    )

    private val recipient = StoryActorRef(StoryActorKind.CHARACTER, "actor")

    @Test fun primarySourceAndRejectedAlternativeAreBothLoaded() = runBlocking {
        chat("chat-A")
        database.messageDao().insertMessage(source("primary", "chat-A"))
        database.messageDao().insertMessage(source("alternate", "chat-A", selected = false))
        val record = legacy("primary")
        val archived = messages.getLegacyMemoryProvenance("chat-A", listOf(record))
        assertEquals(setOf("primary", "alternate"), archived.map { it.messageId }.toSet())
        assertEquals(listOf(record), LegacyMemoryEligibilityFilter.eligible(
            listOf(record), "chat-A", recipient, boundary = null, archivedMessages = archived
        ))
        database.messageDao().updateMessage(source("primary", "chat-A", selected = false))
        database.messageDao().updateMessage(source("alternate", "chat-A", selected = true))
        val updated = messages.getLegacyMemoryProvenance("chat-A", listOf(record))
        assertTrue(LegacyMemoryEligibilityFilter.eligible(
            listOf(record), "chat-A", recipient, boundary = null, archivedMessages = updated
        ).isEmpty())
    }

    @Test fun crossChatSourceCannotBeFetchedOrTreatedAsKnown() = runBlocking {
        chat("chat-A")
        chat("chat-B")
        database.messageDao().insertMessage(source("foreign", "chat-B"))
        val record = legacy("foreign")
        val archived = messages.getLegacyMemoryProvenance("chat-A", listOf(record))
        assertTrue(archived.isEmpty())
        assertTrue(LegacyMemoryEligibilityFilter.eligible(
            listOf(record), "chat-A", recipient, boundary = null, archivedMessages = archived
        ).isEmpty())
    }

    @Test fun ambiguousSelectedVariantsFailClosed() = runBlocking {
        chat("chat-A")
        database.messageDao().insertMessage(source("primary", "chat-A"))
        database.messageDao().insertMessage(source("both-primary", "chat-A"))
        val record = legacy("primary")
        val archived = messages.getLegacyMemoryProvenance("chat-A", listOf(record))
        assertEquals(2, archived.size)
        assertTrue(LegacyMemoryEligibilityFilter.eligible(
            listOf(record), "chat-A", recipient, boundary = null, archivedMessages = archived
        ).isEmpty())
    }

    @Test fun unattributedLegacyMemoryIsNotRestoredAcrossWack() = runBlocking {
        chat("chat-A")
        val record = legacy(sourceId = null)
        assertTrue(messages.getLegacyMemoryProvenance("chat-A", listOf(record)).isEmpty())
        assertTrue(LegacyMemoryEligibilityFilter.eligible(
            listOf(record), "chat-A", recipient, boundary = 100L, archivedMessages = emptyList()
        ).isEmpty())
    }

    @Test fun singleSourceEligibilityPreservesDeletedAndChangedSourceExclusions() = runBlocking {
        chat("chat-A")
        val original = source("primary", "chat-A")
        database.messageDao().insertMessage(original)
        val storyRepo = StoryMemoryRepository(database)
        val saved = storyRepo.saveFromMessage("chat-A", original.messageId)
        val narrator = StoryActorRef(StoryActorKind.NARRATOR, "narrator:chat-A")
        assertTrue(storyRepo.eligibility(saved, "chat-A", null, narrator).eligible)
        database.messageDao().updateMessage(original.copy(content = "Updated content", editedAt = 123L))
        assertFalse(storyRepo.eligibility(saved, "chat-A", null, narrator).eligible)
        database.messageDao().deleteMessage(original)
        assertFalse(storyRepo.eligibility(saved, "chat-A", null, narrator).eligible)
    }
}
