package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression: refreshing chat list sort order must never revoke a Wack. */
@RunWith(RobolectricTestRunner::class)
class ChatTimestampWackBoundaryTest {
    private lateinit var db: AppDatabase
    private lateinit var chats: ChatRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        chats = ChatRepository(db.chatDao(), db.messageDao(), AppDataMaintenanceCoordinator())
    }

    @After fun tearDown() = db.close()

    @Test fun touchAfterWackCannotRestoreStalePreResetBoundary() = runBlocking {
        db.chatDao().insertChat(
            ChatEntity(chatId = "chat-1", displayName = "Story", chatType = ChatType.SOLO,
                updatedAt = 1L)
        )
        val staleChat = requireNotNull(db.chatDao().getChatById("chat-1"))
        assertNull(staleChat.contextResetOrderIndex)
        db.messageDao().insertMessage(
            MessageEntity(
                messageId = "before-wack", chatId = "chat-1",
                speakerType = SpeakerType.PERSONA, speakerId = "persona",
                speakerDisplayNameSnapshot = "Mara", content = "Old context",
                orderIndex = 500L
            )
        )

        val reset = requireNotNull(db.chatDao().establishContextReset("chat-1", 100L))
        assertEquals(500L, reset.contextResetOrderIndex)

        // A previous generation may still hold staleChat here.
        // Timestamp-only touch must not put its null boundary back in Room.
        assertEquals(1, chats.touchChatTimestamp("chat-1", 900L))
        val after = requireNotNull(db.chatDao().getChatById("chat-1"))
        assertEquals(500L, after.contextResetOrderIndex)
        assertEquals(900L, after.updatedAt)
        assertEquals(staleChat.displayName, after.displayName)

        db.chatDao().establishContextReset("chat-1", 1_000L)
        chats.touchChatTimestamp("chat-1", 1_200L)
        assertEquals(1_000L, db.chatDao().getChatById("chat-1")?.contextResetOrderIndex)
    }

    @Test fun touchDoesNotRecreateDeletedChat() = runBlocking {
        assertEquals(0, chats.touchChatTimestamp("nonexistent-chat", 999L))
        assertNull(db.chatDao().getChatById("nonexistent-chat"))
    }
}
