package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regenerate/Continue must recover as-of-target context even if the
 * relevant Persona or earlier turns are outside the loaded chat UI page.
 * Local Room only; no model, network or OpenRouter billing.
 */
@RunWith(RobolectricTestRunner::class)
class PagedHistoricalActionTest {
    private lateinit var db: AppDatabase
    private lateinit var messages: MessageRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        messages = MessageRepository(db.messageDao())
    }

    @After fun tearDown() = db.close()

    private fun row(id: String, chatId: String, order: Long,
                    persona: Boolean = false, primary: Boolean = true,
                    group: String = id) =
        MessageEntity(
            messageId = id, chatId = chatId,
            speakerType = if (persona) SpeakerType.PERSONA else SpeakerType.CHARACTER,
            speakerId = if (persona) "persona" else "character",
            speakerDisplayNameSnapshot = if (persona) "Mara" else "Rowan",
            content = "Fictional scene $id",
            orderIndex = order, variantGroupId = group,
            isPrimaryVariant = primary
        )

    @Test fun tenThousandTurnActionsRecoverOldPersonaAndDoNotReadFuture() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "main", displayName = "Long Story", chatType = ChatType.GROUP
        ))
        db.withTransaction {
            for (i in 1..10_000) {
                db.messageDao().insertMessage(row(
                    "turn-$i", "main", i.toLong(), persona = i == 20
                ))
            }
            db.messageDao().insertMessage(row(
                "alternate-5000", "main", 5_000L, primary = false, group = "turn-5000"
            ))
        }

        val continued = requireNotNull(messages.selectHistoricalAction(
            "main", "turn-5000", isGroup = true,
            isContinuation = true, resetBoundary = null
        ))
        assertEquals("turn-5000", continued.target.messageId)
        assertEquals("turn-20", continued.history.first().messageId)
        assertEquals("turn-4976", continued.history[1].messageId)
        assertEquals("turn-4999", continued.history.last().messageId)
        assertEquals(25, continued.history.size)
        assertFalse(continued.history.any { it.orderIndex >= 5_000L })

        val regenerated = requireNotNull(messages.selectHistoricalAction(
            "main", "turn-5000", isGroup = true,
            isContinuation = false, resetBoundary = null
        ))
        assertEquals("turn-4999", regenerated.current.messageId)
        assertEquals("turn-20", regenerated.history.first().messageId)
        assertEquals("turn-4975", regenerated.history[1].messageId)
        assertEquals("turn-4998", regenerated.history.last().messageId)
        assertEquals(25, regenerated.history.size)

        // The hidden variant shares the selected message's order index, but
        // is inserted later. An as-of target still sees the earlier primary.
        val hidden = requireNotNull(messages.selectHistoricalAction(
            "main", "alternate-5000", isGroup = true,
            isContinuation = false, resetBoundary = null
        ))
        assertEquals("turn-5000", hidden.current.messageId)
        assertEquals("alternate-5000", hidden.target.messageId)

        val solo = requireNotNull(messages.selectHistoricalAction(
            "main", "turn-5000", isGroup = false,
            isContinuation = false, resetBoundary = null
        ))
        assertEquals("turn-20", solo.current.messageId)
        assertEquals("turn-19", solo.history.last().messageId)
        println("PAGED_HISTORICAL_ACTION total=10000 contextRows=${continued.history.size} providerCalls=0")

        val afterWack = requireNotNull(messages.selectHistoricalAction(
            "main", "turn-5000", isGroup = true,
            isContinuation = true, resetBoundary = 1_000L
        ))
        assertEquals(24, afterWack.history.size)
        assertTrue(afterWack.history.all { it.orderIndex > 1_000L })
        assertFalse(afterWack.history.any { it.speakerType == SpeakerType.PERSONA })
        assertNull(messages.selectHistoricalAction(
            "main", "turn-5000", isGroup = false,
            isContinuation = false, resetBoundary = 1_000L
        ))
    }

    @Test fun crossChatTargetIsRejectedAndEqualOrderUsesInsertionTieBreak() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "a", displayName = "A", chatType = ChatType.SOLO
        ))
        db.chatDao().insertChat(ChatEntity(
            chatId = "b", displayName = "B", chatType = ChatType.SOLO
        ))
        db.messageDao().insertMessage(row("persona", "a", 1, persona = true))
        db.messageDao().insertMessage(row("first", "a", 2))
        db.messageDao().insertMessage(row("second", "a", 2))
        db.messageDao().insertMessage(row("foreign", "b", 3, persona = true))

        assertNull(messages.selectHistoricalAction(
            "a", "foreign", isGroup = true, isContinuation = false, resetBoundary = null
        ))
        val regen = requireNotNull(messages.selectHistoricalAction(
            "a", "second", isGroup = true, isContinuation = false, resetBoundary = null
        ))
        assertEquals("first", regen.current.messageId)
        assertEquals("persona", regen.history.single().messageId)
        assertNull(messages.selectHistoricalAction(
            "a", "second", isGroup = true, isContinuation = true, resetBoundary = 2L
        )?.history?.firstOrNull())
    }
}
